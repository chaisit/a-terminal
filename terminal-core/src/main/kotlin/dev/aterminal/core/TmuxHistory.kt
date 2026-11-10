package dev.aterminal.core

/**
 * ticket tmux/08: ดึง history ที่เก่ากว่า app scrollback ผ่าน `capture-pane` (one-shot exec ของตั๋ว 06)
 *
 * wire ที่วัดจริงบน lab 11-07 (3.4 t06 + 3.6 t03 — เหมือนทุกข้อ, หลักฐานในตั๋ว):
 *  - `-S`/`-E` anchored ที่ **ขอบบน viewport**: ค่าลบ = display lines เหนือขอบ, `0` = บรรทัดแรกของ viewport
 *    (ไม่ใช่ top of history — เข้าใจผิดรอบแรกเพราะ `-S 0` คืนบรรทัด visible) → window on top of history = `-S -N`
 *  - `-E -1` = inclusive ถึงบรรทัดสุดท้ายของ history (หน้าต่าง "pinned at tail" — ขอบล่างไม่ไถลเมื่อ pane มี output ใหม่)
 *  - default capture = DISPLAY lines (wrap fragment แยกบรรทัด,ภาษาไทยขาด mid-cluster ได้);
 *    `-J` join fragment กลับเป็น logical line (ทั้งสองรุ่น, robust ข้าม reflow) — เราใช้ `-J` เสมอ
 *  - `history_size` = DISPLAY lines และ **ไม่เสถียรข้าม resize** (reflow 21→22→21) → caller ทำ generation bump
 *  - capture output = logical lines ครบ (ไม่มี trailing padding) — inject เป็น plain text, re-wrap ตามความกว้าง app (by design)
 *
 * paging: หน้าต่างเดียวต่อ pull = `-S -(appDepth+page) -E -1` (pinned tail) แล้ว `dropLast(appLogicalDepth)`
 * — เส้น overlap นับเป็น **logical lines** (isWrapped ฝั่ง app) ไม่ใช่ display; ขอบที่ offset ผิดเพราะ pane
 * ถูก client อื่น resize = ยอมรับ drift ±1 บรรทัดที่ขอบ (user ตัดสิน Q3: continuous > edge-exact)
 * overflow (exec cap 1MB) = ทิ้งทั้งก้อน ห้าม inject partial + `nextPageSize` halve ครั้งถัดไป (Q2)
 */
object TmuxHistory {

    const val DEFAULT_PAGE_LINES = 2_000 // Q2 (user อนุมัติ): 38KB/2007 บรรทัด บน 80-col — ปลอดภัยใต้ exec cap
    const val MIN_PAGE_LINES = 250       // floor ของ halving — ต่ำกว่านี้ gesture ไม่คุ้มค่า exec

    // identity ที่ app สร้างเองเท่านั้น (preset `new -A -s main`, browser `attach -t '$N'`) — custom commandline = null (fail-soft)
    private val NEW_SESSION = Regex("^tmux new\\b[^;|&]*?\\s-s\\s(\\S+)\\s*$")
    private val ATTACH_TARGET = Regex("^tmux attach\\b[^;|&]*?\\s-t\\s'([^']+)'\\s*$")
    private val SAFE_TARGET = Regex("^(\\$\\d+|[A-Za-z0-9_.-]{1,64})$")

    /** session identity ของ tab นี้จาก commandline (`$N` หรือ safe name) — เพี้ยน/unsafe = null (caller fallback เป็น no-target capture) */
    fun sessionFromCommandline(cmdline: String): String? {
        val raw = NEW_SESSION.find(cmdline)?.groupValues?.get(1)
            ?: ATTACH_TARGET.find(cmdline)?.groupValues?.get(1)
            ?: return null
        return raw.takeIf { SAFE_TARGET.matches(it) }
    }

    /** `-t` ครอบ single-quote ตาม identity rule (TmuxListing เดิม); null = server current target (single-session presunt) */
    private fun targetArg(target: String?): String {
        if (target == null) return ""
        require(SAFE_TARGET.matches(target)) { "target must be \$N or [A-Za-z0-9_.-]{1,64}: $target" }
        return " -t '$target'"
    }

    fun historySizeCommand(target: String?): String =
        "tmux display -p${targetArg(target)} '#{history_size}'"

    /** stdout ของ display = integer เดียว (trim); output อื่น ("no server running") = null */
    fun parseHistorySize(stdout: String?): Int? {
        val line = stdout?.trim() ?: return null
        if (line.isNotEmpty() && line.all { it.isDigit() }) line.toIntOrNull()?.let { return it }
        return null
    }

    /**
     * window pinned ที่ tail of history: `-S -windowAbove` (display lines เหนือขอบบน viewport) ถึง `-E -1`
     * ผลลัพธ์ = logical lines (`-J`) ของช่วงนั้น — จำนวนจริงอาจน้อยกว่า windowAbove เมื่อ on top of history
     */
    fun captureCommand(target: String?, windowAbove: Int): String {
        require(windowAbove >= 1) { "windowAbove must cover at least one line: $windowAbove" }
        return "tmux capture-pane -p -J${targetArg(target)} -S -$windowAbove -E -1"
    }

    /** stdout ของ capture (`-p`) =บรรทัด terminating ด้วย `\n`; history ว่าง = stdout ว่าง; บรรทัดว่างกลาง history = ข้อมูลจริง ห้าม drop */
    fun captureLines(stdout: String): List<String> =
        if (stdout.isEmpty()) emptyList()
        else stdout.split("\n").let { if (it.last() == "") it.dropLast(1) else it }

    /** exec overflow = page ใหญ่เกิน cap → ครึ่งหนึ่ง floor MIN; ปกติ = คงเดิม */
    fun nextPageSize(current: Int, overflow: Boolean): Int =
        if (!overflow) current else maxOf(current / 2, MIN_PAGE_LINES)

    /** app (logical lines) ยังไม่เคยเห็นของเก่ากว่านี้ = มีอะไรให้ pull — unit ผิดเป็น direction ปลอดภัย: logical ≤ display จึง pulled too much บ้าง (no-op planInjection) ไม่เคย too little */
    fun hasDeeperHistory(appLogicalDepth: Int, historySize: Int): Boolean =
        appLogicalDepth < historySize

    /**
     * display lines เหนือขอบ viewport ที่ต้อง capture: display ที่ overlap ฝั่ง app กินจริงใน pane + หนึ่ง page, clamp ที่ history_size
     * (unit = display ของ *pane*: `-J` ห่อกลับแล้ว count หาย — caller วัด/learn consumption ratio, windowเป็น display; overlap drop เป็น logical — ดู planInjection)
     */
    fun windowAbove(overlapDisplay: Int, page: Int, historySize: Int): Int =
        minOf(overlapDisplay + page, historySize)

    /**
     * บรรทัดที่จะ inject เข้า scrollback (เก่า→ใหม่, head ของ window): ทิ้งท้าย = overlap ที่ app มีอยู่แล้ว
     * fetched สั้นกว่า overlap = ไม่มีของใหม่ใน window นี้ (caller ควรเช็ค hasDeeperHistory ก่อน)
     */
    fun planInjection(fetched: List<String>, appLogicalDepth: Int): List<String> =
        if (fetched.size <= appLogicalDepth) emptyList()
        else fetched.subList(0, fetched.size - appLogicalDepth).toList()
}
