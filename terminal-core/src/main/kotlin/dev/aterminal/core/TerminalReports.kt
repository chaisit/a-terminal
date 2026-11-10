package dev.aterminal.core

/**
 * ticket tmux/03: คำตอบสำหรับ query ที่ terminal ถูกลูกค้า (tmux/vim) ยิงมา — pure builders, ไม่มี I/O
 *
 * ค่าทั้งหมดยึด xterm.js 5.5 vendored bundle เป็น oracle (วัดจาก bytes ใน `app/src/main/assets/xterm/xterm.js`)
 * เพื่อให้ webview/native ตอบเหมือนกัน bit-for-bit:
 *  - DA1 (`ESC[?1;2c`) / DA2 (`ESC[>0;276;0c`, xterm 276) — :engine ตอบเองใน csi/csiDec (test ของมัน lock
 *    byte; ฝั่ง webview xterm.js ตอบเอง — ไม่มี builder ที่นี่ย้อนขึ้นไปแตะ DA (DA2 276 ไม่ได้ unlock อะไร
 *     ใน tmux 3.4/3.6 — clipboard auto-detect ผูกกับ default terminal-features ไม่ใช่ DA2 ของเรา)
 *  - colour report: `toRgbString(channels, 16)` ของ xterm.js = `rgb:` + channel ละ 4 hex โดย byte ซ้ำคู่
 *    (0x12 → `1212`; equals ×257) และปิดท้ายด้วย **ST** (`ESC backslash`) — ต่างจาก OSC52 reply ที่ tmux
 *    terminfo `Ms` ลงท้าย BEL; ทั้ง tmux/xterm รับ ST เป็น default terminator ของ OSC อยู่แล้ว
 *  - code: 10=foreground, 11=background, 12=cursor; OSC4 (palette index) ใช้ [indexedColorReport]
 *
 * caller (MainActivity/SessionBridge, terminal.html) เป็นผู้ deciding ว่าจะตอบ code ไหนด้วยสีอะไร —
 * ตัวนี้สร้าง string เท่านั้น; input สีผิดรูป = null (ห้ามส่ง report พัง)
 */
object TerminalReports {

    private val HEX6 = Regex("^#?([0-9a-fA-F]{6})$")

    /** `#rrggbb` (leading `#` แล้วแต่) → triple `rrrr/gggg/bbbb` (lowercase, ×257/byte โดย byte ซ้ำคู่) */
    fun toRgb16(color: String): String? {
        val m = HEX6.matchEntire(color.trim()) ?: return null
        val h = m.groupValues[1].lowercase()
        return (0..2).joinToString("/") { i ->
            val byte = h.substring(i * 2, i * 2 + 2)
            byte + byte // xterm.js `s+s`: 8-bit → 16-bit by duplication
        }
    }

    /** color report หรือ null ถ้าสีพัง — ใช้ตัวนี้เมื่อสีมาจาก user/theme ที่เชื่อไม่ได้ 100% */
    fun colorReportOrNull(code: Int, color: String): String? {
        val rgb = toRgb16(color) ?: return null
        return report("$code", rgb)
    }

    /** OSC 4 reply: `ESC]4;<index>;rgb:...ESC\` — index ติดไปด้วยตามคำ query (nullable — สีมาจาก user scheme ที่ user แก้เองได้ ห้าม throw/ห้ามส่ง report พัง) */
    fun indexedColorReportOrNull(index: Int, color: String): String? {
        val rgb = toRgb16(color) ?: return null
        return report("4;$index", rgb)
    }

    // รูป report เดียวกันทุก code: `ESC]<prefix>;rgb:<triple>ESC\` (ST terminators — เหตุผลใน KDoc ข้างบน)
    private fun report(prefix: String, rgb: String): String = "\u001b]$prefix;rgb:$rgb\u001b\\"
}
