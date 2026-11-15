package dev.aterminal.render.input

/**
 * PreEdit — preedit model ฝั่ง native (round 11 T5) ≡ composition hold ของ xterm.js
 * (G8 webview บันทึกไว้ "preedit ไม่ถึง backend (onData=0) = xterm hold ถูกต้อง")
 *
 * path เดิม feed ทุก composition update ลง PTY = shell เห็น intermediate bytes —
 * class นี้ถือที่ view แทน แล้ว feed ครั้งเดียวตอน composing จบ:
 *  - setComposing: IME ส่ง composition มาใหม่ทั้งก้อนทุกครั้ง (สัญญาของ Gboard) — เก็บอย่างเดียว ไม่ feed
 *  - commitText : t คือ text ที่มา *แทน* composing region (Android IC contract) → preedit เก่าถูกแทน
 *    (discard) แล้ว feed แค่ t — ไม่ feed ซ้ำสองรอบ
 *  - finish     : composition จบแบบไม่มี commitText — ส่งตามน้ำทันที 1 ครั้ง *และเปิด correction window*
 *                 (F-2 ticket 12: Gboard "correction-dance" = finish → delete → commit → recompose →
 *                 re-commit โดยตั้งใจให้ *แก้ทับ* คำที่เพิ่ง finish; app ที่ append ทุก call = คำซ้ำ ×2
 *                 วัดจริงบน Honor + zsh oracle 27 cp — design ตัดสินแล้ว: ห้าม deferred flush
 *                 (user กด Enter/backspace ต่อทันทีโดยไม่พิมพ์ซ้ำ = คำหาย), ใช้ window + delta แทน)
 *  - shrinkBefore: backspace ขณะ composing (deleteSurroundingText(1,0)) → ยุบท้าย preedit ก่อน;
 *    preedit ว่างแล้วต่างหาก view ค่อย feed DEL — ไม่งั้น shell จะกินอักขระที่มันไม่เคยได้รับ
 *  - clear (ESC = spec T5, engine swap): ทิ้ง composition ไม่ feed อะไร (ESC byte เป็นของ caller)
 *
 * pure JVM — gate level JVM; kill switch `preeditEnabled` อยู่ชั้น view/host
 * (false = path เดิม byte-for-byte = emergency rollback, บทเรียน G8)
 */
class PreEdit {

    var text: String = ""
        private set

    val active get() = text.isNotEmpty()

    /**
     * F-2 (ticket 12): correction window — text ที่ `finish()` เพิ่ง flush เข้า PTY และยังไม่ถูก
     * "เหตุการณ์จริง" (commit, key, raw-flush, preempt, ESC, swap, feed ภายนอก) ปิด window.
     * "" = window ปิด. ไม่ใช่ timer — dance window วัดได้ ~211 ms แต่ word-typing จริงยาวกว่า (user ตัดสิน)
     */
    var lastFinished: String = ""
        private set

    val correctionOpen get() = lastFinished.isNotEmpty()

    /** ปิด window โดยไม่แตะ PTY — call site = เหตุการณ์จริงทุกทาง (ดู docs ทicket 12) */
    fun closeCorrection() { lastFinished = "" }

    /** string ว่าง = IME ยกเลิก composing region เอง (เก็บความว่างไว้ — active false) */
    fun setComposing(s: String) { text = s }

    /** commitText(t) ปกติ: แทนทั้ง region — คืน t ให้ feed (ว่าง = ไม่มีอะไร feed); preedit + window เคลียร์ */
    fun commit(t: String): String? {
        text = ""
        lastFinished = "" // commit = เหตุการณ์จริง: window ปิด (ถ้าจะ replace view ต้องเรียก correctionCommit ก่อน)
        return if (t.isNotEmpty()) t else null
    }

    /**
     * finishComposingText: flush ตามน้ำ (view feed สิ่งที่ได้ทันที — ห้าม delay) + เปิด correction window
     * null = ไม่มีอะไรค้าง (spurious finish — view ปิด window ไปด้วย)
     */
    fun finish(): String? =
        if (text.isEmpty()) null else text.also { text = ""; lastFinished = it }

    /**
     * dance commit (ticket 12): commitText ขณะ region ปิด + window เปิด = Gboard แก้คำที่เพิ่ง flush —
     * คืน Delta เทียบ lastFinished (common prefix คงไว้, หาง DEL = retreat ใน line editor) แล้ว *ปิด* window.
     * null = window ปิดอยู่ หรือ text ว่าง (commit ว่าง = เหตุการณ์จริง → ปิด window, ไม่มีอะไร feed)
     */
    fun correctionCommit(newText: String): Delta? {
        if (newText.isEmpty()) { lastFinished = ""; return null }
        if (lastFinished.isEmpty()) return null
        val d = deltaAgainstLastFinished(newText)
        lastFinished = ""
        text = ""
        return d
    }

    /**
     * dance composing (ticket 12): setComposingText ขณะ window เปิด = Gboard กำลังแก้เป็นขั้น ๆ —
     * คืน Delta และ *คง window เปิด* (lastFinished = newText เพราะ bytes อยู่ PTY แล้วทั้งก้อน)
     */
    fun correctionCompose(newText: String): Delta? {
        if (newText.isEmpty() || lastFinished.isEmpty()) return null
        val d = deltaAgainstLastFinished(newText)
        lastFinished = newText
        text = ""
        return d
    }

    /**
     * dance delete (ticket 12): backspace ขณะ window เปิด (preedit ว่างแล้ว) = ลบคำที่เพิ่ง flush —
     * หด lastFinished (window คงเปิด: Gboard ลบแล้วแก้ต่อ) คืนจำนวน DEL ที่ view ต้องส่ง;
     * -1 = window ปิดอยู่ (view ใช้ path เดิม)
     */
    fun correctionDelete(before: Int): Int {
        if (lastFinished.isEmpty()) return -1
        val n = lastFinished.codePointCount(0, lastFinished.length)
        val del = minOf(before, n)
        val keep = n - del
        lastFinished = if (keep == 0) "" else lastFinished.substring(0, lastFinished.offsetByCodePoints(0, keep))
        return del
    }

    /** common prefix นับเป็น code point (surrogate ปลอดภัย — บทเรียน shrinkBelow); PTY tail = lastFinished พอดี */
    private fun deltaAgainstLastFinished(newText: String): Delta {
        val old = lastFinished
        var i = 0
        var j = 0
        var common = 0
        while (i < old.length && j < newText.length) {
            val a = old.codePointAt(i)
            val b = newText.codePointAt(j)
            if (a != b) break
            i += Character.charCount(a)
            j += Character.charCount(b)
            common++
        }
        return Delta(old.codePointCount(0, old.length) - common, newText.substring(j))
    }

    /**
     * ยุบ `before` code points ท้าย preedit (นับ code point ไม่ใช่ code unit — surrogate ปลอดภัย)
     * true = preedit ถูกยุบ (view ห้าม feed) | false = preedit ว่าง — view feed DEL path เดิม
     */
    fun shrinkBefore(before: Int): Boolean {
        if (text.isEmpty()) return false
        val n = text.codePointCount(0, text.length)
        val keep = (n - maxOf(1, before)).coerceAtLeast(0)
        text = if (keep == 0) "" else text.substring(0, text.offsetByCodePoints(0, keep))
        return true
    }

    /** ESC / attachEngine / session จบ — ทิ้ง composition + ปิด correction window (ticket 12: PTY tail
     * จะเปลี่ยนโดยไม่มี bookkeeping ต่อ = ห้าม replace อะไรตามหลัง) */
    fun clear() {
        text = ""
        lastFinished = ""
    }
}

/** ผล replace เทียบสิ่งที่เพิ่ง flush (ticket 12): view ส่ง DEL `del` ตัว (line editor retreat) แล้วเขียน `rest` ต่อก้นบรรทัด */
data class Delta(val del: Int, val rest: String)
