package dev.aterminal.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test fun `report carries device, exit reason and body`() {
        val r = CrashLog.format("0.2.1", "HONOR", "V6", 34, "uncaught on main\nboom", "reason=4")
        assertTrue(r.startsWith("A-Terminal 0.2.1 on HONOR V6 (API 34)\n"))
        assertTrue("exit: reason=4" in r)
        assertTrue("boom" in r)
    }

    @Test fun `missing parts are left out`() {
        val r = CrashLog.format("0.2.1", "m", "d", 30, null, "reason=5")
        assertFalse("null" in r)
    }

    @Test fun `stack keeps the cause chain`() {
        val s = CrashLog.stack(IllegalStateException("outer", RuntimeException("inner")))
        assertTrue("outer" in s && "Caused by" in s && "inner" in s)
    }
}
