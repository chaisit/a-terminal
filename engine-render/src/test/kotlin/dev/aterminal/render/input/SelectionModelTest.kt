package dev.aterminal.render.input

import org.junit.Assert.*
import org.junit.Test

class SelectionModelTest {

    @Test fun `normalize forward keeps order`() {
        val m = SelectionModel()
        m.setRange(3, 1, 7, 4)
        assertEquals(listOf(3, 1, 7, 4), m.normalized().toList())
    }

    @Test fun `normalize swaps reversed range row-major`() {
        val m = SelectionModel()
        m.setRange(7, 4, 3, 1) // drag ขึ้นบน — ≡ selectCells ฝั่ง JS ที่สลับ i0/i1
        assertEquals(listOf(3, 1, 7, 4), m.normalized().toList())
    }

    @Test fun `normalize swaps reversed same-row range`() {
        val m = SelectionModel()
        m.setRange(9, 2, 4, 2)
        assertEquals(listOf(4, 2, 9, 2), m.normalized().toList())
    }

    @Test fun `rowRange covers first middle last correctly`() {
        val m = SelectionModel()
        m.setRange(3, 1, 7, 4)
        assertNull(m.rowRange(0))
        assertEquals(3..Int.MAX_VALUE, m.rowRange(1))
        assertEquals(0..Int.MAX_VALUE, m.rowRange(2))
        assertEquals(0..7, m.rowRange(4))
        assertNull(m.rowRange(5))
    }

    @Test fun `rowRange single-row selection clipped both sides`() {
        val m = SelectionModel()
        m.selectWord(2, 6, 3)
        assertEquals(2..6, m.rowRange(3))
        assertNull(m.rowRange(2))
    }

    @Test fun `extend then clear lifecycle`() {
        val m = SelectionModel()
        assertFalse(m.active)
        m.selectWord(2, 6, 3)
        assertTrue(m.active)
        m.extend(10, 5)
        assertEquals(listOf(2, 3, 10, 5), m.normalized().toList())
        m.clear()
        assertFalse(m.active)
        assertNull(m.rowRange(3))
    }

    @Test fun `silent flag set by caller after selection, reset by new selection`() {
        val m = SelectionModel()
        assertFalse(m.silent)
        m.selectWord(0, 3, 0)
        m.silent = true // ≡ find/mark วาด selection (T6/T7 pattern)
        m.extend(5, 0)
        assertTrue(m.silent && m.active) // extend ของ silent selection ยัง silent
        m.selectWord(1, 2, 1) // selection ใหม่ = gesture → reset
        assertFalse(m.silent)
    }
}
