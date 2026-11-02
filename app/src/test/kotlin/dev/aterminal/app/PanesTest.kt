package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TerminalTab — โมเดล pane ของหนึ่ง tab (round 5) ทดสอบ JVM ล้วน
 * handle = String (จริง = TerminalSession; model ไม่รู้จัก type นั้นเลย)
 */
class PanesTest {

    @Test
    fun `split appends and focuses new pane, third split rejected`() {
        val t = TerminalTab<String>(1L)
        assertTrue(t.split("a"))
        assertEquals(1, t.panes.size)
        assertEquals(0, t.focused)                          // pane index 0 = ตัวใหม่ (ยังไม่มี pane เก่า)
        assertTrue(t.split("b"))
        assertEquals(listOf("a", "b"), t.panes)
        assertSame("b", t.focusedPane())
        assertFalse(t.split("c"))                           // cap = 2 (ผู้ใช้เคาะขอบเขต)
        assertEquals(2, t.panes.size)
    }

    @Test
    fun `closePane returns handle and moves focus like WT`() {
        val t = TerminalTab<String>(1L)
        t.split("a"); t.split("b")                          // focused = 1 ("b")
        assertSame("a", t.closePane(0))
        assertEquals(listOf("b"), t.panes)
        assertEquals(0, t.focused)                          // index เล็กกว่า focused → ถอยหนึ่ง (เหลือ 0)

        t.split("c")                                        // [b, c] focused = 1
        assertSame("c", t.closePane(1))                     // ปิดตัวที่ focus เอง → clamp ลง
        assertEquals(0, t.focused)
        assertSame("b", t.focusedPane())
        assertNull(t.closePane(5))                          // เกินขอบเขต = null
    }

    @Test
    fun `closing all panes leaves tab empty with null focus`() {
        val t = TerminalTab<String>(1L)
        t.split("a")
        assertSame("a", t.closePane(0))
        assertTrue(t.panes.isEmpty())
        assertEquals(0, t.focused)
        assertNull(t.focusedPane())
    }

    @Test
    fun `closeOthers keeps only focused at index zero and returns rest`() {
        val t = TerminalTab<String>(1L)
        t.split("a"); t.split("b")
        t.setFocused(0)                                     // focus = a
        assertEquals(listOf("b"), t.closeOthers())
        assertEquals(listOf("a"), t.panes)
        assertEquals(0, t.focused)
        assertEquals(emptyList<String>(), TerminalTab<String>(2L).closeOthers()) // tab ว่าง = ไม่มีอะไรคืน
    }

    @Test
    fun `orientation toggle and explicit set`() {
        val t = TerminalTab<String>(1L)
        assertEquals(SplitOrientation.HORIZONTAL, t.orientation)   // เดิม = ซ้าย-ขวา
        t.toggleOrientation()
        assertEquals(SplitOrientation.VERTICAL, t.orientation)
        t.toggleOrientation()
        assertEquals(SplitOrientation.HORIZONTAL, t.orientation)
        t.setOrientation(SplitOrientation.VERTICAL)         // เมนู splitPane down ระบุตรง ๆ
        assertEquals(SplitOrientation.VERTICAL, t.orientation)
    }

    @Test
    fun `fraction clamps to visible range`() {
        val t = TerminalTab<String>(1L)
        assertEquals(0.5f, t.fraction, 0.0001f)             // ค่าเริ่มต้น = ค่า default ของ WT `size`
        t.setFraction(0.9f); assertEquals(0.75f, t.fraction, 0.0001f)
        t.setFraction(0.01f); assertEquals(0.25f, t.fraction, 0.0001f)
        t.setFraction(0.4f); assertEquals(0.4f, t.fraction, 0.0001f)
    }

    @Test
    fun `setFocused and indexOf reject unknown handles`() {
        val t = TerminalTab<String>(1L)
        t.split("a"); t.split("b")
        assertTrue(t.setFocused(0))
        assertEquals(0, t.focused)
        assertFalse(t.setFocused(2))                        // index เกิน = ไม่ขยับ
        assertEquals(0, t.focused)
        assertEquals(1, t.indexOf("b"))
        assertEquals(-1, t.indexOf("zzz"))
    }
}
