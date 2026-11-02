package dev.aterminal.render.input

/**
 * SelectionModel — view state ของ text selection ฝั่ง native (round 11 T2, ≡ terminal.html touchSelect)
 *
 *  - anchor = จุดเริ่ม (word ที่ long-press จับได้), focus = ปลายที่ drag ถึง — เก็บ col + ABSOLUTE row
 *  - ลำดับ linear row-major เป็นตัว normalize (≡ selectCells ฝั่ง JS ที่สลับ i0/i1)
 *  - silent ≡ window.__atermSilentSelect (find/mark วาด selection ซ้ำ — ห้าม copy storm)
 *
 * pure JVM — ไม่มี Android/engine dependency (ทดสอบตรง JVM ได้)
 */
class SelectionModel {
    var active = false
        private set
    var silent = false // true = caller (find/mark) วาดเอง — ผู้ถือ gesture ห้าม copy

    var anchorCol = 0; var anchorRow = 0
    var focusCol = 0; var focusRow = 0

    fun selectWord(startCol: Int, endColInclusive: Int, row: Int) {
        anchorCol = startCol; anchorRow = row
        focusCol = endColInclusive; focusRow = row
        silent = false // gesture explicit = ผู้ใช้เลือกเอง (find/mark จะ set silent=true เองหลังเรียก — T6/T7)
        active = true
    }

    fun setRange(startCol: Int, startRow: Int, endColInclusive: Int, endRow: Int) {
        anchorCol = startCol; anchorRow = startRow
        focusCol = endColInclusive; focusRow = endRow
        silent = false // เช่นกัน — caller ที่วาดแทน find/mark ต้อง set silent=true ทับ
        active = true
    }

    fun extend(col: Int, row: Int) { focusCol = col; focusRow = row }

    fun clear() { active = false }

    /** (startCol, startRow, endColInclusive, endRow) เรียงตาม linear row-major แล้ว — col เริ่ม 0 */
    fun normalized(): IntArray {
        val a = anchorRow.toLong() * 1_000_000 + anchorCol
        val b = focusRow.toLong() * 1_000_000 + focusCol
        return if (a <= b) intArrayOf(anchorCol, anchorRow, focusCol, focusRow)
        else intArrayOf(focusCol, focusRow, anchorCol, anchorRow)
    }

    /** col range (ปิดสองข้าง; ขวา=Int.MAX_VALUE = ทั้งแถว ผู้เรียก clamp ด้วย cols-1) ของ absRow ที่ทับ — null = ไม่โดน */
    fun rowRange(absRow: Int): IntRange? {
        if (!active) return null
        val n = normalized()
        if (absRow < n[1] || absRow > n[3]) return null
        val from = if (absRow == n[1]) n[0] else 0
        val to = if (absRow == n[3]) n[2] else Int.MAX_VALUE
        return from..to
    }
}
