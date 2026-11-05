package dev.aterminal.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * เกณฑ์ที่ 2 ของ draw path (HW 11-05): "advance ของ run ≡ grid ของมันหรือยัง"
 *
 * สิ่งที่วัดจริงบนเครื่อง (screenshot Honor, native renderer, ตารางใน tmux — Tab S10+ ไม่เป็น):
 * border column ขวาอยู่ที่ x=2086 บนทุกแถว **ยกเว้น** แถวเดียว (y 1275..1321) ที่ x=2078 = ซ้ายไป ~8px
 * (~0.44 cell) ขณะที่ column อื่นของแถวเดียวกัน (53/186/794/965) ตรงเป๊ะ และเส้น `─` ทุกเส้นยังคร่อม
 * 52..2087 เท่าเดิม → คณิตศาสตร์ grid ถูก, ตัวที่ไหลเองคือ **advance สะสมของ run ที่มี fallback glyph**
 * (`─`/`│` มีใน bundled mono → advance ≡ cellW จึงไม่ไหล; ไทย `นานๆ` ไม่มี → advance ของฟอนต์ไทย
 * ที่เครื่อง fallback ให้สะสมทีละ ~1px ต่อ cluster)
 *
 * run นั้นคือ `ต้องการ control/exec channel; killer feature ของงาน remote นานๆ` — **ไม่มี**
 * zero-width codepoint เลย → `CellLock.needsPerCluster` ไม่ทำงาน (ดู test ข้างล่าง) และ path นี้เป็น
 * โค้ดเดิมก่อน round 23 ไม่ใช่ของที่ perf 11-04 เพิ่ม
 */
class PlacementExactTest {

    private val cellW = 18.15f // pitch ที่วัดจาก screenshot Honor (112 cells = 53..2086)

    @Test fun `run whose advance equals its grid span is drawn in one call`() {
        assertTrue(CellLock.placementExact(92 * cellW, 92, cellW))
        assertTrue(CellLock.placementExact(cellW, 1, cellW))
        // wide cell = 2 cells ต่อ cluster (RunMerger แยกไว้แล้ว) = grid กว้าง 2 เท่า
        assertTrue(CellLock.placementExact(2 * cellW, 2, cellW))
    }

    @Test fun `the Honor case - eight px of drift over ninety two cells is rejected`() {
        val drifted = 92 * cellW - 8.4f
        assertFalse(CellLock.placementExact(drifted, 92, cellW))
        // ทิศเดียวไม่พอ: advance เกิน grid (glyph กว้างกว่า cell) ก็ไหลไปทางขวาให้เห็นเหมือนกัน
        assertFalse(CellLock.placementExact(92 * cellW + 8.4f, 92, cellW))
    }

    @Test fun `tolerance is the total drift not per cell and is MAX_DRIFT_PX inclusive`() {
        val cells = 40
        val grid = cells * cellW
        assertTrue(CellLock.placementExact(grid + CellLock.MAX_DRIFT_PX, cells, cellW))
        assertTrue(CellLock.placementExact(grid - CellLock.MAX_DRIFT_PX, cells, cellW))
        assertFalse(CellLock.placementExact(grid + CellLock.MAX_DRIFT_PX + 0.01f, cells, cellW))
        // เพี้ยน cell ละ 0.02px = ต่อ cell มองไม่เห็น แต่ปิดท้ายแถวด้วย border = 0.8px = ขวางตา
        // → เกณฑ์ต้องเป็นผลรวม มิฉะนั้น case จริง (0.09px/cell) จะหลุดเหมือนรอบนี้
        assertFalse(CellLock.placementExact(cells * cellW + 0.8f, cells, cellW))
        assertEquals(0.5f, CellLock.MAX_DRIFT_PX, 0f)
    }

    @Test fun `unknown geometry is never trusted`() {
        // คืน false = เลือก path ที่ช้ากว่าแต่ตรง (fail-safe) แทนที่จะวาดเร็วแล้วเพี้ยน
        assertFalse(CellLock.placementExact(0f, 0, cellW))
        assertFalse(CellLock.placementExact(-5f, 3, cellW))
        assertFalse(CellLock.placementExact(3 * cellW, 3, 0f))
        assertFalse(CellLock.placementExact(Float.NaN, 3, cellW))
    }

    @Test fun `thai without any combining mark passes the zero width gate so gate two is load bearing`() {
        // "นานๆ" = U+0E17 NO NU + U+0E32 SARA AA (spacing mark, w1) + NO NU + U+0E46 MAIYAMOK (w1)
        // escape literal ทุกตัว: กัน editor/normalization ทำหาย (บทเรียนจาก ClustersTest)
        val nanan = "\u0E17\u0E32\u0E17\u0E46"
        assertEquals(4, nanan.length)
        assertFalse(
            "ถ้า gate 1 จับไทยที่ไม่มี mark ได้เลย gate 2 ไม่ต้องมี — test นี้อ้างว่าไม่ใช่",
            CellLock.needsPerCluster(nanan),
        )
        // ส่วน row ที่มี ไม้หันอากาศ (U+0E31, w0) gate 1 ยังทำงานตามปกติ (round 23)
        assertTrue(CellLock.needsPerCluster("\u0E17\u0E31"))
    }
}
