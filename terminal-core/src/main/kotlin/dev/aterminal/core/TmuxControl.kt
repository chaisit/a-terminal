package dev.aterminal.core

/**
 * ticket tmux/09: command builders สำหรับ control session (`tmux -CC`) — pure strings (สไตล์ TmuxHistory/TmuxListing)
 *
 * wire facts (spike 11-08 บน tmux 3.6 local + lab t03 — หลักฐานเต็มในตั๋ว §Notes):
 *  - control mode ต้องการ tty จริง (pipe เปล่า = tcgetattr fail) → transport ต้องเป็น exec-**PTY** channel
 *  - command line ปิดท้าย LF; **บรรทัดว่าง = detach** (control.c) → newline ผลิตโดย ControlSessionSend เท่านั้น
 *  - command grammar (cmd-parse.y): `{`/`}` ปิด token, `#` = comment, `%` ต้น token = condition
 *    → `-B` format ต้องครอบ double-quote; ค่าทุกตัวที่ builder รับ = app-generated, character ที่ break grammar = reject (null)
 *  - alert/activity flag ไม่มี notification ของตัวเองใน 3.6 (%window-flag-changed ไม่มีใน man) → มาทาง subscription value
 */
object TmuxControl {

    private val SAFE_TARGET = Regex("^(\\$\\d+|[A-Za-z0-9_.-]{1,64})$") // rule เดียวกับ TmuxHistory
    private val WINDOW_ID = Regex("^@\\d+$")
    private val SUB_NAME = Regex("^[A-Za-z0-9_-]{1,16}$")
    private val SUB_WHAT = Regex("^(%\\d+|%\\*|@\\d+|@\\*|)$") // man refresh-client -B: empty | %pane | %* | @window | @*
    // whitelist (ไม่ใช่ blacklist): `\n` ใน format = command injection (ส่งคำสั่งที่สองได้) — ตัวอักษรที่ format tmux ต้องการจริงเท่านั้น
    private val SUB_FORMAT = Regex("""^[A-Za-z0-9{}#?!:.,_%$@()*=+ -]{1,200}$""") // `*` (ternary marker) + space (marker `*`/` ` ของ strip format) ต้องอยู่

    /** `tmux -CC attach -t <id>` — unsafe/absent target = null (caller fail-soft: ไม่เปิด control session) */
    fun attachCommand(target: String?): String? {
        if (target == null || !SAFE_TARGET.matches(target)) return null
        return "tmux -CC attach -t '$target'"
    }

    /**
     * subscription value format ของ strip: **marker-prefix สองตัวพอดี + name** — active `*`/space, activity `!`/`:`
     * (prefix ห้ามเปลี่ยนโดยไม่แก้ parser ฝั่ง app: marker ยาวคงที่ 2 ตัว = name ที่มี `*`/`!`/`:` นำหน้าก็ parse ได้ไม่เพี้ยน)
     */
    const val STRIP_FORMAT = "#{?window_active_flag,*, }#{?window_activity_flag,!,:}#{window_name}"

    /** subscription list ของ strip (what=@* หนึ่ง entry ต่อ window, re-expand ≤1/s — spike fact 6) */
    const val STRIP_SUBSCRIPTION_NAME = "w"

    /** -B name:what:format — format ครอบ quote เสมอ; input ที่ break grammar = null (fail-soft) */
    fun subscribeCommand(name: String, what: String, format: String): String? {
        if (!SUB_NAME.matches(name)) return null
        if (!SUB_WHAT.matches(what)) return null
        if (!SUB_FORMAT.matches(format)) return null
        if (!format.contains("#{")) return null // subscription ที่ไม่มี format expansion = ไม่มีของให้ report (และ signal ของ input ผิดที่)
        return "refresh-client -B $name:$what:\"$format\""
    }

    /** ลำดับเปิด strip บน channel ที่ attach แล้ว: mute %output ก่อน แล้ว subscribe window list */
    fun stripInitCommands(): List<String> = listOf(
        "refresh-client -f no-output",
        subscribeCommand(STRIP_SUBSCRIPTION_NAME, "@*", STRIP_FORMAT)
            ?: error("STRIP_FORMAT must satisfy the subscription grammar — this is a build-time invariant"),
    )

    /** tap ที่ pill: select ด้วย @id เท่านั้น (identity convention เดียวกับ browser) — id อื่น = null */
    fun selectWindow(windowId: String): String? =
        if (WINDOW_ID.matches(windowId)) "select-window -t '$windowId'" else null

    /**
     * control client ไม่ดึง size จาก PTY (man) — ต้องบอก size ของ strip เองผ่าน refresh-client -C
     * ต่างจาก builder อื่น (fail-soft null): args เป็น Int — ค่าเป็นไปไม่ได้ = programmer error ต้องดัง ไม่ใช่เงียบ
     */
    fun resizeCommand(cols: Int, rows: Int): String {
        require(cols in 1..10_000 && rows in 1..2_000) { "implausible control size ${cols}x$rows" }
        return "refresh-client -C ${cols}x$rows"
    }
}
