package dev.aterminal.core

/**
 * ImeAutocomplete — "IME ไม่เชื่อ flag ของเรา" จับได้จากอะไร (non-WT extension, HW 11-05)
 *
 * app ขอ [ImeSuggestions.STRICT] ได้แค่ครั้งเดียวต่อ EditorInfo ที่เหลือเป็นการตัดสินใจของ IME
 * (วัดจริง: Tab S10+ เชื่อ = strip หาย, Honor ไม่เชื่อ = user ยังต้องปิดใน Gboard เอง)
 * และ app ไม่มี API ตั้งค่าของ app อื่น — สิ่งที่ทำได้คือ *รู้* ว่าไม่เชื่อ แล้วพา user ไปหน้า
 * ที่ปิดได้จริงหนึ่งครั้ง (ไม่ใช่เด้งทุกครั้งที่เจอ)
 *
 * สัญญาณที่ใช้ได้ทั้งสอง renderer (native = InputConnection, webview = textarea events เพราะ
 * Chromium เป็นเจ้าของ InputConnection เอง) = "ตัวอักษรที่ user ไม่ได้พิมพ์ทีละตัวหลุดเข้ามา"
 *  - COMPOSITION: IME เปิด composing region ให้ข้อความ ASCII ขณะที่เราขอ password mode
 *    = IME ไม่ได้อยู่ในโหมดนั้นจริง (ในโหมดนั้นมันต้อง commit ทีละตัวแบบไม่มี preedit)
 *    ข้อความ non-ASCII (ไทย/ฮินดี...) ไม่นับ = composition เป็นของปกติของภาษาพวกนั้น
 *  - BURST: ข้อความสั้นแบบคำหลุดถึง PTY ทั้งก้อนโดยไม่มี composition = autocorrect/strip
 *    เติมคำให้ (user พิมพ์ 'the' แล้วได้ 'the ' หรือแตะคำจาก strip) — paste ไม่ใช่ จึงตัด
 *    text ที่ตรงกับ clipboards ทิ้ง
 *
 * ทั้งหมดเป็น pure function: state (นับ strike / "เคยเตือน IME นี้ไปแล้ว") อยู่ที่ host
 */
object ImeAutocomplete {

    enum class Kind { COMPOSITION, BURST }

    /**
     * แหล่งที่มาของ write ฝั่ง webview: เท่ากับ [SRC_IME] เท่านั้นจึงเป็นหลักฐานของ IME —
     * ค่าอื่น (`bar` = Keys Bar/preset bytes, `paste` = ⚡/clip) เป็น bytes ที่ *แอป* ส่ง
     * ไม่ใช่สิ่งที่คีย์บอร์ดแทรก
     */
    const val SRC_IME = "ime"
    const val SRC_BAR = "bar"
    const val SRC_PASTE = "paste"

    /**
     * "could have come from a keyboard" — ข้อความที่ IME แทรกไม่เคยมี control byte
     *
     * จำเป็นเพราะ funnel ฝั่ง webview (Droid.onInput) คือ **upstream bytes ทั้งหมด** ไม่ใช่
     * "สิ่งที่ user พิมพ์": วัดจริงจาก IME log บน Honor — ลากนิ้วบน tmux/Claude (mouse mode 1006)
     * สร้าง `ESC[<32;42;12M` = 12 ตัวอักษร 9 ก้อนติดกัน และ terminal reply (`ESC[?62;1;6;22c`)
     * ก็ยาวระดับเดียวกัน [wordLike] ปฏิเสธ ESC อยู่แล้วจึง never counted แต่ log line ยัง
     * หน้าตาเหมือนคำที่ต้องสงสัย = ตัดที่ต้นทางดีกว่าให้คนอ่าน log ไปเดาเอาเอง
     */
    fun plausibleImeText(text: String): Boolean = text.none { it.code < 0x20 || it.code == 0x7F }

    /** ต้องเห็น IME ดื้อกี่ครั้งจึงพาไปตั้งค่า (1 = เด้งจาก swipe ครั้งเดียว = ก่อกวนเกินไป) */
    const val HINT_AFTER = 2

    /** burst ยาวเกินนี้ = ไม่ใช่คำที่ IME เติมให้ (paste/scroll-back paste) — ไม่สนใจ */
    const val MAX_BURST_CELLS = 20

    /**
     * burst สั้นกว่านี้ = คีย์จริงที่ user กด (IME ที่เชื่อ flag จะ commit ทีละตัวพอดี = ต้องไม่ถูก
     * กล่าวหาทุกครั้งที่พิมพ์) — การเติมคำมีอย่างน้อยสองตัวเสมอ (ตัวที่พิมพ์ + ที่ต่อหาง)
     */
    const val MIN_BURST_CELLS = 2

    /**
     * "looks like a word an IME would insert" — ASCII พิมพ์ได้ล้วน มีอักษรอย่างน้อยหนึ่งตัว
     * และสั้นพอ control byte / newline / non-ASCII ตกออกไป = กัน paste กับ Thai เข้ามาปน
     */
    fun wordLike(text: String): Boolean {
        if (text.isEmpty() || text.length > MAX_BURST_CELLS) return false
        var letters = 0
        for (element in text) {
            if (element.code !in 0x20..0x7E) return false // control, newline, ทุกสิ่งที่ไม่ใช่ ASCII
            if (element.isLetter()) letters++
        }
        return letters > 0
    }

    /**
     * burst ต้อง *ตามหลัง* composition ภายในช่วงนี้ (วินาที) — วัดจริงจาก IME log บน Honor:
     * ข้อความหลายตัวอักษรถึง PTY เป็นเรื่องปกติของ paste / คีย์ค้าง / Keys Bar (len 7, 11, 12, 25
     * ในนาทีเดียวกัน) ถ้าไม่นับเฉพาะก้อนที่ออกมาจาก composing region เราจะเตือนคนที่ไม่ได้ทำอะไรผิด
     * IME ที่เติมคำโดยไม่ compose เลยจึงไม่ถูกจับ (under-report = ยอมรับได้: ⌨️ Keyboard settings… ยังอยู่ในเมนู)
     */
    const val BURST_WINDOW_S = 3.0

    /**
     * COMPOSITION = มีการ compose ข้อความ ASCII ทั้งที่เราขอ password mode
     * [sinceCompositionS] = วินาทีนับจาก signal composition ล่าสุด (host จับเวลา; BURST เท่านั้นที่ใช้)
     */
    fun isSuggestion(
        kind: Kind,
        strict: Boolean,
        text: String,
        composing: Boolean,
        clipboard: String,
        sinceCompositionS: Double,
    ): Boolean {
        if (!strict) return false // ไม่ใช่ strict = ไม่มีอะไร "ไม่เชื่อ" — app ไม่ได้ขอ
        return when (kind) {
            Kind.COMPOSITION -> wordLike(text)
            Kind.BURST -> !composing && text.length in MIN_BURST_CELLS..MAX_BURST_CELLS && wordLike(text) &&
                !sinceCompositionS.isNaN() && sinceCompositionS in 0.0..BURST_WINDOW_S &&
                (clipboard.isEmpty() || !text.contains(clipboard)) // paste ทั้งก้อน/ย่อยจาก clip = user สั่งเอง
        }
    }

    /**
     * เตือนแล้วห้ามเด้งอีกสำหรับ IME ตัวเดิม (host จำค่านี้ข้าม session);
     * เปลี่ยน IME = ได้สิทธิ์เตือนใหม่อีกหนึ่งครั้ง เพราะ "ดื้อ" เป็นคุณสมบัติของแต่ละ IME
     */
    fun shouldHint(strikes: Int, hintedFor: String?, imeId: String?): Boolean =
        strikes >= HINT_AFTER && !imeId.isNullOrBlank() && hintedFor != imeId
}
