package dev.aterminal.render.input

import org.junit.Assert.*
import org.junit.Test

/** fixtures ≡ aterm-keys.js markMode (L113–151) — move matrix, linear wrap, clamp, byte table */
class MarkStateMachineTest {

    private fun entered(row: Int = 5, col: Int = 10, cols: Int = 20) =
        MarkStateMachine().also { it.enter(row, col, cols) }

    @Test fun `enter anchors at cursor clamped to last col`() {
        val m = entered(row = 3, col = 19, cols = 20)
        assertTrue(m.active)
        assertEquals(19, m.anchorCol); assertEquals(3, m.anchorRow)
        // posAbs: col = min(cursorX, cols-1) — cursor pending-wrap ที่ cols ก็ clamp
        val m2 = MarkStateMachine().apply { enter(3, 20, 20) }
        assertEquals(19, m2.anchorCol)
    }

    @Test fun `move matrix basic`() {
        val m = entered()
        assertTrue(m.move("right", 20, 99, 24)); assertEquals(11, m.curCol)
        assertTrue(m.move("left", 20, 99, 24)); assertEquals(10, m.curCol)
        assertTrue(m.move("down", 20, 99, 24)); assertEquals(6, m.curRow)
        assertTrue(m.move("up", 20, 99, 24)); assertEquals(5, m.curRow)
        assertTrue(m.move("pagedown", 20, 99, 24)); assertEquals(29, m.curRow)
        assertTrue(m.move("pageup", 20, 99, 24)); assertEquals(5, m.curRow)
        assertTrue(m.move("home", 20, 99, 24)); assertEquals(0, m.curRow); assertEquals(0, m.curCol)
        assertTrue(m.move("end", 20, 99, 24)); assertEquals(99, m.curRow); assertEquals(19, m.curCol)
        assertFalse(m.move("enter", 20, 99, 24)) // ไม่ใช่ move name = ไม่ handled
    }

    @Test fun `col wrap linear buffer แบบ JS`() {
        val m = entered(row = 5, col = 0)
        assertTrue(m.move("left", 20, 99, 24))  // col -1 → +20, row -1
        assertEquals(19, m.curCol); assertEquals(4, m.curRow)
        m.move("right", 20, 99, 24)             // กลับ (5,0)
        assertEquals(0, m.curCol); assertEquals(5, m.curRow)
        m.move("right", 20, 99, 24); m.move("right", 20, 99, 24)
        m.move("right", 20, 99, 24)             // col 3 — ไม่ข้าม
        assertEquals(3, m.curCol); assertEquals(5, m.curRow)
        val m2 = entered(row = 2, col = 19)
        assertTrue(m2.move("right", 20, 99, 24)) // col 20 → -20, row +1
        assertEquals(0, m2.curCol); assertEquals(3, m2.curRow)
    }

    @Test fun `wrap then clamp JS order ที่ขอบบน`() {
        // (0,0) left: wrap → (row -1, col 19) แล้ว clamp row → 0 — col NOT re-clamped (JS = wrap ก่อน clamp)
        val m = entered(row = 0, col = 0)
        assertTrue(m.move("left", 20, 99, 24))
        assertEquals(0, m.curRow); assertEquals(19, m.curCol)
    }

    @Test fun `row clamp at both ends`() {
        val m = entered(row = 1, col = 5)
        m.move("up", 20, 99, 24); m.move("up", 20, 99, 24); m.move("up", 20, 99, 24)
        assertEquals(0, m.curRow)
        repeat(5) { m.move("pagedown", 20, 99, 24) } // 24×5 = 120 > 99 → clamp (JS = ทุก move ท้ายฟังก์ชัน clamp)
        assertEquals(99, m.curRow)
    }

    @Test fun `selectionSpan normalizes both directions incl cross-row`() {
        val m = entered(row = 2, col = 3, cols = 10)
        m.move("end", 10, 5, 24) // cur → (5,9); anchor (2,3): a=23 < c=59
        assertArrayEquals(intArrayOf(3, 2, 9, 5), m.selectionSpan(10))
        m.move("home", 10, 5, 24) // cur (0,0) < anchor → สลับ
        assertArrayEquals(intArrayOf(0, 0, 3, 2), m.selectionSpan(10))
        // enter = cell เดียว (≡ select(x,y,1))
        val one = entered(row = 4, col = 7, cols = 10)
        assertArrayEquals(intArrayOf(7, 4, 7, 4), one.selectionSpan(10))
    }

    @Test fun `consume byte table exactly like JS`() {
        val m = entered()
        assertEquals(MarkKey.Move("up"), m.consume("\u001b[A"))
        assertEquals(MarkKey.Move("down"), m.consume("\u001b[B"))
        assertEquals(MarkKey.Move("right"), m.consume("\u001b[C"))
        assertEquals(MarkKey.Move("left"), m.consume("\u001b[D"))
        assertEquals(MarkKey.CopyExit, m.consume("\r"))
        assertEquals(MarkKey.CopyExit, m.consume("\n"))
        assertEquals(MarkKey.Passthrough, m.consume("x"))
        assertEquals(MarkKey.Passthrough, m.consume("\u001b[E")) // ไม่ใช่ arrow = unmatched
        assertEquals(MarkKey.Exit, m.consume("\u001b")) // state machine คืน Exit — view ต่างหากที่ exit จริง (JS: exitMark ฝั่ง caller)
        assertEquals(MarkKey.Move("up"), m.consume("\u001b[A")) // consume ไม่แก้ state (move ก็ paint ฝั่ง view)
        m.exit()
        assertEquals(MarkKey.Off, m.consume("\u001b")) // ปิด = Off ทุก byte
    }

    @Test fun `inactive move no-op and exit clears flag`() {
        val m = MarkStateMachine()
        assertFalse(m.move("up", 20, 99, 24))
        m.enter(0, 0, 10)
        m.exit()
        assertFalse(m.active)
    }
}
