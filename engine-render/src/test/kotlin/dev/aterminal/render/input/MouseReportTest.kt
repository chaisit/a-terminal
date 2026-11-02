package dev.aterminal.render.input

import dev.aterminal.engine.term.ModeState
import org.junit.Assert.*
import org.junit.Test

/**
 * byte-exact fixtures ≡ xterm.js 5.5.0 CoreMouseService (ดู header MouseReport.kn — source offset ใน bundle)
 * ครอบคลุมตามที่แผน T3 สั่ง: SGR / X10 (DEFAULT) coord ใหญ่ / URxvt(1015 = xterm ไม่รองรับ ≡ ไม่ map) /
 * wheel / drag / tap-pair / restrict ต่อ protocol / dedup / suppression byte>255
 */
class MouseReportTest {

    private val esc = "\u001b"

    private fun sgr(s: String) = (esc + s).toByteArray(Charsets.US_ASCII)

    @Test fun `sgr press release motion wheel byte exact`() {
        assertArrayEquals(sgr("[<0;1;1M"), MouseReport.encodeSgr(0, 1, 0, 0, false, false, false)) // press left
        assertArrayEquals(sgr("[<0;1;1m"), MouseReport.encodeSgr(0, 0, 0, 0, false, false, false)) // release → m
        assertArrayEquals(sgr("[<32;13;7M"), MouseReport.encodeSgr(0, 32, 12, 6, false, false, false)) // drag
        assertArrayEquals(sgr("[<64;13;7M"), MouseReport.encodeSgr(4, 0, 12, 6, false, false, false)) // wheel up 64
        assertArrayEquals(sgr("[<65;13;7M"), MouseReport.encodeSgr(4, 1, 12, 6, false, false, false)) // wheel down 65
        // wheel press ยัง 'M' (release rule กัน button4) — ไม่มี mouseup ของ wheel
        assertArrayEquals(sgr("[<64;1;1M"), MouseReport.encodeSgr(4, 0, 0, 0, false, false, false))
    }

    @Test fun `modifier bits and x10 release rule`() {
        assertEquals(16, MouseReport.buttonCode(0, 1, true, false, false, true)) // ctrl
        assertEquals(4, MouseReport.buttonCode(0, 1, false, true, false, true)) // shift
        assertEquals(8, MouseReport.buttonCode(0, 1, false, false, true, true)) // alt
        assertEquals(24, MouseReport.buttonCode(0, 1, true, false, true, true)) // ctrl+alt
        assertEquals(3, MouseReport.buttonCode(0, 0, false, false, false, false)) // X10 release = |3
        assertEquals(0, MouseReport.buttonCode(0, 0, false, false, false, true)) // SGR release = 0 (ใช้ m suffix)
        assertEquals(33, MouseReport.buttonCode(1, 32, false, false, false, true)) // right-drag = 1|32
        assertEquals(64 or 2, MouseReport.buttonCode(6, 1, false, false, false, true)) // button6 = 2|64
        assertEquals(128, MouseReport.buttonCode(8, 1, false, false, false, true)) // button8 = 128
    }

    @Test fun `x10 default encoding coord is one based plus 32`() {
        // press ที่ (0,0): code+32=' ', col1+32=33, row1+32=33 — JS col++ ก่อน encoder
        assertArrayEquals(
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), 32, 33, 33),
            MouseReport.encodeDefault(0, 1, 0, 0, false, false, false),
        )
        // col 95 (0-based) → 128 = raw byte เกิน ASCII — view ส่ง raw ตรง ๆ (ไม่มี latin1 problem แบบ JS)
        assertArrayEquals(
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), 32, 128.toByte(), 33),
            MouseReport.encodeDefault(0, 1, 95, 0, false, false, false),
        )
        // >255 = report ทั้งอันหาย (JS: encoder คืน "" — col 223 → 224+32 = 256)
        assertNull(MouseReport.encodeDefault(0, 1, 223, 0, false, false, false))
        assertNull(MouseReport.encodeDefault(0, 1, 0, 223, false, false, false))
        // ขอบก่อนหาย: col 222 → 255 ผ่าน
        assertNotNull(MouseReport.encodeDefault(0, 1, 222, 222, false, false, false))
    }

    @Test fun `tap pair equals sgr press then release at same cell`() {
        val p = MouseReport.encodeSgr(0, 1, 12, 6, false, false, false)
        val r = MouseReport.encodeSgr(0, 0, 12, 6, false, false, false)
        assertArrayEquals((esc + "[<0;13;7M" + esc + "[<0;13;7m").toByteArray(Charsets.US_ASCII), p + r)
    }

    // ---- emitter ≡ triggerMouseEvent (guards + restrict + dedup) ----

    private val em = MouseEmitter()

    @Test fun `none protocol reports nothing`() {
        assertNull(em.report(MouseProtocol.NONE, MouseEncoding.SGR, 39, 27, 0, 1, 0, 0))
    }

    @Test fun `bounds and illegal combos rejected`() {
        assertNull(em.report(MouseProtocol.ANY, MouseEncoding.SGR, 39, 27, 0, 1, 39, 0)) // col outside
        assertNull(em.report(MouseProtocol.ANY, MouseEncoding.SGR, 39, 27, 0, 1, 0, 27)) // row outside
        assertNull(em.report(MouseProtocol.ANY, MouseEncoding.SGR, 39, 27, 4, 32, 0, 0)) // wheel+motion
        assertNull(em.report(MouseProtocol.ANY, MouseEncoding.SGR, 39, 27, 3, 1, 0, 0)) // button3 press
        assertNull(em.report(MouseProtocol.ANY, MouseEncoding.SGR, 39, 27, 3, 0, 0, 0)) // button3 release
    }

    @Test fun `vt200 drops motion but keeps click and wheel`() {
        assertNotNull(em.report(MouseProtocol.VT200, MouseEncoding.SGR, 39, 27, 0, 1, 1, 1))
        assertNotNull(em.report(MouseProtocol.VT200, MouseEncoding.SGR, 39, 27, 0, 0, 1, 1))
        assertNull(em.report(MouseProtocol.VT200, MouseEncoding.SGR, 39, 27, 0, 32, 1, 1)) // motion
        assertNotNull(em.report(MouseProtocol.VT200, MouseEncoding.SGR, 39, 27, 4, 0, 1, 1)) // wheel ผ่าน (≡ vim =click ได้ wheel)
    }

    @Test fun `drag protocol keeps button motion, drops buttonless motion`() {
        assertNotNull(em.report(MouseProtocol.DRAG, MouseEncoding.SGR, 39, 27, 0, 32, 2, 2))
        assertNull(em.report(MouseProtocol.DRAG, MouseEncoding.SGR, 39, 27, 3, 32, 2, 2))
        assertNotNull(em.report(MouseProtocol.ANY, MouseEncoding.SGR, 39, 27, 3, 32, 2, 2)) // ANY = hover ผ่าน
    }

    @Test fun `x10 protocol press only and strips modifiers`() {
        assertNull(em.report(MouseProtocol.X10, MouseEncoding.DEFAULT, 39, 27, 0, 0, 1, 1)) // release ตาย
        assertNull(em.report(MouseProtocol.X10, MouseEncoding.DEFAULT, 39, 27, 4, 0, 1, 1)) // wheel ตาย
        // ctrl+press → strip = code 0 (ไม่ใช่ 16)
        assertArrayEquals(
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), 32, 34, 34),
            em.report(MouseProtocol.X10, MouseEncoding.DEFAULT, 39, 27, 0, 1, 1, 1, ctrl = true),
        )
    }

    @Test fun `motion dedup equals last event rule, press resets it`() {
        assertNotNull(em.report(MouseProtocol.DRAG, MouseEncoding.SGR, 39, 27, 0, 32, 5, 5))
        assertNull(em.report(MouseProtocol.DRAG, MouseEncoding.SGR, 39, 27, 0, 32, 5, 5)) // ซ้ำ = xterm dedup
        assertNotNull(em.report(MouseProtocol.DRAG, MouseEncoding.SGR, 39, 27, 0, 32, 6, 5)) // cell เปลี่ยนผ่าน
        assertNotNull(em.report(MouseProtocol.DRAG, MouseEncoding.SGR, 39, 27, 0, 1, 6, 5)) // press
        assertNotNull(em.report(MouseProtocol.DRAG, MouseEncoding.SGR, 39, 27, 0, 32, 6, 5)) // motion = last event = press → ผ่าน
    }

    // ---- MouseGate ≡ DECSET handler ----

    @Test fun `gate precedence and urxvt ignored`() {
        val m = ModeState()
        assertEquals(MouseProtocol.NONE, MouseGate.protocol(m))
        assertFalse(MouseGate.active(m))
        m.mouseClick = true
        assertEquals(MouseProtocol.VT200, MouseGate.protocol(m))
        m.mouseDrag = true
        assertEquals(MouseProtocol.DRAG, MouseGate.protocol(m))
        m.mouseAny = true
        assertEquals(MouseProtocol.ANY, MouseGate.protocol(m))
        assertEquals(MouseEncoding.DEFAULT, MouseGate.encoding(m))
        m.mouseSgr = true
        assertEquals(MouseEncoding.SGR, MouseGate.encoding(m))
        // 1015/1005: xterm.js 5.5.0 ไม่รองรับเอง → flag ต้องไม่มีผลอะไรเลย (≡)
        val u = ModeState().apply { mouseUrxvt = true }
        assertEquals(MouseProtocol.NONE, MouseGate.protocol(u))
        assertEquals(MouseEncoding.DEFAULT, MouseGate.encoding(u))
        assertFalse(MouseGate.active(u))
    }
}
