package dev.aterminal.core

/**
 * ImeSuggestions — คำสั่งของ app ต่อ IME เรื่อง "เดาคำให้" (settings `imeSuggestions`)
 *
 * ทำไมต้องมีสามค่าแทน boolean: app ขอ "อย่าเดา" ด้วย `TYPE_TEXT_FLAG_NO_SUGGESTIONS` มาตลอด
 * แต่ IME ยี่ห้อหลักไม่เคารพ flag นี้ (ยังโชว์ strip + เติมคำให้อัตโนมัติตอนกด space = ส่งอักษร
 * ที่ user ไม่ได้พิมพ์เข้า PTY) ส่วน flag ที่แรงกว่า (password variation) เคยวัดจริงแล้วว่า
 * IME หยุด compose ทั้งก้อน = พิมพ์ภาษาที่ต้องรวม cluster (ไทย/อินเดีย) ไม่ได้
 * → จึงเปิดเป็น choice ที่ user สลับได้ทันทีจากเมนู แทนการตัดสินใจครั้งเดียวในโค้ด
 *
 * ค่า default = [STRICT] (user ตัดสิน 11-05 หลังวัดบนเครื่องจริง: Tab S10+ เชื่อ flag = strip หายเอง,
 * Honor ไม่เชื่อ = ต้องปิดใน IME, ซึ่ง app ทำแทนไม่ได้ → ดู ImeAutocomplete ที่พาไปหน้านั้น)
 * ส่วน [STRICT_ON_HW] ยังอยู่เป็นทางถอยสำหรับคนที่ต้องใช้ compose บนจอสัมผัส (พิมพ์ไทย/ฮินดี)
 */
object ImeSuggestions {

    /** พฤติกรรมที่ shipped มาตลอด: `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS` */
    const val NONE = "noSuggestions"

    /** field แบบรหัสผ่านผ่านตา (visible password) = IME ส่วนใหญ่เลิกเดา แต่เลิก compose ด้วย */
    const val STRICT = "strict"

    /** [STRICT] เฉพาะตอนมี physical keyboard, จอสัมผัส = [NONE] */
    const val STRICT_ON_HW = "strictOnHardware"

    val values = listOf(NONE, STRICT, STRICT_ON_HW)

    /**
     * ค่าไม่รู้จัก (user แก้ settings.json ผิด, หรือค่าจากเวอร์ชันที่ใหม่กว่า) = [NONE]
     * = พฤติกรรมที่ shipped มาตลอด ไม่ใช่ default ใหม่ — ค่าแปลกต้องไม่เพิ่มพฤติกรรมเสี่ยง
     * (default ตอน field หายไปทั้งดุ้นเป็นคนละเรื่อง: เป็น default ของ data class ใน Settings)
     */
    fun parse(raw: String?): String = values.firstOrNull { it == raw } ?: NONE

    /** true = ให้ view ขอ inputType แบบ [STRICT] ในตอนนี้ */
    fun strictNow(raw: String?, hardwareKeyboard: Boolean): Boolean =
        when (parse(raw)) {
            STRICT -> true
            STRICT_ON_HW -> hardwareKeyboard
            else -> false // NONE + ค่าที่ไม่รู้จัก
        }

    /** สำหรับเมนู ⋮: วนค่าถัดไป (กด A/B บนเครื่องจริงได้เร็ว ไม่ persist ในตัว — caller เขียน settings เอง) */
    fun next(raw: String?): String {
        val i = values.indexOf(parse(raw))
        return values[(i + 1) % values.size]
    }

    /** คำเดียวสำหรับ label ในเมนู ⋮ (ยาวเกินนี้จะตัดชื่อ item ที่เหลือ) */
    fun short(raw: String?): String =
        when (parse(raw)) {
            STRICT -> "strict"
            STRICT_ON_HW -> "strict on HW kb"
            else -> "app default"
        }

    /** ข้อความที่ HUD โชว์ตอนสลับ (สั้น ≡ status line อื่นของแอป) */
    fun label(raw: String?): String =
        when (parse(raw)) {
            STRICT -> "IME suggestions: strict (may stop Thai composing)"
            STRICT_ON_HW -> "IME suggestions: strict when hardware keyboard"
            else -> "IME suggestions: app default"
        }
}
