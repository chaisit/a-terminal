package dev.aterminal.core

/**
 * TerminalBackend — คู่เทียบแนวคิด ITerminalConnection ของ Windows Terminal
 * (refs/terminal doc/ORGANIZATION.md: TerminalConnection DLL) แต่พูด VT ตรง ๆ
 * เพราะ backend บน Android (SSH channel / Termux pty) เป็น VT stream อยู่แล้ว — ConPTY ไม่จำเป็น
 *
 * Lifecycle: create -> connect() -> [ onData / write / resize ] -> close()
 * Thread-safety: implementer ต้อง allow เรียก write/resize จาก thread ใดก็ได้,
 * callback ทั้งหมด guarantee ว่า deliver แบบ sequential (order คงไว้)
 */
interface TerminalBackend {
    /** type ของ profile: "ssh" | "termux" — ตรงกับ field ใน Profile (WT-style: source/connectionType) */
    val type: String

    /** เชื่อมต่อ + start shell session; suspend จนพร้อม, throw BackendError เมื่อล้มเหลว (retryable flag) */
    suspend fun connect(startup: StartupArgs)

    /** ผู้ใช้พิมพ์/ paste -> ป้อนเข้า PTY เป็น raw UTF-8 bytes */
    fun write(data: ByteArray)

    /** ปรับขนาด PTY (cols x rows) — เรียกได้ตั้งแต่ connect สำเร็จ */
    fun resize(cols: Int, rows: Int)

    /** ปิด session + ทรัพยากรทั้งหมด (idempotent) */
    fun close()

    /** output จาก shell (VT byte stream) — deliver on caller-chosen dispatcher */
    var onData: (ByteArray) -> Unit
    var onClosed: (reason: CloseReason) -> Unit

    /**
     * tmux/02 (central API ของ feature gates): version+capabilities ของ tmux ฝั่ง remote
     * probe หนึ่งครั้งต่อ connection (exec `tmux -V`) — **ห้าม persist** (tmux เปลี่ยน version ได้, pin ผิด = feature พังเงียบ)
     * null = ยัง probe ไม่เสร็จ / ไม่มี tmux / backend นี้ไม่ probe (echo) — ผู้เรียกต้อง fail-soft (ซ่อน feature)
     */
    val tmuxCapabilities: TmuxCapabilities? get() = null

    /**
     * tmux/07: แจ้งผล probe **หนึ่งครั้งต่อ probe ที่จบ** (ค่าสุดท้าย = `tmuxCapabilities` ที่ตั้งจริง) —
     * app ใช้ arm alert watcher หลัง caps ยืนยันว่ามี tmux (ห้าม arm blind ตอน dial)
     *  - เรียกบน thread ฝั่ง backend (probe daemon) — ผู้ฟังข้าม thread เอง
     *  - backend ที่ไม่ probe (echo) = ไม่เรียกเลย (default no-op)
     */
    var onTmuxProbe: ((TmuxCapabilities?) -> Unit)?
        get() = null
        set(_) {}

    /**
     * tmux/06 (foundation ของ 07/08/09/10): one-shot exec บน connection เดิม — ไม่มี PTY, ไม่แตะ channel หลัก
     *
     * - request/response หนึ่งรอบ: ผูก timeout ให้เอง (deadline ครบ = ปิด channel แล้ว throw CHANNEL_FAILED)
     * - timeoutMs <= 0 = ไม่มี deadline (tmux/07 waiter: command ที่รอข้าม ชม. เช่น `tmux wait-for`) — จบเมื่อ command exit หรือ caller cancel
     * - cancellation: ผู้เรียก cancel ได้ — implementer ต้องปิด channel แล้วคืน CancellationException (ห้ามค้าง thread/pump)
     * - exitCode != 0 = ผลปกติ (เช่น list-sessions บน server ว่าง) — transport fail เท่านั้นที่ throw
     * - backend ที่ไม่มี exec channel (echo) = UNSUPPORTED ชัด ๆ — ห้ามเงียบเป็น empty success
     */
    suspend fun exec(command: String, timeoutMs: Long = 5_000L): ExecResult =
        throw BackendError(BackendError.Code.UNSUPPORTED, "backend '$type' has no exec channel")

    /**
     * tmux/09: channel ยาวสำหรับ control session (`tmux -CC`) — exec **ที่มี PTY**
     * (wire fact spike 11-08: `tmux -CC` บน pipe เปล่า = tcgetattr failed + exit 1 — PTY คือเงื่อนไข ไม่ใช่ option)
     *
     * - suspend จน channel เปิดเสร็จ (fail = throw BackendError, เช่น CHANNEL_FAILED เมื่อ server ปฏิเสธ exec/pty)
     * - channel ที่สองบน connection เดียว — ต้องไม่แตะ channel หลัก (protocol เดียวกับ exec/probe)
     * - [cols]/[rows] = PTY size ของ channel (control client ไม่ดึง size จาก PTY — แต่ tmux ยังต้องการ tty ที่ถูกขนาด)
     * - ผู้เรียกปิด transport เอง (detach/close); connection ปิด = transport จบตาม (onClosed หนึ่งครั้ง)
     * - backend ที่ไม่มี exec-PTY channel (echo) = UNSUPPORTED ชัด ๆ — ห้ามเงียบ
     */
    suspend fun openControlTransport(command: String, cols: Int, rows: Int): ControlTransport =
        throw BackendError(BackendError.Code.UNSUPPORTED, "backend '$type' has no control channel")
}

/** ผล one-shot exec (tmux/06) — stdout/stderr decode UTF-8 เต็มก้อน, exitCode null = server ไม่ส่ง status */
data class ExecResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
)

/** สิ่งที่ profile ต้องส่งให้ backend ตอนเปิด session (mapping ของ WT NewTerminalArgs) */
data class StartupArgs(
    val commandline: String?,        // WT: profile.commandline — สำหรับ ssh = remote command (null = default shell)
    val startingDirectory: String?,  // WT: profile.startingDirectory
    val cols: Int,
    val rows: Int,
    val env: Map<String, String> = emptyMap(), // WT: profile.environment
)

sealed class CloseReason {
    data object Normal : CloseReason()
    data class Error(val message: String, val retryable: Boolean) : CloseReason()
}

class BackendError(
    val code: Code,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Code { UNREACHABLE, AUTH_FAILED, CHANNEL_FAILED, LOCAL_SERVICE_MISSING, UNSUPPORTED, HOST_KEY_MISMATCH, KEY_PASSPHRASE, KEY_INVALID }

    /** retryable — แนวเดียวกับ REA envelope (code + retryable); HOST_KEY_MISMATCH ต้องไม่ retry อัตโนมัติ (MITM guard) */
    val retryable: Boolean
        get() = code == Code.UNREACHABLE || code == Code.CHANNEL_FAILED
}
