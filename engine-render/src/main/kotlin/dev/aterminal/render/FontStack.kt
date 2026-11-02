package dev.aterminal.render

/**
 * FontStack — parse stacks แบบ CSS (`"Cascadia Mono, JetBrains Mono, monospace"`) → รายชื่อ family (round 11 T6)
 *
 * บทเรียน T6: `Typeface.create("Cascadia Mono")` ไม่ throw เมื่อ font ไม่ถูก bundle — มันให้ DEFAULT (Roboto)
 * แบบเงียบ ๆ ทำให้ cellW (วัดจาก M=31) ต่างจาก advance จริง (20) = glyphs ลอยซ้ายทั้ง grid
 * ⇒ ต้องเลือก family ที่ "mono จริง" เท่านั้น (ตรวจด้วย advance ของ i/M/W — ดู TerminalView.resolveTypeface)
 */
object FontStack {

    /** null/blank = stack ว่าง (ผู้เรียก fallback เป็น MONOSPACE ของระบบ) */
    fun families(fontFace: String?): List<String> =
        fontFace?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
