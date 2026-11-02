package dev.aterminal.render.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Finder golden — fixtures ≡ aterm-find.js (scan/resetIdx/next-prev wrap/report format)
 * ทุก expectation pin จาก logic ของ JS โดยตรง (อ่าน aterm-find.js recompute/show/next/prev/report)
 */
class FinderTest {

    private fun buf(vararg lines: String?): (Int) -> String? = { lines.getOrNull(it) }
    private fun size(n: Int) = n

    @Test fun `scan all rows multiple per row no overlap`() {
        val f = Finder().apply { setQuery("ab") }
        f.recompute(buf("ababab", "xx", "zab"), 3, true, 0)
        assertEquals(
            listOf(
                Finder.Match(0, 0, 2), Finder.Match(0, 2, 2), Finder.Match(0, 4, 2),
                Finder.Match(2, 1, 2),
            ),
            f.matches,
        )
        // overlap check ≡ JS: advance = c+len → "aaa" q "aa" = [0] เท่านั้น (indexOf ที่ 2 = -1)
        val g = Finder().apply { setQuery("aa") }
        g.recompute(buf("aaaa"), 1, true, 0)
        assertEquals(listOf(0, 2), g.matches.map { it.col })
    }

    @Test fun `case insensitive default and toggle`() {
        val f = Finder().apply { setQuery("AB") }
        f.recompute(buf("xabX"), 1, true, 0)
        assertEquals(1, f.matches.size) // default ignoreCase ≡ JS (query.toLowerCase)
        f.caseSensitive = true
        f.recompute(buf("xabX"), 1, true, 0)
        assertEquals(0, f.matches.size)
        assertEquals("0/0", f.count())
    }

    @Test fun `reset picks first match at or after viewportTop else wraps to first`() {
        val f = Finder().apply { setQuery("q") }
        f.recompute(buf("q", "x", "q", "x", "q"), 5, true, 2)
        assertEquals(1, f.idx) // row 2 (index 1 ใน matches rows[0,2,4])
        val g = Finder().apply { setQuery("q") }
        g.recompute(buf("q", "x", "q"), 3, true, 99) // ไม่เจอ >= top → idx 0 (JS findIndex<0 → 0)
        assertEquals(0, g.idx)
    }

    @Test fun `next prev wrap modulo like JS show`() {
        val f = Finder().apply { setQuery("q") }
        val b = buf("q", "x", "q", "q"); val n = 4
        f.recompute(b, n, true, 0)
        assertEquals(0, f.idx) // matches rows = [0,2,3]
        assertEquals(2, f.next(b, n, 0)?.row) // idx 1
        assertEquals(3, f.next(b, n, 0)?.row) // idx 2
        assertEquals(0, f.next(b, n, 0)?.row) // last+1 → wrap 0 (JS (idx+n)%n)
        assertEquals(3, f.prev(b, n, 0)?.row) // 0-1 → -1 → wrap ตัวท้าย
        assertEquals("3/3", f.count()) // idx=2 → "3/3" ≡ (idx+1)+'/'+n
    }

    @Test fun `prev from fresh reset lands on idx-1 wrapped, JS-identical`() {
        val f = Finder().apply { setQuery("q") }
        val b = buf("q", "q", "q"); val n = 3
        f.recompute(b, n, true, 1) // idx = 1 (row1)
        assertEquals(0, f.prev(b, n, 0)?.row) // idx=1 → 0
        assertEquals(2, f.prev(b, n, 0)?.row) // idx=0 → -1 → wrap 2
    }

    @Test fun `empty query and no-match report formats`() {
        val f = Finder()
        f.recompute(buf("anything"), 1, true, 0)
        assertEquals("", f.count()) // query ว่าง = "" ≡ JS
        f.setQuery("zz")
        f.recompute(buf("anything"), 1, true, 0)
        assertEquals("0/0", f.count())
        assertNull(f.next(buf("anything"), 1, 0)) // JS next บน matches ว่าง: recompute(false) → ว่าง → null
        assertEquals("0/0", f.count())
    }

    @Test fun `null lines skipped and recompute resets idx unconditionally`() {
        val f = Finder().apply { setQuery("q") }
        f.recompute(buf(null, "q"), 2, true, 0)
        assertEquals(0, f.idx)
        assertEquals(listOf(1), f.matches.map { it.row })
        f.recompute(buf(null, "q"), 2, false, 0) // resetIdx=false → idx -1 (JS: idx=-1 ทุก recompute)
        assertEquals(-1, f.idx)
        assertEquals("0/1", f.count()) // JS report: (idx+1)+'/'+n = 0/1 — format เดิมคงไว้
    }

    @Test fun `reset clears everything like closing the bar`() {
        val f = Finder().apply { setQuery("q") }
        f.recompute(buf("q"), 1, true, 0)
        f.reset()
        assertEquals("", f.count())
        assertEquals(-1, f.idx)
    }
}
