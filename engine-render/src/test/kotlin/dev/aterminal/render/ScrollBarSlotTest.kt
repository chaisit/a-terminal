package dev.aterminal.render

import org.junit.Assert.*
import org.junit.Test

/**
 * Scrollbar slot (round 23 T5): the 4px thumb must never paint over a live column.
 * gridCols reserves the slot from the view width; thumbX clamps the thumb to the right of
 * the painted grid even in transient geometry.
 */
class ScrollBarSlotTest {
    @Test fun `gridCols reserves the thumb slot when slack would be zero`() {
        // 1080 / 8 = 135 fills the view exactly (slack 0 → old thumb covered col 135); reserved = 134
        assertEquals(134, ScrollBarSlot.gridCols(1080f, 8f))
    }

    @Test fun `gridCols unchanged when the leftover already fits the thumb`() {
        // 1078 / 8 = 134 (slack 6 ≥ 4) → reserving costs nothing
        assertEquals(134, ScrollBarSlot.gridCols(1078f, 8f))
    }

    @Test fun `thumbX right-aligns in the unused margin`() {
        assertEquals(1076f, ScrollBarSlot.thumbX(1080f, 1000f), 0f)
    }

    @Test fun `thumbX never sits left of the painted grid edge`() {
        // leftover 2px < thumb: thumb stays flush right of the grid (clipped by the view edge)
        assertEquals(1078f, ScrollBarSlot.thumbX(1080f, 1078f), 0f)
    }

    @Test fun `reserved slot always leaves at least thumbW of unused margin`() {
        // the invariant the fix rests on, swept over realistic widths and cell widths
        for (width in 1..1200) {
            var cellW = 2f
            while (cellW <= 24f) {
                val cols = ScrollBarSlot.gridCols(width.toFloat(), cellW)
                val gridW = cols * cellW
                if (cols > 1) {
                    // reservation holds: the slot is always there → thumb is right-aligned and clear of the grid
                    assertTrue("slack < thumb at w=$width cellW=$cellW", width - gridW >= ScrollBarSlot.THUMB_W)
                    assertEquals("thumb not right-aligned at w=$width cellW=$cellW", width - ScrollBarSlot.THUMB_W,
                        ScrollBarSlot.thumbX(width.toFloat(), gridW), 0f)
                } else {
                    // degenerate 1-col view: clamp keeps the thumb at the grid edge, never over it
                    assertTrue("thumb over grid at w=$width cellW=$cellW", ScrollBarSlot.thumbX(width.toFloat(), gridW) >= minOf(gridW, width.toFloat()))
                }
                cellW += 0.5f
            }
        }
    }
}
