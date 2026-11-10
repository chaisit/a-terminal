package dev.aterminal.backend.ssh

import dev.aterminal.core.CloseReason
import dev.aterminal.core.StartupArgs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

/**
 * ticket tmux/09: control transport = exec channel **ที่มี PTY** (spike 11-08: `tmux -CC` บน pipe เปล่า =
 * tcgetattr fail + exit 1) — channel หลัก (shell) ต้องไม่สะเทือน, byte สองขาต้องถึง parser ครบ
 *
 * fixture = ControlEchoCommand (พฤติกรรม control-mode: echo input, บรรทัดว่าง/EOF = exit 0)
 */
class SshControlTransportTest {

    private lateinit var server: org.apache.sshd.server.SshServer
    private val out = LinkedBlockingQueue<ByteArray>()
    private val control = LinkedBlockingQueue<ByteArray>()
    private val closes = LinkedBlockingQueue<CloseReason>()
    private var backend: SshBackend? = null

    @Before fun setUp() { server = TestSshServer.start(); out.clear(); control.clear(); closes.clear() }

    @After fun tearDown() {
        runCatching { backend?.close() }
        runCatching { server.stop(true) }
    }

    private fun connectedBackend(): SshBackend {
        val b = SshBackend(
            host = "127.0.0.1", port = server.port, user = TestSshServer.USER,
            password = TestSshServer.PASS, hostKeyVerifier = { _, _ -> true },
            heartbeatInterval = SshBackend.KEEPALIVE_INTERVAL,
            diagnostics = null,
        )
        b.onData = { out.put(it) }
        b.onClosed = {}
        backend = b
        runBlocking { b.connect(StartupArgs(null, null, cols = 30, rows = 8)) }
        return b
    }

    private fun drain(q: LinkedBlockingQueue<ByteArray>, contains: String, timeoutMs: Long = 3_000): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !sb.contains(contains)) {
            q.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { sb.append(String(it)) }
        }
        return sb.toString()
    }

    @Test
    fun `control transport is an exec channel with a pty and round-trips bytes`() {
        val b = connectedBackend()
        val t = runBlocking { b.openControlTransport(TestSshServer.CONTROL_ECHO_COMMAND, cols = 120, rows = 40) }
        t.open(sink = { control.put(it) }, closed = { closes.put(it) })
        t.write("refresh-client -f no-output\n".toByteArray(Charsets.UTF_8))
        val got = drain(control, "refresh-client")
        assertTrue("no round-trip: [$got]", got.contains("refresh-client -f no-output\n"))
        // assert หลัง round-trip: MINA ส่ง pty-req/exec *ตามหลัง* open-confirmation (verify กลับก่อนถึง server — วัดจริง)
        // exec string ถึง server ตรง ๆ + pty-req ขนาดที่ขอ (PTY คือเงื่อนไขที่ spike พิสูจน์ว่าขาดไม่ได้)
        assertTrue("exec not recorded: ${TestSshServer.recordedCommands}",
            TestSshServer.recordedCommands.contains(TestSshServer.CONTROL_ECHO_COMMAND))
        assertTrue("pty not requested at our size: ${TestSshServer.recordedPtyReqs}",
            TestSshServer.recordedPtyReqs.contains(120 to 40))
        // channel หลักไม่สะเทือน (control = channel ที่สอง เหมือน probe/exec ของ 02/06)
        b.write("main-alive\r".toByteArray())
        assertTrue("main channel dead: ", drain(out, "main-alive").contains("main-alive"))
    }

    @Test
    fun `protocol detach - empty line - ends the transport as a normal close`() {
        val b = connectedBackend()
        val t = runBlocking { b.openControlTransport(TestSshServer.CONTROL_ECHO_COMMAND, cols = 80, rows = 24) }
        t.open(sink = { control.put(it) }, closed = { closes.put(it) })
        t.write("refresh-client -B w:@*:\"#{window_name}\"\n".toByteArray(Charsets.UTF_8))
        drain(control, "refresh-client")
        t.write("\n".toByteArray(Charsets.UTF_8)) // บรรทัดว่าง = detach ตาม protocol (spike fact 2) → command exit 0
        val reason = closes.poll(3_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(CloseReason.Normal, reason)
    }

    @Test
    fun `a control command that dies with nonzero exit is a non-retryable error`() {
        // review tmux/01 lesson บน channel ยาว: exit != 0 = คำสั่งพังจริง (bad target ฯลฯ) —
        // retryable = true จะให้ redial วนรันคำสั่งพังซ้ำ จึงต้องเป็น Error(retryable=false)
        val b = connectedBackend()
        val t = runBlocking { b.openControlTransport(TestSshServer.CONTROL_CRASH_COMMAND, cols = 80, rows = 24) }
        t.open(sink = { control.put(it) }, closed = { closes.put(it) })
        val reason = closes.poll(3_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("expected Error, got $reason", reason is CloseReason.Error)
        assertEquals(false, (reason as CloseReason.Error).retryable)
    }

    @Test
    fun `closing the transport ends the remote command and silences further writes`() {
        val b = connectedBackend()
        val t = runBlocking { b.openControlTransport(TestSshServer.CONTROL_ECHO_COMMAND, cols = 80, rows = 24) }
        t.open(sink = { control.put(it) }, closed = { closes.put(it) })
        t.close()
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && !TestSshServer.controlCommandEnded) {
            Thread.sleep(50)
        }
        assertTrue("remote command never saw the close", TestSshServer.controlCommandEnded)
        // local close = จบเงียบตาม contract (caller ริเริ่มเอง — closed สำหรับ remote/command จบเท่านั้น)
        Thread.sleep(200)
        org.junit.Assert.assertNull("local close must not deliver a reason", closes.poll())
        t.write("late\n".toByteArray(Charsets.UTF_8)) // หลัง close = no-op (สัญญาฝั่ง ControlSession ทดสอบไว้แล้ว — ฝั่ง transport ต้องไม่ throw)
        // main ยังใช้ต่อได้หลัง control channel จบ
        b.write("still-here\r".toByteArray())
        assertTrue("main channel dead: ", drain(out, "still-here").contains("still-here"))
    }
}
