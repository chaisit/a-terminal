package dev.aterminal.render.input

import dev.aterminal.engine.term.TermLine
import org.junit.Assert.*
import org.junit.Test

/**
 * gold = SelectionGoldData (gen จาก @xterm/headless 5.5.0 buffer จริง — tools/vt/selection-gold.mjs)
 * 10 เคสครอบคลุม: trimRight (space พิมพ์จริงไม่ trim), wrap-merge, NBSP→space, wide+dummy skip,
 * blank code0 กลางแถว = " ", scrollback rows
 */
class BufferTextTest {

    private fun buildLines(case: SelectionGoldData.Case): List<TermLine> = case.rows.map { row ->
        TermLine(case.cols).apply {
            isWrapped = row.wrapped
            for (i in 0 until case.cols) {
                // code0+w1 = ปล่อย null (unwritten ≡ xterm code0 — path ที่ engine ใช้จริง); ที่เหลือ materialize
                if (row.codes[i] == 0 && row.widths[i] == 1) continue
                val c = mutableAt(i)
                c.ch = if (row.codes[i] == 0) "" else String(Character.toChars(row.codes[i]))
                c.width = row.widths[i]
            }
        }
    }

    @Test fun `every gold case extracts identical text`() {
        for (case in SelectionGoldData.cases) {
            val sel = SelectionModel()
            // gold range = endColExclusive → SelectionModel เก็บ inclusive
            sel.setRange(case.sel[0], case.sel[1], case.sel[2] - 1, case.sel[3])
            assertEquals(case.name, case.expected, BufferText.extract(buildLines(case), sel))
        }
    }

    @Test fun `stale selection past eviction clamps instead of crashing`() {
        val case = SelectionGoldData.cases.first { it.name == "multiLines" }
        val lines = buildLines(case)
        val sel = SelectionModel().apply { setRange(0, 0, case.cols - 1, lines.size + 5) } // row เก่าถูก evict ไปได้
        // clamp ไปบรรทัดสุดท้ายจริง (มี 3 บรรทัดว่างท้าย buffer ของ gold case) — ใจความคือไม่ crash
        assertEquals("one\ntwo\nthree" + "\n".repeat(3), BufferText.extract(lines, sel))
    }

    @Test fun `inactive selection extracts empty string`() {
        val case = SelectionGoldData.cases.first { it.name == "plainWord" }
        assertEquals("", BufferText.extract(buildLines(case), SelectionModel()))
    }

    @Test fun `reverse drag equals forward drag via normalize`() {
        val case = SelectionGoldData.cases.first { it.name == "multiLines" }
        val lines = buildLines(case)
        val fwd = SelectionModel().apply { setRange(0, 0, 3, 2) }
        val rev = SelectionModel().apply { setRange(3, 2, 0, 0) }
        assertEquals(BufferText.extract(lines, fwd), BufferText.extract(lines, rev))
    }

    @Test fun `lineText blank-middle and clamp mirror translateToString`() {
        val line = TermLine(4)
        line.mutableAt(0).ch = "a"
        line.mutableAt(3).ch = "b" // col1-2 = unwritten (ch "" ≡ code0)
        assertEquals("a  b", BufferText.lineText(line, 0, 4)) // blank ใน clamp = " "
        assertEquals("a", BufferText.lineText(line, 0, 1))
        assertEquals("  b", BufferText.lineText(line, 1, 4))
        assertEquals("", BufferText.lineText(line, 0, 0))
    }

    @Test fun `wide cell emits once and its dummy never appears`() {
        val line = TermLine(4)
        line.mutableAt(0).ch = "a"
        with(line.mutableAt(1)) { ch = "한"; width = 2 }
        line.mutableAt(2).width = 0 // dummy
        line.mutableAt(3).ch = "b"
        assertEquals("a한b", BufferText.lineText(line, 0, 4))
        // clamp กลาง wide cell = xterm loop เดียวกัน (พิมพ์ head แล้วข้าม past end)
        assertEquals("a한", BufferText.lineText(line, 0, 2))
    }

    @Test fun `cellSpan maps string offsets to cells with wide dummy included`() {
        val line = TermLine(4)
        line.mutableAt(0).ch = "a"
        with(line.mutableAt(1)) { ch = "한"; width = 2 }
        line.mutableAt(2).width = 0 // dummy
        line.mutableAt(3).ch = "b"
        // lineText = "a한b" (index 0=a 1=한 2=b)
        assertEquals(0..0, BufferText.cellSpan(line, 0, 1))
        assertEquals(1..2, BufferText.cellSpan(line, 1, 1)) // wide = 2 columns รวม dummy
        assertEquals(3..3, BufferText.cellSpan(line, 2, 1))
        assertEquals(1..3, BufferText.cellSpan(line, 1, 2)) // 한b
        assertNull(BufferText.cellSpan(line, 3, 1)) // เลย trimRight (match หาย)
        // plain ascii: len n = n columns
        val plain = TermLine(8)
        "hello".forEachIndexed { i, ch -> plain.mutableAt(i).ch = ch.toString() }
        assertEquals(1..3, BufferText.cellSpan(plain, 1, 3))
        assertNull(BufferText.cellSpan(plain, 5, 1))
    }

    @Test fun `wordAt matches terminal html WORD semantics`() {
        assertEquals(0 to 4, BufferText.wordAt("hello world", 2))
        assertEquals(6 to 10, BufferText.wordAt("hello world", 6))
        assertEquals(4 to 22, BufferText.wordAt("see https://x.dev/a-b_c 80", 4)) // "https://x.dev/a-b_c" = 19 chars
        assertEquals(0 to 1, BufferText.wordAt("ab", 5)) // c = min(col, len-1)
        assertNull(BufferText.wordAt("", 0))
        assertNull(BufferText.wordAt(" x", 0)) // space ไม่ใช่ WORD
        // Java/JS \w = ASCII ทั้งคู่ — ไทยไม่ใช่ WORD class (≡ JS เดิม, ไม่ใช่ bug ของเรา)
        assertNull(BufferText.wordAt("ไทย x", 0))
        assertEquals(4 to 4, BufferText.wordAt("ไทย x", 4))
    }
}
