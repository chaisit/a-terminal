package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ticket tmux/03: query-reply builders (pure) — ค่าต้อง ≡ xterm.js 5.5 vendored bundle (วัดจาก bytes):
 *  - DA1/DA2 ไม่ใช่ไฟล์นี้ — :engine ตอบเอง (TerminalEngineTest lock byte) และ xterm.js ตอบเอง
 *  - colour report → `\u001b]<code>;rgb:rrrr/gggg/bbbb\u001b\` (ST, ไม่ใช่ BEL — ต่างจาก OSC52 reply ที่ tmux ใช้ BEL)
 *    `toRgbString(channels,16)`: helper `r` ทำ `s+s` = ขยาย byte → 4 hex ตัวซ้ำคู่ (0x12 → "1212" = ×257)
 *  - เหตุผลที่ต้องตอบ: tmux 3.4/3.6 ยิง DA1/DA2/>q/OSC10/11 ทุก attach (วัด lab) และ 3.6 forward
 *    OSC4 จาก pane ต่อถึง client — ตอบได้ตรง format ก็ไม่หลุดขบวน (tmux ไม่ hang ไม่ว่าอย่างไร แต่ client
 *    ที่เงียบ = vim/tmux-color-scheme บน bare ssh ไม่ได้สีจริง)
 * (ESC/ST ทุกตัวใน literal เป็น escape — raw control byte ห้ามอยู่ในไฟล์ source)
 */
class TerminalReportsTest {

    @Test
    fun `foreground report doubles hex bytes and terminates with ST`() {
        // #123456 → 1212/3434/5656 (s+s จาก bundle) — ST = ESC backslash
        assertEquals(
            "\u001b]10;rgb:1212/3434/5656\u001b\\",
            TerminalReports.colorReportOrNull(10, "#123456"),
        )
        // code 11 = background, 12 = cursor — builder เดียวกัน รับ code เป็นพารามิเตอร์
        assertEquals(
            "\u001b]11;rgb:ffff/ffff/ffff\u001b\\",
            TerminalReports.colorReportOrNull(11, "#ffffff"),
        )
    }

    @Test
    fun `indexed color report keeps the requested index (OSC 4 reply)`() {
        assertEquals(
            "\u001b]4;1;rgb:0000/aaaa/0000\u001b\\",
            TerminalReports.indexedColorReportOrNull(1, "00aa00"), // ไม่ใส่ # ก็ได้
        )
    }

    @Test
    fun `bad color input yields no reply instead of a malformed report`() {
        assertNull(TerminalReports.colorReportOrNull(10, "not-a-color"))
        assertNull(TerminalReports.colorReportOrNull(10, "#12345"))   // สั้นไป
        assertNull(TerminalReports.colorReportOrNull(10, "#1234567")) // ยาวไป
        assertEquals(
            "\u001b]10;rgb:0a0a/0b0b/0c0c\u001b\\",
            TerminalReports.colorReportOrNull(10, "#0A0B0C"), // hex ตัวใหญ่ก็รับ, lower-case output
        )
    }
}
