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
}

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
