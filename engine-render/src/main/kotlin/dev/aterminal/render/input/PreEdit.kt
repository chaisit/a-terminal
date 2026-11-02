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
 *  - finish     : composition จบแบบไม่มี text มาแทน (IME ยอมรับ raw ที่รวมไว้) → คืนให้ feed 1 ครั้ง
 *  - shrinkBefore: backspace ขณะ composing (deleteSurroundingText(1,0)) → ยุบท้าย preedit ก่อน;
 *    preedit ว่างแล้วต่างหาก view ค่อย feed DEL — ไม่งั้น shell จะกินอักขระที่มันยังไม่เคยได้รับ
 *  - clear (ESC = spec T5, engine swap): ทิ้ง composition ไม่ feed อะไร (ESC byte เป็นของ caller)
 *
 * pure JVM — gate level JVM; kill switch `preeditEnabled` อยู่ชั้น view/host
 * (false = path เดิม byte-for-byte = emergency rollback, บทเรียน G8)
 */
class PreEdit {

    var text: String = ""
        private set

    val active get() = text.isNotEmpty()

    /** string ว่าง = IME ยกเลิก composing region เอง (เก็บความว่างไว้ — active false) */
    fun setComposing(s: String) { text = s }

    /** commitText(t): แทนทั้ง region — คืน t ให้ feed (ว่าง = ไม่มีอะไร feed); preedit เคลียร์เสมอ */
    fun commit(t: String): String? {
        text = ""
        return if (t.isNotEmpty()) t else null
    }

    /** finishComposingText: finalize สิ่งที่รวมไว้ตรง ๆ — คืน 1 ครั้ง (null = ไม่มีอะไรค้าง) */
    fun finish(): String? = if (text.isEmpty()) null else text.also { text = "" }

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

    /** ESC / attachEngine / session จบ — ทิ้ง ไม่ feed อะไรทั้งสิ้น */
    fun clear() { text = "" }
}
