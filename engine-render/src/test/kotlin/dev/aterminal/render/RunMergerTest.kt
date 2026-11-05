package dev.aterminal.render

import dev.aterminal.engine.term.Cell
import dev.aterminal.engine.term.TermLine
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
        // "\u0E17\u0E31" = NO NU (U+0E17, w1) + MAI HAN-AKAT (U+0E31, w0) → engine merges into one cell
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

    // ===== perf 11-04 (mergeFrom): TermLine cell source + style-input cache — output must stay identical =====

    private fun lineOf(cells: List<Cell>): TermLine {
        val line = TermLine(cells.size)
        cells.forEachIndexed { i, c ->
            val t = line.mutableAt(i)
            t.ch = c.ch; t.width = c.width; t.fg = c.fg; t.fgMode = c.fgMode
            t.bg = c.bg; t.bgMode = c.bgMode; t.attrs = c.attrs
        }
        return line
    }

    @Test fun mergeLineEqualsMergeOnTheSameCells() {
        // [a][b bold][wide w2][dummy w0][c bg] — every branch of the merge loop in one row
        val cells = listOf(
            cell("a"), cell("b", at = Cell.B_BOLD), cell("\uD55C", 2), cell("", 0), cell("c", bg = 4, bgm = 1 shl 24),
        )
        assertEquals(RunMerger.merge(cells, pal), RunMerger.mergeLine(lineOf(cells), pal))
    }

    @Test fun mergeLineReadsUnwrittenCellsAsDefaultLikeGet() {
        // a cell the engine never wrote = null -> TermLine.DEFAULT (" " w1 default): same runs as merge() over blank Cells
        val written = listOf(cell("h"), cell("i"))
        val line = TermLine(4)
        written.forEachIndexed { i, c -> val t = line.mutableAt(i); t.ch = c.ch; t.width = c.width }
        val expected = RunMerger.merge(written + listOf(Cell(), Cell()), pal)
        assertEquals(expected, RunMerger.mergeLine(line, pal))
    }

    @Test fun styleCacheKeepsRunWhenRawFieldsDifferButStyleIsTheSame() {
        // different bg field but bgMode 0 (= default) -> same style as a plain cell -> still joins into one run
        // (a coarse cache "field changed = new run" fails here: inputs are compared first, resolved styles compared after)
        val runs = RunMerger.merge(listOf(cell("a"), cell("b", bg = 7, bgm = 0), cell("c", bg = 11, bgm = 0)), pal)
        assertEquals(1, runs.size)
        assertEquals("abc", runs[0].text)
    }

    @Test fun styleCacheIgnoresProtectBitLikeResolverDoes() {
        // B_PROTECT (512) is masked out by resolve -> a protected cell and a plain cell are the same style
        val runs = RunMerger.merge(listOf(cell("a"), cell("b", at = Cell.B_PROTECT), cell("c")), pal)
        assertEquals(1, runs.size)
        assertEquals("abc", runs[0].text)
    }

    @Test fun styleCacheStillSplitsWhenResolvedStyleActuallyChanges() {
        // inputs change AND the resolved style really changes (different C16 fg index) -> must split; cache may not swallow splits
        val runs = RunMerger.merge(
            listOf(Cell("a", 1, 1, 1 shl 24, -1, 0, 0), Cell("b", 1, 2, 1 shl 24, -1, 0, 0)), pal,
        )
        assertEquals(2, runs.size)
        assertEquals("a", runs[0].text); assertEquals("b", runs[1].text)
    }

    @Test fun styleCacheSurvivesSkippedDummyBetweenSameStyle() {
        // a dummy (w0) is skipped, so the cell after it breaks adjacency (i != next) = always a new run
        val runs = RunMerger.merge(listOf(cell("a"), cell("", 0), cell("b")), pal)
        assertEquals(2, runs.size)
    }
}
