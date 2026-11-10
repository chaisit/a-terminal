package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * ticket tmux/09: ControlSession — command framing + lifecycle บน transport (exec-PTY ของ backend)
 *
 * wire fact ที่ lock (spike 11-08): บรรทัดว่าง = detach (control.c) → newline ผลิตโดย session เท่านั้น
 * และ transport ทั้งหมดใน test เป็น fake — transport จริง (SSH exec-PTY) test ที่ backend-ssh
 */
class ControlSessionTest {

    private class FakeTransport : ControlTransport {
        val written = mutableListOf<ByteArray>()
        var closed = false
        var onBytes: ((ByteArray) -> Unit)? = null
        var onClosed: ((CloseReason) -> Unit)? = null
        override fun open(sink: (ByteArray) -> Unit, closed: (CloseReason) -> Unit) {
            onBytes = sink
            onClosed = closed
        }

        override fun write(data: ByteArray) { written += data }
        override fun close() { closed = true }
    }

    private fun session(t: FakeTransport) =
        ControlSession(t, onEvent = {}, onClosed = {})

    @Test
    fun `send appends exactly one LF and rejects embedded newlines`() {
        val t = FakeTransport()
        val s = session(t)
        s.send("refresh-client -f no-output")
        assertEquals("refresh-client -f no-output\n", String(t.written.single(), Charsets.UTF_8))
        // newline ใน command = ส่งคำสั่งที่สอง/detach ได้ — programmer error, ต้องดัง
        assertThrows(IllegalArgumentException::class.java) { s.send("a\nb") }
        // CR ก็ equally อันตราย: transport เป็น PTY — canonical ICRNL แปลง CR→NL ก่อน tmux เห็น (spike: บรรทัดว่าง = detach)
        assertThrows(IllegalArgumentException::class.java) { s.send("a\rb") }
        assertEquals(1, t.written.size) // เคส throw ต้องไม่เขียนค้างครึ่งบรรทัด
    }

    @Test
    fun `start pipes transport bytes through the parser and stops emitting after close`() {
        val t = FakeTransport()
        val events = mutableListOf<ControlEvent>()
        val closes = mutableListOf<CloseReason>()
        val s = ControlSession(t, onEvent = { events += it }, onClosed = { closes += it })
        s.start()
        t.onBytes!!.invoke("%window-add @4\r\n".toByteArray(Charsets.UTF_8))
        assertEquals(listOf(ControlEvent.WindowAdd("@4")), events)
        t.onClosed!!.invoke(CloseReason.Normal)
        assertEquals(listOf<CloseReason>(CloseReason.Normal), closes)
        t.onBytes!!.invoke("%window-close @4\r\n".toByteArray(Charsets.UTF_8)) // หลังปิด = เงียบ (stale pump ห้าม rewrite UI)
        assertEquals(1, events.size)
        assertEquals(1, closes.size) // transport guarantee ครั้งเดียว — แต่ session ก็กันไว้ให้แล้ว (idempotent)
    }

    @Test
    fun `detach is the empty line the protocol defines, close is idempotent and blocks further sends`() {
        val t = FakeTransport()
        val s = session(t)
        s.detach()
        assertEquals("\n", String(t.written.single(), Charsets.UTF_8))
        s.send("select-window -t '@1'") // หลัง detach = no-op (client กำลังจบ — ห้ามเขียนซ้อนลงท่อที่กำลังตาย)
        assertEquals(1, t.written.size)
        s.close()
        s.close() // idempotent
        s.send("select-window -t '@1'") // หลัง close = no-op (fail-soft — caller async ห้าม crash)
        assertEquals(1, t.written.size)
        assertEquals(true, t.closed)
    }
}
