package dev.aterminal.app

import dev.aterminal.core.BackendError
import dev.aterminal.core.CloseReason
import dev.aterminal.core.ExecResult
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend
import dev.aterminal.core.TmuxAlerts
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/07: TmuxAlertWatcher — loop ระหว่าง wait-for exec กับ payload (fake backend, scripted steps)
 *
 * script = list of (exact command, result-or-throw) ที่ consume ตามลำดับ — exec ที่ command ไม่ตรง script
 * = fail ทันที (test จึง assert "ลำดับคำสั่ง" ไปด้วยในตัว); script หมด = awaitCancellation (waiter ตัวถัดไปที่ค้างจริง)
 * behavior ทุกข้อผูกกับ lab facts ตั๋ว 07: exit 0 เดี่ยวไม่ใช่ signal (server-ตายก็ 0) → อ่าน payload ก่อน;
 * payload = nothing = เงียบแล้ว re-arm; UNSUPPORTED = จบ ไม่ใช่ hot-loop
 */
class TmuxAlertWatcherTest {

    /** result=null + error=null = "hang step" — exec บล็อกเหมือน wait-for ตัวจริงที่รอ server (Deterministic: executed ไม่โตเกินหลัง log) */
    private class Step(val command: String, val result: ExecResult? = null, val error: Throwable? = null)

    private class ScriptedBackend(private val steps: ArrayDeque<Step>) : TerminalBackend {
        override val type = "fake"
        override var onData: (ByteArray) -> Unit = {}
        override var onClosed: (CloseReason) -> Unit = {}
        val executed = mutableListOf<String>()
        val execTimes = mutableListOf<Long>() // ms — สำหรับวัดจังหวะ backoff (escalation test)
        override suspend fun connect(startup: StartupArgs) {}
        override fun write(data: ByteArray) {}
        override fun resize(cols: Int, rows: Int) {}
        override fun close() {}
        override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
            executed += command
            execTimes += System.nanoTime() / 1_000_000
            val s = steps.firstOrNull()
                ?: kotlinx.coroutines.awaitCancellation() // script หมด = waiter ติดจริง (ต้อง cancel)
            check(s.command == command) { "watcher sent out-of-order command:\n  want ${s.command}\n  got  $command" }
            steps.removeFirst()
            s.error?.let { throw it }
            // waiter ต้องเป็น unbounded (timeoutMs=0) — สัญญา 07: ถ้า backend อื่นเรียกมาพร้อม deadline = test fail
            if (s.command == TmuxAlerts.waitCommand()) assertEquals("waiter must exec unbounded", 0L, timeoutMs)
            if (s.result == null) kotlinx.coroutines.awaitCancellation() // hang step — waiter ค้างจริงจน cancel
            return s.result
        }
    }

    private fun ok(stdout: String = "", exit: Int = 0) = ExecResult(exit, stdout, "")

    private fun scriptWaitReadClear(payload: String?) = arrayOf(
        Step(TmuxAlerts.waitCommand(), ok()), // wait ที่ "ถูกปลุก" (มี result) — ต่างจาก tail hang step
        Step(TmuxAlerts.readCommand(), ok(payload ?: "")),
        Step(TmuxAlerts.clearCommand(), ok()),
    )

    private fun runUntil(backend: ScriptedBackend, n: Int, block: () -> Unit = {}) = runBlocking {
        val alerts = mutableListOf<TmuxAlerts.Alert>()
        val job = launch { TmuxAlertWatcher(backend, { alerts += it }, retryMs = 10).run() }
        withTimeout(2_000) { while (backend.executed.size < n) delay(10) }
        job.cancelAndJoin()
        block()
        alerts
    }

    @Test
    fun `a clean wake reads payload, acks it, fires the callback, and re-arms the waiter`() {
        val be = ScriptedBackend(ArrayDeque((scriptWaitReadClear("bell|alpha|edit") + Step(TmuxAlerts.waitCommand())).toList()))
        val alerts = runUntil(be, 4)
        assertEquals(listOf(TmuxAlerts.Alert("bell", "alpha", "edit")), alerts)
        assertEquals(
            listOf(TmuxAlerts.waitCommand(), TmuxAlerts.readCommand(), TmuxAlerts.clearCommand(), TmuxAlerts.waitCommand()),
            be.executed,
        )
    }

    @Test
    fun `exit-zero wake with no payload (server died, lab DEATH-0) stays silent and re-arms`() {
        val be = ScriptedBackend(ArrayDeque((scriptWaitReadClear(null) + Step(TmuxAlerts.waitCommand())).toList()))
        val alerts = runUntil(be, 4)
        assertTrue("server-death wake must not notify: $alerts", alerts.isEmpty())
    }

    @Test
    fun `garbage payload is cleared but never notified`() {
        val be = ScriptedBackend(ArrayDeque((scriptWaitReadClear("reboot|s|w") + Step(TmuxAlerts.waitCommand())).toList()))
        val alerts = runUntil(be, 4)
        assertTrue("unknown alert type must not notify: $alerts", alerts.isEmpty())
        assertTrue("payload must still be acked: ${be.executed}", be.executed.contains(TmuxAlerts.clearCommand()))
    }

    @Test
    fun `quoted payload with spaces reaches the callback parsed`() {
        val be = ScriptedBackend(ArrayDeque((scriptWaitReadClear("\"activity|beta|my win\"") + Step(TmuxAlerts.waitCommand())).toList()))
        val alerts = runUntil(be, 4)
        assertEquals(listOf(TmuxAlerts.Alert("activity", "beta", "my win")), alerts)
    }

    @Test
    fun `a stolen wake backs off instead of instantly re-arming and killing the live watcher`() {
        // 11-07 device: สอง watcher รอดพร้อมกัน (startup race) → wake เปล่า ( sibling แย่ง payload /
        // reaper เก็บ) แล้ว re-arm ทันที = ฆ่าอีกตัวสลับกัน hot-loop — wake เปล่าต้องถอยก่อน
        val be = ScriptedBackend(ArrayDeque((scriptWaitReadClear(null) + Step(TmuxAlerts.waitCommand())).toList()))
        val alerts = mutableListOf<TmuxAlerts.Alert>()
        runBlocking {
            val job = launch { TmuxAlertWatcher(be, { alerts += it }, retryMs = 1_500).run() }
            withTimeout(2_000) { while (be.executed.size < 3) delay(10) }
            delay(300)
            assertEquals("empty-payload wake must back off before re-arming: ${be.executed}", 3, be.executed.size)
            withTimeout(3_000) { while (be.executed.size < 4) delay(20) } // แล้วต้อง re-arm จริง ไม่ใช่หยุด
            job.cancelAndJoin()
        }
        assertTrue("stolen wake must not notify: $alerts", alerts.isEmpty())
    }

    @Test
    fun `backend without exec stops promptly instead of hot-looping`() = runBlocking {
        val be = ScriptedBackend(ArrayDeque(listOf(Step(TmuxAlerts.waitCommand(), error = BackendError(BackendError.Code.UNSUPPORTED, "no exec")))))
        val alerts = mutableListOf<TmuxAlerts.Alert>()
        val job = launch { TmuxAlertWatcher(be, { alerts += it }, retryMs = 10).run() }
        // ถ้า loop พังแบบ retry (ไม่ใช่ return) job จะไม่มีวันจบ — withTimeout = fail ชัด ไม่ใช่ hang
        withTimeout(2_000) { job.join() }
        delay(60) // give a broken retry loop (retryMs=10) room to misbehave before asserting
        assertEquals(emptyList<TmuxAlerts.Alert>(), alerts)
        assertEquals("must stop after UNSUPPORTED, not retry: ${be.executed}", listOf(TmuxAlerts.waitCommand()), be.executed)
    }

    @Test
    fun `transient wait failure retries after backoff and still delivers the next alert`() {
        val steps = listOf(
            Step(TmuxAlerts.waitCommand(), error = BackendError(BackendError.Code.CHANNEL_FAILED, "conn down")),
        ) + scriptWaitReadClear("bell|gamma|one") + listOf(Step(TmuxAlerts.waitCommand()))
        val be = ScriptedBackend(ArrayDeque(steps))
        val alerts = runUntil(be, 5)
        assertEquals(listOf(TmuxAlerts.Alert("bell", "gamma", "one")), alerts)
    }

    @Test
    fun `a dead tmux server backs off harder each retry instead of re-execing on the flat cadence forever`() {
        // ticket rule "ไม่มี polling": wait-for เจอ server ตาย = exit 1 ทันที (lab 11-06) — retry จังหวะคงที่
        // = exec round-trip ทุก 5s ต่อ pane ไปเรื่อย ๆ = polling กลม ๆ ในสภาพ degraded → ต้อง escalating (มี cap)
        // และ alert จริงตัวเดียว = รีเซ็ตกลับจังหวะฐาน (ไม่ลงโทษ session ที่ฟื้นแล้ว)
        val dead = Step(TmuxAlerts.waitCommand(), ok(exit = 1))
        val steps = listOf(dead, dead, dead, dead, dead) +
            scriptWaitReadClear("bell|delta|one") +
            listOf(dead, dead)
        val be = ScriptedBackend(ArrayDeque(steps))
        val alerts = mutableListOf<TmuxAlerts.Alert>()
        runBlocking {
            val job = launch { TmuxAlertWatcher(be, { alerts += it }, retryMs = 100).run() }
            withTimeout(10_000) { while (be.executed.size < 10) delay(10) } // 5 dead + (wait,read,clear) + 2 dead
            job.cancelAndJoin()
        }
        assertEquals(1, alerts.size)
        val waitTimes = be.execTimes.filterIndexed { i, _ -> be.executed[i] == TmuxAlerts.waitCommand() }
        val gaps = (1 until waitTimes.size).map { waitTimes[it] - waitTimes[it - 1] } // 6 gaps
        // schedule เดลต้า (retryMs=100): 100,200,400,800 | wake-ปลุกจริง 0 | หลัง alert: 100,200
        assertTrue("consecutive dead wakes must escalate, gaps=$gaps", gaps[3] >= 600)
        assertTrue("gap must grow monotonically pre-alert, gaps=$gaps", gaps[2] >= gaps[1])
        assertTrue("real alert resets backoff to base, gaps=$gaps", gaps[6] <= 400)
    }
}
