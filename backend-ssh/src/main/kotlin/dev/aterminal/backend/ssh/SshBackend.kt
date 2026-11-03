package dev.aterminal.backend.ssh

import dev.aterminal.core.BackendError
import dev.aterminal.core.CloseReason
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.channel.PtyChannelConfiguration
import org.apache.sshd.common.channel.StreamingChannel
import org.apache.sshd.common.io.IoInputStream
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.kex.KexState
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.core.CoreModuleProperties
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ตัดสินจากเวลาจริงระหว่างรอบ: ช่วงห่างเกิน 2 เท่าแปลว่า process ไม่ได้ทำงาน (ถูกหยุดหรือเครื่องหลับ)
 * รอบที่หายไปตอนนั้นไม่ใช่หลักฐานว่า network ตาย จึงเริ่มนับใหม่ ใช้นาฬิกา wall เพราะเดินต่อทั้งตอนถูกหยุดและตอนหลับ
 */
internal class HeartbeatMonitor(private val intervalMs: Long, private val missLimit: Int, startMs: Long) {
    enum class Action { SEND, SEND_AFTER_STALL, LOST }

    @Volatile var misses = 0
        private set
    private var lastTickMs = startMs

    fun lastGapMs(nowMs: Long): Long = nowMs - lastTickMs

    fun onTick(nowMs: Long): Action {
        val gap = nowMs - lastTickMs
        lastTickMs = nowMs
        if (gap > intervalMs * 2) {
            misses = 1
            return Action.SEND_AFTER_STALL
        }
        if (misses >= missLimit) return Action.LOST
        misses++
        return Action.SEND
    }

    fun onReply() {
        misses = 0
    }
}

enum class KeyCheck { OK, NEEDS_PASSPHRASE, INVALID }

/**
 * SshBackend — TerminalBackend เหนือ SSH shell channel (PTY) ผ่าน Apache MINA SSHD (Apache-2.0)
 *
 * hostKeyVerifier: TOFU hook — ถูกเรียกกลาง kex (thread ของ MINA, synchronous)
 *   คืน true = ยอมรับ key นี้, false = ตัด connection (BackendError.HOST_KEY_MISMATCH)
 *   null = ไม่ verify (accept-all) — ห้ามใช้ใน production path; app จริงต้องส่ง store เข้ามาเสมอ
 */
class SshBackend(
    private val host: String,
    private val port: Int = 22,
    private val user: String? = null,
    // ส่งเป็น function ไม่ใช่ path: key เก็บแบบเข้ารหัส ถอดเฉพาะตอนใช้ แล้ว backend ล้าง array ทิ้ง
    private val privateKey: (() -> ByteArray)? = null,
    private val password: String? = null,
    private val hostKeyVerifier: ((address: java.net.SocketAddress, key: java.security.PublicKey) -> Boolean)? = null,
    // T2 (round 12): heartbeat interval — ค่า default ใช้จริงบน production; test สั้นลงได้ (JVM lab)
    private val heartbeatInterval: java.time.Duration = KEEPALIVE_INTERVAL,
    // เหตุที่ session ปิด สำหรับ log ที่ user คัดลอกเองได้: ข้อความผ่าน redact แล้ว ไม่มี host/user/address
    private val diagnostics: ((String) -> Unit)? = null,
    private val keyPassphrase: String? = null,
) : TerminalBackend {

    override val type = "ssh"
    override var onData: (ByteArray) -> Unit = {}
    override var onClosed: (CloseReason) -> Unit = {}

    private var client: SshClient? = null
    private var session: ClientSession? = null
    private var shell: ChannelShell? = null
    private val closed = AtomicBoolean(false)

    private fun diag(msg: String) {
        diagnostics?.let { runCatching { it(msg) } }
    }

    private fun describe(t: Throwable): String =
        generateSequence(t) { it.cause }.take(3)
            .joinToString(" <- ") { "${it.javaClass.name}: ${redact(it.message, listOf(host, user))}" }

    // resize off-main (StrictMode) — serial = window-change ไม่สลับลำดับเองมั่ว ๆ
    private val resizeExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "aterm-ssh-resize").apply { isDaemon = true }
    }
    // R4-fix2: คิวขาเข้า (app→server) — writer thread เดียว serialized writeBuffer (ดู startStdinWriter)
    private val stdinQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val stdinBytes = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * R4 (F-1 backpressure, แบบ asyncOut pump — verdict R0/P-SSHD, FACT จาก sources 2.14.0):
     * pull API บน thread ของเรา: read(buf) ครบจำนวนแล้ว sshd ค่อย wLocal.release = WINDOW_ADJUST
     * ⇒ pump หยุด read เมื่อบน block (BoundedByteQueue เต็ม) = window ไม่ถูก adjust = server throttle เอง
     * (ทางเก่า ForwardingStream = sync write บน nio2 thread — block แล้ว freeze read loop ทั้ง session, ปฏิเสธไว้)
     * landmines ที่ถือตาม: pending read ครั้งเดียว (loop ของเรา sequential), EOF = getRead()<0
     */
    /**
     * R4-fix2 (device-gate R10, FACT จาก sources 2.14.0 ChannelAsyncOutputStream:98-121):
     * writeBuffer = ONE outstanding future ต่อ channel — เรียกซ้ำขณะ pending → WritePendingException
     * (keystroke 4 ตัวรวด/ช่อง window stall = พังจริงบน lab)
     * ⇒ เขียนขา app→server ผ่าน writer thread เดียว: FIFO, writeBuffer → future.await() → ตัวถัดไป
     * (caller = main thread ห้าม block รอ RTT; backlog มีเพดาน = backpressure ฝั่ง input)
     */
    private var stdinThread: Thread? = null

    private fun startStdinWriter() {
        stdinThread = Thread {
            while (!closed.get()) {
                val data = try {
                    stdinQueue.take()
                } catch (t: Throwable) {
                    break // interrupt จาก close
                }
                stdinBytes.addAndGet(-data.size.toLong())
                val sh0 = shell ?: break
                try {
                    val f = sh0.asyncIn.writeBuffer(ByteArrayBuffer(data))
                    if (!f.await()) break
                } catch (t: Throwable) {
                    if (!closed.get()) diag("stdin write failed ${describe(t)}")
                    break // channel ตาย = ปกติ — closeFutureListener บอกสถานะจริงเอง
                }
            }
        }.apply { name = "aterm-ssh-stdin"; isDaemon = true; start() }
    }

    private var keepalive: java.util.concurrent.Future<*>? = null

    /** fixed delay ไม่ใช่ fixed rate: รอบที่ค้างระหว่าง process ถูกหยุดต้องหายไป ไม่ยิงตามทีหลัง */
    private fun startKeepalive(c: SshClient, s: ClientSession) {
        val monitor = HeartbeatMonitor(heartbeatInterval.toMillis(), KEEPALIVE_MISS_LIMIT, System.currentTimeMillis())
        keepalive = c.scheduledExecutorService.scheduleWithFixedDelay({
            try {
                if (s.kexState == KexState.DONE && !closed.get()) {
                    val now = System.currentTimeMillis()
                    val gap = monitor.lastGapMs(now)
                    when (monitor.onTick(now)) {
                        HeartbeatMonitor.Action.LOST -> {
                            diag("keepalive lost misses=$KEEPALIVE_MISS_LIMIT")
                            s.close(true) // channel close listener รายงาน Error(retryable) ต่อเอง
                        }
                        HeartbeatMonitor.Action.SEND_AFTER_STALL -> {
                            diag("keepalive stall gap=${gap}ms")
                            sendKeepalive(s, monitor)
                        }
                        HeartbeatMonitor.Action.SEND -> sendKeepalive(s, monitor)
                    }
                }
            } catch (t: Throwable) {
                // ห้ามปล่อย throw: scheduler จะเลิกเรียก task นี้เงียบ ๆ แล้ว session ที่ตายจะไม่มีใครตรวจเจอ
                diag("keepalive failed ${describe(t)}")
            }
        }, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun sendKeepalive(s: ClientSession, monitor: HeartbeatMonitor) {
        val buf = s.createBuffer(SshConstants.SSH_MSG_GLOBAL_REQUEST, KEEPALIVE_REQUEST.length + Byte.SIZE_BITS)
        buf.putString(KEEPALIVE_REQUEST)
        buf.putBoolean(true)
        // reply ทุกชนิดรวม failure/unimplemented แปลว่า server ยังอยู่
        s.request(buf, KEEPALIVE_REQUEST) { _, _ -> monitor.onReply() }
    }

    private fun startPump(out: IoInputStream) {
        Thread {
            val buf = ByteArrayBuffer(32 * 1024)
            try {
                while (!closed.get()) {
                    val w0 = buf.wpos()
                    val f = out.read(buf)
                    if (!f.await()) break // interrupted = app ปิด
                    val n = f.read
                    if (n < 0) break // EOF — channel ตาย/preClose; onClosed ตัวจริงมาจาก closeFutureListener
                    if (n > 0) {
                        onData(java.util.Arrays.copyOfRange(buf.array(), w0, w0 + n)) // MAY block = backpressure
                        buf.clear()
                    }
                }
            } catch (t: Throwable) {
                if (!closed.get()) diag("pump failed ${describe(t)}")
            } // channel ตายกลาง read = ปกติ — อย่า propagate, อย่า toast
        }.apply { name = "aterm-ssh-pump"; isDaemon = true; start() }
    }

    private val hostKeyRejected = AtomicBoolean(false)
    @Volatile private var verifierFailure: Throwable? = null

    override suspend fun connect(startup: StartupArgs) = withContext(Dispatchers.IO) {
        val c = SshClient.setUpDefaultClient()
        // ไม่ใช้ heartbeat ในตัวของ MINA (HEARTBEAT_INTERVAL) เพราะ scheduleAtFixedRate ยิงรอบที่ค้างรวดเดียวหลัง process ถูกหยุด
        // แล้วตัด session ทั้งที่ network ดี (พบจริงบน Honor: หยุด 37 วินาทีตอนอยู่ background) ดู startKeepalive
        // ปิด IDLE_TIMEOUT เพราะ startKeepalive ตรวจการเชื่อมต่อตายแทนแล้ว และถ้าหยุดเกิน 10 นาที timeout จะตัดทันทีตอนกลับมา
        CoreModuleProperties.IDLE_TIMEOUT.set(c, Duration.ZERO)
        // SO_KEEPALIVE ระดับ kernel เป็นเส้นสำรองระยะยาว
        CoreModuleProperties.SOCKET_KEEPALIVE.set(c, true)
        // default ของ MINA เสนอทุก key ใน ~/.ssh ให้ server: ต้องเสนอเฉพาะ key ที่ profile ระบุเท่านั้น
        c.keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
        // exception จริงอยู่ใน listener นี้เท่านั้น: หลังจากนี้ onClosed เหลือแค่ "channel ปิด" ไม่บอกว่าเพราะอะไร
        c.addSessionListener(object : SessionListener {
            override fun sessionException(session: Session, t: Throwable) {
                diag("session exception ${describe(t)}")
            }
            override fun sessionDisconnect(session: Session, reason: Int, msg: String?, language: String?, initiator: Boolean) {
                diag("session disconnect code=$reason byClient=$initiator msg=${redact(msg, listOf(host, user))}")
            }
            override fun sessionClosed(session: Session) {
                diag("session closed")
            }
        })
        c.start()
        client = c
        hostKeyRejected.set(false)
        if (hostKeyVerifier != null) {
            c.setServerKeyVerifier { _, address, key ->
                val ok = try {
                    hostKeyVerifier(address, key)
                } catch (t: Throwable) {
                    verifierFailure = t // อย่า swallow เงียบ — app ต้อง diagnosis ได้
                    false
                }
                if (!ok) hostKeyRejected.set(true)
                ok
            }
        }
        try {
            val s = c.connect(user ?: System.getProperty("user.name"), host, port)
                .verify(TIMEOUT).session
            session = s

            if (privateKey != null) loadIdentities(privateKey.invoke(), keyPassphrase).forEach { s.addPublicKeyIdentity(it) }
            if (password != null) s.addPasswordIdentity(password)
            s.auth().verify(TIMEOUT)

            val pty = PtyChannelConfiguration().apply {
                ptyType = "xterm-256color"
                ptyColumns = startup.cols
                ptyLines = startup.rows
            }
            val sh = s.createShellChannel(pty, mapOf("TERM" to "xterm-256color") + startup.env)
            // R4: async + merge stderr ต้อง set "ก่อน open()" (doOpen สร้าง asyncOut ตาม streaming — sources: ChannelSession)
            // บทเรียน device-gate R10: streaming=Async ทำให้ `setIn(pipe)` ไม่มีคน pull = input ตายเงียบ (เจอจริงบน lab)
            // ⇒ ขา app→server ต้องไป asyncIn.writeBuffer เท่านั้น (javap: IoOutputStream.writeBuffer(Buffer):IoWriteFuture)
            sh.streaming = StreamingChannel.Streaming.Async
            sh.isRedirectErrorStream = true // stderr = stream เดียวตามพฤติกรรม terminal
            sh.open().verify(TIMEOUT)
            shell = sh
            startKeepalive(c, s)
            startPump(sh.asyncOut)
            startStdinWriter() // R4-fix2: ขา app→server แบบ serialized (single outstanding writeBuffer)

            sh.addCloseFutureListener {
                if (closed.compareAndSet(false, true)) {
                    // T3 (round 12): แยก "shell จบเอง" จาก "ท่อขาด" — exit-status เป็น request ที่ server
                    // ส่งก่อน channel_close เสมอ (TCP ordering การันตี arriving ก่อน close future) —
                    // javap ยืนยัน ClientChannel.getExitStatus(): Integer, null = ไม่เคยถูกส่ง
                    //  ⇒ exit ≠ null = user พิมพ์ exit / shell ตายฝั่ง server → Normal (T3 ห้าม redial)
                    //  ⇒ exit = null = transport ขาดจริง (half-open/crash) → Error(retryable) ให้ redial
                    keepalive?.cancel(false)
                    val exit = runCatching { sh.exitStatus }.getOrNull()
                    diag("channel closed exit=$exit")
                    onClosed(if (exit != null) CloseReason.Normal
                    else CloseReason.Error("ssh channel closed", retryable = true))
                }
            }
        } catch (e: BackendError) {
            closeQuietly()
            throw e
        } catch (e: Exception) {
            closeQuietly()
            val code = classifyConnectFailure(hostKeyRejected.get(), e)
            if (code == BackendError.Code.HOST_KEY_MISMATCH && verifierFailure == null) {
                throw BackendError(code, "host key changed for $host:$port, possible MITM. Connection refused.", e)
            }
            throw BackendError(
                code,
                "ssh connect failed: $host:$port" + (verifierFailure?.let { " (host-key verifier threw: $it)" } ?: ""),
                verifierFailure ?: e,
            )
        }
    }

    /**
     * T2 (round 12): classification — ปิด wart round 6 (connection ตายกลาง auth โผล่เป็น AUTH_FAILED)
     *
     * บทเรียนเดิม: "auth" substring heuristic พัง เพราะ sshd ใช้ข้อความ "Authentication failed while
     * waiting %d msec" (DefaultAuthFuture.verify) **ทั้งสอง** กรณี — server ปฏิเสธจริง กับ auth ค้างเพราะ
     * connection ตาย — ข้อความจึงแยกกันไม่ได้ ต้องดู cause chain:
     *   1) transport ตาย (SocketException/reset/closed/timeout/"Session is closed") → UNREACHABLE **ก่อน** —
     *      นี่คือ wart: chainพวกนี้มักมีข้อความ auth/PendingAuthName ติดมาด้วย
     *   2) server ปฏิเสธผ่าน protocol: SshException(disconnectCode=14 NO_MORE_AUTH_METHODS_AVAILABLE,
     *      "No more authentication methods available") — FACT จาก ClientUserAuthService.tryNext (sources 2.14.0)
     *   3) อย่างอื่น = UNREACHABLE (fail-safe: retryable — ห้าม stop-permission ผิดกรณี MITM/TOFU ซึ่งจับที่ 0)
     */
    internal fun classifyConnectFailure(hostKeyRejected: Boolean, e: Exception): BackendError.Code {
        if (hostKeyRejected) return BackendError.Code.HOST_KEY_MISMATCH
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        val transportDead = chain.any { t ->
            t is java.net.SocketException || // รวม SocketTimeoutException ("connect timed out" ฯลฯ)
                t is java.nio.channels.ClosedChannelException ||
                t is java.io.EOFException || // peer ปิดกลาง handshake
                t.message?.contains("Session is closed") == true || // ClientUserAuthService.preClose (wart path)
                t is java.util.concurrent.TimeoutException // verifyResult timeout cause (AbstractSshFuture)
        }
        if (transportDead) return BackendError.Code.UNREACHABLE
        val authRejected = chain.any { t ->
            (t is org.apache.sshd.common.SshException &&
                t.disconnectCode == org.apache.sshd.common.SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE) ||
                t.message?.contains("No more authentication methods available") == true
        }
        return if (authRejected) BackendError.Code.AUTH_FAILED else BackendError.Code.UNREACHABLE
    }

    /** fingerprint แบบ `ssh-keygen -lf` (SHA256 base64 no-padding) — ให้ TOFU UI ใช้ */
    companion object {
        internal val TIMEOUT = Duration.ofSeconds(20)
        private val PROBE_KEX_TIMEOUT = Duration.ofSeconds(10)

        internal const val DIAG_MAX_MESSAGE = 160
        private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
        private val IPV6 = Regex("""\b[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){2,7}\b""")

        private class PassphraseRequested : java.io.IOException()

        private fun parse(bytes: ByteArray, passwords: FilePasswordProvider): List<KeyPair> =
            SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("key"), ByteArrayInputStream(bytes), passwords)
                ?.toList().orEmpty()

        /** ตรวจแบบไม่ถอดรหัส: ใช้ตอน import และก่อนถาม passphrase ไม่ล้าง bytes ให้ ผู้เรียกเป็นเจ้าของ */
        fun inspectKey(bytes: ByteArray): KeyCheck = try {
            if (parse(bytes) { _, _, _ -> throw PassphraseRequested() }.isEmpty()) KeyCheck.INVALID else KeyCheck.OK
        } catch (t: Throwable) {
            if (generateSequence(t) { it.cause }.take(8).any { it is PassphraseRequested }) KeyCheck.NEEDS_PASSPHRASE
            else KeyCheck.INVALID
        }

        /** ล้าง bytes เสมอ MINA ไม่บอกชนิดความผิดที่ชัดเจนเมื่อ passphrase ผิด จึงตัดสินจากว่าไฟล์เข้ารหัสอยู่ไหม */
        internal fun loadIdentities(bytes: ByteArray, passphrase: String?): List<KeyPair> {
            try {
                val check = inspectKey(bytes)
                if (check == KeyCheck.INVALID) throw BackendError(BackendError.Code.KEY_INVALID, "not a supported private key")
                if (check == KeyCheck.NEEDS_PASSPHRASE && passphrase.isNullOrEmpty()) {
                    throw BackendError(BackendError.Code.KEY_PASSPHRASE, "key passphrase required")
                }
                return try {
                    parse(bytes, FilePasswordProvider.of(passphrase)).ifEmpty { throw java.io.IOException("no keys") }
                } catch (e: Exception) {
                    throw BackendError(BackendError.Code.KEY_PASSPHRASE, "wrong key passphrase or unsupported encryption", e)
                }
            } finally {
                bytes.fill(0)
            }
        }

        /** ข้อความจาก library/server อาจมีชื่อเครื่อง, user หรือ address ติดมา: ตัดออกก่อนเข้า log */
        internal fun redact(message: String?, known: List<String?>): String {
            var out = message ?: return ""
            for (k in known) if (!k.isNullOrEmpty()) out = out.replace(k, "<redacted>")
            out = IPV6.replace(IPV4.replace(out, "<ip>"), "<ip>")
            return if (out.length > DIAG_MAX_MESSAGE) out.take(DIAG_MAX_MESSAGE) + "…" else out
        }

        // ตัดเมื่อไม่มี reply เกินเพดานนี้: network ตายจริงรู้ใน ~75 วินาที (15s x 5)
        // เดิม 1 ครั้ง (~30 วินาที) ตัด session ที่แค่ Wi-Fi พักชั่วคราวทิ้ง
        internal val KEEPALIVE_INTERVAL = Duration.ofSeconds(15)
        internal const val KEEPALIVE_REQUEST = "keepalive@openssh.com"
        internal const val KEEPALIVE_MISS_LIMIT = 4
        /** R4-fix2: เพดาน backlog ขาเข้า (paste ยักษ์ + server window stall = main รอได้ไม่เกิน 2s) */
        const val STDIN_MAX_QUEUED = 8 * 1024 * 1024
        private const val STDIN_BACKPRESSURE_TIMEOUT_NS = 2_000_000_000L

        /**
         * Android ไม่มี user.home → class-init ของ MINA (DefaultConfigFileHostEntryResolver) พังด้วย
         * ExceptionInInitializerError — ต้องเรียก setHomeFolderResolver ก่อน setUpDefaultClient ครั้งแรก
         * (app ส่ง filesDir; MINA จะใช้เป็นที่จำ known_hosts/config — เรา not ใช้ไฟล์พวกนั้น)
         */
        fun setHomeFolder(homeDir: java.io.File) {
            org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver { homeDir.toPath() }
        }

        /**
         * fingerprint แบบ `ssh-keygen -lf` จริง — hash ของ SSH wire blob (NOT key.getEncoded()!
         * บน Android getEncoded() = X.509 DER ซึ่งให้ค่าคนละค่า = user เทียบกับ server ไม่ได้ = TOFU พัง)
         * PublicKeyEntry.toString(key) = "algo base64blob" — base64 ส่วนตัวคือ blob ที่ ssh-keygen ใช้
         */
        fun fingerprint(key: java.security.PublicKey): String {
            val line = org.apache.sshd.common.config.keys.PublicKeyEntry.toString(key)
            val blob = java.util.Base64.getDecoder().decode(line.substringAfter(' '))
            val dig = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + java.util.Base64.getEncoder().encodeToString(dig).trimEnd('=')
        }

        /** ssh key type name (เช่น ecdsa-sha2-nistp256) — ดีกว่า Key.algorithm ("EC") สำหรับแสดงใน TOFU dialog */
        fun sshKeyType(key: java.security.PublicKey): String =
            org.apache.sshd.common.config.keys.PublicKeyEntry.toString(key).substringBefore(' ')

        /**
         * TOFU probe: TCP+kex อย่างเดียว ไม่ส่ง user หรือ auth request เลย (เดิมลอง auth แล้ว sshd บันทึก Invalid user,
         * ให้ penalty ต่อ IP และ fail2ban แบนได้) ใช้เฉพาะ host ที่ยังไม่มี pin แล้วเก็บ host key จาก kex
         * — ให้ UI prompt บน main thread ได้ โดยไม่ต้องบล็อก MINA ioThread กลาง connect จริง
         * (บทเรียนจาก device: verifier ที่ latch-รอ dialog = ioThread ถูก interrupt = InterruptedException)
         * throw BackendError(UNREACHABLE) เมื่อติดต่อ server ไม่ได้
         */
        suspend fun probeHostKey(host: String, port: Int): Pair<String, java.security.PublicKey> =
            withContext(Dispatchers.IO) {
                val c = SshClient.setUpDefaultClient().also {
                    it.keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
                    it.start()
                }
                try {
                    var captured: java.security.PublicKey? = null
                    c.setServerKeyVerifier { _, _, key -> captured = key; true }
                    val s = c.connect("probe", host, port).verify(TIMEOUT).session
                    // connect().verify() กลับหลัง version exchange: WAIT_AUTH = kex จบแล้ว verifier ถูกเรียกแล้ว
                    runCatching { s.waitFor(listOf(ClientSession.ClientSessionEvent.WAIT_AUTH), PROBE_KEX_TIMEOUT) }
                    runCatching { s.close(true) }
                    // รับ TCP แต่ไม่จบ kex = ติดต่อไม่ได้เหมือนกัน (server ค้าง, proxy, network เพิ่งกลับ) ต้อง retry ได้
                    captured?.let { sshKeyType(it) to it } ?: throw java.io.IOException("no host key: key exchange did not finish")
                } catch (e: Exception) {
                    throw BackendError(BackendError.Code.UNREACHABLE, "cannot reach $host:$port for host-key probe", e)
                } finally {
                    runCatching { c.close() }
                }
            }
    }

    override fun write(data: ByteArray) {
        if (shell == null || closed.get()) throw BackendError(BackendError.Code.CHANNEL_FAILED, "not connected")
        if (data.isEmpty()) return
        // R4-fix (device-gate R10): streaming=Async ⇒ `in` stream ไม่มีคน pull — ขา app→server ต้องไป asyncIn
        // R4-fix2: ห้ามเรียก writeBuffer จาก caller ตรง ๆ (single-outstanding-future → WritePendingException)
        // — enqueue ให้ writer thread เดียว; เพดาน backlog = กัน main ค้างตอน server stall + paste ยักษ์
        if (stdinBytes.get() + data.size > STDIN_MAX_QUEUED) {
            val deadline = System.nanoTime() + STDIN_BACKPRESSURE_TIMEOUT_NS
            while (stdinBytes.get() + data.size > STDIN_MAX_QUEUED) {
                if (closed.get()) throw BackendError(BackendError.Code.CHANNEL_FAILED, "not connected")
                if (System.nanoTime() > deadline) {
                    throw BackendError(BackendError.Code.CHANNEL_FAILED, "stdin back-pressure timeout (server stalled?)")
                }
                Thread.sleep(2) // รอ writer สะสาง (worst case 2s < ANR 5s — path เกิดจริงยาก: stall+paste ≥8MB)
            }
        }
        stdinQueue.put(data) // LBQ ไร้เพดานของจริง — ตัวเลข stdinBytes คือ gate เหนือ put
        stdinBytes.addAndGet(data.size.toLong())
    }

    override fun resize(cols: Int, rows: Int) {
        // device-gate R10 (FATAL): sendWindowChange = socket I/O — caller มักเป็น main (view onSizeChanged
        // / coalescer trail บน Main) → StrictMode NetworkOnMainThreadException = MINA ปิด session ทั้งวง!
        // (บทเรียน: pump/stdinWriter อยู่ off-main เสมอ — resize คือ path เดียวที่เผลอ run บน main)
        val sh = shell ?: return
        resizeExec.execute { runCatching { sh.sendWindowChange(cols, rows) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeQuietly()
        onClosed(CloseReason.Normal)
    }

    private fun closeQuietly() {
        keepalive?.cancel(false)
        resizeExec.shutdownNow()
        stdinThread?.interrupt() // ปลุก writer ที่ค้างใน take() (daemon ก็หยุดเองไม่ได้ถ้าไม่มี poison)
        runCatching { shell?.close(true) }
        runCatching { session?.close(true) }
        runCatching { client?.close() }
    }
}
