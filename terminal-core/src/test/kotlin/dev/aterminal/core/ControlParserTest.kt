package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ticket tmux/09: control-mode stream parser (pure, JVM-gated)
 *
 * fixtures = bytes จริงที่ capture จาก tmux 3.6 (สอง environment แยกกันยืนยันตรงกัน):
 *  - stream เปิดด้วย DCS `\eP1000p` *ติดกัน* %begin แรก (บรรทัดเดียวกัน, ไม่มี separator)
 *  - กรอบคำสั่ง = `%begin <time> <number> <flag>` … `%end|%error <time> <number> <flag>` — field ที่ 3
 *    เป็น flag (CMDQ_STATE_CONTROL) ไม่ใช่ checksum (cmdq_guard @3.6: `"%%%s %ld %u %d"`) → จับคู่ด้วย (time,number)
 *  - channel เป็น PTY → ท้ายบรรทัด `\r\n` และ input echo/ขยะที่ไม่ใช่ % =บรรทัดที่ต้องข้าม (โครงสร้าง ignore โดย design:
 *    notification ห้ามเกิดใน output block — man CONTROL MODE)
 *  - chunk boundary ผิดตำแหน่งได้ทุกจุด (feed = incremental, buffer ต่อ)
 */
class ControlParserTest {

    // bytes จริงจาก /tmp/t09sp4.log (local 3.6) — ตอน attach: DCS fused + begin/end ของ refresh-client -C
    // + session-changed (fixtures ยาวเต็มใช้ cycle ถัดไป)
    private val attachFixture =
        "\u001bP1000p%begin 1794101864 347 0\r\n" +
        "%end 1794101864 347 0\r\n" +
        "%session-changed \$0 spike\r\n"

    @Test
    fun `attach stream yields control marker, begin-end frames paired by time and number, and session changed`() {
        val events = ControlParser().feed(attachFixture.toByteArray(Charsets.UTF_8))
        assertEquals(
            listOf(
                ControlEvent.ControlModeMarker,
                ControlEvent.Begin(time = 1794101864L, number = 347, flag = 0),
                ControlEvent.End(time = 1794101864L, number = 347, flag = 0),
                ControlEvent.SessionChanged("\$0", "spike"),
            ),
            events,
        )
    }

    // bytes จริงจาก /tmp/t09sp4.log — subscription dump (what=@* → pane token เป็น "-", what=%* → "%n")
    // + window churn (winZ ไม่มีในเครื่อง → window โผล่แล้วปิดทันที = fixture %unlinked-window-close จริง)
    @Test
    fun `subscription dump parses name session window index pane and value, dash pane means none`() {
        val bytes = (
            "%subscription-changed b \$0 @0 0 - : zsh\r\n" +
                "%subscription-changed c \$0 @2 3 %2 : zsh\r\n"
            ).toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf(
                ControlEvent.SubscriptionChanged("b", "\$0", "@0", 0, null, "zsh"),
                ControlEvent.SubscriptionChanged("c", "\$0", "@2", 3, "%2", "zsh"),
            ),
            ControlParser().feed(bytes),
        )
    }

    @Test
    fun `window churn notifications map to typed events`() {
        // bytes จริง (SEG B ของ t09sp4): active window เปลี่ยน, winZ เกิด→rename→ปิดเพราะโปรแกรมไม่มีจริง
        val bytes = (
            "%session-window-changed \$0 @3\r\n" +
                "%window-add @3\r\n" +
                "%window-renamed @3 tmux\r\n" +
                "%session-window-changed \$0 @2\r\n" +
                "%unlinked-window-close @3\r\n" +
                "%window-close @1\r\n" +
                "%unlinked-window-add @9\r\n" +
                "%sessions-changed\r\n"
            ).toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf(
                ControlEvent.SessionWindowChanged("\$0", "@3"),
                ControlEvent.WindowAdd("@3"),
                ControlEvent.WindowRenamed("@3", "tmux"),
                ControlEvent.SessionWindowChanged("\$0", "@2"),
                ControlEvent.UnlinkedWindowClose("@3"),
                ControlEvent.WindowClose("@1"),
                ControlEvent.UnlinkedWindowAdd("@9"),
                ControlEvent.SessionsChanged,
            ),
            ControlParser().feed(bytes),
        )
    }

    @Test
    fun `output data uses octal escapes per control_append_data and decodes to raw bytes`() {
        // bytes จริง (t09sp4 SEG B: zsh error line) + เคส \\134 = backslash (escape rule: ทุก byte <0x20 หรือ '\' → "\\%03o" — control.c @3.6)
        val bytes = (
            "%output %3 zsh:1: command not found: winZ\\015\\012\r\n" +
                "%output %0 \\033[?2004h\\134x\r\n" +
                "%extended-output %0 0 : y\r\n" +
                "%extended-output %0 4321 : \\015\\012y\r\n" +
                "%pause %0\r\n" +
                "%continue %0\r\n"
            ).toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf(
                ControlEvent.Output("%3", "zsh:1: command not found: winZ\r\n".toByteArray(Charsets.UTF_8)),
                ControlEvent.Output("%0", byteArrayOf(0x1b) + "[?2004h".toByteArray(Charsets.UTF_8) + byteArrayOf(0x5c) + "x".toByteArray(Charsets.UTF_8)),
                ControlEvent.ExtendedOutput("%0", 0L, "y".toByteArray(Charsets.UTF_8)),
                ControlEvent.ExtendedOutput("%0", 4321L, "\r\ny".toByteArray(Charsets.UTF_8)),
                ControlEvent.Pause("%0"),
                ControlEvent.Continue("%0"),
            ),
            ControlParser().feed(bytes),
        )
    }

    @Test
    fun `lines between begin and end are block content, closing at end or error with same time and number`() {
        // bytes จริง: SEG B t09sp4 (kill-window เป้าไม่มี → %error พร้อม message ใน block)
        // + list-windows จริงจาก t09sp2 (content หลายบรรทัด, คั่น %begin/%)
        val bytes = (
            "%begin 1794101869 363 1\r\n" +
                "can't find window: winZ\r\n" +
                "%error 1794101869 363 1\r\n" +
                "%begin 1794101607 312 1\r\n" +
                "0: zsh- (1 panes) [80x24] [layout b25d,80x24,0,0,0] @0\r\n" +
                "1: win3* (1 panes) [80x24] [layout b25e,80x24,0,0,1] @1 (active)\r\n" +
                "%end 1794101607 312 1\r\n"
            ).toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf(
                ControlEvent.Begin(1794101869L, 363, 1),
                ControlEvent.BlockLine("can't find window: winZ"),
                ControlEvent.BlockError(1794101869L, 363, 1),
                ControlEvent.Begin(1794101607L, 312, 1),
                ControlEvent.BlockLine("0: zsh- (1 panes) [80x24] [layout b25d,80x24,0,0,0] @0"),
                ControlEvent.BlockLine("1: win3* (1 panes) [80x24] [layout b25e,80x24,0,0,1] @1 (active)"),
                ControlEvent.End(1794101607L, 312, 1),
            ),
            ControlParser().feed(bytes),
        )
    }

    @Test
    fun `inside a block even a percent-looking line is content - notifications never occur inside output blocks (man)`() {
        // rule-based (man CONTROL MODE): "A notification will never occur inside an output block"
        // → content ที่ *ดูเหมือน* notification (capture-pane ของ stream control mode!) ต้องไม่ถูก parse ซ้ำ
        val bytes = (
            "%begin 1 1 1\r\n" +
                "%output %9 fake captured line\r\n" +
                "%end 1 1 1\r\n"
            ).toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf(
                ControlEvent.Begin(1L, 1, 1),
                ControlEvent.BlockLine("%output %9 fake captured line"),
                ControlEvent.End(1L, 1, 1),
            ),
            ControlParser().feed(bytes),
        )
    }

    @Test
    fun `exit carries optional reason and unknown notifications survive as Unknown for forward-compat`() {
        // `%exit` ว่าง = รูปที่เห็นจริงทุก spike log; [reason] = man; `%paste-buffer-changed` = man 3.6 (ยังไม่ map = ต้องไม่ crash stream)
        // `%unlinked-window-renamed` = man: window-id เท่านั้น (ต่าง %window-renamed ที่มี name)
        val bytes = (
            "%unlinked-window-renamed @5\r\n" +
                "%paste-buffer-chared nope\r\n" +
                "%paste-buffer-changed buf\r\n" +
                "%exit\r\n" +
                "%exit no sessions\r\n"
            ).toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf(
                ControlEvent.UnlinkedWindowRenamed("@5"),
                ControlEvent.Unknown("paste-buffer-chared", "nope"),
                ControlEvent.Unknown("paste-buffer-changed", "buf"),
                ControlEvent.Exit(null),
                ControlEvent.Exit("no sessions"),
            ),
            ControlParser().feed(bytes),
        )
    }

    @Test
    fun `feed is incremental - frames split across arbitrary chunk boundaries parse identically`() {
        val bytes = attachFixture.toByteArray(Charsets.UTF_8)
        val parser = ControlParser()
        val events = mutableListOf<ControlEvent>()
        var i = 0
        while (i < bytes.size) {
            val n = minOf(7, bytes.size - i) // 7 = ตัวเลขที่ "ไม่ตรง" ตำแหน่ง boundary จริงของทุก notification — บังคับให้กลางบรรทัดแน่
            events += parser.feed(bytes.copyOfRange(i, i + n))
            i += n
        }
        assertEquals(
            listOf(
                ControlEvent.ControlModeMarker,
                ControlEvent.Begin(time = 1794101864L, number = 347, flag = 0),
                ControlEvent.End(time = 1794101864L, number = 347, flag = 0),
                ControlEvent.SessionChanged("\$0", "spike"),
            ),
            events,
        )
    }
}
