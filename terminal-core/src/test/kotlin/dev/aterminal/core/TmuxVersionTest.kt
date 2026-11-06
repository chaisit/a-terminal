package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/02: parse `tmux -V` + capability table (fail-soft — ผล probe คือ untrusted remote output)
 *
 * gate ทั้งหมดอ้าง research §1.12 (CHANGELOG ตรวจแล้ว) — ไม่ใช่ ticket prose:
 * ticket เขียน "set-clipboard 3.2+" แต่ §1.12 (primary source) = 2.6 → ยึด §1.12
 */
class TmuxVersionTest {

    // ---------- parser ----------

    @Test
    fun `plain versions parse to major minor`() {
        assertEquals(TmuxVersion(3, 6), TmuxVersion.parse("tmux 3.6"))
        assertEquals(TmuxVersion(2, 6), TmuxVersion.parse("tmux 2.6"))
        assertEquals(TmuxVersion(3, 1), TmuxVersion.parse("tmux 3.1\r\n")) // CRLF จากช่อง exec จริง
    }

    @Test
    fun `patch letter parsed (3_2a is a real release, gate boundary of client-detached)`() {
        assertEquals(TmuxVersion(3, 2, 'a'), TmuxVersion.parse("tmux 3.2a"))
        assertEquals(TmuxVersion(3, 6, 'a'), TmuxVersion.parse("tmux 3.6a"))
    }

    @Test
    fun `git build string next-X parsed as X (dev build carries the upcoming features)`() {
        // FACT (research §1.12): build จาก git tree พิมพ์ "tmux next-3.7" — gate "3.7+" ต้องเปิด
        assertEquals(TmuxVersion(3, 7), TmuxVersion.parse("tmux next-3.7"))
        assertEquals(TmuxVersion(3, 8), TmuxVersion.parse("tmux next-3.8"))
    }

    @Test
    fun `garbage and missing tmux parse to null (fail-soft, no tmux = features silent)`() {
        assertNull(TmuxVersion.parse(""))
        assertNull(TmuxVersion.parse("bash: line 1: tmux: command not found"))
        assertNull(TmuxVersion.parse("tmux"))
        assertNull(TmuxVersion.parse("tmux -V: not found"))
        assertNull(TmuxVersion.parse("tmux 3"))            // no minor = ไม่ใช่รูปแบบ tmux -V
        assertNull(TmuxVersion.parse("notmux 3.6"))
        assertNull(TmuxVersion.parse("tmux 3.6.1-beta"))   // tmux ไม่เคยออก 3-part — รับ format จริงเท่านั้น
        assertNull(TmuxVersion.parse(null))
    }

    @Test
    fun `version line found even when wrapper shim prints first (stderr merged, scan all lines)`() {
        // review tmux/02: managed host พิมพ์ wrapper notice นำหน้า — ห้าม commit กับ line แรก
        assertEquals(
            TmuxVersion(3, 6),
            TmuxVersion.parse("tmux wrapper: loading /opt/tmux/module\n\ntmux 3.6\n"),
        )
        assertNull(TmuxVersion.parse("tmux wrapper: broken install\nno version here"))
    }

    // ---------- ordering (patch letter ต้อง order ถูก เพราะ gate 3.2a มีจริง) ----------

    @Test
    fun `ordering across patch letters and minors`() {
        assertTrue(TmuxVersion(3, 1) < TmuxVersion(3, 2))
        assertTrue(TmuxVersion(3, 2) < TmuxVersion(3, 2, 'a')) // no-letter < any letter
        assertTrue(TmuxVersion(3, 2, 'a') < TmuxVersion(3, 2, 'b'))
        assertTrue(TmuxVersion(3, 2, 'a') < TmuxVersion(3, 3))
    }

    // ---------- capability table (gates = research §1.12) ----------

    private fun caps(v: String) = TmuxCapabilities(TmuxVersion.parse(v)!!)

    @Test
    fun `osc52 set-clipboard gates at 2_6 (3-state landed in 2_6 per §1_12, not 3_2)`() {
        assertFalse(caps("tmux 2.5").osc52Clipboard)
        assertTrue(caps("tmux 2.6").osc52Clipboard)
        assertTrue(caps("tmux 3.6").osc52Clipboard)
    }

    @Test
    fun `send-keys hex (-H) gates at 3_0`() {
        assertFalse(caps("tmux 2.9a").sendKeysHex) // 2.9a = release ก่อน 3.0 ที่มี letter จริง
        assertTrue(caps("tmux 3.0").sendKeysHex)
    }

    @Test
    fun `control-mode extensions (pause-after, extended-output, -B subscriptions) gate at 3_2`() {
        assertFalse(caps("tmux 3.1").pauseAfter)
        assertTrue(caps("tmux 3.2").pauseAfter)
        assertTrue(caps("tmux 3.2").extendedOutput)
        assertTrue(caps("tmux 3.2").subscriptions)
    }

    @Test
    fun `client-detached notification gates at 3_2a (patch-level gate — the reason patch exists)`() {
        assertFalse(caps("tmux 3.2").clientDetachedNotify)
        assertTrue(caps("tmux 3.2a").clientDetachedNotify)
        assertTrue(caps("tmux 3.3").clientDetachedNotify) // 3.3 > 3.2a
    }

    @Test
    fun `config-error notification gates at 3_4`() {
        assertFalse(caps("tmux 3.3a").configErrorNotify)
        assertTrue(caps("tmux 3.4").configErrorNotify)
    }

    @Test
    fun `extended-keys-format gates at 3_5`() {
        assertFalse(caps("tmux 3.4").extendedKeysFormat)
        assertTrue(caps("tmux 3.5").extendedKeysFormat)
    }

    @Test
    fun `OSC forwarding (set-title, OSC 4-10-11, refresh-client -r) gates at 3_6`() {
        assertFalse(caps("tmux 3.5a").oscForwarding)
        assertTrue(caps("tmux 3.6").oscForwarding)
        assertTrue(caps("tmux 3.6a").oscForwarding)
    }

    @Test
    fun `layout JSON gates at 3_7 (control-mode layout became JSON in 3_7, 3_8 only extends it)`() {
        assertFalse(caps("tmux 3.6a").layoutJson)
        assertTrue(caps("tmux 3.7").layoutJson)
        assertTrue(caps("tmux next-3.7").layoutJson) // git build of 3.7 = นับว่า 3.7
    }

    @Test
    fun `theme options gate at 3_8 (unreleased at sprint time — flag for future)`() {
        assertFalse(caps("tmux 3.7").themeOptions)
        assertTrue(caps("tmux next-3.8").themeOptions)
    }
}
