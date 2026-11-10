package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ticket tmux/07: wait-for waiter + payload ฝั่ง core (pure, gate ด้วย JVM)
 *
 * expected strings = คำสั่งที่วัดจริงบน lab (form Z ผ่าน config-file parser, socket -L ztest, 11-06)
 * — ไม่ใช่ที่ prose สั่ง; parser fail-soft เหมือน TmuxListing (payload มาจาก remote = untrusted)
 */
class TmuxAlertsTest {

    // ---------- command builders (cycle 1) ----------

    @Test
    fun `wait command blocks server-side on the latched wait-for channel`() {
        // lab 11-06: `wait-for CHANNEL` รอข้าม signal-hiatus ได้ (latch), exit 0 = มี signal (หรือ server ตาย — ดู payload ทีหลัง)
        // lab 11-07 orphan guard (วัดจริงบน production path): OpenSSH sshd ไม่ฆ่า process เมื่อ exec channel (ไม่มี pty)
        // ปิด → orphan wait-for ยัง attach กิน latch ของ signal รอบถัดไปเงียบ ๆ → ครอบด้วย stdin-EOF kill:
        // channel ปิด → sshd ปิด stdin → `read` คืน → kill tmux client ฝั่งเราก่อน = wait-for ร่วงแบบยังไม่ consume latch
        // lab 11-07 (2): pre-start pkill แพ้ startup race — สอง dial พร้อมกัน, pkill ของแต่ละ wrapper
        // รันก่อน client ของอีกฝ่าย fork ขึ้น = ไม่เจออะไรจะฆ่า → รอดทั้งคู่ (วัดบน device จริง)
        // → ไล่ฆ่าหลัง client เราขึ้นเอง ด้วย pgrep + ข้าม PID ตัวเอง ($w): latest-started ชนะแบบ deterministic
        assertEquals(
            "sh -c 'exec 3<&0; tmux wait-for ATMALERT & w=\$!; for p in \$(pgrep -f \"^tmux wait-for ATMALERT\$\"); do [ \"\$p\" != \"\$w\" ] && kill \"\$p\" 2>/dev/null; done; ( read _ <&3; kill \"\$w\" 2>/dev/null ) & r=\$!; wait \"\$w\" 2>/dev/null; e=\$?; kill \"\$r\" 2>/dev/null; exit \$e'",
            TmuxAlerts.waitCommand(),
        )
    }

    @Test
    fun `payload read and clear address the global user option the hook writes`() {
        assertEquals("tmux show -g -v '@atm_alert'", TmuxAlerts.readCommand())
        assertEquals("tmux set -gu '@atm_alert'", TmuxAlerts.clearCommand())
    }

    @Test
    fun `builders reject anything that is not a bare token (shell-injection guard)`() {
        for (bad in listOf("A; rm -rf /", "A b", "A'b", "", "A\tb")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxAlerts.waitCommand(bad) }
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxAlerts.readCommand("@x; reboot") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxAlerts.readCommand("atm_alert") } // ขาด @ = ไม่ใช่ user option
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TmuxAlerts.clearCommand("@A UPPER") }
    }

    // ---------- payload parser (cycle 2) ----------

    @Test
    fun `lab-measured payload parses exactly (form Z bell round, 3_4)`() {
        // ค่าจริงที่ `show -g -v @atm_alert` พิมพ์ (no spaces = ไม่ครอบ quote) — หลักฐาน od 11-06
        assertEquals(
            TmuxAlerts.Alert("bell", "alert-test", "bash"),
            TmuxAlerts.parseAlert("bell|alert-test|bash"),
        )
    }

    @Test
    fun `quoted payload from show -v is unquoted and unescaped`() {
        // ชื่อ window มี space = show -v ครอบ quote + escape `"` ในค่า (tmux option quoting)
        assertEquals(TmuxAlerts.Alert("activity", "beta", "my win"), TmuxAlerts.parseAlert("\"activity|beta|my win\""))
        assertEquals(
            TmuxAlerts.Alert("bell", "s", "say \"hi\""),
            TmuxAlerts.parseAlert("\"bell|s|say \\\"hi\\\"\""),
        )
    }

    @Test
    fun `window name containing pipe survives (free text is the last field)`() {
        assertEquals(TmuxAlerts.Alert("bell", "s", "a|b"), TmuxAlerts.parseAlert("bell|s|a|b"))
    }

    // ---------- notification content + id resolution (slice 4) ----------

    @Test
    fun `notification strings name the session and window per alert type`() {
        // strings = ที่ user เห็นบน heads-up — test คุม exact เพราะเป็น UX contract (English-first ตาม CODING_STANDARDS)
        assertEquals("Bell in alpha", TmuxAlerts.title(TmuxAlerts.Alert("bell", "alpha", "edit")))
        assertEquals("Activity in beta", TmuxAlerts.title(TmuxAlerts.Alert("activity", "beta", "logs")))
        assertEquals("Silence in gamma", TmuxAlerts.title(TmuxAlerts.Alert("silence", "gamma", "one")))
        assertEquals("window edit", TmuxAlerts.text(TmuxAlerts.Alert("bell", "alpha", "edit")))
        assertEquals("session alpha", TmuxAlerts.text(TmuxAlerts.Alert("bell", "alpha", ""))) // window = free text อาจว่าง
    }

    @Test
    fun `alert session name resolves to the stable id only on exact match`() {
        val sessions = listOf(
            dev.aterminal.core.TmuxSessionInfo("\$0", 2, true, "alpha"),
            dev.aterminal.core.TmuxSessionInfo("\$1", 1, false, "beta"),
        )
        // tap ต้อง attach ด้วย $id (name เปลี่ยนได้หลัง alert — research §3 T8)
        assertEquals("\$1", TmuxAlerts.sessionIdNamed(sessions, "beta"))
        assertNull(TmuxAlerts.sessionIdNamed(sessions, "ALPHA"))   // exact match เท่านั้น
        assertNull(TmuxAlerts.sessionIdNamed(sessions, "ghost"))   // หายไปแล้ว (kill ระหว่างทาง) = null, caller fallback
        assertNull(TmuxAlerts.sessionIdNamed(emptyList(), "beta"))
    }

    @Test
    fun `garbage fails soft to null, never a crash and never an invented alert`() {
        assertNull(TmuxAlerts.parseAlert(null))
        assertNull(TmuxAlerts.parseAlert(""))
        assertNull(TmuxAlerts.parseAlert("no pipes here"))
        assertNull(TmuxAlerts.parseAlert("reboot|s|w"))   // type แปลก = ไม่ notify สิ่งที่ app ไม่รู้จัก
        assertNull(TmuxAlerts.parseAlert("bell||w"))      // session = field ที่ต้องชี้ตัวได้
        assertNull(TmuxAlerts.parseAlert("\"unbalanced|s|w"))
    }
}
