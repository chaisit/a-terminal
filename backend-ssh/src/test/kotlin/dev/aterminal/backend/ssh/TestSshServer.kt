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
     * dispatch ตรงบน command string — FACT (javap 2.14.0): `CommandFactory.createCommand(ChannelSession, String)`
     * **เห็น command จริง** (คอมเมนต์ยุค tmux/02 ที่ว่า "ไม่ได้เห็น" = จำผิด, review tmux/06 ชี้+ตรวจแล้ว)
     * จึงไม่ต้อง peek recordedCommands ซึ่ง race กับ probe ที่ยิงขนานบน connection เดียวกัน
     */
    @Volatile var versionOutput: String? = null

    /**
     * ticket tmux/06: canned exec table สำหรับ one-shot exec (key = command string ตรงตัว)
     */
    data class Canned(val stdout: String, val stderr: String, val exit: Int)
    @Volatile var execCanned: Map<String, Canned> = emptyMap()
    /** command ที่ hang ตลอด (test timeout/cancel) — ไม่ exit ไม่ output */
    const val HANG_COMMAND = "hang-forever"
    /** stream ยุบยิบไม่มีวันว่าง (deadline ต้องตัดได้แม้ stream busy — review tmux/06) */
    const val SLOW_FLOOD_COMMAND = "slow-flood-forever"
    /** 700KiB ลง stdout + 700KiB ลง stderr แล้ว exit 0 — แต่ละ stream ไม่แตะ 1MiB, รวมเกิน (cap แบบรวม, review tmux/06) */
    const val CAP_BURST_COMMAND = "cap-burst-both-streams"
    /** พิมพ์ output หลัง ~300ms แล้ว exit 0 — fixture ของ unbounded exec (tmux/07 waiter shape) */
    const val SLEEPY_COMMAND = "sleep-then-exit"
    /** อ่าน stdin จน EOF แล้ว record — fixture ของ stdin guard (tmux/07 orphan-killer wrapper ต้องได้ EOF *เฉพาะตอน channel ปิด*) */
    const val STDIN_EOF_COMMAND = "drain-stdin-until-eof"
    // ผลจาก StdinEofCommand — test อ่านหลัง cancel (start() รีเซ็ต)
    @Volatile var stdinEofReached = false
    @Volatile var stdinDestroyed = false
    @Volatile var stdinBytesRead = -1

    /** tmux/09: fixture ของ control transport — echo ทุก byte, บรรทัดว่าง/EOF = exit 0 (≡ `tmux -CC` + detach) */
    const val CONTROL_ECHO_COMMAND = "control-echo-loop"
    /** tmux/09: fixture ที่ตายทันทีด้วย exit 4 (≡ bad target บน `tmux -CC attach`) — ปิด channel ด้วย Error(non-retryable) */
    const val CONTROL_CRASH_COMMAND = "control-crash-exit-4"
    // true เมื่อ ControlEchoCommand จบ (EOF หรือ detach) — test อ่านหลัง transport.close()
    @Volatile var controlCommandEnded = false

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
        execCanned = emptyMap()
        stdinEofReached = false
        stdinDestroyed = false
        stdinBytesRead = -1
        controlCommandEnded = false
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
        // dispatch ตรงบน command ที่ client ส่งมา (javap: createCommand เห็น command — ดู doc ด้านบน)
        server.commandFactory = CommandFactory { _, cmd ->
            val canned = versionOutput
            when {
                cmd == HANG_COMMAND -> HangCommand() // tmux/06: timeout/cancel fixture
                cmd == SLOW_FLOOD_COMMAND -> SlowFloodCommand() // tmux/06: busy-stream deadline fixture
                cmd == CAP_BURST_COMMAND -> CapBurstCommand() // tmux/06: combined-cap fixture
                cmd == SLEEPY_COMMAND -> SleepyCommand() // tmux/07: unbounded-exec fixture
                cmd == STDIN_EOF_COMMAND -> StdinEofCommand() // tmux/07: stdin guard fixture
                cmd == CONTROL_ECHO_COMMAND -> ControlEchoCommand() // tmux/09: control transport fixture
                cmd == CONTROL_CRASH_COMMAND -> ControlCrashCommand() // tmux/09: dead-command fixture
                execCanned.containsKey(cmd) -> ExecCommand(execCanned.getValue(cmd)) // tmux/06 one-shot exec
                canned != null && cmd == PROBE_COMMAND -> CannedCommand(canned)
                else -> EchoShell()
            }
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

/** tmux/06: exec ที่ตอบ stdout+stderr ตาม canned แล้วยิง exit code จริง (รวม ≠0) */
class ExecCommand(private val canned: TestSshServer.Canned) : Command {
    private var exit: ExitCallback? = null
    private var out: OutputStream? = null
    private var err: OutputStream? = null

    override fun setInputStream(stream: InputStream) {}
    override fun setOutputStream(stream: OutputStream) { out = stream }
    override fun setErrorStream(stream: OutputStream) { err = stream }
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        out?.write(canned.stdout.toByteArray()); out?.flush()
        err?.write(canned.stderr.toByteArray()); err?.flush()
        exit?.onExit(canned.exit)
    }

    override fun destroy(channel: ChannelSession) {}
}

/**
 * tmux/06 review (deadline starvation): stream ไม่เคยเงียบเลย แต่ volume ต่ำกว่า cap —
 * deadline ต้องยังตัดได้ (โค้ดเดิมเช็ค deadline เฉพาะจังหวะ avail==0 ระหว่าง chunk)
 * หมายเหตุ: localhost client drain เป็น burst เร็วกว่า writer จึงเกิด avail==0 แทรกเป็นระยะ —
 * test นี้ therefore pin พฤติกรรม (กัน refactor ทำ starvation กลับมา) มากกว่า red ก่อน fix;
 * เคส starvation จริง (server เร็วกว่า drain) เครื่องช้า/เน็ตจริงถึงจะชน — ตอนนั้น cap รับอีกชั้น
 */
class SlowFloodCommand : Command {
    @Volatile private var stopped = false
    private var out: OutputStream? = null
    override fun setInputStream(stream: InputStream) {}
    override fun setOutputStream(stream: OutputStream) { out = stream }
    override fun setErrorStream(stream: OutputStream) {}
    override fun setExitCallback(callback: ExitCallback) {}

    override fun start(channel: ChannelSession, env: Environment) {
        Thread({
            val chunk = ByteArray(4096)
            while (!stopped && !Thread.currentThread().isInterrupted) {
                try {
                    out!!.write(chunk); out!!.flush()
                } catch (_: Throwable) { break } // client ปิด channel = จบ
                Thread.sleep(5) // ~800KB/s — 400ms deadline ถึง cap ก่อนไม่ได้ (320KB < 1MiB)
            }
        }, "flood-cmd").apply { isDaemon = true; start() }
    }

    override fun destroy(channel: ChannelSession) { stopped = true }
}

/**
 * tmux/06 review (combined cap): 700KiB ลงแต่ละ stream — ไม่มี streamไหนแตะ 1MiB คนเดียว แต่รวม = 1.4MiB
 * cap ต่อ stream (โค้ดเดิม) = ไม่ overflow → exit 0 + buffer 1.4MiB (เกินเพดานที่ doc สัญญา 1MiB)
 * cap รวม (ที่แก้) = overflow ตั้งแต่ 1MiB → exitCode null
 */
class CapBurstCommand : Command {
    private var out: OutputStream? = null
    private var err: OutputStream? = null
    private var exit: ExitCallback? = null
    override fun setInputStream(stream: InputStream) {}
    override fun setOutputStream(stream: OutputStream) { out = stream }
    override fun setErrorStream(stream: OutputStream) { err = stream }
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        Thread({
            try {
                val chunk = ByteArray(4096)
                repeat(700 / 4) { // 175 × 4096 = 700KiB ต่อ stream
                    out!!.write(chunk); err!!.write(chunk)
                }
                out!!.flush(); err!!.flush()
                exit?.onExit(0)
            } catch (_: Throwable) { /* client break ที่ cap แล้วปิด channel — ปกติ */ }
        }, "cap-burst").apply { isDaemon = true; start() }
    }

    override fun destroy(channel: ChannelSession) {}
}

/** tmux/07: command ที่เงียบ ~300ms พิมพ์ "slept" แล้ว exit 0 — พิสูจน์ unbounded exec รอถึง exit จริงได้ */
class SleepyCommand : Command {
    private var output: OutputStream? = null
    private var exit: ExitCallback? = null

    override fun setInputStream(stream: InputStream) {}
    override fun setOutputStream(stream: OutputStream) { output = stream }
    override fun setErrorStream(stream: OutputStream) {}
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        Thread({
            try {
                Thread.sleep(300)
                output?.write("slept\n".toByteArray())
                output?.flush()
                exit?.onExit(0)
            } catch (_: InterruptedException) {
                runCatching { exit?.onExit(1) }
            }
        }, "sleepy-cmd").apply { isDaemon = true; start() }
    }

    override fun destroy(channel: ChannelSession) {}
}

/**
 * tmux/07 stdin guard fixture: อ่าน stdin เรื่อย ๆ จน EOF แล้ว record (eof + byte count) —
 * ถ้า client ส่ง EOF ตอน open (MINA default เมื่อ `in` ไม่ถูก set) flag จะขึ้นทันที = bug;
 * ถูกต้อง = EOF เห็นได้หลัง channel ปิดเท่านั้น (destroy นับเป็น fallback — real sshd ปิด fd ให้ลูก_process อยู่แล้ว)
 */
class StdinEofCommand : Command {
    private var input: InputStream? = null
    private var exit: ExitCallback? = null

    override fun setInputStream(stream: InputStream) { input = stream }
    override fun setOutputStream(stream: OutputStream) {}
    override fun setErrorStream(stream: OutputStream) {}
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        Thread({
            try {
                var n = 0
                val ins = input
                while (ins != null && ins.read() != -1) n++
                TestSshServer.stdinBytesRead = n
                TestSshServer.stdinEofReached = true
                runCatching { exit?.onExit(0) }
            } catch (_: Throwable) {
                runCatching { exit?.onExit(1) }
            }
        }, "stdin-eof-cmd").apply { isDaemon = true; start() }
    }

    override fun destroy(channel: ChannelSession) { TestSshServer.stdinDestroyed = true }
}

/**
 * tmux/09: fixture ของ control transport — จำพฤติกรรม `tmux -CC` (spike 11-08):
 * echo input กลับ raw, **บรรทัดว่าง = detach** (exit 0), stdin EOF ก็ exit 0 — ไม่มี banner
 * (byte-level state machine: `\n` ขณะอยู่ต้นบรรทัด = detach ตาม control.c, นับข้าม chunk)
 */
class ControlEchoCommand : Command {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var exit: ExitCallback? = null
    @Volatile private var running = false

    override fun setInputStream(stream: InputStream) { input = stream }
    override fun setOutputStream(stream: OutputStream) { output = stream }
    override fun setErrorStream(stream: OutputStream) {}
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        running = true
        Thread({
            try {
                val ins = input!!
                val out = output!!
                val buf = ByteArray(1024)
                var atLineStart = true
                while (running) {
                    val n = ins.read(buf)
                    if (n < 0) break // EOF = channel ปิด — ≡ detach เช่นกัน
                    out.write(buf, 0, n)
                    out.flush()
                    for (i in 0 until n) {
                        if (buf[i] == '\n'.code.toByte()) {
                            if (atLineStart) { // บรรทัดว่าง = detach ตาม protocol
                                TestSshServer.controlCommandEnded = true
                                runCatching { exit?.onExit(0) }
                                return@Thread
                            }
                            atLineStart = true
                        } else atLineStart = false
                    }
                }
                TestSshServer.controlCommandEnded = true
                runCatching { exit?.onExit(0) } // EOF: detach ฝั่ง server (≡ spike fact 2 "EOF ก็จบ")
            } catch (_: Throwable) { /* channel ปิด */ }
        }, "control-echo").apply { isDaemon = true; start() }
    }

    override fun destroy(channel: ChannelSession) {
        running = false
        TestSshServer.controlCommandEnded = true
    }
}

/** tmux/09: control command ที่ตายทันที exit 4 (≡ attach target ที่ไม่มีจริง) — client ต้อง map เป็น Error(non-retryable) */
class ControlCrashCommand : Command {
    private var exit: ExitCallback? = null
    override fun setInputStream(stream: InputStream) {}
    override fun setOutputStream(stream: OutputStream) {}
    override fun setErrorStream(stream: OutputStream) {}
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        exit?.onExit(4) // framework ส่ง exit-status แล้วปิด channel — client เห็น exit=4
    }

    override fun destroy(channel: ChannelSession) {}
}

/** tmux/06: exec ที่ไม่มีวันจบ (forced-command style) — deadline/cancel ของ client ต้องตัดได้จริง */
class HangCommand : Command {
    @Volatile private var stopped = false
    override fun setInputStream(stream: InputStream) {}
    override fun setOutputStream(stream: OutputStream) {}
    override fun setErrorStream(stream: OutputStream) {}
    override fun setExitCallback(callback: ExitCallback) {}

    override fun start(channel: ChannelSession, env: Environment) {
        Thread({
            while (!stopped && !Thread.currentThread().isInterrupted) Thread.sleep(50)
        }, "hang-cmd").apply { isDaemon = true; start() }
    }

    override fun destroy(channel: ChannelSession) { stopped = true }
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
