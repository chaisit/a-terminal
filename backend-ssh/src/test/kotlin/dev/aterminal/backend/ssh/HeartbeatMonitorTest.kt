package dev.aterminal.backend.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatMonitorTest {
    private val interval = 15_000L
    private fun monitor() = HeartbeatMonitor(interval, missLimit = 4, startMs = 0)

    @Test fun `silence on evenly spaced ticks is lost only after the limit`() {
        val m = monitor()
        val actions = (1..5).map { m.onTick(it * interval) }
        assertEquals(List(4) { HeartbeatMonitor.Action.SEND } + HeartbeatMonitor.Action.LOST, actions)
    }

    @Test fun `a reply clears the misses`() {
        val m = monitor()
        for (i in 1..4) m.onTick(i * interval)
        m.onReply()
        assertEquals(0, m.misses)
        assertEquals(HeartbeatMonitor.Action.SEND, m.onTick(5 * interval))
    }

    @Test fun `a frozen process does not count as missed replies`() {
        val m = monitor()
        for (i in 1..4) m.onTick(i * interval)
        val thawed = 4 * interval + 37_421
        assertEquals(HeartbeatMonitor.Action.SEND_AFTER_STALL, m.onTick(thawed))
        assertEquals(1, m.misses)
    }

    @Test fun `repeated freezes never lose a working session`() {
        val m = monitor()
        var now = 0L
        repeat(50) {
            now += 60_000
            assertEquals(HeartbeatMonitor.Action.SEND_AFTER_STALL, m.onTick(now))
        }
    }

    @Test fun `silence after a freeze is still detected`() {
        val m = monitor()
        val thawed = 600_000L
        m.onTick(thawed)
        val after = (1..4).map { m.onTick(thawed + it * interval) }
        assertEquals(HeartbeatMonitor.Action.LOST, after.last())
        assertTrue(after.dropLast(1).all { it == HeartbeatMonitor.Action.SEND })
    }

    @Test fun `clock moving backwards is harmless`() {
        val m = monitor()
        assertEquals(HeartbeatMonitor.Action.SEND, m.onTick(-5_000))
    }
}
