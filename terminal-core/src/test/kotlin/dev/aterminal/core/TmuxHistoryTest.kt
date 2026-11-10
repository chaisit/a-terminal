package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/08: history pull — command builders + page/window planning (pure, JVM-gated)
 *
 * wire contract ที่ test นี้ lock ไว้ = วัดจริงบน lab 11-07 (หลักฐานเต็มในตั๋ว):
 * `-S`/`-E` anchored ที่ขอบบน viewport (ไม่ใช่ history), `-E -1` = บรรทัดสุดท้ายของ history,
 * `-J` คืน logical lines (fragment ที่ขาดจากกันเพราะ wrap ถูก join)
 */
class TmuxHistoryTest {

    @Test
    fun `session identity extracted only from shapes we generate`() {
        assertEquals("main", TmuxHistory.sessionFromCommandline("tmux new -A -s main"))
        assertEquals("\$0", TmuxHistory.sessionFromCommandline("tmux attach -t '\$0'"))
        assertNull(TmuxHistory.sessionFromCommandline("vim /var/log/syslog")) // custom 01 = ไม่มี tmux = no pull
        assertNull(TmuxHistory.sessionFromCommandline("tmux new -A -s 'two words'")) // unsafe = fail-soft, ไม่ใช่ target ดิบ
        assertNull(TmuxHistory.sessionFromCommandline("tmux attach")) // ไม่มี target ชัด
    }

    @Test
    fun `history size command quotes id targets and omits target when unknown`() {
        assertEquals("tmux display -p -t 'main' '#{history_size}'", TmuxHistory.historySizeCommand("main"))
        assertEquals("tmux display -p -t '\$0' '#{history_size}'", TmuxHistory.historySizeCommand("\$0"))
        assertEquals("tmux display -p '#{history_size}'", TmuxHistory.historySizeCommand(null))
    }

    @Test
    fun `parseHistorySize accepts a single integer line only`() {
        assertEquals(21, TmuxHistory.parseHistorySize("21\n"))
        assertEquals(5, TmuxHistory.parseHistorySize("5"))
        assertNull(TmuxHistory.parseHistorySize(""))
        assertNull(TmuxHistory.parseHistorySize(null))
        assertNull(TmuxHistory.parseHistorySize("no server running\n"))
        assertNull(TmuxHistory.parseHistorySize("-1"))
    }

    @Test
    fun `capture command is pinned to end of history with absolute window above viewport`() {
        // -S -(windowAbove) = display lines เหนือขอบบน viewport; -E -1 = inclusive ถึงบรรทัดสุดท้ายของ history
        assertEquals(
            "tmux capture-pane -p -J -t 'main' -S -2400 -E -1",
            TmuxHistory.captureCommand("main", 2400),
        )
        assertEquals("tmux capture-pane -p -J -S -10 -E -1", TmuxHistory.captureCommand(null, 10))
    }

    @Test
    fun `overflow halves the page and floors at MIN`() {
        assertEquals(2000, TmuxHistory.nextPageSize(2000, overflow = false))
        assertEquals(1000, TmuxHistory.nextPageSize(2000, overflow = true))
        assertEquals(500, TmuxHistory.nextPageSize(1000, overflow = true))
        assertEquals(250, TmuxHistory.nextPageSize(400, overflow = true))
        assertEquals(250, TmuxHistory.nextPageSize(250, overflow = true)) // floor — ห้ามลดต่อจนหมดความหมาย
    }

    @Test
    fun `deeper history exists only when app logical depth is under history size`() {
        assertTrue(TmuxHistory.hasDeeperHistory(appLogicalDepth = 0, historySize = 21))
        assertFalse(TmuxHistory.hasDeeperHistory(appLogicalDepth = 21, historySize = 21))
        assertFalse(TmuxHistory.hasDeeperHistory(appLogicalDepth = 30, historySize = 21))
    }

    @Test
    fun `window covers the overlap's display span plus one page clamped to history size`() {
        // window = display units ที่ *pane* ใช้จริง (tmux anchor); overlap drop (planInjection) = logical units — สองคนละมิติโดยเจตนา
        // overlapDisplay มาจากการคูณ consumption ratio ที่ caller วัด (HistoryPuller) — ฟังก์ชันนี้รวม+clamp อย่างเดียว
        assertEquals(2000, TmuxHistory.windowAbove(overlapDisplay = 0, page = 2000, historySize = 5000))
        assertEquals(2500, TmuxHistory.windowAbove(overlapDisplay = 500, page = 2000, historySize = 5000))
        assertEquals(600, TmuxHistory.windowAbove(overlapDisplay = 500, page = 2000, historySize = 600))
    }

    @Test
    fun `captureLines keeps interior blank lines and drops only the final newline artifact`() {
        assertEquals(listOf("a", "b"), TmuxHistory.captureLines("a\nb\n"))
        assertEquals(listOf("a", "", "b"), TmuxHistory.captureLines("a\n\nb\n")) // blank กลาง history = ข้อมูลจริง
        assertEquals(emptyList<String>(), TmuxHistory.captureLines(""))
        assertEquals(listOf("x"), TmuxHistory.captureLines("x")) // ไม่มี trailing \n (กัน channel cụt) = คงบรรทัดไว้
    }

    @Test
    fun `injection plan drops the overlap the app already holds`() {
        val fetched = (1..12).map { "line$it" }
        assertEquals((1..7).map { "line$it" }, TmuxHistory.planInjection(fetched, appLogicalDepth = 5))
        assertEquals((1..12).map { "line$it" }, TmuxHistory.planInjection(fetched, appLogicalDepth = 0))
        assertEquals(emptyList<String>(), TmuxHistory.planInjection(fetched, appLogicalDepth = 12))
        assertEquals(emptyList<String>(), TmuxHistory.planInjection(fetched, appLogicalDepth = 99))
        assertEquals(emptyList<String>(), TmuxHistory.planInjection(emptyList(), appLogicalDepth = 0))
    }
}
