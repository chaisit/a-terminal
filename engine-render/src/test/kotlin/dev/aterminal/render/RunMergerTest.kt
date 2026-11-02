package dev.aterminal.render

import dev.aterminal.engine.term.Cell
import org.junit.Assert.assertEquals
import org.junit.Test

class RunMergerTest {
    private val pal = Palette(IntArray(16) { it }, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    private fun cell(ch: String, w: Int = 1, at: Int = 0, bg: Int = -1, bgm: Int = 0) = Cell(ch, w, -1, 0, bg, bgm, at)

    @Test fun plainAsciiJoinsIntoOneRun() {
        val runs = RunMerger.merge(listOf(cell("a"), cell("b"), cell("c")), pal)
        assertEquals(1, runs.size)
        assertEquals("abc", runs[0].text); assertEquals(0, runs[0].startCol); assertEquals(3, runs[0].endCol)
    }

    @Test fun styleChangeSplitsRun() {
        val runs = RunMerger.merge(listOf(cell("a"), cell("b", at = Cell.B_BOLD), cell("c", at = Cell.B_BOLD)), pal)
        assertEquals(2, runs.size)
        assertEquals("a", runs[0].text); assertEquals("bc", runs[1].text); assertEquals(1, runs[1].startCol)
    }

    @Test fun wideIsOwnRunAndDummySkipped() {
        // [한 w2][dummy w0][x] → สอง run; endCol ของ wide = +2; dummy ไม่ join ข้าม
        val runs = RunMerger.merge(listOf(cell("한", 2), cell("", 0), cell("x")), pal)
        assertEquals(2, runs.size)
        assertEquals("한", runs[0].text); assertEquals(0, runs[0].startCol); assertEquals(2, runs[0].endCol)
        assertEquals("x", runs[1].text); assertEquals(2, runs[1].startCol); assertEquals(3, runs[1].endCol)
    }

    @Test fun emptyChDumpsAsSpaceInText() {
        val runs = RunMerger.merge(listOf(cell("", 1), cell("", 1)), pal)
        assertEquals("  ", runs[0].text)
    }

    @Test fun bgSplit() {
        val runs = RunMerger.merge(listOf(cell("a"), cell("b", bg = 4, bgm = 1 shl 24), cell("c", bg = 4, bgm = 1 shl 24)), pal)
        assertEquals(2, runs.size)
        assertEquals("bc", runs[1].text)
    }
}
