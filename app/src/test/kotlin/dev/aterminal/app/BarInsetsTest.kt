package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BarInsets — ตาราง inset bottom เดียวของแอป (round 23 task 6, ADR 0002 edge-to-edge)
 *  - bar background ต้องลามถึงขอบกายภาพใต้ gesture pill → root ห้ามกัน bottom เมื่อ IME ปิด *และ bar แสดง*
 *  - ปุ่มต้องยกเหนือ systemBars().bottom → keysBar รับ bottom padding เอง (Gutter ซ้ายห้ามหาย)
 *  - gotcha round 22: root padding + bar padding พร้อมกัน = DOUBLE gap — ตารางนี้คือที่ล็อกพฤติกรรม
 *  - fix wave A2: สอง bar GONE (hw-kb auto-hide) = root รับ barsBottom เอง (orphan fix —
 *    เดิม root=0 & bar padding ไม่มีคนใช้ → terminal rows สุดท้ายใต้ gesture pill)
 */
class BarInsetsTest {

    // (imeVisible, barsBottom, imeBottom, barsVisible, expectedRoot, expectedBar) — px สไตล์ emulator 1080×2400
    private val table = listOf(
        Triple(false, 63, 0) to (true to (0 to 63)),    // IME ปิด: root ไม่กันล่าง (bar ลามใต้ pill), ปุ่มยก 63
        Triple(true, 63, 812) to (true to (812 to 0)),  // IME เปิด: root กันถึง keyboard, bar = 0 (ห้าม double gap)
        Triple(false, 0, 0) to (true to (0 to 0)),      // API34 fullscreen/adjustResize: inset = 0 → พฤติกรรมเดิม
        Triple(true, 0, 500) to (true to (500 to 0)),   // fullscreen ไม่มี nav bar: ime inset อย่างเดียว
        Triple(false, 120, 0) to (true to (0 to 120)),  // 3-button nav bar สูง: bar ยกตาม barsBottom
        Triple(true, 63, 63) to (true to (63 to 0)),    // ime inset == bars (keyboard โผล่แต่ nav strip) = root ขับ
        // ---- fix wave A2 rows: สอง bar GONE ----
        Triple(false, 63, 0) to (false to (63 to 0)),   // auto-hide + IME ปิด: root ยกเนื้อหาเอง (orphan case)
        Triple(false, 120, 0) to (false to (120 to 0)), // 3-button nav สูง + bar ซ่อน = root ยกตาม bars
        Triple(false, 0, 0) to (false to (0 to 0)),     // fullscreen inset = 0: ไม่มีอะไรยก = 0 ทุกทาง
        Triple(true, 63, 812) to (false to (812 to 0)), // IME เปิดชนะเสมอ (bar GONE ก็ imeBottom ตัวเดิม)
    )

    @Test
    fun `root bottom and bar bottom follow the table - never both non-zero`() {
        for ((row, expectation) in table) {
            val (imeVisible, barsBottom, imeBottom) = row
            val (barsVisible, expected) = expectation
            val root = BarInsets.rootBottom(imeVisible, barsBottom, imeBottom, barsVisible)
            val bar = BarInsets.barBottom(imeVisible, barsBottom, barsVisible)
            assertEquals(
                "rootBottom(ime=$imeVisible, bars=$barsBottom, ime=$imeBottom, barsVisible=$barsVisible)",
                expected.first, root
            )
            assertEquals(
                "barBottom(ime=$imeVisible, bars=$barsBottom, barsVisible=$barsVisible)",
                expected.second, bar
            )
            // invariant กัน double gap (round 22 gotcha): สองค่าห้ามพร้อมกัน > 0 — unconditional
            assertEquals("double gap at ($imeVisible,$barsBottom,$imeBottom,$barsVisible)",
                true, !(root > 0 && bar > 0))
        }
    }

    @Test
    fun `bars-gone row drives root bottom exactly where bars-visible row leaves off`() {
        // A2 non-vacuity probe: with bars visible the root MUST NOT lift (0) and the bar MUST lift (63);
        // hiding the bars flips those roles exactly — if the flag were ignored both rows would read the same
        assertEquals(0, BarInsets.rootBottom(false, 63, 0, true))
        assertEquals(63, BarInsets.barBottom(false, 63, true))
        assertEquals(63, BarInsets.rootBottom(false, 63, 0, false))
        assertEquals(0, BarInsets.barBottom(false, 63, false))
    }
}
