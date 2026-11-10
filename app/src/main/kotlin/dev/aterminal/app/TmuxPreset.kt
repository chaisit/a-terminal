package dev.aterminal.app

/**
 * ticket tmux/04: "tmux preset" = commandline แนะนำ (ต่อจาก ticket 01) + snippet `.tmux.conf`
 * ที่ app generate ให้ **copy เท่านั้น** (ห้ามเขียนไฟล์ user — user เป็นผู้ paste เอง, เครื่อง user is the owner)
 *
 * gating: set-titles/mouse เป็น tmux ยุค 1.x+ — ปลอดภัยกับทุก version ที่ app รองรับ
 * section alert (07) = form ที่วัดผ่านบน 3.4/3.6; server เก่ากว่าที่ set-hook ไม่รับ = tmux พิมพ์ error
 * ตอน source แล้วข้ามบรรทัดนั้น (conf ไม่พังทั้งไฟล์, feature แค่ไม่ทำงาน — user เห็น error ใน tmux เอง)
 */
object TmuxPreset {

    /** recipe continuity ของ admin/devops (ticket 01): ทุก redial กลับเข้า session เดิม */
    const val commandline = "tmux new -A -s main"

    /**
     * exact text ที่ user copy (test คุมต่อแถว) — ticket 07: section alert เป็นบรรทัดใช้งานจริง
     *
     * form Z = รูปแบบเดียวที่ผ่าน config-file parser (lab 11-06: บล็อก `{ }` ใช้ใน hook string ไม่ได้ = เงียบทั้ง hook;
     * วัด payload จริง `bell|alert-test|bash` บน 3.4) — channel/option ดึงจาก TmuxAlerts ตรง ๆ กัน drift
     *  - `monitor-bell on` (global window option): off = alert-bell hook ไม่ยิงบน window นั้น (วัด lab เช่นกัน)
     *  - `bell-action any`: bell จาก window อื่นก็ raise alert (default = only current window)
     * activity/silence ยังไม่ติด (ยังไม่ได้วัด semantics ด้วย client ที่ attach จริง — deferred ในตั๋ว 07; TYPES ฝั่ง parser รองรับไว้แล้ว)
     */
    val snippet: String = """
        # A-Terminal tmux preset — paste into ~/.tmux.conf
        set -g set-titles on
        set -g set-titles-string '#S:#I:#W'
        set -g mouse on

        # clipboard → Android (ticket 03): default `external` = pane OSC 52 ถูกทิ้ง, ต้อง on
        set -s set-clipboard on
        set -as terminal-features 'xterm*:clipboard'

        # alerts → Android notifications (bell): payload ลง option แล้วปลุก wait-for ของ app
        set -gw monitor-bell on
        set -g bell-action any
        set -g visual-bell off
        set-hook -ag alert-bell 'run-shell "tmux set -g ${dev.aterminal.core.TmuxAlerts.PAYLOAD_OPTION} \"bell|#S|#W\"; tmux wait-for -S ${dev.aterminal.core.TmuxAlerts.CHANNEL}"'
    """.trimIndent()

    /**
     * ticket tmux/05: แถว quick-keys ขณะ prefix pending (grill Q4 = ชุดเดิม 11-05; **ขยาย 11-06**
     * เพิ่ม `c [ , s` ตาม tmux usage research 2026-11-06 (primary sources: tmux key-bindings.c + man + public configs) — user อนุมัติชุด+ลำดับใหม่)
     * `afterPrefix` = bytes ที่ตามหลัง prefix byte (ตัวคำสั่ง raw / CSI ทั้ง sequence สำหรับลูกศร —
     * tmux อ่าน `C-b ESC[C` เป็น prefix + Right = select-pane)
     * digits ไม่อยู่ในแถวนี้ = พิมพ์จาก Gboard ผ่าน funnel ตรง (C-b แล้วเลข = choose-window ตาม bind)
     * กลุ่มที่กันออกโดยจงใจ (0-9 q o ! ( ) t) — เหตุผลเต็มในงานวิจัย ห้ามเพิ่มโดยไม่ผ่านหลักฐาน
     *
     * **ลำดับ = fold-priority ไม่ใช่ grouping** (review 11-06): [KeysBarFit.widths] นับทุกปุ่มรวม prefix
     * และตัด head = k−1 (k = floor(body/44)) — จอมือถือ portrait จริง (body ≈ 222–275dp) เห็นแค่
     * prefix + ~4 ปุ่ม, หางทั้งหมดลง popup `⋯` (T5 พิสูจน์แล้วว่า usable) → ปุ่มแรก ๆ ต้องเป็น
     * ตัวที่ want-on-screen เสมอ: `% c d z` แล้วตามด้วย nav/pane ops, ` , ` (rename, one-shot) and
     * arrows (tap แลก pane ได้) อยู่หางแถวพับก่อน
     */
    data class QuickKey(val label: String, val afterPrefix: String)

    val quickKeys: List<QuickKey> = listOf(
        QuickKey("%", "%"),   // split window (vertical)
        QuickKey("c", "c"),   // new window (strong tier: ทุก config study preserve)
        QuickKey("d", "d"),   // detach (exec จบ exit 0 = Normal ตาม tmux/01 contract) — คีย์ escape ต้องไม่พับ
        QuickKey("z", "z"),   // zoom pane
        QuickKey("n", "n"),   // next window
        QuickKey("p", "p"),   // previous window
        QuickKey("w", "w"),   // choose-windows
        QuickKey("x", "x"),   // kill pane (tmux ถาม confirm เอง)
        QuickKey("[", "["),   // copy-mode = ทางเดียวถึง scrollback เมื่อ server ไม่เปิด mouse (default off)
        QuickKey("s", "s"),   // choose-tree (session list — ครอบคลุม ( ))
        QuickKey(",", ","),   // rename window (one-shot ต่อ window = พับได้)
        QuickKey("←", "\u001B[D"),
        QuickKey("↑", "\u001B[A"),
        QuickKey("↓", "\u001B[B"),
        QuickKey("→", "\u001B[C"),
    )
}
