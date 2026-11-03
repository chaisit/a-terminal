package dev.aterminal.app

import dev.aterminal.core.BackendError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * re-pin (round 16): decision "ถามหรือไม่" เมื่อ host key เปลี่ยน + สูตร string ของ pin + ข้อความเตือน
 * หลักการที่ user อนุมัติ: ผู้ใช้ต้องเห็น fingerprint เก่า+ใหม่ก่อนยืนยัน — ห้ามเปลี่ยน pin เงียบ ๆ
 * และ countdown (auto dial) ห้ามเด้ง dialog ทุกชนิด (กฎเดิมของ repo)
 */
class RepromptPolicyTest {

    @Test fun `prompt only on host key mismatch from a manual dial`() {
        assertTrue(RepromptPolicy.shouldPrompt(BackendError.Code.HOST_KEY_MISMATCH, manualDial = true))
    }

    @Test fun `auto dial never prompts - countdown must not pop dialogs`() {
        assertFalse(RepromptPolicy.shouldPrompt(BackendError.Code.HOST_KEY_MISMATCH, manualDial = false))
    }

    @Test fun `other error codes never prompt`() {
        for (c in listOf(
            BackendError.Code.AUTH_FAILED,
            BackendError.Code.UNREACHABLE,
            BackendError.Code.CHANNEL_FAILED,
            BackendError.Code.KEY_PASSPHRASE,
        )) assertFalse(c.toString(), RepromptPolicy.shouldPrompt(c, manualDial = true))
        assertFalse(RepromptPolicy.shouldPrompt(null, manualDial = true))
    }

    @Test fun `pin value round trips through parsePin`() {
        val v = RepromptPolicy.pinValue("ssh-ed25519", "SHA256:abcd==")
        assertEquals("ssh-ed25519 SHA256:abcd==", v)
        assertEquals("ssh-ed25519" to "SHA256:abcd==", RepromptPolicy.parsePin(v))
    }

    @Test fun `parsePin rejects malformed stored values`() {
        assertNull(RepromptPolicy.parsePin(null))
        assertNull(RepromptPolicy.parsePin(""))
        assertNull(RepromptPolicy.parsePin("ed25519"))   // ไม่มี fingerprint
        assertNull(RepromptPolicy.parsePin("ed25519 "))  // fingerprint ว่าง
        assertNull(RepromptPolicy.parsePin(" SHA256:x")) // algo ว่าง
    }

    @Test fun `warning message names host and both fingerprints`() {
        val m = RepromptPolicy.warningMessage("example.com", 22, "ssh-ed25519", "SHA256:old", "ssh-ed25519", "SHA256:new")
        assertTrue(m, m.contains("example.com:22"))
        // ต้องโชว์ทั้งสอง fingerprint ให้ user เทียบได้จริง (นี่คือ whole point ของ feature) — format เดียวกับ tofuDialog
        assertTrue(m, m.contains("SHA256: old"))
        assertTrue(m, m.contains("SHA256: new"))
        // ต้องเตือน MITM — ห้ามเป็นข้อความกลาง ๆ ที่ชวนกดรับเฉย ๆ
        val low = m.lowercase()
        assertTrue(m, low.contains("intercept") || low.contains("man-in-the-middle"))
    }
}
