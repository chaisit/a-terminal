package dev.aterminal.app

import dev.aterminal.core.ControlEvent
import dev.aterminal.core.ControlSession
import dev.aterminal.core.ControlTransport
import dev.aterminal.core.TmuxControl
import dev.aterminal.core.WindowStrip

/**
 * ticket tmux/09 (Seam 3b): control session ฝั่ง app — หนึ่ง host ต่อ connection,
 * เปิดเฉพาะเมื่อ tab ที่มี tmux ถูก focus (user ruling 11-08) — resource gate โดยโครงสร้าง: ≤1 control session ทั้งเครื่อง
 *
 * lifecycle: start (probe-armed caller เท่านั้น — pattern เดียวกับ 07/08b) → strip init (mute, subscribe, size)
 * → events ไหลผ่าน [WindowStrip] → snapshot ออก [onWindows] → stop = ปิด channel (server reap client เอง, main PTY ไม่แตะ — spike fact 3)
 *
 * fail-soft ทุก path: target ไร้/ห้าม = ไม่เปิดเงียบ ๆ; เปิด channel พัง = warn ครั้งเดียว; remote ตาย = clear strip + warn
 * (strip เป็น affordance เสริม — ห้ามทำให้ dial/pane พังตาม)
 *
 * threading: [onWindows] ยิงจาก transport pump thread — ผู้เรียก marshal เอง (pattern onTmuxProbe);
 * [select]/[resize]/[stop] เรียกจาก main; fields ข้าม thread ใช้ volatile + immutable list
 */
internal class WindowStripHost(
    private val open: suspend (command: String, cols: Int, rows: Int) -> ControlTransport,
    private val target: () -> String?,
    private val size: () -> Pair<Int, Int>,
    private val onWindows: (List<WindowStrip.Window>) -> Unit,
    private val onWarn: (String) -> Unit = {},
) {
    @Volatile private var session: ControlSession? = null
    @Volatile private var windows: List<WindowStrip.Window> = emptyList()
    // กัน start ที่ค้างกลาง open แล้ว stop วิ่งทับ (สลับ tab เร็วกว่า RTT): generation mismatch = transport ที่เพิ่งได้ต้องปิดทิ้ง
    // ไม่งั้น resource gate (≤1 control session) พังเงียบ — session ถูก set หลัง open เท่านั้น
    private val gen = java.util.concurrent.atomic.AtomicInteger()

    suspend fun start() {
        if (session != null) return // gate: หนึ่ง control session ต่อ host (ห้ามเปิดซ้ำตอน re-probe/redial เล็กน้อย)
        val cmd = TmuxControl.attachCommand(target()) ?: return // ไม่มี target ชัด = ไม่มี strip (fail-soft เงียบโดยเจตนา)
        val (cols, rows) = size()
        val g = gen.incrementAndGet()
        val transport = try {
            open(cmd, cols, rows)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            onWarn("control open failed: ${t.javaClass.simpleName}")
            return
        }
        if (gen.get() != g) { // stop()/start() อื่นวิ่งทับระหว่าง open — ทิ้ง channel นี้ (close fail ห้ามเงียบ: resource gate พังแบบมองไม่เห็น)
            runCatching { transport.close() }
                .onFailure { onWarn("stale control close failed: ${it.javaClass.simpleName}") }
            return
        }
        val s = ControlSession(
            transport,
            onEvent = { ev -> event(ev) },
            onClosed = { reason ->
                // pump ฝั่ง transport การันตี closed หลัง events ทั้งหมด — clear คือ snapshot สด ไม่ใช่ race ทับของใหม่
                windows = emptyList()
                onWindows(emptyList())
                onWarn("control closed: ${reason.javaClass.simpleName}")
            },
        )
        session = s
        s.start()
        TmuxControl.stripInitCommands().forEach { s.send(it) }
        s.send(TmuxControl.resizeCommand(cols, rows))
    }

    private fun event(ev: ControlEvent) {
        if (session == null) return // stop แล้ว = of-stale pump (ControlSession กันอีกชั้น — นี่ belt)
        val next = WindowStrip.reduce(windows, ev) ?: return
        windows = next
        onWindows(next)
    }

    /** tap pill: select ด้วย @id — id แปลกปลอม fail-soft เงียบ */
    fun select(windowId: String) {
        TmuxControl.selectWindow(windowId)?.let { session?.send(it) }
    }

    /** grid ของ pane เปลี่ยน — บอก client size ของ control session ด้วย (PTY size ไม่ propagate — spike fact 13) */
    fun resize(cols: Int, rows: Int) {
        runCatching { session?.send(TmuxControl.resizeCommand(cols, rows)) }
            .onFailure { onWarn("control resize rejected: ${it.message}") }
    }

    /** ปิด channel + ทิ้ง snapshot (idempotent; เรียกตอน tab loses focus / pane ตาย / dial ฝัง) */
    fun stop() {
        gen.incrementAndGet() // ทำลาย start ที่กำลัง await open (ดู gen guard ใน start)
        val s = session
        session = null
        s?.close()
        windows = emptyList()
        onWindows(emptyList())
    }
}
