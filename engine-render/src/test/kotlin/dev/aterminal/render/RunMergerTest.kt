package dev.aterminal.render

import dev.aterminal.engine.term.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun invisibleGlyphBecomesBlankButKeepsCell() { // SGR 8: glyph ซ่อนทั่ว body (≡ xterm) — bg/width คง, run ไม่หาย
        val runs = RunMerger.merge(listOf(cell("a"), cell("b", at = Cell.B_INVISIBLE), cell("c")), pal)
        assertEquals(1, runs.size) // style เท่า plain → run เดียว, glyph = " "
        assertEquals("a c", runs[0].text)
        assertEquals(0, runs[0].startCol); assertEquals(3, runs[0].endCol)
    }

    @Test fun wideIsOwnRunAndDummySkipped() {
        // [한 w2][dummy w0][x] → สอง run; endCol ของ wide = +2; dummy ไม่ join ข้าม
        val runs = RunMerger.merge(listOf(cell("\uD55C", 2), cell("", 0), cell("x")), pal)
        assertEquals(2, runs.size)
        assertEquals("\uD55C", runs[0].text); assertEquals(0, runs[0].startCol); assertEquals(2, runs[0].endCol)
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

    // ===== CellLock (round 23): per-cluster cell lock predicate for the API31+ draw path =====
    // Rule: true iff any codepoint in the text has Unicode.width(cp) == 0 (combining/zero-width).
    // Such runs must be anchored per cluster at their own column instead of one run-relative shape.

    @Test fun cellLockPureAsciiIsFalse() {
        assertFalse(CellLock.needsPerCluster("abc"))
        assertFalse(CellLock.needsPerCluster(""))
        assertFalse(CellLock.needsPerCluster("box │ dash")) // U+2502 = width 1, not zero
    }

    @Test fun cellLockThaiClusterIsTrue() {
        // "\u0E17\u0E31" = THO THAHAN (U+0E17, w1) + MAI HAN-AKAT (U+0E31, w0) → engine merges into one cell
        assertTrue(CellLock.needsPerCluster("\u0E17\u0E31"))
        // mixed run: ASCII run-merge carries the zero-width cp along → whole run needs the lock
        assertTrue(CellLock.needsPerCluster("ab" + "\u0E17\u0E31" + "cd"))
    }

    @Test fun cellLockWideEmojiIsFalse() {
        // U+1F600 (surrogate pair) = width 2 → placement handled by the wide-single-run rule, not the lock
        assertFalse(CellLock.needsPerCluster("\uD83D\uDE00"))
        assertFalse(CellLock.needsPerCluster("\uD55C"))
    }

    @Test fun cellLockLoneZeroWidthMarkIsTrue() {
        // standalone leading mark (engine gives it its own w0-head cell)
        assertTrue(CellLock.needsPerCluster("\u0E48"))
        assertTrue(CellLock.needsPerCluster("\u0E48" + "\u0E01"))
        // U+0E32 SARA AA is a SPACING vowel (Lo, width 1 in xterm Unicode 11) \u2014 no lock
        assertFalse(CellLock.needsPerCluster("\u0E32" + "\u0E01"))
    }
}
