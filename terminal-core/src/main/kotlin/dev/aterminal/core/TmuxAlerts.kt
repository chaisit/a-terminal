package dev.aterminal.core

/**
 * ticket tmux/07: สัญญาฝั่ง tmux ของ alert waiter — command builders + payload parser (pure-Kotlin)
 *
 * สถาปัตยกรรม (user ตัดสิน 11-06, หลักฐานเต็มในตั๋ว 07): app เปิด exec `wait-for` no-deadline (06's channel),
 * hook ใน tmux (ติดผ่าน preset — TmuxPreset) เขียน payload ลง user option แล้ว `wait-for -S` ปลุก waiter
 *
 * facts ที่ builders นี้ยึด (วัดบน lab 11-06, container 3.4 + host 3.6):
 *  - `wait-for` latch: signal ก่อน waiter = waiter คนถัดไป return ทันที, consume ครั้งเดียว → re-arm ไร้ race
 *  - server ตาย = wait-for ก็ exit 0 — caller ห้าม notify จาก exit code เดี่ยว ต้องอ่าน payload ก่อน (parse null = เงียบ)
 *  - payload `type|session|window`: free-text (window) อยู่ท้าย เช่นเดียวกับ TmuxListing — limit-split ไม่ขาด
 *  - `show -g -v` พิมพ์ค่า raw; ค่ามีช่องว่าง/`"` จะถูกครอบ quote + escape → parser unescape
 */
object TmuxAlerts {

    /** wait-for channel ที่ hook ฝั่ง tmux ยิง (ชื่อคงที่ = preset กับ app ตรงกัน; single app per server — ดู comment ตั๋ว 07) */
    const val CHANNEL = "ATMALERT"

    /** global user option ที่ hook เขียน payload ลงไป */
    const val PAYLOAD_OPTION = "@atm_alert"

    private val TOKEN_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val OPTION_RE = Regex("^@[a-z0-9_]{1,64}$")

    /**
     * คำสั่ง waiter (exec ด้วย timeoutMs=0 — unbounded): server block จนกว่า hook จะ -S หรือ connection ตาย
     *
     * orphan guard (วัดจริง 11-07): OpenSSH sshd **ไม่ฆ่า** process เมื่อ exec channel (pty-less) ปิด —
     * orphan `tmux wait-for` ที่ยัง attach จะกิน latch ของ signal รอบถัดไปเงียบ ๆ = alert หายทั้งลูก
     * จึงครอบด้วย stdin-EOF kill: channel ปิด → sshd ปิด stdin → `read` คืนค่า → kill tmux client
     * ฝั่งเราก่อน = wait-for ร่วงแบบ **ไม่ consume** latch → waiter รุ่นใหม่ปลุกทันทีด้วย signal ที่ค้าง
     * ปกติ (wake): wait-for คืนก่อน → kill reader ที่ค้าง read → exit ตาม wait-for (0; server ตายก็ 0)
     * channel ครอบด้วย $CHANNEL ตรงไม่ได้ (TOKEN_RE กัน meta-chars ครบแล้ว) เพราะอยู่ใน single-quote นอกรวม
     *
     * `exec 3<&0` + `read _ <&3` ห้ามยุบเหลือ `read _`: POSIX บังคับ stdin ของ async list (`( ... ) &`)
     * เป็น /dev/null เมื่อ shell ไม่ทำ job control — dash บน lab ทำตามนั้นเป๊ะ (วัด 11-07: reader ได้ EOF
     * ปลอมทันที → ฆ่า wait-forตัวเอง, exit 143; drill ผ่านทั้งก้อนบน OpenSSH จริงเท่านั้นถึงจะเห็น)
     *
     * Kill-others reaper (`pgrep -f "^tmux wait-for CH$"` แล้วฆ่าทุก PID ที่ไม่ใช่ `$w`): half-open
     * network (FIN ไม่มา) = stdin ไม่ EOF → orphan cross-generation ยังอยู่กิน latch → รุ่นใหม่ไล่ฆ่า
     * ตัวอื่นของ channel นี้ **หลัง client ของตัวเอง fork ขึ้น** — latest-started ชนะ deterministic
     * (เวอร์ชัน pre-start `pkill -o` แพ้ startup race วัดบน device 11-07: สอง wrapper ที่ dial พร้อมกัน
     * ไล่ฆ่าตอน rival client ยัง ไม่เกิด = รอดทั้งคู่); anchor `^...$` ห้ามถอด (wrapper cmdline มี
     * substring เดียวกัน — pgrep -f จะ-match ถึง sh ด้วยถ้าถอด); เครื่องไม่มี pgrep = no-op เงียบ
     * (fail-soft เหลือ EOF-guard); semantics = latest watcher ต่อ host-user ถือ watch จริง — caller
     * ต้อง backoff เมื่อ wake แล้วไร้ payload ไม่งั้น watcher สองตัว re-arm ฆ่ากันเอง hot-loop
     * — client ที่โดน SIGTERM ออกแบบ clean exit 0 (วัด lab 11-07): exit 0 ของรุ่นที่ถูกเก็บงาน
     * ไม่ใช่ "consume latch" — signal รุ่นถัดไปยังปลุกรุ่นใหม่ได้ (drill ยืนยัน)
     */
    fun waitCommand(channel: String = CHANNEL): String {
        require(TOKEN_RE.matches(channel)) { "alert channel must be [A-Za-z0-9_-]{1,64}: $channel" }
        return "sh -c 'exec 3<&0; tmux wait-for $channel & w=\$!; for p in \$(pgrep -f \"^tmux wait-for $channel\$\"); do [ \"\$p\" != \"\$w\" ] && kill \"\$p\" 2>/dev/null; done; ( read _ <&3; kill \"\$w\" 2>/dev/null ) & r=\$!; wait \"\$w\" 2>/dev/null; e=\$?; kill \"\$r\" 2>/dev/null; exit \$e'"
    }

    /** อ่าน payload หลังถูกปลุก — `-v` = ค่าล้วน (ไม่มีชื่อ option) ตามที่วัด lab */
    fun readCommand(option: String = PAYLOAD_OPTION): String {
        require(OPTION_RE.matches(option)) { "alert payload option must be @[a-z0-9_]{1,64}: $option" }
        return "tmux show -g -v '$option'"
    }

    /** ล้าง payload หลัง consume — กัน notification ซ้ำรอบ + กัน payload เก่าหลอก round ใหม่ */
    fun clearCommand(option: String = PAYLOAD_OPTION): String {
        require(OPTION_RE.matches(option)) { "alert payload option must be @[a-z0-9_]{1,64}: $option" }
        return "tmux set -gu '$option'"
    }

    /** ชนิดที่ app รู้จัก — hook อื่น (type แปลก) = parse fail-soft ไม่ notify สิ่งที่ไม่เข้าใจ */
    val TYPES = setOf("bell", "activity", "silence")

    data class Alert(val type: String, val session: String, val window: String)

    /** หัว notification — English-first (CODING_STANDARDS); ชื่อ session ตามที่ hook ส่งมาตรง ๆ */
    fun title(alert: Alert): String = when (alert.type) {
        "bell" -> "Bell in ${alert.session}"
        "activity" -> "Activity in ${alert.session}"
        else -> "Silence in ${alert.session}"
    }

    /** body: window ที่ alert (free text จาก payload — อาจว่างได้ = บอกแค่ session) */
    fun text(alert: Alert): String =
        if (alert.window.isBlank()) "session ${alert.session}" else "window ${alert.window}"

    /**
     * ชื่อ session จาก payload → `$id` ที่เสถียร (research §3 T8: name เปลี่ยนได้ทุกเมื่อ — tap ต้อง attach ด้วย id)
     * exact match เท่านั้น; null = ชื่อหาย/rename แล้ว — caller ห้าม attach ชื่อ (สร้าง session ผิดตัวได้)
     */
    fun sessionIdNamed(sessions: List<TmuxSessionInfo>, name: String): String? =
        sessions.firstOrNull { it.name == name }?.id

    /**
     * parse `type|session|window` (raw = stdout ของ readCommand) — null = ไม่มี payload / เพี้ยน = เงียบ
     * unquote ตาม tmux option quoting: ครอบ `"..."` แล้ว single-pass unescape `X` → X ทุกกรณี
     * (single-pass จำเป็น: replace ซ้อนชั้นจะกิน space จริงในค่า เช่น window "my win")
     */
    fun parseAlert(raw: String?): Alert? {
        var v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
            val inner = v.substring(1, v.length - 1)
            val sb = StringBuilder(inner.length)
            var i = 0
            while (i < inner.length) {
                if (inner[i] == '\\' && i + 1 < inner.length) { sb.append(inner[i + 1]); i += 2 }
                else { sb.append(inner[i]); i++ }
            }
            v = sb.toString()
        }
        val parts = v.split('|', limit = 3)
        if (parts.size != 3) return null
        val (type, session, window) = parts
        if (type !in TYPES || session.isEmpty()) return null
        return Alert(type, session, window)
    }
}
