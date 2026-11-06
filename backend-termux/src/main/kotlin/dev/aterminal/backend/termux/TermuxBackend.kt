package dev.aterminal.backend.termux

import dev.aterminal.core.BackendError
import dev.aterminal.core.CloseReason
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend

/**
 * TermuxBackend — backend "local shell" โดยไม่สร้าง userland เอง (Android 10+ W^X block exec จาก app data)
 *
 * แผน 2 ทาง (ตกลงแล้ว):
 *  A. localhost-SSH: ผู้ใช้รัน sshd ใน Termux (pkg install openssh; termux-ssh-setup; sshd)
 *     → delegate = SshBackend("127.0.0.1", 8022) — **wired แล้ว round 6 ผ่าน factory injection**
 *     ข้อดี: streaming เต็ม, resize จริง, ไร้ permission พิเศษ
 *  B. RUN_COMMAND bridge (ไม่ stream — fire-and-forget + result broadcast) — เหมาะ "run one command" ไม่ใช่ terminal session
 *
 * การตรวจ Termux ติดตั้งอยู่ (PackageManager) เป็นเรื่องของ app module — ที่นี่เก็บ contract ล้วน:
 *  - factory == null (app ไม่ wire / unit test โสด) → LOCAL_SERVICE_MISSING ตาม contract เดิม
 *  - factory from app = closure ที่ capture auth/TOFU-verifier ของ session นั้น ๆ (SshBackend เดิมทั้ง flow)
 */
class TermuxBackend(
    private val sshPort: Int = DEFAULT_SSH_PORT,
    private val factory: ((host: String, port: Int) -> TerminalBackend)? = null,
) : TerminalBackend {

    override val type = "termux"
    override var onData: (ByteArray) -> Unit = {}
    override var onClosed: (CloseReason) -> Unit = {}
    private var delegate: TerminalBackend? = null

    // tmux/02: delegate คือ SshBackend เสมอ (Termux = sshd บนเครื่อง) → capability gate เห็น version ผ่านทางนี้
    override val tmuxCapabilities: dev.aterminal.core.TmuxCapabilities? get() = delegate?.tmuxCapabilities

    override suspend fun connect(startup: StartupArgs) {
        val f = factory ?: throw BackendError(
            BackendError.Code.LOCAL_SERVICE_MISSING,
            "termux backend is not wired, use a profile with type=ssh host=127.0.0.1 port=$sshPort",
        )
        val d = f("127.0.0.1", sshPort)
        d.onData = { onData(it) }
        d.onClosed = { onClosed(it) }
        delegate = d
        try {
            d.connect(startup)
        } catch (e: Exception) {
            delegate = null    // connect พัง = ไม่เหลือ delegate ครึ่ง ๆ (write ถัดไปต้องได้ notConnected)
            throw e
        }
    }

    override fun write(data: ByteArray) { delegate?.write(data) ?: throw notConnected() }
    override fun resize(cols: Int, rows: Int) { delegate?.resize(cols, rows) }
    override fun close() {
        val d = delegate ?: return      // close ซ้ำ/ปิดก่อน connect = silent (contract เดิมของ SessionController)
        delegate = null
        d.close()
        onClosed(CloseReason.Normal)
    }

    private fun notConnected() = BackendError(BackendError.Code.CHANNEL_FAILED, "termux backend not connected")

    companion object {
        const val DEFAULT_SSH_PORT = 8022
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    }
}
