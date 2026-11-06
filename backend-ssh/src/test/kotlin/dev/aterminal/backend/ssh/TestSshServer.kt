package dev.aterminal.backend.ssh

import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelFactory
import org.apache.sshd.common.channel.RequestHandler
import org.apache.sshd.common.keyprovider.HostKeyCertificateProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Environment
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process SSH lab — ไม่พึ่ง host sshd/เน็ต:
 *  - password auth tester/secret
 *  - EchoShell: พิมพ์ banner ขนาด PTY ตอน start แล้ว echo input กลับ raw (test roundtrip + resize)
 *  - RecordingChannelSession: จับ window_change (cols,rows) ที่ client ส่งมา (assert sendWindowChange)
 */
object TestSshServer {
    const val USER = "tester"
    const val PASS = "secret"
    const val HEARTBEAT_NAME = "keepalive@openssh.com"

    val recordedWindows = CopyOnWriteArrayList<Pair<Int, Int>>()
    // ticket tmux/01: exec request (startup.commandline) ต้องถึง server ตรง ๆ — จับ string ที่ client ส่งมา
    val recordedCommands = CopyOnWriteArrayList<String>()
    // pty-req แรกของ channel (cols,rows) — ทางเดียวที่ assert ขนาด PTY ฝั่ง server ได้จริง
    // (ข้อสังเกต 2.14.0: Environment ของ exec Command ไม่มี COLUMNS/LINES seeding — อย่าวัดขนาดจาก env)
    val recordedPtyReqs = CopyOnWriteArrayList<Pair<Int, Int>>()

    /**
     * half-open จำลองบน JVM (T2 gate): เมื่อเปิด flag, server รับ keepalive request แล้วยุบเงียบ —
     * socket/echo ปกติทุกอย่าง มีเฉพาะ heartbeat ที่ไม่มี reply กลับ (≠ kill server = ได้ FIN/RST)
     *
     * FACT จาก sources 2.14.0 `AbstractConnectionService.sendGlobalResponse`:
     * `Result.Replied` = "handler ตอบเองแล้ว" ⇒ framework **ไม่ส่ง packet ใด** (fulfilled future เปล่า)
     * ส่วน `Unsupported` + wantReply → ส่ง SSH_MSG_REQUEST_FAILURE = live reply ปกติ
     */
    @Volatile var deafToHeartbeat = false
    val heartbeatSeen = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * ticket tmux/02: set = exec `tmux -V` (probe) ตอบ canned output นี้แล้ว exit 0 (≡ tmux จริง/ไม่มี tmux จริง)
     * commandFactory ของ 2.14.0 **ไม่ได้เห็น command string** (FACT — ดู signature) → peek recordedCommands ตัวล่าสุด
     * ปลอดภัยเพราะ client **start probe หลัง main channel open().verify() จบเท่านั้น** — main exec (ถ้ามี)
     * ถูก record+create บน request เดียวกันก่อนเสมอ; probe ตัวเดียวต่อ connection (SshBackend เรียกครั้งเดียว)
     * ถ้า probe เคยถูกยิงขนานหลายเส้น ต้อง key ด้วย token ต่อ channel แทน (review tmux/02)
     */
    @Volatile var versionOutput: String? = null

    // SAM explicit (abstract method ชื่อ process ไม่ใช่ SAM ชื่อ interface — Kotlin ไม่ infer Lambda ให้)
    private val heartbeatSpy = object : RequestHandler<org.apache.sshd.common.session.ConnectionService> {
        override fun process(
            service: org.apache.sshd.common.session.ConnectionService,
            req: String,
            wantReply: Boolean,
            buffer: org.apache.sshd.common.util.buffer.Buffer,
        ): RequestHandler.Result =
            if (req != HEARTBEAT_NAME) RequestHandler.Result.Unsupported
            else {
                heartbeatSeen.incrementAndGet()
                // Replied = "ตอบเองแล้ว" → framework ไม่ส่ง reply (half-open จำลอง); Unsupported → REQUEST_FAILURE
                if (deafToHeartbeat) RequestHandler.Result.Replied else RequestHandler.Result.Unsupported
            }
    }

    fun start(): SshServer {
        recordedWindows.clear()
        recordedCommands.clear()
        recordedPtyReqs.clear()
        deafToHeartbeat = false
        heartbeatSeen.set(0)
        versionOutput = null
        val server = SshServer.setUpDefaultServer()
        server.port = 0 // ephemeral
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(
            Files.createTempDirectory("aterminal-test-hostkey").resolve("hostkey.ser")
        )
        server.setPasswordAuthenticator { u, p, _ -> u == USER && p == PASS }
        // ยอมรับทุก key แต่ MINA ยังตรวจลายเซ็นจริง: พิสูจน์ได้ว่าโหลด private key ถูกต้อง
        server.setPublickeyAuthenticator { u, _, _ -> u == USER }
        // shell request → ShellFactory (ไม่ใช่ CommandFactory — ตัวนั้นคือ exec)
        server.shellFactory = org.apache.sshd.server.shell.ShellFactory { _ -> EchoShell() }
        // ticket tmux/02: exec "tmux -V" (probe) = canned output เมื่อ versionOutput ถูก set (ดู peek-note ด้านบน)
        server.commandFactory = CommandFactory { _, _ ->
            val canned = versionOutput
            if (canned != null && recordedCommands.lastOrNull() == PROBE_COMMAND) CannedCommand(canned) else EchoShell()
        }
        server.channelFactories = listOf(RecordingSessionFactory)
        // handler ของเราอยู่หน้าสุด — Unsupported แล้ว framework จะไล่ handler ถัดไปเอง (sources: globalRequest loop)
        server.globalRequestHandlers = listOf(heartbeatSpy) + (server.globalRequestHandlers ?: emptyList())
        server.start()
        return server
    }

    /** banner ที่ EchoShell พิมพ์เมื่อ start — "TESTBANNER <cols>x<rows>" */
    const val BANNER_PREFIX = "TESTBANNER "

    /** คำสั่ง probe ที่ SshBackend ต้องยิงต่อ connection (tmux/02) — test assert ตรง string นี้ */
    const val PROBE_COMMAND = "tmux -V"
}

/**
 * ticket tmux/02: exec command ที่ตอบ output กำหนดเองครั้งเดียวแล้ว exit 0 (≡ `tmux -V` จริงบน server)
 * output = untrusted string ได้ตาม test (รวม garbage ที่ parser ต้อง fail-soft)
 */
class CannedCommand(private val output: String) : Command {
    private var exit: ExitCallback? = null
    private var out: OutputStream? = null

    override fun setInputStream(stream: InputStream) {}
    override fun setOutputStream(stream: OutputStream) { out = stream }
    override fun setErrorStream(stream: OutputStream) {}
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        out?.write(output.toByteArray())
        out?.flush()
        exit?.onExit(0)
    }

    override fun destroy(channel: ChannelSession) {}
}

class EchoShell : Command {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var error: OutputStream? = null
    private var exit: ExitCallback? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun setInputStream(stream: InputStream) { input = stream }
    override fun setOutputStream(stream: OutputStream) { output = stream }
    override fun setErrorStream(stream: OutputStream) { error = stream }
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        val cols = env.env[Environment.ENV_COLUMNS] ?: "?"
        val rows = env.env[Environment.ENV_LINES] ?: "?"
        running = true
        thread = Thread({
            val out = output!!
            try {
                out.write((TestSshServer.BANNER_PREFIX + cols + "x" + rows + "\r\n").toByteArray())
                out.flush()
                val buf = ByteArray(1024)
                val line = StringBuilder()
                while (running) {
                    val n = input!!.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                    // T3 gate: "exit" (บรรทัดเดี่ยว) = shell จบเอง → onExit(0) = framework ส่ง exit-status ก่อน close
                    // (≠ transport ตาย which ไม่มี exit-status — client แยกสอง case นี้เพื่อ auto-reconnect)
                    for (c in String(buf, 0, n)) {
                        if (c == '\r' || c == '\n') {
                            if (line.toString().trim() == "exit") { // shell จบเอง ≡ exit-status แล้ว close
                                running = false
                                exit?.onExit(0)
                                break
                            }
                            // review tmux/01: "fail" = จบด้วย exit ≠ 0 (เคส command typo / tmux lost server)
                            if (line.toString().trim() == "fail") {
                                running = false
                                exit?.onExit(3)
                                break
                            }
                            line.setLength(0)
                        } else line.append(c)
                    }
                }
            } catch (_: Throwable) { /* channel ปิด */ }
        }, "echo-shell").apply { isDaemon = true; start() }
    }

    override fun destroy(channel: ChannelSession) {
        running = false
        thread?.interrupt()
        exit?.onExit(0)
    }
}

object RecordingSessionFactory : ChannelFactory {
    override fun getName(): String = "session"
    override fun createChannel(session: org.apache.sshd.common.session.Session): Channel =
        RecordingChannelSession()
}

class RecordingChannelSession : ChannelSession() {
    override fun handleWindowChangeParsed(
        width: Int, height: Int, pxWidth: Int, pxHeight: Int,
    ): RequestHandler.Result {
        TestSshServer.recordedWindows += width to height
        return super.handleWindowChangeParsed(width, height, pxWidth, pxHeight)
    }

    // 2.14.0 วัดจริง (ขัด javap param-name เดิม): arg แรก = request name "exec", arg สอง = ตัวคำสั่ง
    override fun handleExecParsed(request: String, command: String): RequestHandler.Result {
        TestSshServer.recordedCommands.add(command)
        return super.handleExecParsed(request, command)
    }

    // 2.14.0: param ต้องเป็น Kotlin Map (Java Map = override ไม่ match — วัดจริงตอน compile)
    override fun handlePtyReqParsed(
        term: String, columns: Int, rows: Int, width: Int, height: Int,
        modes: Map<org.apache.sshd.common.channel.PtyMode, Int>,
    ): RequestHandler.Result {
        TestSshServer.recordedPtyReqs.add(columns to rows)
        return super.handlePtyReqParsed(term, columns, rows, width, height, modes)
    }
}
