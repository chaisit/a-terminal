package dev.aterminal.app

import dev.aterminal.core.ExecResult
import dev.aterminal.core.TerminalBackend
import dev.aterminal.core.TmuxListing
import dev.aterminal.core.TmuxPaneInfo
import dev.aterminal.core.TmuxSessionInfo
import dev.aterminal.core.TmuxWindowInfo

/**
 * ticket tmux/06: session/window/pane browser ฝั่ง app —บาง ๆ บน exec channel + TmuxListing
 *
 * - ทุก command ผ่าน TmuxListing (identity = id, injection guard อยู่ที่นั่น)
 * - exec fail (timeout/channel/UNSUPPORTED) = Result.failure ให้ UI up-level เป็น toast/ซ่อน feature —
 *   ห้าม map เป็น empty list (empty list = server ว่างจริง คนละเรื่องกับ "ถามไม่สำเร็จ")
 * - exit code = สัญญาเดียวกับ review tmux/06: != 0 = tmux ปฏิเสธ (fail loud พร้อม stderr)
 *   ข้อยกเว้นเดียว = "server ว่าง/ไม่มี server" (exit 1 + stderr sentinel) ซึ่ง list-sessions ตอบแบบนี้จริง
 *   exitCode == null (channel ปิดก่อนส่ง status / output เกิน cap) = ผลไม่ทราบ = failure เสมอ (review: ห้ามนับเป็น success)
 */
internal class TmuxBrowser(private val backend: TerminalBackend) {

    private suspend fun run(command: String): ExecResult = backend.exec(command)

    /** empty-server = คำตอบ "ไม่มีอะไร" ไม่ใช่ error — sentinel ตาม output จริงของ tmux 3.6 (lab 2026-11-06) */
    private val emptyServer = Regex("no sessions|no server (?:running|exists)")

    private fun requireOk(r: ExecResult, allowEmptyServer: Boolean) {
        val code = r.exitCode
            ?: error("no exit status from tmux (channel closed early or output capped)") // ผลไม่ทราบ ≠ สำเร็จ โดยเฉพาะ kill
        if (code == 0) return
        if (allowEmptyServer && r.stdout.isBlank() && emptyServer.containsMatchIn(r.stderr)) return
        error(r.stderr.ifBlank { "tmux exit $code" })
    }

    // list-windows/-panes ของ target ที่มีจริง ยาวไม่น้อยกว่า 1 บรรทัดเสมอ — ไม่มี empty-sentinel case (fail ทุก exit != 0)
    suspend fun sessions(): Result<List<TmuxSessionInfo>> =
        runCatching {
            val r = run(TmuxListing.listSessionsCommand())
            requireOk(r, allowEmptyServer = true)
            TmuxListing.parseSessions(r.stdout)
        }

    suspend fun windows(sessionId: String): Result<List<TmuxWindowInfo>> =
        runCatching {
            val r = run(TmuxListing.listWindowsCommand(sessionId))
            requireOk(r, allowEmptyServer = false)
            TmuxListing.parseWindows(r.stdout)
        }

    suspend fun panes(windowId: String): Result<List<TmuxPaneInfo>> =
        runCatching {
            // tail command<TAB>path ส่งได้เฉพาะ tmux ที่ TAB รอดข้าม wire (3.4 ทำ TAB เป็น `_` — lab od 11-06)
            val withPath = backend.tmuxCapabilities?.tabInFormatSafe == true
            val r = run(TmuxListing.listPanesCommand(windowId, withPath))
            requireOk(r, allowEmptyServer = false)
            TmuxListing.parsePanes(r.stdout)
        }

    // ---------- actions (UI ยืนยัน destructive ก่อนเรียก — kill ไม่มี confirm ตรงนี้) ----------

    private suspend fun action(command: String): Result<Unit> = runCatching {
        requireOk(run(command), allowEmptyServer = false)
        Unit
    }

    suspend fun selectWindow(windowId: String): Result<Unit> = action(TmuxListing.selectWindowCommand(windowId))

    suspend fun selectPane(paneId: String): Result<Unit> = action(TmuxListing.selectPaneCommand(paneId))

    suspend fun killSession(sessionId: String): Result<Unit> = action(TmuxListing.killSessionCommand(sessionId))

    suspend fun killWindow(windowId: String): Result<Unit> = action(TmuxListing.killWindowCommand(windowId))

    suspend fun killPane(paneId: String): Result<Unit> = action(TmuxListing.killPaneCommand(paneId))
}
