package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAlivePolicyTest {
    @Test fun `nothing active means no service and no wake lock`() {
        val d = KeepAlivePolicy.decide(connected = 0, connecting = 0, reconnecting = 0, keepCpuAwake = true)
        assertFalse(d.run)
        assertFalse(d.wakeLock)
    }

    @Test fun `each active state keeps the service running`() {
        assertTrue(KeepAlivePolicy.decide(1, 0, 0, false).run)
        assertTrue(KeepAlivePolicy.decide(0, 1, 0, false).run)
        assertTrue(KeepAlivePolicy.decide(0, 0, 1, false).run)
    }

    @Test fun `wake lock only when the user turned it on and something is active`() {
        assertFalse(KeepAlivePolicy.decide(1, 0, 0, false).wakeLock)
        assertTrue(KeepAlivePolicy.decide(1, 0, 0, true).wakeLock)
    }

    @Test fun `notification text counts sessions and mentions reconnecting`() {
        assertEquals("1 session connected", KeepAlivePolicy.decide(1, 0, 0, false).text)
        assertEquals("2 sessions connected", KeepAlivePolicy.decide(2, 0, 0, false).text)
        assertEquals("Connecting…", KeepAlivePolicy.decide(0, 1, 0, false).text)
        assertEquals("Reconnecting…", KeepAlivePolicy.decide(0, 0, 2, false).text)
        assertEquals("1 session connected, reconnecting others…", KeepAlivePolicy.decide(1, 0, 1, false).text)
    }
}
