package dev.aterminal.app

/**
 * ticket tmux/05: state "prefix pending" ของ tmux keys mode — pure Kotlin, JVM-tested (`TmuxPrefixStateTest`)
 *
 * Q3 (user อนุมัติ 11-05) = **buffer**: tap ปุ่ม prefix = armed อย่างเดียว *ยังไม่ส่ง byte ใด*
 *  - input ถัดไปจาก funnel ใดก็ได้ (bar / Gboard ผ่าน Droid.onInput / native IME) = prefix + input พร้อมกัน
 *  - ยกเลิกทุกทาง (tap ซ้ำ = literal, timeout token-guarded, pane switch, dial) = **ไม่เคยมี byte ค้างใน PTY**
 *    (design ผิดที่ห้ามพลาด: ส่ง 0x02 ก่อนแล้ว user ไม่ tap ต่อ = ตัวพิมพ์ถัดไปกลายเป็นคำสั่ง tmux)
 *  - heuristic ฝั่ง client ล้วน — ห้าม claim synchronized กับ server (วิจัย §3 T4)
 *
 * fail-soft แบบ [KeyModifierState]: input ที่ไม่ใช่ "ปุ่มคำสั่ง" เดียว (paste/composition หลายตัวอักษร)
 * = ทิ้ง prefix, ส่ง input ตรง — ห้าม drop input เด็ดขาด
 *
 * owner = MainActivity (singleton ตาม `mods`); funnel เรียก [transform] หลัง `mods.transform` เสมอ
 * (latched CTRL + 'd' = 0x04 แล้ว prefix ครอบ → `C-b C-d` = detach-window-from-others... ไม่ใช่ —
 * 0x02 0x04 = tmux `C-b C-d` which is the real detach-from-foreground binding — semantics ของ server, ของเราคือ compose byte)
 */
class TmuxPrefixState {

    /** UI redraw — ยิงทุก transition of armed (bar สลับ pending row); เรียกจากทุก thread (bridge) = host post เข้า main เอง */
    var onChange: (() -> Unit)? = null

    private var prefix: Byte = DEFAULT_PREFIX
    private var armed = false
    private var gen = 0L

    /** profile.tmuxPrefix (form "C-a") — host set ตอน dial/focus pane; null = default */
    fun setPrefix(b: Byte) {
        prefix = b
    }

    @Synchronized fun isArmed(): Boolean = armed

    /** เริ่ม pending — return token สำหรับ [onTimeout] (timeout รุ่นเก่าห้ามล้าง pending รุ่นใหม่) */
    @Synchronized
    fun arm(): Long {
        armed = true
        gen++
        fire()
        return gen
    }

    /** manual cancel (pane switch / dial ใหม่ / back) — ไม่ส่งอะไร */
    @Synchronized
    fun disarm() {
        if (!armed) return
        armed = false
        gen++
        fire()
    }

    /** trailing timeout: ยกเลิกเฉพาะถ้า pending ยังเป็นรุ่นเดียวกับ [token] */
    @Synchronized
    fun onTimeout(token: Long) {
        if (!armed || token != gen) return
        armed = false
        gen++
        fire()
    }

    /**
     * tap ปุ่ม prefix ขณะ pending = tmux `C-b C-b` (ใส่ prefix ตัวตรง) — disarm + คืน prefix สอง byte
     * (เรียกเมื่อ isArmed() เท่านั้น; ตอน not armed caller ต้อง [arm] แทน)
     */
    @Synchronized
    fun tapWhileArmed(): String {
        val p = prefixText()
        armed = false
        gen++
        fire()
        return p + p
    }

    /**
     * quick-key row tap ขณะ pending = ขอ prefix byte (host เขียน prefix + afterPrefix ตาม)
     * null = ไม่ armed (race หลัง timeout — ห้ามมีอะไรหลุด)
     */
    @Synchronized
    fun beginCommand(): String? {
        if (!armed) return null
        armed = false
        gen++
        fire()
        return prefixText()
    }

    /**
     * funnel: แปลง input ที่จะเขียน PTY (หลัง `mods.transform`) — armed อยู่ = ต่อ prefix นำหน้า
     * "ปุ่มคำสั่ง" เดียวเท่านั้น: single char หรือ escape sequence ที่ขึ้นต้นด้วย ESC
     * multi-char อื่น (paste/composition/emoji) = ทิ้ง prefix ส่งตรง (fail-soft, pending จบ)
     */
    @Synchronized
    fun transform(input: String): String {
        if (!armed) return input
        armed = false
        gen++
        val isCommandKey = input.length == 1 || input.startsWith("\u001B")
        fire()
        return if (isCommandKey) prefixText() + input else input
    }

    private fun prefixText(): String = String(byteArrayOf(prefix)) // control byte = identical ทุก charset ที่ app ใช้

    private fun fire() = onChange?.invoke()

    companion object {
        const val DEFAULT_PREFIX: Byte = 0x02 // C-b

        /**
         * "C-a".."C-z" (case-insensitive) → control byte; อื่น ๆ = null (form toast แล้วทิ้ง)
         * null/blank input ไม่ใช่หน้าที่ function นี้ — caller แปลงเป็น default เอง
         */
        fun parseSpec(spec: String?): Byte? {
            val s = spec?.trim() ?: return null
            if (!s.startsWith("C-", ignoreCase = true)) return null
            val c = s.substring(2).singleOrNull() ?: return null
            if (c !in 'a'..'z' && c !in 'A'..'Z') return null
            return (c.lowercaseChar() - 'a' + 1).toByte()
        }
    }
}
