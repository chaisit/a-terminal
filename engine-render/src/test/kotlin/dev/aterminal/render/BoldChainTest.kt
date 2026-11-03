package dev.aterminal.render

import dev.aterminal.engine.TerminalEngine
import dev.aterminal.engine.term.Cell
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * full-chain (engine SGR → Cell → StyleResolver) สำหรับ bold-brighten (round 19 D4):
 * JVM = deterministic, device = visual confirmation — ไม่ครอบคลุม cursor (วาดบน Canvas = ยืนยันบนเครื่อง)
 */
class BoldChainTest {
    private val pal = Palette(
        colors16 = IntArray(16) { TerminalView.DEFAULT16[it] },
        defaultFg = 0xFFCCCCCC.toInt(), defaultBg = 0xFF0C0C0C.toInt(), cursor = 0xFFCCCCCC.toInt(),
    )

    private fun feed(s: String): TerminalEngine {
        val e = TerminalEngine(80, 24)
        e.write(s.toByteArray(Charsets.US_ASCII))
        return e
    }

    private fun cellAt(e: TerminalEngine, x: Int): Cell = e.buf.lines[e.buf.y].get(x)

    @Test fun boldAnsi31ResolvesToBrightRedViaEngine() {
        val e = feed("\u001b[31m\u001b[1mR")
        val c = cellAt(e, 0)
        assertEquals(Cell.B_BOLD, c.attrs and Cell.B_BOLD)
        assertEquals(TerminalView.DEFAULT16[9], StyleResolver.resolve(c, pal).fg) // #E74856
    }

    @Test fun boldSgr256Index1ViaEngineStaysBasicRed() { // ESC[38;5;1m + bold = ไม่ brighten (device-proven ≡ xterm)
        val e = feed("\u001b[38;5;1m\u001b[1mR")
        val c = cellAt(e, 0)
        assertEquals(Cell.B_BOLD, c.attrs and Cell.B_BOLD)
        assertEquals(TerminalView.DEFAULT16[1], StyleResolver.resolve(c, pal).fg) // #C50F1F
    }

    @Test fun boldRgbViaEngineStaysExact() {
        val e = feed("\u001b[38;2;1;2;3m\u001b[1mR")
        assertEquals(0xFF000000.toInt() or 0x010203, StyleResolver.resolve(cellAt(e, 0), pal).fg)
    }
}
