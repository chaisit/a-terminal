package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Back-as-Escape (ADR 0002, round 23): ESC เข้า shell เฉพาะ enabled + pane เปิด + IME ปิด
 * pure JVM — พฤติกรรมจริงบนเครื่อง (keyevent 4) เป็น emulator gate ของรอบ
 */
class BackPolicyTest {

    @Test fun `escape only when enabled with pane open and ime down`() {
        assertEquals(EscOrSystem.Esc, BackPolicy.decide(backAsEscape = true, imeUp = false, hasActivePane = true))
    }

    @Test fun `disabled setting falls to system`() {
        assertEquals(EscOrSystem.System, BackPolicy.decide(backAsEscape = false, imeUp = false, hasActivePane = true))
    }

    @Test fun `ime up goes to system so platform closes the IME first`() {
        assertEquals(EscOrSystem.System, BackPolicy.decide(backAsEscape = true, imeUp = true, hasActivePane = true))
    }

    @Test fun `no active pane goes to system`() {
        assertEquals(EscOrSystem.System, BackPolicy.decide(backAsEscape = true, imeUp = false, hasActivePane = false))
    }

    @Test fun `ime up with no pane still system`() {
        assertEquals(EscOrSystem.System, BackPolicy.decide(backAsEscape = true, imeUp = true, hasActivePane = false))
    }
}
