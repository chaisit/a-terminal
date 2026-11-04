package dev.aterminal.render

import org.junit.Assert.*
import org.junit.Test

/** Mark Headroom (round 23): clearance is a pure function of font size — one tunable constant. */
class MarkHeadroomTest {
    @Test fun `clearance at 14px is ceil of 0_18em`() {
        // ceil(14 * 0.18) = ceil(2.52) = 3
        assertEquals(3f, MarkHeadroom.clearancePx(14f), 0f)
    }

    @Test fun `small fonts still get at least two pixels`() {
        assertTrue(MarkHeadroom.clearancePx(8f) >= 2f)
    }

    @Test fun `clearance is monotonic in font size`() {
        var prev = MarkHeadroom.clearancePx(1f)
        var size = 1f
        while (size < 96f) {
            size += 0.5f
            val next = MarkHeadroom.clearancePx(size)
            assertTrue("clearance shrank at $size: $prev -> $next", next >= prev)
            prev = next
        }
    }
}
