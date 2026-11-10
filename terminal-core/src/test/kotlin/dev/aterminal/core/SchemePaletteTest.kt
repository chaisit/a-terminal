package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ticket tmux/03: index → ชื่อ field ของ palette (OSC 4 report ต้องสีเดียวกับที่ renderer วาด)
 * ลำดับ ≡ xterm/WT: 0-7 = normal (black..white), 8-15 = bright — ผิดลำดับ = vim theme เพี้ยนเงียบ
 */
class SchemePaletteTest {

    @Test
    fun `palette index maps to the scheme fields in xterm order`() {
        val s = Scheme(name = "t")
        assertEquals("#0C0C0C", s.paletteColor(0))  // black = bg value by default (Campbell)
        assertEquals("#C50F1F", s.paletteColor(1))  // red
        assertEquals("#CCCCCC", s.paletteColor(7))  // white
        assertEquals("#767676", s.paletteColor(8))  // brightBlack
        assertEquals("#E74856", s.paletteColor(9))  // brightRed
        assertEquals("#F2F2F2", s.paletteColor(15)) // brightWhite
    }

    @Test
    fun `out of range index has no color`() {
        val s = Scheme(name = "t")
        assertNull(s.paletteColor(-1))
        assertNull(s.paletteColor(16))
    }
}
