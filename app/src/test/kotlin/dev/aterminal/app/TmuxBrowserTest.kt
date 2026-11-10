package dev.aterminal.app

import dev.aterminal.core.CloseReason
import dev.aterminal.core.ExecResult
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend
import dev.aterminal.core.TmuxListing
import dev.aterminal.core.TmuxSessionInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/06: TmuxBrowser — wiring between exec channel และ parser (fake backend, canned tmux output)
 * pattern เดียวกับ SessionControllerTest.FakeBackend (JVM ล้วน ไม่มี Robolectric)
 */
class TmuxBrowserTest {

    private class FakeExecBackend(
        val responses: Map<String, ExecResult> = emptyMap(),
        val onError: (String) -> Throwable = { IllegalStateException("unexpected exec: $it") },
        // UI gate เปิด browser ได้เฉพาะเมื่อ probe ให้ capability แล้ว → fake จึงมีค่า default เป็น 3.6 (production-like)
        override val tmuxCapabilities: dev.aterminal.core.TmuxCapabilities? =
            dev.aterminal.core.TmuxCapabilities(dev.aterminal.core.TmuxVersion(3, 6)),
    ) : TerminalBackend {
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
            return responses[command] ?: throw onError(command)
        }
    }

    private fun ok(stdout: String) = ExecResult(0, stdout, "")

    @Test
    fun `sessions lists with the listing command and parses rows`() = runBlocking {
        val be = FakeExecBackend(
            mapOf(TmuxListing.listSessionsCommand() to ok("\$0|2|1|main\n\$1|1|0|spare\n")),
        )
        val sessions = TmuxBrowser(be).sessions().getOrThrow()
        assertEquals(
            listOf(TmuxSessionInfo("\$0", 2, true, "main"), TmuxSessionInfo("\$1", 1, false, "spare")),
            sessions,
        )
        assertEquals(listOf(TmuxListing.listSessionsCommand()), be.executed)
    }

    @Test
    fun `drilling down sends id-targeted commands, action commands reach exec`() = runBlocking {
        val be = FakeExecBackend(
            mapOf(
                TmuxListing.listWindowsCommand("\$0") to ok("@3|0|1|1|edit\n"),
                TmuxListing.listPanesCommand("@3") to ok("%9|0|1|vim\t/tmp\n"),
                TmuxListing.selectWindowCommand("@3") to ok(""),
                TmuxListing.killSessionCommand("\$0") to ok(""),
            ),
        )
        val b = TmuxBrowser(be)
        assertEquals(listOf(dev.aterminal.core.TmuxWindowInfo("@3", 0, 1, true, "edit")), b.windows("\$0").getOrThrow())
        assertEquals(listOf(dev.aterminal.core.TmuxPaneInfo("%9", 0, true, "vim", "/tmp")), b.panes("@3").getOrThrow())
        b.selectWindow("@3").getOrThrow(); b.killSession("\$0").getOrThrow()
        assertEquals(
            listOf(
                TmuxListing.listWindowsCommand("\$0"),
                TmuxListing.listPanesCommand("@3"),
                TmuxListing.selectWindowCommand("@3"),
                TmuxListing.killSessionCommand("\$0"),
            ),
            be.executed,
        )
    }

    @Test
    fun `action with nonzero exit is a failure carrying tmux stderr (stale-id race)`() = runBlocking {
        // user taps kill on a session someone else already killed → tmux: exit 1 + "can't find session"
        val be = FakeExecBackend(
            mapOf(TmuxListing.killSessionCommand("\$9") to ExecResult(1, "", "can't find session: \$9\n")),
        )
        val r = TmuxBrowser(be).killSession("\$9")
        assertTrue("must be failure: $r", r.isFailure)
        assertTrue(
            "message must carry tmux's reason: ${r.exceptionOrNull()}",
            r.exceptionOrNull()?.message?.contains("can't find session") == true,
        )
    }

    @Test
    fun `action whose exit status never arrived is a failure, not a silent success`() = runBlocking {
        // review tmux/06: server ปิด channel ก่อนส่ง exit-status (หรือ output เกิน cap) → exitCode = null
        // destructive action ที่ "ผลไม่ทราบ" ต้อง NOT report success — user ต้องเห็นว่า kill อาจไม่เกิด
        val be = FakeExecBackend(mapOf(TmuxListing.killPaneCommand("%9") to ExecResult(null, "", "")))
        val r = TmuxBrowser(be).killPane("%9")
        assertTrue("unknown exit must be failure: $r", r.isFailure)
    }

    @Test
    fun `exec failure surfaces to caller, never a silent empty list`() = runBlocking {
        val be = FakeExecBackend(onError = { IllegalStateException("boom") })
        val r = TmuxBrowser(be).sessions()
        assertTrue("must be failure: $r", r.isFailure)
    }

    @Test
    fun `pane listing drops the path tail when the connection's tmux corrupts TABs`() = runBlocking {
        // lab 11-06: tmux 3.4 ทำ literal TAB ใน -F เป็น `_` → path ติดกับ command ("sleep_/") — ต้องไม่ส่ง TAB ไป
        val cmdNoPath = TmuxListing.listPanesCommand("@3", withPath = false)
        val be = FakeExecBackend(
            mapOf(cmdNoPath to ok("%7|0|1|vim\n")),
            tmuxCapabilities = dev.aterminal.core.TmuxCapabilities(dev.aterminal.core.TmuxVersion(3, 4)),
        )
        val panes = TmuxBrowser(be).panes("@3").getOrThrow()
        assertEquals(listOf(dev.aterminal.core.TmuxPaneInfo("%7", 0, true, "vim", "")), panes)
        assertEquals(listOf(cmdNoPath), be.executed)
    }

    @Test
    fun `empty server (exit 1 no sessions) reads as empty list, not an error`() = runBlocking {
        val be = FakeExecBackend(
            mapOf(TmuxListing.listSessionsCommand() to ExecResult(1, "", "no sessions\n")),
        )
        val r = TmuxBrowser(be).sessions()
        assertEquals(emptyList<TmuxSessionInfo>(), r.getOrThrow())
    }

    @Test
    fun `listing with a real error exit fails loudly, never masquerades as no sessions`() = runBlocking {
        // review tmux/06: tmux reject format/unknown flag = exit 3 + empty stdout —
        // display "no sessions" = โกหก user, เช้าถึง cause ไม่ได้ (ต่างจาก exit 1 "no sessions" ที่ว่างจริง)
        val be = FakeExecBackend(
            mapOf(
                TmuxListing.listSessionsCommand() to ExecResult(3, "", "unknown flag: -Z\n"),
                TmuxListing.listWindowsCommand("\$0") to ExecResult(127, "", "tmux: not found\n"),
            ),
        )
        val rs = TmuxBrowser(be).sessions()
        assertTrue("exit 3 must be failure: $rs", rs.isFailure)
        assertTrue(rs.exceptionOrNull()?.message?.contains("unknown flag") == true)
        val rw = TmuxBrowser(be).windows("\$0")
        assertTrue(rw.exceptionOrNull()?.message?.contains("not found") == true)
    }
}
