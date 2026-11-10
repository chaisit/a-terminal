package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ticket tmux/09: command builders สำหรับ control session (`tmux -CC`) — pure strings (สไตล์ TmuxHistory)
 *
 * wire facts ที่บังคับ form (spike 11-08 บน tmux 3.6 local + lab t03):
 *  - command line ปิดท้าย LF; **บรรทัดว่าง = detach** (control.c) → builder ห้ามปล่อย newline ออกไปนอก ControlSession.send
 *  - `-B name:what:format` ต้อง **quote format** (`"#{...}"`) — `{`/`}`/`%` ต้น token เป็น special ของ command lexer (cmd-parse.y)
 *  - target identity ใช้ rule เดียวกับ TmuxHistory/TmuxListing (`$N` หรือ safe name,ครอบ single-quote)
 */
class TmuxControlTest {

    @Test
    fun `attach command targets by identity and fails soft on unsafe target`() {
        assertEquals("tmux -CC attach -t '\$0'", TmuxControl.attachCommand("\$0"))
        assertEquals("tmux -CC attach -t 'main'", TmuxControl.attachCommand("main"))
        assertEquals(null, TmuxControl.attachCommand("two words"))
        assertEquals(null, TmuxControl.attachCommand(null))
    }

    @Test
    fun `subscribe command quotes the format - unquoted braces are a parse error on the wire`() {
        assertEquals(
            "refresh-client -B w:@*:\"#{window_name}#{?window_active_flag,*}\"",
            TmuxControl.subscribeCommand("w", "@*", "#{window_name}#{?window_active_flag,*}"),
        )
        // what = "" (session-level) ยังเป็นสามส่วนด้วย colon (man: name:what:format)
        assertEquals("refresh-client -B s::\"#{session_name}\"", TmuxControl.subscribeCommand("s", "", "#{session_name}"))
    }

    @Test
    fun `subscribe rejects inputs that would break the wire grammar`() {
        // format มี double-quote หรือ newline = grammar แตก — reject ชัด (app เป็นคนสร้าง format ทั้งหมด)
        assertEquals(null, TmuxControl.subscribeCommand("w", "@*", "#{window_name \"evil\"}"))
        assertEquals(null, TmuxControl.subscribeCommand("w", "@*", "#{window_name}\nselect-window -t @1"))
        assertEquals(null, TmuxControl.subscribeCommand("w", "@*", "no-braces"))
        // what ต้องอยู่ใน set ที่ man นิยาม (empty|%n|%*|@n|@*)
        assertEquals(null, TmuxControl.subscribeCommand("w", "%bad%", "#{x}"))
        // name = app-generated token
        assertEquals(null, TmuxControl.subscribeCommand("bad name", "@*", "#{x}"))
    }

    @Test
    fun `window select and resize use id targets and client size`() {
        assertEquals("select-window -t '@12'", TmuxControl.selectWindow("@12"))
        assertEquals(null, TmuxControl.selectWindow("spike:1")) // ไม่ใช่ @id — fail-soft (browser convention เดิม)
        assertEquals("refresh-client -C 120x40", TmuxControl.resizeCommand(120, 40))
    }

    @Test
    fun `initial sequence mutes pane output before subscribing`() {
        // no-output ก่อน = stream ของ strip ไม่โดน %output ก้อนใหญ่กลืน (spike: flood 20k บรรทัด = 0 bytes)
        // marker เป็น prefix สองตัวอักษร (active `*`/space + activity `!`/`:`) แล้วตามด้วย name —
        // name ที่มี `*`/`!` (เช่น path!) ไม่ทำให้ parse เพี้ยน เพราะอ่านจากหัวบรรทัดเท่านั้น
        assertEquals(
            listOf(
                "refresh-client -f no-output",
                "refresh-client -B w:@*:\"#{?window_active_flag,*, }#{?window_activity_flag,!,:}#{window_name}\"",
            ),
            TmuxControl.stripInitCommands(),
        )
    }
}
