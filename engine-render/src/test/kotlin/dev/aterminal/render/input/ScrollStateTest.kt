package dev.aterminal.render.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1 pass criteria — sticky/pinned/clamp/reveal ≡ กติกา xterm + JS reveal ที่ port */
class ScrollStateTest {

    @Test fun `at bottom follows output (sticky)`() {
        val s = ScrollState()
        s.onOutput(0, 0)
        s.onOutput(0, 5) // 5 บรรทัดเข้า, baseY 0→5 — user อยู่ bottom → follow
        assertEquals(5, s.top)
        assertTrue(s.isAtBottom(5))
    }

    @Test fun `scrolled up stays pinned on output`() {
        val s = ScrollState()
        s.onOutput(0, 100)
        s.scrollBy(-10, 100) // ดูเก่าขึ้น 10
        assertEquals(90, s.top)
        s.onOutput(100, 105) // output เข้า 5 — ต้องไม่ดึงกลับ
        assertEquals(90, s.top)
        assertFalse(s.isAtBottom(105))
    }

    @Test fun `scroll back to bottom re-attaches`() {
        val s = ScrollState()
        s.onOutput(0, 50)
        s.scrollBy(-20, 50)
        s.toBottom(50)
        s.onOutput(50, 60)
        assertEquals(60, s.top) // กลับ sticky แล้ว
    }

    @Test fun `clamps at both ends`() {
        val s = ScrollState()
        s.onOutput(0, 10)
        s.scrollBy(1000, 10)
        assertEquals(10, s.top)
        s.scrollBy(-1000, 10)
        assertEquals(0, s.top)
    }

    @Test fun `resize shrink clamps, grow does not follow`() {
        val s = ScrollState()
        s.onOutput(0, 100)
        s.top // 100
        s.clamp(40) // rows โตขึ้น/eviction — baseY หด
        assertEquals(40, s.top)
        s.scrollBy(-5, 40)
        s.clamp(60) // baseY โต — ห้ามเลื่อน top เอง
        assertEquals(35, s.top)
    }

    @Test fun `page moves by rows and clamps`() {
        val s = ScrollState()
        s.onOutput(0, 100)
        s.pageUp(24, 100); assertEquals(76, s.top)
        s.pageUp(24, 100); assertEquals(52, s.top)
        s.pageDown(1000, 100); assertEquals(100, s.top)
    }

    @Test fun `reveal centers only when outside viewport`() {
        val s = ScrollState()
        s.onOutput(0, 200)
        s.scrollBy(-200, 200) // top = 0, viewport 24
        assertFalse(s.revealCenter(10, 24, 200)) // ใน [0,24) — ไม่เลื่อน
        assertEquals(0, s.top)
        assertTrue(s.revealCenter(150, 24, 200)) // นอก → center = 150-12
        assertEquals(138, s.top)
        assertTrue(s.revealCenter(5, 24, 200))
        assertEquals(0, s.top.coerceIn(0, 200)) // 5-12 ลบ → clamp 0
    }

    @Test fun `attach resets to bottom`() {
        val s = ScrollState()
        s.onOutput(0, 50)
        s.scrollBy(-30, 50)
        s.attach(0) // engine ใหม่ (empty)
        assertEquals(0, s.top)
    }

    // ── OPEN #3 (round 12): eviction compensation — gold ≡ xterm probe (evidence/round-11/xterm-sticky-probe.txt)

    @Test fun `pinned view content-locks under eviction - gold probe ydisp 20 to 14`() {
        val s = ScrollState()
        s.onOutput(0, 30)
        s.scrollBy(-10, 30) // user scroll ขึ้น: top = 20 (≡ probe viewportY 20)
        // flood ที่ cap: evicted = 6, baseY คงที่ 30 → probe: xterm ydisp 20 → 14, stable:true
        s.onOutput(30, 30, evicted = 6)
        assertEquals(14, s.top)
    }

    @Test fun `eviction floors at zero`() {
        val s = ScrollState()
        s.onOutput(0, 30)
        s.toTop(30) // top = 0 — ดูบรรทัดเก่าที่สุด
        s.onOutput(30, 30, evicted = 100) // eviction มหาศาล
        assertEquals(0, s.top) // coerceAtLeast(0) — ห้ามติดลบ
    }

    @Test fun `sticky at bottom ignores eviction - stays tail`() {
        val s = ScrollState()
        s.onOutput(0, 30) // bottom (top == baseY)
        s.onOutput(30, 30, evicted = 6) // flood ที่ cap ขณะ sticky
        assertEquals(30, s.top) // follow tail = baseY เดิม (cap)
    }

    @Test fun `no eviction keeps old clamp behavior exactly`() {
        val s = ScrollState()
        s.onOutput(0, 30)
        s.scrollBy(-10, 30) // top = 20
        s.onOutput(30, 40) // scrollback ยังไม่เต็ม cap — baseY โต, evicted = 0
        assertEquals(20, s.top) // pinned จริง — content index ไม่เลื่อน
    }

    // ≡ xterm.js scrollOnUserInput (default true) — T6 soak supplement: native คง pin
    // กลาง flood แม้ user พิมพ์ (command ทำงานจริงแต่มองไม่เห็น) = parity gap จริงบน device
    @Test fun `input reveals pinned view to tail`() {
        val s = ScrollState()
        s.onOutput(0, 30)
        s.scrollBy(-10, 30) // top = 20 (pinned)
        assertTrue(s.revealForInput(40)) // baseY โตขึ้นระหว่าง user พิมพ์ — ต้องไป tail ใหม่
        assertEquals(40, s.top)
    }

    @Test fun `input at tail is a no-op (no spurious invalidate)`() {
        val s = ScrollState()
        s.onOutput(0, 30) // sticky at bottom
        assertFalse(s.revealForInput(30))
        assertEquals(30, s.top)
    }
}
