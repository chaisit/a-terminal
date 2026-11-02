package dev.aterminal.backend.ssh

import dev.aterminal.core.BackendError
import dev.aterminal.core.StartupArgs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before fun setUp() { server = TestSshServer.start(); out.clear() }

    @After fun tearDown() {
        runCatching { backend?.close() }
        runCatching { server.stop(true) }
    }

    private fun newBackend(
        password: String = TestSshServer.PASS,
        verify: ((java.net.SocketAddress, java.security.PublicKey) -> Boolean)? = { _, _ -> true },
        heartbeat: java.time.Duration = SshBackend.KEEPALIVE_INTERVAL,
    ) = SshBackend(
        host = "127.0.0.1", port = server.port, user = TestSshServer.USER,
        password = password, hostKeyVerifier = verify, heartbeatInterval = heartbeat,
    ).also { b ->
        b.onData = { out.put(it) }
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

    @Test fun `probeHostKey returns key without auth (TOFU two-phase UI flow)`() {
        val got = runBlocking { SshBackend.probeHostKey("127.0.0.1", server.port) }
        assertTrue("probe returned no key", got != null)
        val (algo, key) = got!!
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
            assertTrue("host-key mismatch must not auto-retry", !e.retryable)
        }
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
