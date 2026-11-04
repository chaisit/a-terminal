package dev.aterminal.app

/**
 * เลข inset bottom ของ edge-to-edge (round 23 task 6, ADR 0002) — จุดเดียวที่อนุญาตให้มี inset math
 * (KeyboardWatcher =เจ้าของ insets ตัวเดียวของแอป, คำนวณแล้วส่งเข้านี้; view code ห้ามคำนวณเอง)
 *
 * กฎ (BarInsetsTest ล็อกด้วยตาราง):
 *  - IME ปิด + bar แสดง  → root = 0 (bar ลามถึงขอบกายภาพ, background ใต้ gesture pill) / keysBar = barsBottom (ปุ่มยก)
 *  - IME เปิด            → root = imeBottom (root เป็นตัวหด) / keysBar = 0 — ห้ามสองค่าพร้อมกัน > 0 (double gap round 22)
 *  - IME ปิด + สอง bar GONE (hw-kb auto-hide, final review A2) → root = barsBottom เอง — ไม่งั้น
 *    rows สุดท้ายของ termContainer โผล่ใต้ gesture pill (orphan: ไม่มีใครยกเนื้อหาเลย)
 *  - barsVisible = false ⇒ barBottom คืน 0 (bar ที่ GONE ไม่ consume padding — invariant
 *    "สองค่าห้ามพร้อมกัน > 0" คงจริงแบบ unconditional)
 *  - gotcha (parked (j)): จังหวะ hide-animation dispatch แรกอาจ report ime หายแล้วแต่ inset ชุดใหม่
 *    ยังมาไม่ถึง = one transient frame ที่ root=0 & bar=0 — dispatch ถัดไปแก้เอง (self-healing)
 *    ห้ามใส่ latch/ค่า fallback ที่นี่ เพราะจะทำลายกฎ double-gap ข้างบน
 */
object BarInsets {

    /** bottom padding ของ root — barsVisible = keysBar หรือ miniBar อย่างน้อยหนึ่งตัว VISIBLE (เขียนโดย applyKeysBars funnel) */
    fun rootBottom(imeVisible: Boolean, barsBottom: Int, imeBottom: Int, barsVisible: Boolean): Int =
        when {
            imeVisible -> imeBottom
            !barsVisible -> barsBottom // A2: ไม่มี bar ยกปุ่ม = root ต้องกันล่างเอง
            else -> 0
        }

    /** bottom padding ของ keysBar (padding แนวนอนของ Gesture Gutter ไม่เกี่ยวกับฟังก์ชันนี้ — caller ต้องคงไว้) */
    fun barBottom(imeVisible: Boolean, barsBottom: Int, barsVisible: Boolean): Int =
        when {
            imeVisible -> 0
            !barsVisible -> 0 // bar ถูกซ่อน — root รับการยกเอง (ดู rootBottom)
            else -> barsBottom
        }
}
