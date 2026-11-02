package dev.aterminal.render

import dev.aterminal.engine.TerminalEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * OPEN #2 (round 11 T8) — ตรวจ claim "native ED(2) ล้าง scrollback ≢ xterm.js"
 * วาง test ฝั่ง :engine-render (terminal ผ่าน API สาธารณะของ :engine — purity lock ไม่โดนแตะ)
 *
 * gold อ้างอิง (@xterm/headless 5.5.0 semantics):
 *  - ESC[2J = blank viewport เท่านั้น, history (scrollback) คงเดิม, baseY ไม่ลด
 *  - ESC[3J = ล้าง history (≡ `clear` ยุคใหม่ที่ยิง E3 ด้วย — scrollbar หายหลัง clear = ถูกต้อง ≢ bug)
 */
class EdScrollbackTest {

    private fun lineText(e: TerminalEngine, srow: Int): String =
        (0 until e.cols).joinToString("") { e.buf.line(srow).get(it).ch }.trim()

    private fun filled(): Pair<TerminalEngine, Int> {
        val e = TerminalEngine(20, 5, 100)
        // 20 บรรทัดใน viewport 5 → scrollback ต้องโต
        for (i in 1..20) e.write("L$i\r\n".toByteArray())
        val grown = e.buf.lines.size
        assertTrue("scrollback ต้องโตก่อน (lines=$grown)", grown > 5)
        return e to grown
    }

    @Test fun `ED2 keeps scrollback, blanks viewport`() {
        val (e, grown) = filled()
        e.write("\u001b[2J".toByteArray())
        assertEquals("ED(2) ห้ามแตะ history length", grown, e.buf.lines.size)
        assertTrue("baseY ต้องคง (>0)", e.buf.baseY > 0)
        // viewport (srow 0..4 → abs ท้าย lines) กล้างทุก row — line(srow) map ผ่าน baseY
        for (r in 0 until 5) {
            assertEquals("srow $r ต้อง blank", "", lineText(e, r))
        }
        // เนื้อ scrollback (abs row 0) ยังอยู่
        assertEquals("L1", lineText(e, -e.buf.baseY))
    }

    @Test fun `ED3 clears history to viewport size`() {
        val (e, grown) = filled()
        e.write("\u001b[3J".toByteArray())
        assertTrue("ED(3) ต้องลด history → rows", grown > e.buf.lines.size)
        assertEquals(5, e.buf.lines.size)
        assertEquals(0, e.buf.baseY)
    }

    @Test fun `ED2 then ED3 sequence = clear semantics`() {
        // ≡ bash clear ยุคใหม่: H + 2J + 3J — ผลรวม = scrollback หาย (ถูกต้อง ≡ xterm, ปิด OPEN #2)
        val (e, _) = filled()
        e.write("\u001b[H\u001b[2J\u001b[3J".toByteArray())
        assertEquals(5, e.buf.lines.size)
        assertEquals(0, e.buf.baseY)
    }
}
