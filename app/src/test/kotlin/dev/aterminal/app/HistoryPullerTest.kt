package dev.aterminal.app

import dev.aterminal.core.BackendError
import dev.aterminal.core.CloseReason
import dev.aterminal.core.ExecResult
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend
import dev.aterminal.core.TmuxHistory
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/08: HistoryPuller — ลำดับ exec + page planning + fail-soft เหนือ fake backend (script ตามแบบ tmux/07)
 *
 * script = (exact command, result-or-throw) ตามลำดับ; command ไม่ตรง = fail ทันที (lock ลำดับไปในตัว)
 * contract ที่ lock: HS ก่อน capture เสมอ (window ต้อง clamp), exitCode null = ทิ้งทั้งก้อน + halve,
 * resize กลาง pull (generation เปลี่ยน) = ทิ้งไม่ inject, on top of history = ไม่มี capture
 */
class HistoryPullerTest {

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
            val s = steps.firstOrNull()
                ?: kotlinx.coroutines.awaitCancellation()
            check(s.command == command) { "puller sent out-of-order command:\n  want ${s.command}\n  got  $command" }
            steps.removeFirst()
            s.error?.let { throw it }
            if (s.result == null) kotlinx.coroutines.awaitCancellation()
            return s.result
        }
    }

    private fun ok(stdout: String = "", exit: Int = 0) = ExecResult(exit, stdout, "")

    /** body ของ capture ที่ planInjection = [fetched.dropLast(8)] — 10 บรรทัด → inject 2 บรรทัดแรก */
    private fun tenLines() = (1..10).joinToString("\n") { "L$it" } + "\n"

    private class Harness(steps: List<Step>, target: String? = "main", logical: Int = 8, display: Int = 100, gen: () -> Int = { 0 }) {
        val backend = ScriptedBackend(ArrayDeque(steps))
        val injected = mutableListOf<List<String>>()
        val warns = mutableListOf<String>()
        val statuses = mutableListOf<Boolean>()
        var logicalNow = logical
        val puller = HistoryPuller(
            backend = backend,
            target = { target },
            appDepths = { logicalNow to display },
            generation = gen,
            onInject = { injected += it },
            onStatus = { statuses += it },
            onWarn = { warns += it },
        )
    }

    private fun pull(h: Harness) = runBlocking { h.puller.pull() }

    @Test
    fun `happy path asks history_size first then captures a tail-pinned window`() {
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand("main"), ok("5000\n")),
                Step(TmuxHistory.captureCommand("main", 100 + 2_000), ok(tenLines())),
            ),
        )
        pull(h)
        assertEquals(listOf(listOf("L1", "L2")), h.injected)
        assertEquals(listOf(true, false), h.statuses)
        assertTrue(h.warns.isEmpty())
    }

    @Test
    fun `no target falls back to server-current commands`() {
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand(null), ok("5000\n")),
                Step(TmuxHistory.captureCommand(null, 2_100), ok(tenLines())),
            ),
            target = null,
        )
        pull(h)
        assertEquals(1, h.injected.size)
    }

    @Test
    fun `at top of history pulls nothing - no capture exec, no busy toast`() {
        val h = Harness(listOf(Step(TmuxHistory.historySizeCommand("main"), ok("8\n"))))
        pull(h)
        assertEquals(emptyList<List<String>>(), h.injected)
        assertEquals(listOf(TmuxHistory.historySizeCommand("main")), h.backend.executed)
        assertFalse("no-op pull ต้องไม่ raise Loading toast", h.statuses.contains(true)) // Q4: จบเงียบ = เงียบจริง
    }

    @Test
    fun `overflow discards the whole page and halves the next window`() {
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand("main"), ok("5000\n")),
                Step(TmuxHistory.captureCommand("main", 2_100), ExecResult(null, "half a page", "")), // cap overflow
                Step(TmuxHistory.historySizeCommand("main"), ok("5000\n")),
                Step(TmuxHistory.captureCommand("main", 100 + 1_000), ok(tenLines())),
            ),
        )
        pull(h) // รอบ 1: overflow
        assertEquals(emptyList<List<String>>(), h.injected)
        pull(h) // รอบ 2: หน้าต่างเหลือ display + 1000
        assertEquals(listOf(listOf("L1", "L2")), h.injected)
    }

    @Test
    fun `resize mid-pull drops the page for a clean re-anchor next gesture`() {
        var reads = 0 // gen เปลี่ยนระหว่าง call ที่ 1 (pull start) กับ call ที่ 2 (pre-inject) = reflow กลาง capture
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand("main"), ok("5000\n")),
                Step(TmuxHistory.captureCommand("main", 2_100), ok(tenLines())),
            ),
            gen = { if (++reads >= 2) 1 else 0 },
        )
        pull(h)
        assertEquals(emptyList<List<String>>(), h.injected)
    }

    @Test
    fun `transport failure and tmux refusal are silent no-ops with traces`() {
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand("main"), error = BackendError(BackendError.Code.CHANNEL_FAILED, "drop")),
                Step(TmuxHistory.historySizeCommand("main"), ok("", exit = 1)),
            ),
        )
        pull(h)
        pull(h)
        assertEquals(emptyList<List<String>>(), h.injected)
        assertEquals(2, h.warns.size)
    }

    @Test
    fun `a second pull while one is in flight is dropped, not queued`() {
        val h = Harness(listOf(Step(TmuxHistory.historySizeCommand("main")))) // hang step
        runBlocking {
            val first = launch { h.puller.pull() }
            withTimeout(2_000) { while (h.backend.executed.isEmpty()) delay(10) }
            h.puller.pull() // busy = ต้อง return ทันที ไม่คิวซ้ำ
            assertEquals(listOf(TmuxHistory.historySizeCommand("main")), h.backend.executed)
            first.cancelAndJoin()
        }
    }

    @Test
    fun `wrapped-heavy consumption is learned from the capture and grows the next window`() {
        // r_pane = 8 display/logical ขณะที่ avg ฝั่ง app = 2 (pane แคบกว่า app / history บรรทัดยาวมาก)
        // window แรกรวม overlap ตาม avg ฝั่ง app = ไม่พอทับ overlap → plan ว่าง = draft เดียว stall นิรันดร์
        val body8 = (1..500).joinToString("\n") { "L%04d".format(it) } + "\n" // window 4000 ÷ 8
        val bodyWide = (1..1250).joinToString("\n") { "W%04d".format(it) } + "\n" // window 10000 ÷ 8
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand("main"), ok("80000")),
                Step(TmuxHistory.captureCommand("main", 4_000), ok(body8)),
                Step(TmuxHistory.historySizeCommand("main"), ok("80000")),
                Step(TmuxHistory.captureCommand("main", 10_000), ok(bodyWide)),
            ),
            logical = 1_000, display = 2_000,
        )
        pull(h)
        assertEquals(emptyList<List<String>>(), h.injected) // 500 logical < overlap 1000 = ห้าม inject, warn + จด ratio
        assertEquals(1, h.warns.size)
        pull(h)
        val page = h.injected.single()
        assertEquals(250, page.size) // 1250 fetched − overlap 1000 (window โตตาม ratio ที่วัดได้ = 8)
        assertEquals("W0001", page.first())
    }

    @Test
    fun `true top of a wrapped history ends quietly even when logical depth never reaches display units`() {
        // device ground truth (main @ t06, 11-08): 1935 display lines = 729 logical under -J — gate ที่ใช้
        // hasDeeperHistory(logical, historySize) ไม่มีทางปิดเอง = ทุก gesture ที่ top = toast+capture+warn นิรันดร์
        // (overlay เจอก่อน เพราะเปิดที่ depth 0: หน้าแรก = history ทั้งก้อน, gesture ถัดไป = ขอบจริง)
        // สัญญาณจบที่ถูกต้อง: window ถูก clamp ที่ historySize (pinned ที่ top) และ fetched ≤ overlap = ไม่มีของเก่ากว่านี้
        val body729 = (1..729).joinToString("\n") { "M%04d".format(it) } + "\n"
        val h = Harness(
            listOf(
                Step(TmuxHistory.historySizeCommand("main"), ok("1935")),
                Step(TmuxHistory.captureCommand("main", 1_935), ok(body729)),
                Step(TmuxHistory.historySizeCommand("main"), ok("1935")),
                Step(TmuxHistory.captureCommand("main", 1_935), ok(body729)), // gesture #2 = discovery: fresh ว่าง, window==size
                Step(TmuxHistory.historySizeCommand("main"), ok("1935")),     // gesture #3 = gate เงียบ, ห้าม capture
                Step(TmuxHistory.historySizeCommand("main"), ok("2000")),     // history โตขึ้น (live output) = gate เปิดใหม่
                Step(TmuxHistory.captureCommand("main", 2_000), ok((1..735).joinToString("\n") { "N%04d".format(it) } + "\n")),
            ),
            logical = 0, display = 0, // overlay-style: appDepths = delivered ที่ pager จ่าย (mock ผ่าน logicalNow)
        )
        // ผู้เรียก overlay-style: depths = สิ่งที่ pager deliver แล้ว (live path = pane จริง) — test นี้ mock ผ่าน logicalNow
        pullTopLoop(h)
        assertEquals("first page = whole wrapped history, oldest-first", "M0001", h.injected.first().first())
        assertEquals(729, h.injected.first().size)
        assertTrue("top discovery must not warn (it is the normal end, not a stall)", h.warns.isEmpty())
        assertEquals(
            "gesture #3 gated silently (HS only); #4 reopens on growth",
            listOf(
                TmuxHistory.historySizeCommand("main"),
                TmuxHistory.captureCommand("main", 1_935),
                TmuxHistory.historySizeCommand("main"),
                TmuxHistory.captureCommand("main", 1_935),
                TmuxHistory.historySizeCommand("main"),
                TmuxHistory.historySizeCommand("main"),
                TmuxHistory.captureCommand("main", 2_000),
            ),
            h.backend.executed,
        )
        assertEquals(6, h.injected.last().size) // 735 fetched − overlap 729
        assertEquals("N0001", h.injected.last().first())
    }

    /** drives pull() while feeding the harness the delivered depth (overlay pager semantics: logical=display=delivered) */
    private fun pullTopLoop(h: Harness) {
        repeat(4) {
            runBlocking { h.puller.pull() }
            h.logicalNow = h.injected.sumOf { it.size } // = OverlayPager.appDepths { delivered to delivered }
        }
    }

    // ---------- parseHistoryText: capture text → TermLine (throwaway engine, app cols) ----------

    @Test
    fun `parseHistoryText keeps order blanks and wrap-continuations at app width`() {
        val parsed = parseHistoryText(listOf("first", "", "0123456789AB"), cols = 10)
        assertEquals(4, parsed.size) // input 3 + wrap continuation 1 (ห้ามตก tail ของบรรทัดยาว)
        assertEquals("f", parsed[0].get(0).ch)
        assertFalse(parsed[0].isWrapped)
        assertEquals(" ", parsed[1].get(0).ch) // blank line = ข้อมูลจริง (DEFAULT cell = " ") ไม่ใช่ artifact
        assertEquals("0", parsed[2].get(0).ch)
        assertFalse(parsed[2].isWrapped)
        assertEquals("A", parsed[3].get(0).ch)
        assertTrue(parsed[3].isWrapped)
    }
}
