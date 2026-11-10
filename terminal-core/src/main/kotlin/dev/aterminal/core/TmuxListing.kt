package dev.aterminal.core

/**
 * ticket tmux/06: session/window/pane listing ผ่าน one-shot exec (`tmux list-* -F`) — pure-Kotlin, JVM-gated
 *
 * format contract (verified บน lab 3.6, 11-06 — socket แยก `tmux -L t06probe`, ไม่ได้แตะ session ใด):
 *  - field คั่นด้วย `|`, id/ตัวเลข/flag มาก่อน, free-text ท้ายสุด — ชื่อมี `|` ได้ (lab: `beta|win` ผ่านทั้งเส้น)
 *  - id เป็น zero-based (`$0`/`@0`/`%0`) — ห้ามเดาว่า start at 1
 *  - `list-sessions` ไม่มี flag `-a` (3.6 reject) และ default รวม unattached อยู่แล้ว (`session_attached=0` โผล่จริง)
 *  - id target ต้อง single-quote ตอนต่อ command (`'$0'`) — double quote = sh กิน `$0` เป็น positional param
 *  - fail-soft ทุกชั้น: line แปลก = ทิ้ง line เดียว, output เพี้ยนทั้งก้อน = list ว่าง (feature เงียบ ไม่ใช่ crash)
 */
data class TmuxSessionInfo(
    val id: String,        // `$N` — identity จริง (วิจัย §3 T8: ห้ามพึ่ง name)
    val windows: Int,
    val attached: Boolean,
    val name: String,      // free text — display only
)

data class TmuxWindowInfo(
    val id: String,        // `@N`
    val index: Int,
    val panes: Int,
    val active: Boolean,
    val name: String,      // free text — display only
)

data class TmuxPaneInfo(
    val id: String,        // `%N`
    val index: Int,
    val active: Boolean,
    val command: String,   // free text — display only
    val path: String,      // free text (rarest char `|` never appears here; tab-split tail)
)

object TmuxListing {

    private val SESSION_ID = Regex("^\\$\\d+$")
    private val WINDOW_ID = Regex("^@\\d+$")
    private val PANE_ID = Regex("^%\\d+$")

    /** -F order ตรง parser: session_id | session_windows | session_attached | session_name (name ท้าย = free text) */
    fun listSessionsCommand(): String =
        "tmux list-sessions -F '#{session_id}|#{session_windows}|#{session_attached}|#{session_name}'"

    /** server-controlled output — malformed line dropped, never fatal */
    fun parseSessions(output: String?): List<TmuxSessionInfo> =
        output?.lineSequence()?.map { it.trim('\r') }
            ?.mapNotNull { line ->
                val f = line.split('|', limit = 4)
                if (f.size < 4) return@mapNotNull null
                val id = f[0]
                if (!SESSION_ID.matches(id)) return@mapNotNull null
                val windows = f[1].toIntOrNull() ?: return@mapNotNull null
                val attached = when (f[2]) { "1" -> true; "0" -> false; else -> return@mapNotNull null }
                TmuxSessionInfo(id, windows, attached, f[3])
            }?.toList() ?: emptyList()

    /** -F order ตรง parser: window_id | window_index | window_panes | window_active | window_name */
    fun listWindowsCommand(sessionId: String): String {
        require(SESSION_ID.matches(sessionId)) { "session id must be \$N (identity from listing, never name)" }
        return "tmux list-windows -t '$sessionId' -F '#{window_id}|#{window_index}|#{window_panes}|#{window_active}|#{window_name}'"
    }

    fun parseWindows(output: String?): List<TmuxWindowInfo> =
        output?.lineSequence()?.map { it.trim('\r') }
            ?.mapNotNull { line ->
                val f = line.split('|', limit = 5)
                if (f.size < 5) return@mapNotNull null
                val id = f[0]
                if (!WINDOW_ID.matches(id)) return@mapNotNull null
                val index = f[1].toIntOrNull() ?: return@mapNotNull null
                val panes = f[2].toIntOrNull() ?: return@mapNotNull null
                val active = when (f[3]) { "1" -> true; "0" -> false; else -> return@mapNotNull null }
                TmuxWindowInfo(id, index, panes, active, f[4])
            }?.toList() ?: emptyList()

    /**
     * -F order ตรง parser: pane_id | pane_index | pane_active | command[<TAB>path]
     * tail = ฟิลด์เดียว split ด้วย TAB — แต่ **literal TAB ใน arg ถูก tmux เก่าทำลาย**:
     * lab 2026-11-06 (od -c): 3.6 host ส่ง TAB ผ่านตรง ๆ, 3.4 container ได้ `_` แทน →
     * path ติดกับ command ("sleep_/") — caller ต้อง gate ผ่าน TmuxCapabilities.panePathInListing
     * withPath=false = ท้ายฟิลด์เดียว command เปล่า ๆ (parse ได้ทุก version, path ว่าง → UI fallback เป็น command)
     */
    fun listPanesCommand(windowId: String, withPath: Boolean = true): String {
        require(WINDOW_ID.matches(windowId)) { "window id must be @N (identity from listing, never name)" }
        val tail = if (withPath) "#{pane_current_command}	#{pane_current_path}" else "#{pane_current_command}"
        return "tmux list-panes -t '$windowId' -F '#{pane_id}|#{pane_index}|#{pane_active}|$tail'"
    }

    fun parsePanes(output: String?): List<TmuxPaneInfo> =
        output?.lineSequence()?.map { it.trim('\r') }
            ?.mapNotNull { line ->
                val f = line.split('|', limit = 4)
                if (f.size < 4) return@mapNotNull null
                val id = f[0]
                if (!PANE_ID.matches(id)) return@mapNotNull null
                val index = f[1].toIntOrNull() ?: return@mapNotNull null
                val active = when (f[2]) { "1" -> true; "0" -> false; else -> return@mapNotNull null }
                val tail = f[3].split('\t', limit = 2)
                TmuxPaneInfo(id, index, active, tail[0], tail.getOrNull(1).orEmpty())
            }?.toList() ?: emptyList()

    // ---------- actions (ticket UI: attach / select / kill — ทุกคำสั่ง identity = id, ห้ามพึ่ง name) ----------
    // require() = guard สองชั้น: identity rule (วิจัย §3 T8) + shell-injection guard (id embed ลง single-quote)

    /** new tab ใต้ profile เดิม (01: StartupArgs.commandline) — attach by id, session หาย = fail ชัด ห้ามเงียบ create */
    fun attachCommandline(sessionId: String): String {
        require(SESSION_ID.matches(sessionId)) { "session id must be \$N" }
        return "tmux attach -t '$sessionId'"
    }

    fun selectWindowCommand(windowId: String): String {
        require(WINDOW_ID.matches(windowId)) { "window id must be @N" }
        return "tmux select-window -t '$windowId'"
    }

    fun selectPaneCommand(paneId: String): String {
        require(PANE_ID.matches(paneId)) { "pane id must be %N" }
        return "tmux select-pane -t '$paneId'"
    }

    fun killSessionCommand(sessionId: String): String {
        require(SESSION_ID.matches(sessionId)) { "session id must be \$N" }
        return "tmux kill-session -t '$sessionId'"
    }

    fun killWindowCommand(windowId: String): String {
        require(WINDOW_ID.matches(windowId)) { "window id must be @N" }
        return "tmux kill-window -t '$windowId'"
    }

    fun killPaneCommand(paneId: String): String {
        require(PANE_ID.matches(paneId)) { "pane id must be %N" }
        return "tmux kill-pane -t '$paneId'"
    }
}
