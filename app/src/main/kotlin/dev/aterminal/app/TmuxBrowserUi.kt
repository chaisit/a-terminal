package dev.aterminal.app

import android.app.AlertDialog
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dev.aterminal.core.TmuxListing
import dev.aterminal.core.TmuxPaneInfo
import dev.aterminal.core.TmuxSessionInfo
import dev.aterminal.core.TmuxWindowInfo
import kotlinx.coroutines.launch

/**
 * ticket tmux/06: touch choose-tree — session → window → pane ผ่าน exec channel (แทน choose-tree บนคีย์บอร์ด)
 *
 * - ทุก action ยิง tmux ผ่าน exec (id target เท่านั้น — TmuxListing guard) **ไม่แตะ PTY หลัก**:
 *   select/kill = server-side state, client ที่ attach อยู่ (commandline จาก 01) เห็นผลเองบน grid
 * - attach = "เปิด tab ใหม่ด้วย commandline" ตามตั๋ว 01: profile.copy(commandline = attach-by-id)
 * - kill = destructive → confirm ทุกครั้ง (confirmDelete style)
 * - house style: setItems + showGuarded (DialogGuards); fetch บน lifecycleScope (exec ผูก deadline เอง)
 * - ทุก action สำเร็จ = refresh list ระดับเดิม — state ค้างจาก id เก่า (race) จะโผล่เป็น "can't find" จาก tmux เอง
 */
class TmuxBrowserUi(private val act: MainActivity) {

    /** entry point — caller (pane menu) ตรวจ capability ก่อนเรียก (02: null = ไม่มี tmux = ไม่มี menu item) */
    fun open(s: TerminalSession) {
        act.closeImeForMenus()
        val backend = s.controller.activeBackend ?: return // ปิด/dial ค้าง = ไม่มีอะไรให้ browse
        showSessions(TmuxBrowser(backend), s)
    }

    // ---------- level 1: sessions ----------

    private fun showSessions(b: TmuxBrowser, s: TerminalSession) {
        act.lifecycleScope.launch {
            b.sessions()
                .onFailure { fail(it) }
                .onSuccess { list ->
                    if (list.isEmpty()) { toast("tmux: no sessions on this host"); return@onSuccess }
                    val labels = list.map { "${it.name}  ·  ${it.windows} win · ${if (it.attached) "attached" else "detached"}" }
                    AlertDialog.Builder(act)
                        .setTitle("tmux sessions")
                        .setItems(labels.toTypedArray()) { d, which ->
                            d.dismiss()
                            sessionActions(b, s, list[which])
                        }
                        .setNegativeButton("Close", null)
                        .showGuarded(act)
                }
        }
    }

    private fun sessionActions(b: TmuxBrowser, s: TerminalSession, sess: TmuxSessionInfo) {
        val profile = s.profile
        val labels = buildList {
            if (profile != null) add("▶  Attach in new tab")
            add("▤  Windows…")
            add("✖  Kill session")
        }
        AlertDialog.Builder(act)
            .setTitle(sess.name)
            .setItems(labels.toTypedArray()) { d, which ->
                d.dismiss()
                val label = labels[which]
                when {
                    label.startsWith("▶") && profile != null ->
                        // 01: commandline ต่อ profile — attach-by-id; session หายก่อน = tab fail ชัด (fail loud)
                        act.connectProfile(profile.copy(commandline = TmuxListing.attachCommandline(sess.id)))
                    label.startsWith("▤") -> showWindows(b, s, sess)
                    else -> confirmAndRun(
                        s,
                        title = "Kill session '${sess.name}'?",
                        message = "The tmux server kills the session and everything running inside it.",
                        action = { killSession(sess.id) },
                        refresh = { bb, ss -> showSessions(bb, ss) },
                    )
                }
            }
            .setNegativeButton("Back", null)
            .showGuarded(act)
    }

    // ---------- level 2: windows ----------

    private fun showWindows(b: TmuxBrowser, s: TerminalSession, sess: TmuxSessionInfo) {
        act.lifecycleScope.launch {
            b.windows(sess.id)
                .onFailure { fail(it) }
                .onSuccess { list ->
                    if (list.isEmpty()) { toast("tmux: no windows in '${sess.name}'"); return@onSuccess }
                    val labels = list.map { w ->
                        "${w.index}: ${w.name}  ·  ${w.panes} pane${if (w.panes == 1) "" else "s"}${if (w.active) "  ●" else ""}"
                    }
                    AlertDialog.Builder(act)
                        .setTitle("tmux · ${sess.name}")
                        .setItems(labels.toTypedArray()) { d, which ->
                            d.dismiss()
                            windowActions(b, s, sess, list[which])
                        }
                        .setNegativeButton("Back", null)
                        .showGuarded(act)
                }
        }
    }

    private fun windowActions(b: TmuxBrowser, s: TerminalSession, sess: TmuxSessionInfo, w: TmuxWindowInfo) {
        val labels = listOf("✓  Select (show in this session)", "▦  Panes…", "✖  Kill window")
        AlertDialog.Builder(act)
            .setTitle("${w.index}: ${w.name}")
            .setItems(labels.toTypedArray()) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> act.lifecycleScope.launch {
                        b.selectWindow(w.id)
                            .onFailure { fail(it) }
                            .onSuccess { toast("tmux: window ${w.index} selected"); showWindows(b, s, sess) }
                    }
                    1 -> showPanes(b, s, sess, w)
                    else -> confirmAndRun(
                        s,
                        title = "Kill window '${w.name}'?",
                        message = "Everything running in this window is killed.",
                        action = { killWindow(w.id) },
                        refresh = { bb, ss -> showWindows(bb, ss, sess) },
                    )
                }
            }
            .setNegativeButton("Back", null)
            .showGuarded(act)
    }

    // ---------- level 3: panes ----------

    private fun showPanes(b: TmuxBrowser, s: TerminalSession, sess: TmuxSessionInfo, w: TmuxWindowInfo) {
        act.lifecycleScope.launch {
            b.panes(w.id)
                .onFailure { fail(it) }
                .onSuccess { list ->
                    if (list.isEmpty()) { toast("tmux: no panes in '${w.name}'"); return@onSuccess }
                    val labels = list.map { p ->
                        val where = if (p.path.isNotBlank()) p.path else p.command
                        "${p.index}: ${p.command}  $where${if (p.active) "  ●" else ""}"
                    }
                    AlertDialog.Builder(act)
                        .setTitle("tmux · ${sess.name}:${w.index}")
                        .setItems(labels.toTypedArray()) { d, which ->
                            d.dismiss()
                            paneActions(b, s, sess, w, list[which])
                        }
                        .setNegativeButton("Back", null)
                        .showGuarded(act)
                }
        }
    }

    private fun paneActions(
        b: TmuxBrowser, s: TerminalSession,
        sess: TmuxSessionInfo, w: TmuxWindowInfo, p: TmuxPaneInfo,
    ) {
        val labels = listOf("✓  Select (focus in this window)", "✖  Kill pane")
        AlertDialog.Builder(act)
            .setTitle("${p.index}: ${p.command}")
            .setItems(labels.toTypedArray()) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> act.lifecycleScope.launch {
                        b.selectPane(p.id)
                            .onFailure { fail(it) }
                            .onSuccess { toast("tmux: pane ${p.index} selected"); showPanes(b, s, sess, w) }
                    }
                    else -> confirmAndRun(
                        s,
                        title = "Kill pane '${p.command}'?",
                        message = "The process running in this pane is killed.",
                        action = { killPane(p.id) },
                        refresh = { bb, ss -> showPanes(bb, ss, sess, w) },
                    )
                }
            }
            .setNegativeButton("Back", null)
            .showGuarded(act)
    }

    // ---------- shared ----------

    /** destructive = confirm เสมอ (confirmDelete style); refresh re-fetch list ระดับเดิมหลังสำเร็จ */
    private fun confirmAndRun(
        s: TerminalSession,
        title: String,
        message: String,
        action: suspend TmuxBrowser.() -> Result<Unit>,
        refresh: (TmuxBrowser, TerminalSession) -> Unit,
    ) {
        AlertDialog.Builder(act)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Kill") { d, _ ->
                d.dismiss()
                val backend = s.controller.activeBackend ?: return@setPositiveButton // session ตายระหว่าง confirm
                act.lifecycleScope.launch {
                    val b = TmuxBrowser(backend)
                    b.action()
                        .onFailure { fail(it) }
                        .onSuccess { toast("tmux: done"); refresh(b, s) }
                }
            }
            .setNegativeButton("Cancel", null)
            .showGuarded(act)
    }

    private fun fail(t: Throwable?) {
        // transport fail/exit≠0 = บอก user ตรง ๆ (fail-soft = ซ่อน feature ไม่ใช่เงียบ) — message สั้นพอเป็น toast
        val m = t?.message?.take(140) ?: t?.javaClass?.simpleName ?: "unknown"
        toast("tmux: $m")
    }

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
}
