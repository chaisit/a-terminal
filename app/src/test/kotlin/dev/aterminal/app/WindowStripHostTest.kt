package dev.aterminal.app

import dev.aterminal.core.CloseReason
import dev.aterminal.core.ControlTransport
import dev.aterminal.core.TmuxControl
import dev.aterminal.core.WindowStrip
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/09 (Seam 3b): WindowStripHost — lifecycle ของ control session ฝั่ง app
 * (หนึ่ง host ต่อ connection, เปิดเฉพาะ tab ที่ focus — user ruling 11-08)
 *
 * transport เป็น fake (exec-PTY จริง test ที่ backend-ssh) — Seam ที่ตกลง: host over fake transport
 */
class WindowStripHostTest {

    private class FakeTransport : ControlTransport {
        val written = mutableListOf<ByteArray>()
        var closed = false
        var onBytes: ((ByteArray) -> Unit)? = null
        var onClosed: ((CloseReason) -> Unit)? = null
        override fun open(sink: (ByteArray) -> Unit, closed: (CloseReason) -> Unit) { onBytes = sink; onClosed = closed }
        override fun write(data: ByteArray) { written += data }
        override fun close() { closed = true }
    }

    private class Harness {
        val transports = mutableListOf<FakeTransport>()
        val warns = mutableListOf<String>()
        var windows = emptyList<WindowStrip.Window>()
        var openCmd: String? = null
        var openSize: Pair<Int, Int>? = null
        var failOpen = false

        fun host(target: String?, cols: Int = 109, rows: Int = 29) = WindowStripHost(
            open = { cmd, c, r ->
                if (failOpen) throw dev.aterminal.core.BackendError(dev.aterminal.core.BackendError.Code.CHANNEL_FAILED, "nope")
                openCmd = cmd; openSize = c to r
                FakeTransport().also { transports += it }
            },
            target = { target },
            size = { cols to rows },
            onWindows = { windows = it },
            onWarn = { warns += it },
        )
    }

    @Test
    fun `start opens the attach command with pty size and sends the init sequence in order`() {
        val h = Harness()
        val host = h.host("main")
        runBlocking { host.start() }
        assertEquals("tmux -CC attach -t 'main'", h.openCmd)
        assertEquals(109 to 29, h.openSize)
        val t = h.transports.single()
        // attach = exec request string (ไม่อยู่ใน stream); stream = mute → subscribe → size
        assertEquals(
            listOf(
                "refresh-client -f no-output\n",
                "refresh-client -B w:@*:\"#{?window_active_flag,*, }#{?window_activity_flag,!,:}#{window_name}\"\n",
                "refresh-client -C 109x29\n",
            ),
            t.written.map { String(it, Charsets.UTF_8) },
        )
    }

    @Test
    fun `unsafe or absent target never opens a channel`() {
        val h = Harness()
        val host = h.host(null)
        runBlocking { host.start() }
        assertEquals(emptyList<FakeTransport>(), h.transports)
        assertEquals(emptyList<String>(), h.warns) // fail-soft เงียบโดยเจตนา: ไม่มี tmux target = ไม่มี strip, ไม่ใช่ความผิด
    }

    @Test
    fun `channel failure warns once and leaves the strip empty`() {
        val h = Harness().apply { failOpen = true }
        val host = h.host("main")
        runBlocking { host.start() } // ต้องไม่ throw — UI path ห้ามพังเพราะ strip
        assertEquals(emptyList<WindowStrip.Window>(), h.windows)
        assertEquals(1, h.warns.size)
    }

    @Test
    fun `subscription bytes drive the snapshot and select sends the window command`() {
        val h = Harness()
        val host = h.host("main")
        runBlocking { host.start() }
        val t = h.transports.single()
        t.onBytes!!.invoke(
            "%subscription-changed w \$0 @1 0 - : *:edit\r\n%subscription-changed w \$0 @2 1 - :  !build\r\n".toByteArray(),
        )
        assertEquals(
            listOf(
                WindowStrip.Window("@1", 0, "edit", active = true, activity = false),
                WindowStrip.Window("@2", 1, "build", active = false, activity = true),
            ),
            h.windows,
        )
        host.select("@2")
        assertEquals("select-window -t '@2'\n", String(t.written.last(), Charsets.UTF_8))
        host.select("bogus") // fail-soft: ไม่มี command ออกไป
        assertEquals(4, t.written.size)
    }

    @Test
    fun `stop closes the channel and remote close clears the strip with a warn`() {
        val h = Harness()
        val host = h.host("main")
        runBlocking { host.start() }
        val t = h.transports.single()
        t.onBytes!!.invoke("%subscription-changed w \$0 @1 0 - : *:edit\r\n".toByteArray())
        assertEquals(1, h.windows.size)
        host.stop()
        assertTrue(t.closed)
        assertEquals(emptyList<WindowStrip.Window>(), h.windows)
        // remote-side death (retryable) = strip ต้อง empty + warning ลง log
        val h2 = Harness()
        val host2 = h2.host("main")
        runBlocking { host2.start() }
        val t2 = h2.transports.single()
        t2.onBytes!!.invoke("%subscription-changed w \$0 @1 0 - : *:edit\r\n".toByteArray())
        t2.onClosed!!.invoke(CloseReason.Error("control channel closed", retryable = true))
        assertEquals(emptyList<WindowStrip.Window>(), h2.windows)
        assertEquals(1, h2.warns.size)
    }

    @Test
    fun `a start still awaiting open when stop runs discards its channel (resource gate survives fast tab switches)`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        var stranded: FakeTransport? = null
        val host = WindowStripHost(
            open = { _, _, _ ->
                stranded = FakeTransport().also { }
                gate.await() // RTT จำลอง: stop วิ่งทับระหว่าง open
                stranded!!
            },
            target = { "main" },
            size = { 80 to 24 },
            onWindows = {},
        )
        // Dispatchers.IO จำเป็น: gate.await() ใน open เป็น BLOCKING call — ขืนรันบน event loop เดียวกับ
        // runBlocking (single-thread) มันล็อกทั้ง loop = delay/stop/countDown ไม่มีวันได้รัน (deadlock วัดจริง 11-08)
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.IO) { host.start() }
            kotlinx.coroutines.delay(50)
            host.stop()
            gate.countDown()
            job.join()
        }
        assertTrue("channel opened after stop must be closed", stranded?.closed == true)
        host.select("@1") // host ต้องไม่มี session เหลือ — ไม่มี byte เขียนเพิ่ม
        assertEquals(0, stranded!!.written.size)
    }

    @Test
    fun `resize reaches the live channel only, with the fresh grid size`() {
        val h = Harness()
        val host = h.host("main")
        host.resize(120, 40) // ก่อน start = no-op (ยังไม่มี channel — ห้าม throw)
        runBlocking { host.start() }
        host.resize(120, 40)
        val t = h.transports.single()
        assertEquals("refresh-client -C 120x40\n", String(t.written.last(), Charsets.UTF_8))
    }
}
