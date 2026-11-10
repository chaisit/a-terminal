package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/04: snippet generator ของ tmux preset — exact string (user copy ไปใส่ `.tmux.conf` เอง,
 * app ห้ามเขียนไฟล์ user; Q4: section ของ ticket 07 มาแบบ comment วันนี้)
 */
class TmuxPresetTest {

    @Test
    fun `snippet contains the three core lines verbatim`() {
        val s = TmuxPreset.snippet
        // tmux config syntax — quoting ตรงตัว (single quotes = tmux ไม่ expand ก่อนส่งให้ format)
        assertTrue(s, s.contains("set -g set-titles on"))
        assertTrue(s, s.contains("set -g set-titles-string '#S:#I:#W'"))
        assertTrue(s, s.contains("set -g mouse on"))
    }

    @Test
    fun `alert section ships live with the lab-verified form-Z bell hook`() {
        val s = TmuxPreset.snippet
        // form Z = formเดียวที่ผ่าน config-file parser ของ tmux (lab 11-06, วัด payload จริง `bell|alert-test|bash` บน 3.4)
        val hook = "set-hook -ag alert-bell 'run-shell \"tmux set -g @atm_alert \\\"bell|#S|#W\\\"; tmux wait-for -S ATMALERT\"'"
        assertTrue("ขาด hook line form Z:\n$s", s.contains(hook))
        // monitor-bell off บน window ปัจจุบัน = hook ไม่ยิง (วัด lab) — ต้อง open global
        assertTrue(s, s.contains("set -gw monitor-bell on"))
        assertTrue(s, s.contains("set -g bell-action any"))
        // channel/option ใน hook ต้องตรงกับที่ app wait — drift = notifications ตายเงียบ
        assertTrue(s, s.contains(dev.aterminal.core.TmuxAlerts.CHANNEL))
        assertTrue(s, s.contains(dev.aterminal.core.TmuxAlerts.PAYLOAD_OPTION))
        // placeholder แบบ comment เดิมต้องหายหมด (user อย่าได้ chance เข้าใจว่า feature ยังไม่เปิด)
        org.junit.Assert.assertFalse("ยังเหลือ placeholder เก่า", s.contains("uncomment when A-Terminal"))
    }

    @Test
    fun `clipboard section enables OSC 52 forwarding (user approved both lines 11-07)`() {
        val s = TmuxPreset.snippet
        // วัด lab 11-07: `set-clipboard` default = external → pane OSC 52 ถูก tmux ทิ้งทั้งก้อน
        // ต้อง `on` เท่านั้นถึง forward ออกมาหา app (3.4/3.6 เหมือนกัน)
        assertTrue(s, s.contains("set -s set-clipboard on"))
        // terminal-features มี default `xterm*:clipboard` แล้วบน ≥3.4 แต่ `on` ยังต้อง TTYC_MS —
        // ใส่ -as (append ไม่ใช่ replace) ให้ coverage กับ terminfo/server ที่ไม่มี glob นั้น
        assertTrue(s, s.contains("set -as terminal-features 'xterm*:clipboard'"))
    }

    @Test
    fun `every snippet line is blank, a comment, or a live tmux statement`() {
        // snippet = artifact ที่ user paste ตรง — syntax ผิดบรรทัดเดียว = conf พังทั้งไฟล์
        TmuxPreset.snippet.lines().filter { it.isNotBlank() && !it.trim().startsWith("#") }
            .forEach {
                assertTrue("บรรทัด active ไม่รู้จัก: $it",
                    it.startsWith("set -g ") || it.startsWith("set -gw ") ||
                        it.startsWith("set -s ") || it.startsWith("set -as ") ||
                        it.startsWith("set-hook "))
            }
    }

    @Test
    fun `commandline default matches ticket 01 continuity recipe`() {
        assertEquals("tmux new -A -s main", TmuxPreset.commandline)
    }

    // ---- ticket tmux/05: quick-key row ขณะ prefix pending (ชุดที่ user อนุมัติ Q4; ขยาย 11-06
    // ตาม tmux usage research 2026-11-06 (primary sources: tmux key-bindings.c + man + public configs) = เพิ่ม c [ , s — user อนุมัติชุด+ลำดับ fold-priority) ----

    @Test
    fun `quick keys are exactly the approved set in order`() {
        assertEquals(
            listOf("%", "c", "d", "z", "n", "p", "w", "x", "[", "s", ",", "←", "↑", "↓", "→"),
            TmuxPreset.quickKeys.map { it.label },
        )
    }

    @Test
    fun `quick key bytes are the plain tmux command keys, arrows are full CSI`() {
        val byLabel = TmuxPreset.quickKeys.associate { it.label to it.afterPrefix }
        assertEquals("%", byLabel["%"])
        assertEquals("c", byLabel["c"])
        assertEquals(",", byLabel[","])
        assertEquals("[", byLabel["["])
        assertEquals("s", byLabel["s"])
        assertEquals("z", byLabel["z"])
        assertEquals("d", byLabel["d"])
        assertEquals("\u001B[D", byLabel["←"])
        assertEquals("\u001B[A", byLabel["↑"])
        assertEquals("\u001B[B", byLabel["↓"])
        assertEquals("\u001B[C", byLabel["→"])
    }

    @Test
    fun `every quick key has bytes and a glyph-short label`() {
        for (k in TmuxPreset.quickKeys) {
            assertTrue(k.afterPrefix.isNotEmpty())
            assertTrue("label ต้องสั้นพอสำหรับ fit math: " + k.label, k.label.length <= 2)
        }
    }
}
