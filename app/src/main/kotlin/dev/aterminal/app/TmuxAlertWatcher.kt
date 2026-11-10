package dev.aterminal.app

import dev.aterminal.core.BackendError
import dev.aterminal.core.TerminalBackend
import dev.aterminal.core.TmuxAlerts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * ticket tmux/07: ลูป wait-for ฝั่ง app — push ล้วน ไม่มี polling (battery rule ของตั๋ว)
 *
 * หนึ่ง instance ต่อ connection (tmux-capable เท่านั้น — gate อยู่ที่ caller ที่ start)
 *  - wait: `exec(waitCommand, timeoutMs = 0)` unbounded (contract 06/07) — block ฝั่ง server, ไม่มี traffic ระหว่างรอ
 *  - wake แล้วห้าม notify จาก exit code เดี่ยว: lab วัดว่า tmux server ตาย = wait-for exit 0 เหมือน signal จริง
 *    → อ่าน payload จาก user option ก่อน, parse ไม่ผ่าน/ว่าง = เงียบ (clear ก็ยังทำ — ack กัน payload ค้าง)
 *  - latch ของ `wait-for` (signal ก่อน waiter = ไม่หล่น, วัด 3.4/3.6) ทำให้ re-arm ระหว่างรอบ ไร้ race
 *  - fail: cancel → rethrow; UNSUPPORTED → จบ (backend ไม่มี exec = ไม่มีอะไร watch, ห้าม hot-loop); อื่น ๆ → backoff แล้วไปต่อ
 *    (connection ตาย/reconnect คือกรณีนี้อยู่แล้ว — caller เริ่มใหม่ได้หลัง probe สำเร็จรอบถัดไป)
 *  - backoff = escalating (retryMs ×2 ทุก wake ที่ไร้ alert, cap 16×) — ticket rule "ไม่มี polling":
 *    wait-for เจอ tmux server ตาย = exit 1 ทันที ถ้า retry จังหวะคงที่ = exec round-trip ทุก 5s ต่อ pane
 *    เรื่อยไป = polling กลม ๆ ในสภาพ degraded; alert จริงตัวเดียวรีเซ็ตกลับจังหวะฐาน (session ที่ฟื้นแล้วไม่ถูกลงโทษ)
 *  - ทุก swallow ต้องทิ้งร่องรอย (§Threading) — ผู้ inject onWarn เท่านั้นที่เห็น log (watcher ไม่มี Android dependency ใน test path)
 */
internal class TmuxAlertWatcher(
    private val backend: TerminalBackend,
    private val onAlert: (TmuxAlerts.Alert) -> Unit,
    private val retryMs: Long = 5_000,
    private val onWarn: (String) -> Unit = {},
) {

    private var missStreak = 0 // wake ติดกันที่ไร้ alert (escalation counter — run() เรียกจาก coroutine เดียว)

    private fun nextBackoffMs(): Long {
        val ms = retryMs shl minOf(missStreak, 4) // cap 16× (default 5s → 80s) — degraded state ถอยให้ไกลแต่ไม่มีวันหยุด
        missStreak++
        return ms
    }

    suspend fun run() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val wake = runCatching { backend.exec(TmuxAlerts.waitCommand(), timeoutMs = 0) }
            // null = "transport fail, retry" — continue จาก lambda ไม่ได้ จึง encode เป็นค่าแทน control flow
            val r: dev.aterminal.core.ExecResult? = wake.getOrElse { e ->
                when {
                    e is kotlinx.coroutines.CancellationException -> throw e
                    e is BackendError && e.code == BackendError.Code.UNSUPPORTED -> return
                    else -> { // transport fail (connection drop, tmux หายกลางทาง ฯลฯ) — retry ด้านล่าง
                        onWarn("wait exec failed: ${(e as? BackendError)?.code ?: e.javaClass.simpleName}")
                        null
                    }
                }
            }
            if (r == null) { delay(nextBackoffMs()); continue }
            if (r.exitCode != 0) { // wait-for exit !=0 = ผิดปกติ (server หายตอน start = 1) ไม่ใช่ alert
                onWarn("wait-for exit ${r.exitCode} (tmux server likely down)")
                delay(nextBackoffMs())
                continue
            }
            val read = runCatching { backend.exec(TmuxAlerts.readCommand()) }
            currentCoroutineContext().ensureActive()
            if (read.isFailure) onWarn("payload read failed: ${read.exceptionOrNull()?.javaClass?.simpleName}")
            val alert = if (read.isSuccess) TmuxAlerts.parseAlert(read.getOrThrow().stdout) else null
            runCatching { backend.exec(TmuxAlerts.clearCommand()) } // ack เสมอ — ล้มเหลว worst case = อ่านค่าเดิมซ้ำ (at-least-once)
                .onFailure { onWarn("payload ack failed: ${it.javaClass.simpleName}") }
            if (alert == null) {
                // wake ไร้ payload = server ตาย หรือ sibling watcher แย่งอ่านก่อน — ถอยก่อน re-arm:
                // re-arm ทันที = ฆ่า watcher ที่ถืออยู่ผ่าน reaper สลับกัน hot-loop (วัด device 11-07)
                delay(nextBackoffMs())
                continue
            }
            missStreak = 0
            onAlert(alert)
        }
    }
}
