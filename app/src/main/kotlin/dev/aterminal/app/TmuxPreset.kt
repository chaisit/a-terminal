package dev.aterminal.app

/**
 * ticket tmux/04: "tmux preset" = commandline แนะนำ (ต่อจาก ticket 01) + snippet `.tmux.conf`
 * ที่ app generate ให้ **copy เท่านั้น** (ห้ามเขียนไฟล์ user — user เป็นผู้ paste เอง, เครื่อง user is the owner)
 *
 * gating: ทุกบรรทัดใน snippet เป็น tmux syntax ยุค 1.x+ (set-titles/mouse) — ใช้ได้กับทุก version
 * ที่ app รองรับ (probe ต่ำกว่า 2.6 = feature ฝั่ง app เงียบ แต่ config ไม่พัง)
 */
object TmuxPreset {

    /** recipe continuity ของ admin/devops (ticket 01): ทุก redial กลับเข้า session เดิม */
    const val commandline = "tmux new -A -s main"

    /**
     * exact text ที่ user copy (test คุมต่อแถว) — section "alert" มาแบบ comment วันนี้
     * เพื่อ user ที่ paste รอบนี้ไม่ต้องกลับมา paste ใหม่อีกรอบเมื่อ ticket 07 มาถึง (Q4)
     */
    val snippet: String = """
        # A-Terminal tmux preset — paste into ~/.tmux.conf
        set -g set-titles on
        set -g set-titles-string '#S:#I:#W'
        set -g mouse on

        # alert bells/hooks — uncomment when A-Terminal ships notification hooks (ticket 07)
        # set -g bell-action any
        # set -g visual-bell off
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
