package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/06: parse output ของ `tmux list-* -F` (fail-soft — remote output คือ untrusted)
 *
 * contract: field = `|`-separated, id/ตัวเลข/flag มาก่อน, free-text (name/command/path) อยู่ท้าย
 * เพราะชื่อฝั่ง user ใส่ `|` ได้ — limit-split เกิน field จริงจึงไม่ขาด
 * expected values มาจาก format contract (man FORMATS) ไม่ใช่ code — independent literal
 */
class TmuxListingTest {

    // ---------- parseSessions ----------

    @Test
    fun `session lines parse with id first and name last`() {
        val out = "\$1|3|1|main\n\$2|1|0|scratch\n"
        assertEquals(
            listOf(
                TmuxSessionInfo("\$1", windows = 3, attached = true, name = "main"),
                TmuxSessionInfo("\$2", windows = 1, attached = false, name = "scratch"),
            ),
            TmuxListing.parseSessions(out),
        )
    }

    @Test
    fun `session name containing pipe survives (free text is the last field)`() {
        val one = TmuxListing.parseSessions("\$7|2|1|weird|name|here")
        assertEquals(1, one.size)
        assertEquals("weird|name|here", one[0].name)
        assertEquals("\$7", one[0].id)
    }

    @Test
    fun `malformed session lines are dropped not fatal (untrusted output, fail-soft)`() {
        val out = "not-a-session-line\n\$1|oops|1|x\n\$2|2|1|good\nshort\n"
        assertEquals(listOf(TmuxSessionInfo("\$2", 2, true, "good")), TmuxListing.parseSessions(out))
        assertTrue(TmuxListing.parseSessions("").isEmpty())
        assertTrue(TmuxListing.parseSessions(null).isEmpty())
    }

    // ---------- parseWindows (literals captured from lab tmux 3.6, 11-06) ----------

    @Test
    fun `window lines parse, pipe-containing name kept intact at tail`() {
        val out = "@0|0|1|0|zsh\n@1|1|1|1|beta|win\n"
        assertEquals(
            listOf(
                TmuxWindowInfo("@0", index = 0, panes = 1, active = false, name = "zsh"),
                TmuxWindowInfo("@1", index = 1, panes = 1, active = true, name = "beta|win"),
            ),
            TmuxListing.parseWindows(out),
        )
    }

    @Test
    fun `malformed window lines dropped (id shape and counts enforced)`() {
        val out = "\$0|0|1|0|wrongprefix\n@1|x|1|1|badcount\n@2|2|2|0|ok"
        assertEquals(listOf(TmuxWindowInfo("@2", 2, 2, false, "ok")), TmuxListing.parseWindows(out))
        assertTrue(TmuxListing.parseWindows(null).isEmpty())
    }

    // ---------- parsePanes (tab-in-tail ผ่านเฉพาะ tmux ≥3.6 — ดู test withPath ด้านล่าง) ----------

    @Test
    fun `pane lines parse with command and path split from tab tail`() {
        val out = "%0|0|1|zsh\t/home/user/demo\n"
        assertEquals(
            listOf(TmuxPaneInfo("%0", index = 0, active = true, command = "zsh", path = "/home/user/demo")),
            TmuxListing.parsePanes(out),
        )
    }

    @Test
    fun `pane tail without tab still yields command (path empty, fail-soft)`() {
        val one = TmuxListing.parsePanes("%7|2|0|vim\n")
        assertEquals(listOf(TmuxPaneInfo("%7", 2, false, "vim", "")), one)
    }

    // ---------- command builders (single-quoted id target — double quote = sh eats `$0`; lab 3.6 11-06) ----------

    @Test
    fun `list commands embed single-quoted id targets`() {
        assertEquals(
            "tmux list-windows -t '\$0' -F '#{window_id}|#{window_index}|#{window_panes}|#{window_active}|#{window_name}'",
            TmuxListing.listWindowsCommand("\$0"),
        )
        assertEquals(
            "tmux list-panes -t '@1' -F '#{pane_id}|#{pane_index}|#{pane_active}|#{pane_current_command}	#{pane_current_path}'",
            TmuxListing.listPanesCommand("@1", withPath = true),
        )
    }

    // lab 2026-11-06: tmux 3.4 (ubuntu noble container) เปลี่ยน literal TAB ใน -F arg เป็น `_`
    // (ตรวจด้วย od -c: "a<tab>b" → "a_b") — 3.6 host ผ่านตรง ๆ → pre-3.6 ต้องส่ง format ที่ไม่มี TAB
    @Test
    fun `listPanesCommand withPath false ships no TAB and no path field (pre-3_6 tmux corrupts tabs)`() {
        val cmd = TmuxListing.listPanesCommand("@1", withPath = false)
        assertFalse("TAB must not ship to pre-3.6 tmux: $cmd", cmd.contains('\t'))
        assertFalse("path field must be absent: $cmd", cmd.contains("pane_current_path"))
        assertEquals(
            "tmux list-panes -t '@1' -F '#{pane_id}|#{pane_index}|#{pane_active}|#{pane_current_command}'",
            cmd,
        )
    }

    @Test
    fun `action commands target by id only`() {
        assertEquals("tmux attach -t '\$3'", TmuxListing.attachCommandline("\$3"))
        assertEquals("tmux select-window -t '@2'", TmuxListing.selectWindowCommand("@2"))
        assertEquals("tmux select-pane -t '%4'", TmuxListing.selectPaneCommand("%4"))
        assertEquals("tmux kill-session -t '\$3'", TmuxListing.killSessionCommand("\$3"))
        assertEquals("tmux kill-window -t '@2'", TmuxListing.killWindowCommand("@2"))
        assertEquals("tmux kill-pane -t '%4'", TmuxListing.killPaneCommand("%4"))
    }

    @Test
    fun `builders reject anything that is not a bare id (identity rule + shell-injection guard)`() {
        // name-based targeting ต้องห้าม (วิจัย §3 T8) — และ single-quote injection ต้องไม่มีทางผ่าน
        for (bad in listOf("main", "\$1; rm -rf /", "'; kill-server; '", "@", "x1", "%-2")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                TmuxListing.killSessionCommand(bad)
            }
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxListing.attachCommandline("main") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxListing.selectWindowCommand("%1") } // wrong id class
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxListing.selectPaneCommand("@1") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxListing.listWindowsCommand("main") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxListing.listPanesCommand("\$0", withPath = true) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxListing.listPanesCommand("main", withPath = false) }
    }

    // ---------- exec contract (interface default) ----------

    @Test
    fun `backends without exec say UNSUPPORTED loudly (never a silent empty success)`() {
        val echo = object : TerminalBackend {
            override val type = "echo"
            override suspend fun connect(startup: StartupArgs) {}
            override fun write(data: ByteArray) {}
            override fun resize(cols: Int, rows: Int) {}
            override fun close() {}
            override var onData: (ByteArray) -> Unit = {}
            override var onClosed: (CloseReason) -> Unit = {}
        }
        val e = org.junit.Assert.assertThrows(BackendError::class.java) {
            kotlinx.coroutines.runBlocking { echo.exec("tmux list-sessions") }
        }
        assertEquals(BackendError.Code.UNSUPPORTED, e.code)
    }
}
