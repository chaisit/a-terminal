package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/04: OSC 0/2 → tab pill title — pure state ของ debounce gate (timing เป็นของผู้เรียก)
 *
 * contract ที่ test นี้คุม (user ตัดสิน 11-05):
 *  - trailing debounce: offer หลายค่าใน window เดียว = emit ค่าล่าสุดครั้งเดียว (กัน title spam จาก prompt)
 *  - ค่าเท่าเดิม = ไม่ pending (UI ต้องไม่ refresh ซ้ำ)
 *  - ไม่มี clear-back: title จาก terminal คงจนกว่าจะโดน OSC ค่าใหม่ (Q2) — gate ไม่ reset เอง, caller ห้าม reset ต่อ dial
 *  - sanitize: control bytes/ newline หาย,ยาวเกิน cap = truncate (pill ellipsize อยู่แล้ว แต่กัน string ก้อนยักษ์จาก MITM)
 */
class TerminalTitleGateTest {

    @Test
    fun `starts empty - caller falls back to profile name`() {
        val g = TerminalTitleGate()
        assertNull(g.title)
    }

    @Test
    fun `changed title becomes pending then emits on flush`() {
        val g = TerminalTitleGate()
        assertTrue("title ใหม่ต้อง pending", g.offer("aterm-test:1:web"))
        assertEquals("ก่อน flush ยังไม่ emit", null, g.title)
        assertTrue(g.flush())
        assertEquals("aterm-test:1:web", g.title)
        assertFalse("flush ซ้ำไม่มีอะไรเปลี่ยน", g.flush())
    }

    @Test
    fun `spam within window coalesces to last value emitted once`() {
        val g = TerminalTitleGate()
        g.offer("A")
        g.offer("B")
        g.offer("C") // prompt ยิง OSC ถี่ — flush ครั้งเดียวต้องได้ค่าท้าย
        assertTrue(g.flush())
        assertEquals("C", g.title)
        assertFalse(g.flush())
    }

    @Test
    fun `same value is not pending (no needless reframe)`() {
        val g = TerminalTitleGate()
        g.offer("win"); g.flush()
        assertFalse("ค่าเท่ากับ current ห้าม pending", g.offer("win"))
        assertFalse(g.flush())
        assertEquals("win", g.title)
    }

    @Test
    fun `sanitize strips control chars newlines and collapses spaces`() {
        val g = TerminalTitleGate()
        assertTrue(g.offer("  a\rb\tcd\u0007  \n"))
        g.flush()
        assertEquals("a b cd", g.title) // ทุก C0 = separator แล้ว collapse — ไม่มี control char ตกค้าง
    }

    @Test
    fun `blank titles are ignored (tmux clearing with empty string must not blank the pill)`() {
        val g = TerminalTitleGate()
        assertFalse(g.offer(""))
        assertFalse(g.offer("   "))
        assertNull(g.title)
    }

    @Test
    fun `oversized title truncates at cap (server-controlled length guard)`() {
        val g = TerminalTitleGate()
        assertTrue(g.offer("x".repeat(TerminalTitleGate.MAX_LEN + 500)))
        g.flush()
        assertEquals(TerminalTitleGate.MAX_LEN, g.title!!.length)
    }

    @Test
    fun `newer value after flush replaces older (window cycle)`() {
        val g = TerminalTitleGate()
        g.offer("one"); g.flush()
        assertTrue(g.offer("two"))
        g.flush()
        assertEquals("two", g.title)
    }

    // --- regression (review 11-05) ---

    @Test
    fun `latest value equal to displayed title cancels stale pending (no stale-wins)`() {
        val g = TerminalTitleGate()
        g.offer("A"); g.flush() // กำลังแสดง A
        g.offer("B") // pending = B (timer รันอยู่)
        assertFalse("latest == ที่แสดง ไม่ถือเป็น emit ใหม่", g.offer("A")) // prompt set/restore title กลาง debounce
        assertFalse("pending ต้องโดน neutralize — stale B ห้ามชนะตอน flush", g.flush())
        assertEquals("A", g.title)
    }

    @Test
    fun `arm grants schedule once until disarm (bridge-thread-safe flag)`() {
        val g = TerminalTitleGate()
        assertTrue(g.arm())
        assertFalse("schedule ซ้ำไม่ได้ระหว่าง window", g.arm())
        g.disarm()
        assertTrue("หลัง disarm ต้อง schedule ใหม่ได้", g.arm())
    }

    @Test
    fun `bidi overrides and C1 controls are stripped, not shown (spoofing guard)`() {
        val g = TerminalTitleGate()
        assertTrue(g.offer("pa\u202Bss\u200Ewt")) // RLE + RLM แทรก
        g.flush()
        assertEquals("pa ss wt", g.title) // ทุก ตัว = separator (space semantics เดียวกับ C0)
        assertTrue(g.offer("a\u0085b")) // C1 NEL = separator เหมือน C0
        g.flush()
        assertEquals("a b", g.title)
    }

    @Test
    fun `cap never leaves a lone surrogate (no tofu on pill)`() {
        val g = TerminalTitleGate()
        // "x"*(MAX_LEN-1) + emoji (2 units) → cut กลาง pair พอดี
        assertTrue(g.offer("x".repeat(TerminalTitleGate.MAX_LEN - 1) + "😀tail"))
        g.flush()
        val t = g.title!!
        assertEquals(TerminalTitleGate.MAX_LEN - 1, t.length) // lone high surrogate ถูกตัดทิ้ง
        assertFalse(Character.isHighSurrogate(t[t.length - 1]))
    }
}
