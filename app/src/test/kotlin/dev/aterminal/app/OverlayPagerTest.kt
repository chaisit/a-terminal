package dev.aterminal.app

import dev.aterminal.core.BackendError
import dev.aterminal.core.CloseReason
import dev.aterminal.core.ExecResult
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend
import dev.aterminal.core.TmuxHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/08b: OverlayPager — overlay depth = overlap ของตัวมันเอง (live buffer ไม่เกี่ยว)
 *
 * script-lock ลำดับเดียวกับ HistoryPullerTest: HS → capture; จุดต่างที่ lock:
 *  - เปิด (depth=0) = ได้ทั้งหน้า ไม่ drop ใด (planInjection dropLast(0))
 *  - หน้าถัดไป window = depth + page, delivered = dropLast(depth) — ไม่มีบรรทัดซ้ำ
 *  - cap = trim ที่ tail (newest, user เห็นใน pane อยู่แล้ว) — overlap ยังนับเส้นที่ trim ไปแล้ว
 *    (กัน trimmed line ถูก deliver ซ้ำ = dup ที่ขอบล่าง)
 */
class OverlayPagerTest {

    private class Step(val command: String, val result: ExecResult? = null, val error: Throwable? = null)

    private class ScriptedBackend(private val steps: ArrayDeque<Step>) : TerminalBackend {
        override val type = "fake"
        override var onData: (ByteArray) -> Unit = {}
        override var onClosed: (CloseReason) -> Unit = {}
        val executed = mutableListOf<String>()
        override suspend fun connect(startup: StartupArgs) {}
        override fun write(data: ByteArray) {}
        override fun resize(cols: Int, rows: Int) {}
        override fun close() {}
        override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
            executed += command
            val s = steps.firstOrNull() ?: kotlinx.coroutines.awaitCancellation()
            check(s.command == command) { "pager sent out-of-order command:\n  want ${s.command}\n  got  $command" }
            steps.removeFirst()
            s.error?.let { throw it }
            if (s.result == null) kotlinx.coroutines.awaitCancellation()
            return s.result
        }
    }

    private fun ok(stdout: String = "", exit: Int = 0) = ExecResult(exit, stdout, "")
    // fixture rule: body size == window ที่เรียก = ratio ที่ puller เรียน ≡ 1.0
    // (body สั้นกว่า window = จำลอง wrap-heavy → ratio โต → หน้าต่างถัดไปบานตาม = ทิศ over-capture ที่ปลอดภัยแต่ทำให้เลขคาดยาก)
    private fun body(n: Int) = (1..n).joinToString("\n") { "L%04d".format(it) } + "\n"
    private fun bodyA(n: Int) = (1..n).joinToString("\n") { "A%04d".format(it) } + "\n"
    private fun bodyB(n: Int) = (1..n).joinToString("\n") { "B%04d".format(it) } + "\n"

    private class Harness(steps: List<Step>, target: String? = "main", maxLines: Int = Int.MAX_VALUE) {
        val backend = ScriptedBackend(ArrayDeque(steps))
        val prepended = mutableListOf<List<String>>()
        val trims = mutableListOf<Int>()
        val warns = mutableListOf<String>()
        val pager = OverlayPager(
            backend = backend,
            target = { target },
            maxLines = maxLines,
            onPrepend = { prepended += it },
            onTrimBottom = { trims += it },
            onWarn = { warns += it },
        )
    }

    private fun pull(h: Harness) = runBlocking { h.pager.pull() }

    @Test
    fun `open delivers the whole first page with zero overlap`() {
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand("main"), ok("5000\n")),
                Step(TmuxHistory.captureCommand("main", 2_000), ok(body(10))),
            ),
        )
        pull(h)
        assertEquals(1, h.prepended.size)
        assertEquals(10, h.prepended.single().size) // depth=0 → dropLast(0) = ทั้งหน้า
        assertEquals("L0001", h.prepended.single().first())
        assertEquals(emptyList<Int>(), h.trims)
    }

    @Test
    fun `second pull overlaps by its own delivered depth - no duplicated boundary`() {
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand("main"), ok("8000\n")),
                Step(TmuxHistory.captureCommand("main", 2_000), ok(body(2_000))),
                Step(TmuxHistory.historySizeCommand("main"), ok("8000\n")),
                Step(TmuxHistory.captureCommand("main", 4_000), ok(body(4_000))), // window = depth 2000 + page
            ),
        )
        pull(h)
        pull(h)
        val page2 = h.prepended[1]
        assertEquals(2_000, page2.size) // fetched 4000 − overlap = depth ที่ deliver ไปแล้ว (2000)
        assertEquals("L0001", page2.first()) // older-first: ขอบบนเลื่อนขึ้นต่อจากหน้าแรก ไม่มีซ้ำ
        assertEquals("L2000", page2.last()) // L2001..L4000 = สิ่งที่ overlay แสดงแล้ว = ถูก drop
    }

    @Test
    fun `cap trims newest at the tail but overlap keeps counting trimmed lines`() {
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand("main"), ok("9000\n")),
                Step(TmuxHistory.captureCommand("main", 2_000), ok(bodyA(2_000))), // delivered 2000 → lines 15 (trim 1985 ที่ tail)
                Step(TmuxHistory.historySizeCommand("main"), ok("9000\n")),
                Step(TmuxHistory.captureCommand("main", 4_000), ok(bodyB(4_000))), // window = depth(2000 รวมที่ trim)+2000 — กัน dup ขอบล่าง
                Step(TmuxHistory.historySizeCommand("main"), ok("9000\n")),
                Step(TmuxHistory.captureCommand("main", 6_000), ok(body(1))), // depth 4000 → fail-soft (1 < overlap)
            ),
            maxLines = 15,
        )
        pull(h)
        pull(h)
        assertEquals(listOf(1_985, 2_000), h.trims) // trim = newest ที่ user เห็นใน pane แล้ว, oldest ไม่ขยับ
        assertEquals(15, h.pager.lines.size)
        assertEquals("B0001", h.pager.lines.first())
        assertEquals("B0015", h.pager.lines.last())
        pull(h)
        assertEquals(2, h.prepended.size) // fail-soft: ไม่มีหน้าใหม่, ไม่มี dup จากเส้นที่ trim ไป
        assertEquals(1, h.warns.size)
    }

    @Test
    fun `transport failure keeps the overlay silent but traced`() {
        val h = Harness(listOf(Step(TmuxHistory.historySizeCommand("main"), error = BackendError(BackendError.Code.CHANNEL_FAILED, "drop"))))
        pull(h)
        assertEquals(emptyList<List<String>>(), h.prepended)
        assertEquals(1, h.warns.size)
    }
}
