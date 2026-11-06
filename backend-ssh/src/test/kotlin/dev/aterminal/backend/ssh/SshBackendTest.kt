package dev.aterminal.backend.ssh

import dev.aterminal.core.BackendError
import dev.aterminal.core.StartupArgs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * SshBackend vs in-process MINA SshServer (TestSshServer) —
 * proof ว่า backend ทำงาน end-to-end จริง (bytes flow / resize ถึง server / auth fail / TOFU)
 */
class SshBackendTest {

    private lateinit var server: org.apache.sshd.server.SshServer
    private val out = LinkedBlockingQueue<ByteArray>()
    private var backend: SshBackend? = null
    // review tmux/01: close-reason contract tests
    @Volatile private var closedReason: dev.aterminal.core.CloseReason? = null

    @Before fun setUp() { server = TestSshServer.start(); out.clear() }

    @After fun tearDown() {
        runCatching { backend?.close() }
        runCatching { server.stop(true) }
    }

    private fun newBackend(
        password: String = TestSshServer.PASS,
        verify: ((java.net.SocketAddress, java.security.PublicKey) -> Boolean)? = { _, _ -> true },
        heartbeat: java.time.Duration = SshBackend.KEEPALIVE_INTERVAL,
        diagnostics: ((String) -> Unit)? = null,
    ) = SshBackend(
        host = "127.0.0.1", port = server.port, user = TestSshServer.USER,
        password = password, hostKeyVerifier = verify, heartbeatInterval = heartbeat,
        diagnostics = diagnostics,
    ).also { b ->
        b.onData = { out.put(it) }
        b.onClosed = { closedReason = it }
        backend = b
    }

    private fun drain(timeoutMs: Long = 3000): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val chunk = out.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                ?: break
            sb.append(String(chunk))
        }
        return sb.toString()
    }

    @Test fun `connect streams banner with pty size and echoes input`() {
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = null, startingDirectory = null, cols = 30, rows = 8)) }
        val banner = drain()
        assertTrue("banner missing: [$banner]", banner.contains("TESTBANNER 30x8"))
        b.write("ping\r".toByteArray())
        val echoed = drain()
        assertTrue("echo missing: [$echoed]", echoed.contains("ping"))
    }

    // ── tmux/01: startup.commandline = exec request แทน default shell (WT profile.commandline)

    @Test fun `commandline runs as exec with pty instead of shell`() {
        val b = newBackend()
        runBlocking {
            b.connect(StartupArgs(commandline = "tmux new -A -s aterm", startingDirectory = null, cols = 30, rows = 8))
        }
        val banner = drain()
        assertTrue("exec channel did not start (no banner): [$banner]", banner.contains("TESTBANNER"))
        assertTrue("exec command not received: ${TestSshServer.recordedCommands}",
            TestSshServer.recordedCommands.contains("tmux new -A -s aterm"))
        // PTY must be negotiated at open with the requested size (tmux reads it via TIOCGWINSZ)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline &&
            TestSshServer.recordedPtyReqs.none { it == 30 to 8 }
        ) Thread.sleep(50)
        assertTrue("pty-req 30x8 not seen on exec channel: ${TestSshServer.recordedPtyReqs}",
            TestSshServer.recordedPtyReqs.contains(30 to 8))
        b.write("ping\r".toByteArray())
        val echoed = drain()
        assertTrue("echo missing: [$echoed]", echoed.contains("ping"))
    }

    @Test fun `blank commandline keeps shell path with no exec request`() {
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = "   ", startingDirectory = null, cols = 80, rows = 24)) }
        val banner = drain()
        assertTrue("shell banner missing: [$banner]", banner.contains("TESTBANNER 80x24"))
        // ticket tmux/02 เปลี่ยน invariant: shell path = ไม่มี exec ของ *ผู้ใช้* (probe "tmux -V" ยิงทุก connection — แยก assert)
        assertTrue("blank commandline ต้องไม่ยิง exec: ${TestSshServer.recordedCommands}",
            TestSshServer.recordedCommands.filterNot { it == TestSshServer.PROBE_COMMAND }.isEmpty())
    }

    private fun awaitClose(timeoutMs: Long = 5000): dev.aterminal.core.CloseReason? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && closedReason == null) Thread.sleep(50)
        return closedReason
    }

    @Test fun `exec non-zero exit is Error non-retryable, not silent Normal`() {
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = "tmux new -A -s x", startingDirectory = null, cols = 80, rows = 24)) }
        drain(1500)
        b.write("fail\r".toByteArray())
        val reason = awaitClose()
        assertTrue("exec exit=3 ต้อง not Normal: $reason", reason is dev.aterminal.core.CloseReason.Error)
        assertTrue("exit-code failure ต้องไม่ redial วน: $reason",
            !(reason as dev.aterminal.core.CloseReason.Error).retryable)
        assertTrue("reason ต้องบอก exit code: ${reason.message}", reason.message.contains("3"))
    }

    @Test fun `exec clean exit stays Normal (detach path unchanged)`() {
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = "tmux new -A -s x", startingDirectory = null, cols = 80, rows = 24)) }
        drain(1500)
        b.write("exit\r".toByteArray())
        val reason = awaitClose()
        org.junit.Assert.assertEquals(dev.aterminal.core.CloseReason.Normal, reason)
    }

    @Test fun `shell non-zero exit keeps legacy Normal (no behavior change)`() {
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = null, startingDirectory = null, cols = 80, rows = 24)) }
        drain(1500)
        b.write("fail\r".toByteArray())
        val reason = awaitClose()
        org.junit.Assert.assertEquals(dev.aterminal.core.CloseReason.Normal, reason)
    }

    @Test fun `unset commandline keeps shell path with no exec request`() {
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = null, startingDirectory = null, cols = 80, rows = 24)) }
        drain(1500) // banner
        // ticket tmux/02: probe "tmux -V" คือ exec เดียวที่ shell path แตะ commandFactory ได้
        assertTrue("shell path ต้องไม่แตะ commandFactory ด้วยคำสั่งผู้ใช้: ${TestSshServer.recordedCommands}",
            TestSshServer.recordedCommands.filterNot { it == TestSshServer.PROBE_COMMAND }.isEmpty())
    }

    // ── tmux/02: probe ต่อ connection (exec `tmux -V` ไม่มี PTY, fail-soft, เก็บไม่ persist)

    private fun awaitCaps(b: SshBackend, timeoutMs: Long = 4000): dev.aterminal.core.TmuxCapabilities? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && b.tmuxCapabilities == null) Thread.sleep(50)
        return b.tmuxCapabilities
    }

    @Test fun `probe exposes capability table from canned tmux version`() {
        TestSshServer.versionOutput = "tmux 3.2a"
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = null, startingDirectory = null, cols = 80, rows = 24)) }
        assertTrue("banner missing", drain().contains("TESTBANNER 80x24"))
        val caps = awaitCaps(b)
        org.junit.Assert.assertNotNull("probe ไม่ได้ version — probe exec ที่เห็น: ${TestSshServer.recordedCommands}", caps)
        org.junit.Assert.assertEquals(dev.aterminal.core.TmuxVersion(3, 2, 'a'), caps!!.version)
        // gate smoke (ตารางจริง test อยู่ฝั่ง terminal-core — ที่นี่ proof ว่า probe ต่อตารางถูกตัว)
        assertTrue("3.2a ต้องมี client-detached notify", caps.clientDetachedNotify)
        assertTrue("3.2a ต้องมี osc52", caps.osc52Clipboard)
        assertFalse("3.2a ยังไม่มี layout JSON", caps.layoutJson)
        assertTrue("probe ต้องส่ง exec tmux -V: ${TestSshServer.recordedCommands}",
            TestSshServer.recordedCommands.contains(TestSshServer.PROBE_COMMAND))
    }

    @Test fun `probe uses a second exec channel without pty-req`() {
        TestSshServer.versionOutput = "tmux 3.6"
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = null, startingDirectory = null, cols = 80, rows = 24)) }
        awaitCaps(b)
        // channel หลัก (shell) = pty-req เดียว — probe ห้ามขอ PTY (exec ธรรมดา, output เล็ก)
        org.junit.Assert.assertEquals("probe ห้ามสร้าง PTY: ${TestSshServer.recordedPtyReqs}",
            listOf(80 to 24), TestSshServer.recordedPtyReqs.toList())
    }

    @Test fun `probe fail-soft no tmux output leaves caps null and session healthy`() {
        TestSshServer.versionOutput = "bash: line 1: tmux: command not found"
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = null, startingDirectory = null, cols = 80, rows = 24)) }
        assertTrue("banner missing", drain().contains("TESTBANNER"))
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline &&
            !TestSshServer.recordedCommands.contains(TestSshServer.PROBE_COMMAND)
        ) Thread.sleep(50)
        Thread.sleep(300) // canned exit(0) ถึง client แล้ว — parse ต้องจบ (null), ไม่ throw
        org.junit.Assert.assertNull("output garbage ต้องเป็น null (fail-soft)", b.tmuxCapabilities)
        // session หลักต้องไม่สะเทือน
        b.write("ping\r".toByteArray())
        assertTrue("echo after failed probe missing", drain().contains("ping"))
    }

    @Test fun `probe coexists with commandline exec path`() {
        TestSshServer.versionOutput = "tmux 3.6"
        val b = newBackend()
        runBlocking {
            b.connect(StartupArgs(commandline = "tmux new -A -s x", startingDirectory = null, cols = 80, rows = 24))
        }
        assertTrue("banner missing", drain().contains("TESTBANNER"))
        val caps = awaitCaps(b)
        org.junit.Assert.assertNotNull("probe fails เมื่อ main เป็น exec: ${TestSshServer.recordedCommands}", caps)
        assertTrue("3.6 ต้องมี osc forwarding", caps!!.oscForwarding)
        assertFalse("3.6 ยังไม่มี layout JSON", caps.layoutJson)
        assertTrue("both execs ต้องถึง server: ${TestSshServer.recordedCommands}",
            TestSshServer.recordedCommands.containsAll(listOf("tmux new -A -s x", TestSshServer.PROBE_COMMAND)))
        // pty-req ต้องมีเฉพาะ channel หลัก (exec ผู้ใช้) — probe ไม่ขอ PTY
        org.junit.Assert.assertEquals(listOf(80 to 24), TestSshServer.recordedPtyReqs.toList())
    }

    @Test fun `redial clears stale capabilities before the new probe resolves`() {
        // review tmux/02: gate ห้ามอ่าน version ของ connection เก่าตอน redial (instance reuse)
        TestSshServer.versionOutput = "tmux 3.6"
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(commandline = null, startingDirectory = null, cols = 80, rows = 24)) }
        org.junit.Assert.assertNotNull(awaitCaps(b))
        // redial บน instance เดียว: round-2 ที่ probe ยังไม่เสร็จ (versionOutput=null → exec ไม่ exit, deadline 3วิ)
        // ค่าเดิมต้องเป็น null ทันทีที่ connect() คืน — determinism: ไม่มีทางที่ probe ใหม่เขียนเข้าก่อน assert
        TestSshServer.versionOutput = null
        runBlocking { b.connect(StartupArgs(commandline = null, startingDirectory = null, cols = 80, rows = 24)) }
        org.junit.Assert.assertNull("caps เก่าค้างหลัง redial", b.tmuxCapabilities)
    }

    @Test fun `probe bounds server-controlled output (flood cannot balloon the buffer)`() {
        // review tmux/02: output ผ่าน probe = untrusted 100% — cap 4KiB แล้ว fail-soft (mem-bomb กันMITM sshd)
        TestSshServer.versionOutput = "x".repeat(1_000_000)
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val b = newBackend(diagnostics = { lines.add(it) })
        runBlocking { b.connect(StartupArgs(commandline = null, startingDirectory = null, cols = 80, rows = 24)) }
        assertTrue("banner missing", drain().contains("TESTBANNER"))
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline && lines.none { it.startsWith("tmux probe") }) Thread.sleep(50)
        assertTrue("probe ต้องจบด้วย none (cap cut) ไม่ใช่ค้าง: $lines", lines.any { it == "tmux probe -> none" })
        org.junit.Assert.assertNull("outputขยะทั้งก้อนต้อง null", b.tmuxCapabilities)
    }

    @Test fun `probeHostKey returns key without auth (TOFU two-phase UI flow)`() {
        val (algo, key) = runBlocking { SshBackend.probeHostKey("127.0.0.1", server.port) }
        val fp = SshBackend.fingerprint(key)
        assertTrue("algo should be an ssh wire name: $algo", algo.startsWith("ssh-") || algo.startsWith("ecdsa-"))
        assertTrue("bad fingerprint: $fp", fp.startsWith("SHA256:") && !fp.endsWith("="))
    }

    @Test fun `resize reaches server as window_change`() {
        val b = newBackend()
        runBlocking { b.connect(StartupArgs(null, null, 30, 8)) }
        drain(1500) // banner
        b.resize(80, 24)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline &&
            TestSshServer.recordedWindows.none { it == 80 to 24 }
        ) Thread.sleep(50)
        assertTrue("window_change 80x24 not seen: ${TestSshServer.recordedWindows}",
            TestSshServer.recordedWindows.contains(80 to 24))
    }

    @Test fun `wrong password maps to AUTH_FAILED`() {
        val b = newBackend(password = "wrong-password")
        try {
            runBlocking { b.connect(StartupArgs(null, null, 80, 24)) }
            fail("expected BackendError")
        } catch (e: BackendError) {
            assertEquals(BackendError.Code.AUTH_FAILED, e.code)
        }
    }

    @Test fun `host key rejection maps to HOST_KEY_MISMATCH and is not retryable`() {
        val b = newBackend(verify = { _, _ -> false })
        try {
            runBlocking { b.connect(StartupArgs(null, null, 80, 24)) }
            fail("expected BackendError")
        } catch (e: BackendError) {
            assertEquals(BackendError.Code.HOST_KEY_MISMATCH, e.code)
            assertTrue("ต้องบอกเหตุชัดเจนเพราะไม่มีด่าน probe แล้ว: ${e.message}", e.message.contains("MITM"))
            assertTrue("host-key mismatch must not auto-retry", !e.retryable)
        }
    }

    // ── private key จริงจาก ssh-keygen ทุกแบบที่ user น่าจะมี

    private val keyDir: java.io.File by lazy { java.nio.file.Files.createTempDirectory("sshkeys").toFile() }

    private fun keygen(name: String, vararg args: String, passphrase: String = ""): ByteArray {
        val r = runCatching {
            ProcessBuilder(listOf("ssh-keygen", "-q", "-N", passphrase, "-C", "test", "-f", "$keyDir/$name") + args)
                .redirectErrorStream(true).start().waitFor()
        }.getOrDefault(-1)
        assumeTrue("ต้องมี ssh-keygen", r == 0)
        return java.io.File(keyDir, name).readBytes()
    }

    private fun loginWithKey(key: ByteArray, passphrase: String?) {
        val b = SshBackend(
            host = "127.0.0.1", port = server.port, user = TestSshServer.USER,
            privateKey = { key }, hostKeyVerifier = { _, _ -> true }, keyPassphrase = passphrase,
        ).also { it.onData = { d -> out.put(d) }; backend = it }
        runBlocking { b.connect(StartupArgs(null, null, 30, 8)) }
        assertTrue("key ต้องถูกล้างหลังใช้", key.all { it == 0.toByte() })
        b.write("ping\r".toByteArray())
        assertTrue("session ใช้งานไม่ได้", drain(1500).contains("ping"))
    }

    @Test fun `login with ed25519 without passphrase`() {
        val k = keygen("ed", "-t", "ed25519")
        assertEquals(KeyCheck.OK, SshBackend.inspectKey(k))
        loginWithKey(k, null)
    }

    @Test fun `login with encrypted ed25519`() {
        val k = keygen("ed-enc", "-t", "ed25519", passphrase = "correct horse")
        assertEquals(KeyCheck.NEEDS_PASSPHRASE, SshBackend.inspectKey(k))
        loginWithKey(k, "correct horse")
    }

    @Test fun `login with rsa openssh format and encrypted`() {
        loginWithKey(keygen("rsa", "-t", "rsa", "-b", "2048"), null)
        loginWithKey(keygen("rsa-enc", "-t", "rsa", "-b", "2048", passphrase = "pw12345"), "pw12345")
    }

    @Test fun `login with encrypted pem rsa`() {
        val k = keygen("pem-enc", "-t", "rsa", "-b", "2048", "-m", "PEM", passphrase = "pw12345")
        assertEquals(KeyCheck.NEEDS_PASSPHRASE, SshBackend.inspectKey(k))
        loginWithKey(k, "pw12345")
    }

    @Test fun `login with ecdsa`() {
        loginWithKey(keygen("ec", "-t", "ecdsa"), null)
    }

    private fun keyError(key: ByteArray, passphrase: String?): BackendError = try {
        SshBackend.loadIdentities(key, passphrase)
        throw AssertionError("expected BackendError")
    } catch (e: BackendError) {
        assertTrue("รหัสผิดห้าม auto-retry", !e.retryable)
        e
    }

    @Test fun `wrong passphrase is not retryable and not called invalid`() {
        val k = keygen("ed-wrong", "-t", "ed25519", passphrase = "right-one")
        assertEquals(BackendError.Code.KEY_PASSPHRASE, keyError(k, "wrong-one").code)
    }

    @Test fun `missing passphrase asks instead of failing as invalid`() {
        val k = keygen("ed-none", "-t", "ed25519", passphrase = "right-one")
        assertEquals(BackendError.Code.KEY_PASSPHRASE, keyError(k, null).code)
    }

    @Test fun `file that is not a key is invalid`() {
        val junk = "hello world, this is not a key\n".toByteArray()
        assertEquals(KeyCheck.INVALID, SshBackend.inspectKey(junk))
        assertEquals(BackendError.Code.KEY_INVALID, keyError(junk, null).code)
    }

    @Test fun `public key file is invalid`() {
        keygen("pub", "-t", "ed25519")
        assertEquals(KeyCheck.INVALID, SshBackend.inspectKey(java.io.File(keyDir, "pub.pub").readBytes()))
    }

    // ── Honor หยุด process 37 วินาทีตอนอยู่ background: รอบที่ค้างห้ามยิงรวดเดียวแล้วตัด session ทั้งที่ network ดี

    @Test fun `process stall does not kill a healthy session`() {
        val closed = java.util.concurrent.CountDownLatch(1)
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val b = newBackend(heartbeat = java.time.Duration.ofMillis(300), diagnostics = { lines.add(it) })
            .also { it.onClosed = { closed.countDown() } }
        runBlocking { b.connect(StartupArgs(null, null, 30, 8)) }
        drain(500)
        val f = SshBackend::class.java.getDeclaredField("client").apply { isAccessible = true }
        val c = f.get(b) as org.apache.sshd.client.SshClient
        c.scheduledExecutorService.submit { Thread.sleep(2500) }
        assertTrue("session หลุดหลังหยุดชั่วคราว: $lines", !closed.await(4000, TimeUnit.MILLISECONDS))
        assertTrue("ไม่มีบันทึก stall: $lines", lines.any { it.startsWith("keepalive stall") })
        b.write("ping\r".toByteArray())
        assertTrue("session ใช้งานไม่ได้หลัง stall", drain(1500).contains("ping"))
    }

    @Test fun `silence shorter than the limit keeps the session`() {
        val closed = java.util.concurrent.CountDownLatch(1)
        val b = newBackend(heartbeat = java.time.Duration.ofMillis(300)).also { it.onClosed = { closed.countDown() } }
        runBlocking { b.connect(StartupArgs(null, null, 30, 8)) }
        drain(500)
        TestSshServer.deafToHeartbeat = true
        try {
            assertTrue("หลุดเร็วเกินไปตอน network เงียบสั้น ๆ", !closed.await(800, TimeUnit.MILLISECONDS))
        } finally {
            TestSshServer.deafToHeartbeat = false
        }
        assertTrue("ต้องกลับมาปกติเมื่อ reply มา", !closed.await(2500, TimeUnit.MILLISECONDS))
    }

    // ── server รับ TCP แต่ไม่ตอบ (เครื่องค้าง, proxy, network เพิ่งกลับ): ต้องเป็น error ที่ retry ได้ ไม่ใช่ทำให้ reconnect หยุดถาวร

    @Test(timeout = 40_000) fun `probe of a silent server fails as retryable`() {
        java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress()).use { silent ->
            val accepted = Thread {
                runCatching { silent.accept().use { Thread.sleep(35_000) } }
            }.apply { isDaemon = true; start() }
            try {
                runBlocking { SshBackend.probeHostKey("127.0.0.1", silent.localPort) }
                fail("expected BackendError")
            } catch (e: BackendError) {
                assertEquals(BackendError.Code.UNREACHABLE, e.code)
                assertTrue(e.retryable)
            } finally {
                accepted.interrupt()
            }
        }
    }

    // ── เหตุที่ session ปิดต้องถึง log โดยไม่มี host/user ติดไปด้วย

    @Test fun `diagnostics report why the session closed without host or user`() {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val closed = java.util.concurrent.CountDownLatch(1)
        val b = newBackend(diagnostics = { lines.add(it) }).also { it.onClosed = { closed.countDown() } }
        runBlocking { b.connect(StartupArgs(null, null, 30, 8)) }
        drain()
        server.stop(true)
        assertTrue("onClosed ไม่มาหลัง server ตาย", closed.await(5, TimeUnit.SECONDS))
        val log = lines.joinToString("\n")
        assertTrue("ไม่มีบรรทัด session closed: $log", log.contains("session closed"))
        assertTrue("ไม่มีบรรทัด channel closed: $log", log.contains("channel closed exit=null"))
        assertTrue("host รั่วลง log: $log", !log.contains("127.0.0.1"))
        assertTrue("user รั่วลง log: $log", !log.contains(TestSshServer.USER))
    }

    @Test fun `lost keepalive reaches diagnostics`() {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val closed = java.util.concurrent.CountDownLatch(1)
        val b = newBackend(heartbeat = java.time.Duration.ofMillis(300), diagnostics = { lines.add(it) })
            .also { it.onClosed = { closed.countDown() } }
        runBlocking { b.connect(StartupArgs(null, null, 30, 8)) }
        drain(500)
        TestSshServer.deafToHeartbeat = true
        try {
            assertTrue(closed.await(4, TimeUnit.SECONDS))
            assertTrue("ไม่มีเหตุใน log: $lines", lines.any { it.startsWith("keepalive lost") })
        } finally {
            TestSshServer.deafToHeartbeat = false
        }
    }

    @Test fun `redact removes addresses and known names and truncates`() {
        val r = SshBackend.redact("failed user1@srv.example /10.1.2.3:22 [fe80::1:2]:22 " + "x".repeat(300), listOf("srv.example", "user1", null))
        assertTrue(r, !r.contains("srv.example") && !r.contains("user1") && !r.contains("10.1.2.3") && !r.contains("fe80::1:2"))
        assertTrue("ไม่ถูกตัดความยาว: ${r.length}", r.length <= SshBackend.DIAG_MAX_MESSAGE + 1)
    }

    // ── T2 (round 12): keepalive half-open detection — half-open = server เงียบเฉพาะ heartbeat, socket ปกติ
    // (≠ kill server — อันนั้นได้ FIN = pump EOF เจออยู่แล้ว; สิ่งที่ต้องพิสูจน์คือ case ไม่มี FIN/RST)

    @Test fun `heartbeat kills half-open session and reports retryable close within a few beats`() {
        val beat = java.time.Duration.ofMillis(300)
        val closed = java.util.concurrent.CountDownLatch(1)
        var reason: dev.aterminal.core.CloseReason? = null
        val b = newBackend(heartbeat = beat).also { it.onClosed = { r -> reason = r; closed.countDown() } }
        runBlocking { b.connect(StartupArgs(null, null, 30, 8)) }
        drain() // banner
        Thread.sleep(5 * beat.toMillis()) // control: beat มี reply ตลอด — session ต้องยังดี
        assertTrue("client ส่ง heartbeat ไม่ถึง 3 ครั้ง: ${TestSshServer.heartbeatSeen.get()}",
            TestSshServer.heartbeatSeen.get() >= 3)
        b.write("ping\r".toByteArray())
        assertTrue("echo ก่อน impair ต้องผ่าน", drain(1500).contains("ping"))

        TestSshServer.deafToHeartbeat = true
        val t0 = System.currentTimeMillis()
        assertTrue("half-open ไม่ถูกตรวจจับใน 4s (interval=${beat.toMillis()}ms)",
            closed.await(4000, TimeUnit.MILLISECONDS))
        val elapsed = System.currentTimeMillis() - t0
        val r = reason
        assertTrue("ต้องเป็น Error(retryable=true) เพื่อ T3 auto-reconnect: $r",
            r is dev.aterminal.core.CloseReason.Error && r.retryable)
        // ทฤษฎี: beat t+300 (miss#1) → t+600 (miss#2 > max=1) → exceptionCaught; เผื่อ schedule skew
        assertTrue("detect ช้าผิดปกติ: ${elapsed}ms", elapsed <= 3000)
    }

    // T3 (round 12): exit-wart — user พิมพ์ exit แล้ว app ต้อง NOT redial (server ส่ง exit-status ก่อน close เสมอ)
    @Test fun `clean server-side exit maps to Normal close, not retryable`() {
        val closed = java.util.concurrent.CountDownLatch(1)
        var reason: dev.aterminal.core.CloseReason? = null
        val b = newBackend().also { it.onClosed = { r -> reason = r; closed.countDown() } }
        runBlocking { b.connect(StartupArgs(null, null, 30, 8)) }
        drain() // banner
        b.write("exit\r".toByteArray())
        assertTrue("exit แล้ว channel ไม่ปิดใน 3s", closed.await(3000, TimeUnit.MILLISECONDS))
        val r = reason
        assertTrue("exit ต้อง ≡ CloseReason.Normal (T3: Normal = ห้าม auto-redial): $r",
            r is dev.aterminal.core.CloseReason.Normal)
    }

    @Test fun `classifyConnectFailure - transport death beats auth text (wart round 6 closed)`() {
        val b = SshBackend("h")
        val sshEx = org.apache.sshd.common.SshException(
            org.apache.sshd.common.SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE,
            "No more authentication methods available",
        )
        //wart: chain ตายกลาง auth ที่ _มี_ ข้อความ auth ติดมา (old code → AUTH_FAILED)
        val midAuthDeath = org.apache.sshd.common.SshException(
            "Failed (SocketException) to execute: Connection reset during auth",
        ).apply { initCause(java.net.SocketException("Connection reset")) }
        val timeoutChain = org.apache.sshd.common.SshException(
            "Failed to get operation result within specified timeout: 20000 msec",
        ).apply { initCause(java.util.concurrent.TimeoutException("Timed out after 20000 msec")) }
        val sessionClosed = org.apache.sshd.common.SshException("Session is closed") // preClose path

        assertEquals(BackendError.Code.AUTH_FAILED, b.classifyConnectFailure(false, sshEx))
        assertEquals(BackendError.Code.UNREACHABLE, b.classifyConnectFailure(false, midAuthDeath))
        assertEquals(BackendError.Code.UNREACHABLE, b.classifyConnectFailure(false, timeoutChain))
        assertEquals(BackendError.Code.UNREACHABLE, b.classifyConnectFailure(false, sessionClosed))
        assertEquals(BackendError.Code.UNREACHABLE, b.classifyConnectFailure(false, RuntimeException("junk")))
        // TOFU/MITM ต้องชนะเสมอแม้ chain เป็น auth fail
        assertEquals(BackendError.Code.HOST_KEY_MISMATCH, b.classifyConnectFailure(true, sshEx))
    }

    @Test fun `fingerprint format matches ssh-keygen SHA256 style`() {
        // key จริงจาก server ที่รันอยู่ไม่ได้ expose ตรง ๆ — ทดสอบ deterministic กับ keypair ที่ generate
        val gen = KeyPairGeneratorTestFactory.rsa()
        val fp = SshBackend.fingerprint(gen.public)
        assertTrue(fp, fp.startsWith("SHA256:") && fp.length > 20 && !fp.endsWith("="))
    }

    @Test fun `fingerprint is byte-identical to ssh-keygen -lf`() {
        // TOFU ต้องให้ user เทียบกับ output ของ ssh-keygen บน server ได้ — hash ต้องตรงกันจริง
        // (ตรวจ cross-check กับ ssh-keygen process จริง; ข้ามถ้าเครื่องนั้นไม่มี)
        assumeTrue("ssh-keygen not available", runCatching {
            ProcessBuilder("ssh-keygen", "-l", "-f", "/dev/null").start().waitFor() != 0
        }.getOrDefault(false))

        // ใช้ EC P-256 = ประเภท host key default ของ OpenSSH (และ path ที่ Android device ต้อง marshal จริง)
        val kpg = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }
        val kp = kpg.generateKeyPair()
        val pubFile = java.io.File.createTempFile("aterm-fp-", ".pub")
        // PublicKeyEntry.toString = "algo base64" = รูปแบบ .pub ของ OpenSSH พอดี
        pubFile.writeText(org.apache.sshd.common.config.keys.PublicKeyEntry.toString(kp.public) + " test-comment\n")
        try {
            val out = ProcessBuilder("ssh-keygen", "-lf", pubFile.absolutePath)
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            val sshKeygenFp = out.lineSequence().firstOrNull()
                ?.split(" ")?.getOrNull(1) ?: error("unexpected ssh-keygen output: $out")
            assertEquals(sshKeygenFp, SshBackend.fingerprint(kp.public))
        } finally {
            pubFile.delete()
        }
    }
}

private object KeyPairGeneratorTestFactory {
    fun rsa(): java.security.KeyPair =
        java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
