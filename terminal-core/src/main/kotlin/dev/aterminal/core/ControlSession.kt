package dev.aterminal.core

/**
 * ticket tmux/09: duplex byte pipe ของ control session — implementer = backend (SSH exec-**PTY** channel;
 * spike 11-08: `tmux -CC` บน pipe เปล่า = tcgetattr fail ทันที)
 *
 * callback ทั้งหมด deliver เรียงลำดับ (contract เดียวกับ TerminalBackend.onData); thread ใดเรียก write/close ได้
 */
interface ControlTransport {
    /**
     * connect แล้ว pump bytes เข้า [sink]; [closed] เรียกครั้งเดียวเมื่อ channel จบ **จากฝั่ง remote/command**
     * (idempotent ฝั่ง implementer, deliver **หลัง** bytes ที่ส่งมาก่อนหน้าเสมอ — ห้าม race ทับ events ขาเข้า)
     * [close] ที่ caller เรียกเอง = จบเงียบ ไม่มี [closed] (caller ริเริ่มเอง ย่อมรู้แล้วว่าจบ)
     */
    fun open(sink: (ByteArray) -> Unit, closed: (CloseReason) -> Unit)

    fun write(data: ByteArray)
    fun close()
}

/**
 * control session = transport + [ControlParser] + command framing
 *
 * framing rules (wire facts spike 11-08, tmux 3.6):
 *  - ทุก command ปิดท้าย LF โดย session เอง; input ที่มี `\n` **หรือ `\r`** = ปฏิเสธ (บรรทัดว่าง = detach,
 *    newline กลาง = inject; `\r` ด้วยเพราะ transport เป็น PTY — canonical ICRNL แปลง CR→NL ก่อน tmux เห็น = inject เดียวกัน)
 *  - [detach] = บรรทัดว่างตรง ๆ (ทางปิดที่ protocol นิยาม — clean, `%exit` ตามมา)
 *  - หลัง close/detach: [send]/[detach] = no-op (fail-soft — ผู้เรียกอยู่เหนือ async boundary)
 *    detach ยังปล่อย events/[onClosed] ไหลต่อจน client ฝั่ง server จบจริง (ต่างจาก close ที่ตัดเงียบ)
 *
 * threading: [send]/[detach]/[close] จาก main; onEvent/onClosed deliver บน thread ฝั่ง transport (caller cross-thread เอง — convention เดียวกับ onTmuxProbe)
 */
class ControlSession(
    private val transport: ControlTransport,
    private val onEvent: (ControlEvent) -> Unit,
    private val onClosed: (CloseReason) -> Unit,
) {
    private val parser = ControlParser()
    // ข้าม thread จริง: อ่านบน transport pump thread (sink/closed), เขียนจาก main (send/close) — AtomicBoolean ไม่ใช่ @Volatile
    // เพราะ "ปิดครั้งเดียว" เป็น check-then-act (CAS) — volatile กันแค่ stale read เดียว (pattern เดียวกับ SshBackend.closed)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    // detach = client กำลังจบตาม protocol — กัน send ซ้อนลงไปท่อที่กำลังตาย แต่ยังไม่ปิด channel (ไม่กระทบ [closed])
    private val detached = java.util.concurrent.atomic.AtomicBoolean(false)

    /** เริ่ม pump; bytes ทั้งหมดเข้า parser → [onEvent] (fail-soft: parser throw ไม่ได้ โดย construction) */
    fun start() {
        transport.open(
            sink = { bytes -> parser.feed(bytes).forEach { ev -> if (!closed.get()) onEvent(ev) } },
            closed = { reason -> if (closed.compareAndSet(false, true)) onClosed(reason) },
        )
    }

    fun send(command: String) {
        if (closed.get() || detached.get()) return
        // `\r` ห้ามด้วย: PTY canonical ICRNL แปลง CR→NL ก่อน tmux เห็น = detach/inject ได้เหมือน `\n`
        require(!command.contains('\n') && !command.contains('\r')) {
            "control command must be a single line without CR/LF (empty line = detach): $command"
        }
        transport.write((command + "\n").toByteArray(Charsets.UTF_8))
    }

    /** บรรทัดว่าง = detach ตาม protocol (spike fact 2: `%exit` ตามด้วย client จบ, session ไม่กระทบ) */
    fun detach() {
        if (closed.get() || !detached.compareAndSet(false, true)) return
        transport.write("\n".toByteArray(Charsets.UTF_8))
    }

    /** ปิด channel + ทิ้ง callback ที่ตามหลัง (idempotent) — app kill ก็จบ path นี้ (server reap client เอง — spike fact 3) */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        transport.close()
    }
}
