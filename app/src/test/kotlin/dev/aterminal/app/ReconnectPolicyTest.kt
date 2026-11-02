package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3 (round 12): นโยบาย auto-reconnect — pure JVM ตามกติกาที่ user อนุมัติ (auto backoff + banner)
 * จุดสำคัญที่สุดคือ branch "หยุดถาวร": MITM/auth-fail ห้าม dial ซ้ำเงียบ ๆ
 */
class ReconnectPolicyTest {

    @Test fun `backoff sequence 1 2 4 8 16 then cap 30s`() {
        val p = ReconnectPolicy()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            (1..8).map { p.nextDelayMs(retryable = true) })
        assertEquals(8, p.attempts) // นับไว้แสดง (n) บน banner
    }

    @Test fun `non-retryable returns null and does not count the attempt`() {
        val p = ReconnectPolicy()
        p.nextDelayMs(true); p.nextDelayMs(true)
        // AUTH_FAILED / HOST_KEY_MISMATCH = fatal ทันที ต้องไม่เหลือคิว dial
        assertNull(p.nextDelayMs(retryable = false))
        assertEquals(2, p.attempts)
    }

    @Test fun `user stop wins even when retryable`() {
        val p = ReconnectPolicy()
        p.userStop()
        assertNull(p.nextDelayMs(retryable = true))
        assertTrue(p.stoppedByUser)
    }

    @Test fun `noteConnected releases user stop and resets the backoff`() {
        val p = ReconnectPolicy()
        repeat(4) { p.nextDelayMs(true) } // = 1+2+4+8
        p.userStop()
        assertNull(p.nextDelayMs(true))
        p.noteConnected()
        assertEquals(0, p.attempts)
        assertEquals(false, p.stoppedByUser)
        // หลุดรอบใหม่ = เริ่ม count ใหม่จาก 1s (ไม่ใช่ต่อจาก 30s)
        assertEquals(1_000L, p.nextDelayMs(retryable = true))
    }

    @Test fun `policy never invents a delay once stopped - long press means stop`() {
        val p = ReconnectPolicy()
        p.userStop()
        repeat(5) { assertNull(p.nextDelayMs(retryable = true)) }
        assertEquals(0, p.attempts)
    }
}
