package dev.aterminal.backend.ssh

import dev.aterminal.core.BackendError
import dev.aterminal.core.StartupArgs
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

/**
 * ticket tmux/06: one-shot exec channel (foundation ของ session browser + 07/08/09/10)
 * ผ่าน MINA exec request จริงบน TestSshServer — channel หลัก (shell) ต้องไม่สะเทือน
 */
class SshExecTest {

    private lateinit var server: org.apache.sshd.server.SshServer
    private val out = LinkedBlockingQueue<ByteArray>()
    private var backend: SshBackend? = null

    @Before fun setUp() { server = TestSshServer.start(); out.clear() }

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

    @Test
    fun `exec returns canned stdout, stderr, and exit code`() {
        TestSshServer.execCanned = mapOf(
            "tmux list-sessions -F '#S'" to TestSshServer.Canned(stdout = "\$0|1|1|main\n", stderr = "warn: thing", exit = 0),
        )
        val b = connectedBackend()
        val r = runBlocking { b.exec("tmux list-sessions -F '#S'", timeoutMs = 3_000) }
        assertEquals(0, r.exitCode)
        assertEquals("\$0|1|1|main\n", r.stdout)
        assertEquals("warn: thing", r.stderr)
    }

    @Test
    fun `nonzero exit is a normal result not an error (no sessions = exit 1 from tmux)`() {
        TestSshServer.execCanned = mapOf("tmux list-sessions" to TestSshServer.Canned("", "no sessions", 1))
        val b = connectedBackend()
        val r = runBlocking { b.exec("tmux list-sessions", timeoutMs = 3_000) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stderr.contains("no sessions"))
    }

    @Test
    fun `hanging exec hits deadline, throws CHANNEL_FAILED, and never blocks longer`() {
        val b = connectedBackend()
        val t0 = System.currentTimeMillis()
        try {
            runBlocking { b.exec(TestSshServer.HANG_COMMAND, timeoutMs = 400) }
            fail("must throw on timeout")
        } catch (e: BackendError) {
            assertEquals(BackendError.Code.CHANNEL_FAILED, e.code)
        }
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("deadline not enforced: ${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun `busy stream cannot starve the deadline - slow flood is cut by timeoutMs`() {
        // review tmux/06: deadline ต้องเช็คทุก iteration ไม่ใช่เฉพาะจังหวะ avail==0 (probe loop ทำถูกอยู่แล้ว)
        // localhost drain เร็วกว่า writer จึงมี avail==0 แทรก — test นี้ pin พฤติกรรมกัน regression (ดู note ที่ fixture)
        val b = connectedBackend()
        val t0 = System.currentTimeMillis()
        try {
            runBlocking { b.exec(TestSshServer.SLOW_FLOOD_COMMAND, timeoutMs = 400) }
            fail("must throw on timeout despite busy stream")
        } catch (e: BackendError) {
            assertEquals(BackendError.Code.CHANNEL_FAILED, e.code)
        }
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("deadline not enforced: ${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun `output cap counts both streams combined - exit status unknown when capped`() {
        // review tmux/06: 700KiB stdout + 700KiB stderr = 1.4MiB รวม — cap เดิมต่อ stream = ปล่อยผ่าน exit 0
        // พฤติกรรมที่ถูก: overflow ที่ 1MiB รวม → ไม่ trust exit status (ข้อมูล truncated) → exitCode null
        val b = connectedBackend()
        val r = runBlocking { b.exec(TestSshServer.CAP_BURST_COMMAND, timeoutMs = 5_000) }
        assertTrue(
            "combined output must be capped: out=${r.stdout.length} err=${r.stderr.length}",
            r.stdout.length + r.stderr.length < 1_048_576 + 4096,
        )
        org.junit.Assert.assertNull("truncated output must not claim a clean exit", r.exitCode)
    }

    @Test
    fun `timeoutMs zero means no deadline - returns when the slow command exits`() {
        // tmux/07: waiter shape (`tmux wait-for`) — exec ต้องรอนานเท่าที่ command อยู่ ไม่ cut by deadline
        val b = connectedBackend()
        val r = runBlocking { b.exec(TestSshServer.SLEEPY_COMMAND, timeoutMs = 0) }
        assertEquals(0, r.exitCode)
        assertEquals("slept", r.stdout.trim())
    }

    @Test
    fun `timeoutMs zero exec actually waits and is cancellable with the main channel alive`() {
        val b = connectedBackend()
        var stillWaiting = false
        runBlocking {
            val job = launch { runCatching { b.exec(TestSshServer.HANG_COMMAND, timeoutMs = 0) } }
            kotlinx.coroutines.delay(300)
            stillWaiting = job.isActive // unbounded = ต้องยังรออยู่ (bounded-at-0 = bug เดิม: fail ทันที)
            job.cancelAndJoin()
        }
        assertTrue("unbounded exec returned/failed immediately instead of waiting", stillWaiting)
        b.write("alive3\r".toByteArray())
        val deadline = System.currentTimeMillis() + 3_000
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadline && !sb.contains("alive3")) {
            out.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { sb.append(String(it)) }
        }
        assertTrue("main channel dead after cancel: [$sb]", sb.contains("alive3"))
    }

    @Test
    fun `cancelling an exec returns promptly and the main shell channel still works`() {
        val b = connectedBackend()
        val t0 = System.currentTimeMillis()
        runBlocking {
            val job = launch { runCatching { b.exec(TestSshServer.HANG_COMMAND, timeoutMs = 60_000) } }
            kotlinx.coroutines.delay(200)
            job.cancelAndJoin()
        }
        // cancellation must land in ~the delay, not the 60s timeout
        assertTrue("cancel not honored: ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 5_000)
        // main channel alive: input still echoes
        b.write("alive\r".toByteArray())
        val deadline = System.currentTimeMillis() + 3_000
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadline && !sb.contains("alive")) {
            out.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { sb.append(String(it)) }
        }
        assertTrue("main channel dead after cancel: [$sb]", sb.contains("alive"))
    }

    @Test
    fun `exec keeps stdin open while the channel lives so a waiter can arm an EOF-kill switch`() {
        // tmux/07 orphan guard: ฝั่ง tmux ครอบ wait-for ด้วย "read stdin จน EOF → kill" —
        // ถ้า client ส่ง EOF ตั้งแต่ open (MINA default เมื่อไม่ set `in`) switch จะ fire ทันที = waiter ตายคาที่
        // (วัดจริงบน device 11-07: process set หายเรียบ ไม่มี wait-for ค้างบน server เลย)
        val b = connectedBackend()
        val t0 = System.currentTimeMillis()
        runBlocking {
            val job = launch { runCatching { b.exec(TestSshServer.STDIN_EOF_COMMAND, timeoutMs = 0) } }
            kotlinx.coroutines.delay(400)
            assertTrue(
                "stdin got EOF at open — exec must hold stdin open (client pumps a never-ending stream)",
                !TestSshServer.stdinEofReached,
            )
            job.cancelAndJoin()
        }
        // cancel = channel ปิด → stdin ต้องถูกปิดถึง command (EOF หรือ destroy) — ไม่งั้น guard ฝั่ง real ก็ไม่ทำงาน
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline &&
            !TestSshServer.stdinEofReached && !TestSshServer.stdinDestroyed
        ) {
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(50) }
        }
        assertTrue(
            "command never saw stdin close after channel close (eof=${TestSshServer.stdinEofReached} destroyed=${TestSshServer.stdinDestroyed} after ${System.currentTimeMillis() - t0}ms)",
            TestSshServer.stdinEofReached || TestSshServer.stdinDestroyed,
        )
    }

}
