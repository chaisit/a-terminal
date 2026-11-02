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
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.core.CoreModuleProperties
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

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
    private val privateKeyPath: String? = null,
    private val password: String? = null,
    private val hostKeyVerifier: ((address: java.net.SocketAddress, key: java.security.PublicKey) -> Boolean)? = null,
    // T2 (round 12): heartbeat interval — ค่า default ใช้จริงบน production; test สั้นลงได้ (JVM lab)
    private val heartbeatInterval: java.time.Duration = KEEPALIVE_INTERVAL,
) : TerminalBackend {

    override val type = "ssh"
    override var onData: (ByteArray) -> Unit = {}
    override var onClosed: (CloseReason) -> Unit = {}

    private var client: SshClient? = null
    private var session: ClientSession? = null
    private var shell: ChannelShell? = null
    private val closed = AtomicBoolean(false)
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
                    break // channel ตาย = ปกติ — closeFutureListener บอกสถานะจริงเอง
                }
            }
        }.apply { name = "aterm-ssh-stdin"; isDaemon = true; start() }
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
            } catch (_: Throwable) {
            } // channel ตายกลาง read = ปกติ — อย่า propagate, อย่า toast
        }.apply { name = "aterm-ssh-pump"; isDaemon = true; start() }
    }

    private val hostKeyRejected = AtomicBoolean(false)
    @Volatile private var verifierFailure: Throwable? = null

    override suspend fun connect(startup: StartupArgs) = withContext(Dispatchers.IO) {
        val c = SshClient.setUpDefaultClient()
        // T2 (round 12) — keepalive/close-detection, FACT จาก sources jar 2.14.0 (ห้ามจำ — ตรวจแล้วทีละตัว):
        // • heartbeat **มีในตัว** (ClientConnectionService.startHeartBeat) แต่ default ปิด:
        //   HEARTBEAT_INTERVAL=ZERO → ตั้ง 15s; wantReply = (NO_REPLY_MAX > 0);
        //   ส่งครั้งที่ (MAX+1) โดยไม่มี reply → SshException → session.exceptionCaught → close → closeFutureListener → onClosed
        //   (ตรวจ reply ทุกชนิดรวม UNIMPLEMENTED/REQUEST_FAILURE = live — callback reset outstanding)
        //   ⇒ half-open (peer เงียบ, ไม่ส่ง FIN/RST) ตายภายใน ~2×interval; reply ยัง reset IDLE_TIMEOUT ให้ด้วย
        //      (sources: requestSuccess/requestFailure/handleUnimplemented → resetIdleTimeout —
        //       channel DATA **ไม่** reset: ถ้าไม่มี heartbeat, session idle 10 นาทีโดน IDLE_TIMEOUT ฆ่าเอง!)
        // • SOCKET_KEEPALIVE = SO_KEEPALIVE ระดับ kernel (default false) — เส้นสำรองระยะยาว
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(c, heartbeatInterval)
        CoreModuleProperties.HEARTBEAT_REQUEST.set(c, KEEPALIVE_REQUEST)
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(c, KEEPALIVE_MISS_LIMIT)
        CoreModuleProperties.SOCKET_KEEPALIVE.set(c, true)
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

            if (privateKeyPath != null) {
                FileKeyPairProvider(Paths.get(privateKeyPath))
                    .loadKeys(null)
                    .forEach { s.addPublicKeyIdentity(it) }
            }
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
            startPump(sh.asyncOut)
            startStdinWriter() // R4-fix2: ขา app→server แบบ serialized (single outstanding writeBuffer)

            sh.addCloseFutureListener {
                if (closed.compareAndSet(false, true)) {
                    // T3 (round 12): แยก "shell จบเอง" จาก "ท่อขาด" — exit-status เป็น request ที่ server
                    // ส่งก่อน channel_close เสมอ (TCP ordering การันตี arriving ก่อน close future) —
                    // javap ยืนยัน ClientChannel.getExitStatus(): Integer, null = ไม่เคยถูกส่ง
                    //  ⇒ exit ≠ null = user พิมพ์ exit / shell ตายฝั่ง server → Normal (T3 ห้าม redial)
                    //  ⇒ exit = null = transport ขาดจริง (half-open/crash) → Error(retryable) ให้ redial
                    val exit = runCatching { sh.exitStatus }.getOrNull()
                    onClosed(if (exit != null) CloseReason.Normal
                    else CloseReason.Error("ssh channel closed", retryable = true))
                }
            }
        } catch (e: Exception) {
            closeQuietly()
            val code = classifyConnectFailure(hostKeyRejected.get(), e)
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

        // T2 (round 12) — heartbeat: เปิด using library mechanism (ดู comment ใน connect)
        // 15s × NO_REPLY_MAX=1 → half-open ตรวจจับ+ปิดตัวเองภายใน ~2×15s = 30s (gate T2)
        internal val KEEPALIVE_INTERVAL = Duration.ofSeconds(15)
        internal const val KEEPALIVE_REQUEST = "keepalive@openssh.com" // OpenSSH ตอบ success; server อื่นได้
        // UNIMPLEMENTED/FAILURE reply = live ทั้งนั้น (ClientConnectionService callback นับทุก reply)
        internal const val KEEPALIVE_MISS_LIMIT = 1
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
         * TOFU probe: TCP+kex อย่างเดียว (ไม่ auth, ไม่เปิด channel) แล้วเก็บ host key จาก kex
         * — ให้ UI prompt บน main thread ได้ โดยไม่ต้องบล็อก MINA ioThread กลาง connect จริง
         * (บทเรียนจาก device: verifier ที่ latch-รอ dialog = ioThread ถูก interrupt = InterruptedException)
         * throw BackendError(UNREACHABLE) เมื่อติดต่อ server ไม่ได้
         */
        suspend fun probeHostKey(host: String, port: Int): Pair<String, java.security.PublicKey>? =
            withContext(Dispatchers.IO) {
                val c = SshClient.setUpDefaultClient().also { it.start() }
                try {
                    var captured: java.security.PublicKey? = null
                    c.setServerKeyVerifier { _, _, key -> captured = key; true }
                    val s = c.connect("aterm-probe", host, port).verify(TIMEOUT).session
                    // connect().verify() กลับหลัง version exchange — kex (ที่เรียก verifier) อาจยังไม่จบ
                    // dummy auth (ไม่มี identity) บังคับให้ kex/SshMsgNewKeys จบก่อน แล้ว auth ค่อย fail — ทิ้งผลได้
                    runCatching { s.auth().verify(Duration.ofSeconds(5)) }
                    runCatching { s.close(true) }
                    captured?.let { sshKeyType(it) to it }
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
        resizeExec.shutdownNow()
        stdinThread?.interrupt() // ปลุก writer ที่ค้างใน take() (daemon ก็หยุดเองไม่ได้ถ้าไม่มี poison)
        runCatching { shell?.close(true) }
        runCatching { session?.close(true) }
        runCatching { client?.close() }
    }
}
