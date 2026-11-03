package dev.aterminal.render

import dev.aterminal.core.Scheme
import dev.aterminal.engine.term.Cell

/**
 * StyleResolver — Cell (number space ของ xterm.js) → สี/flag พร้อมวาด (pure JVM, ทดสอบได้)
 * number space is pinned: mode = bits 24-25 →
 *   0=default, 1=C16, 2=C256, 3=RGB (ค่าสี = low 24 bits)
 */
data class RenderStyle(
    val fg: Int,               // ARGB เต็ม (alpha 255)
    val bg: Int,               // ARGB เต็ม; palette.defaultBg เมื่อ cell bg default
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val overline: Boolean,
    val dimAlpha: Boolean,     // วาด fg ด้วย alpha ครึ่ง (xterm.js renderer = globalAlpha 0.5 สำหรับ dim)
)

class Palette(
    val colors16: IntArray,    // indices 0..15 (ARGB)
    val defaultFg: Int,
    val defaultBg: Int,
    val cursor: Int,
    // WT Scheme.selectionBackground — overlay selection/mark/find (round 11 T2); default = ค่าเดียวกับ settings.defaults.json
    val selectionBg: Int = SELECTION_DEFAULT,
) {
    val colors: IntArray = IntArray(256) { i ->
        when {
            i < 16 -> colors16[i]
            i < 232 -> { // xterm 6x6x6 cube — levels เดียวกับ xterm.js (0,95,135,175,215,255)
                val v = i - 16
                val lv = intArrayOf(0, 95, 135, 175, 215, 255)
                rgb(lv[v / 36], lv[v % 36 / 6], lv[v % 6])
            }
            else -> { val g = 8 + 10 * (i - 232); rgb(g, g, g) }
        }
    }

    /** fg/bg ของ cell (mode = bits 24-25 ของ fgMode/bgMode ตาม number space — เทียบค่า shift จริง, ไม่ใช่ 1/2/3) */
    fun fgOf(color: Int, mode: Int, fallback: Int): Int = when (mode and RGB_MODE_MASK) {
        0 -> fallback
        1 shl 24, 2 shl 24 -> colors[color and 0xFF]
        else -> 0xFF000000.toInt() or (color and 0xFFFFFF)
    }

    companion object {
        const val RGB_MODE_MASK = 3 shl 24
        const val SELECTION_DEFAULT = 0xFF3A3A3A.toInt()
        fun rgb(r: Int, g: Int, b: Int) = (0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)

        /** hex "#RGB" | "#RRGGBB" — schema WT ใช้สอง form */
        fun parseHex(s: String, fallback: Int): Int {
            val h = s.removePrefix("#")
            return runCatching {
                when (h.length) {
                    3 -> rgb(h[0].digitToInt(16) * 17, h[1].digitToInt(16) * 17, h[2].digitToInt(16) * 17)
                    6 -> rgb(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
                    else -> fallback
                }
            }.getOrDefault(fallback)
        }

        fun fromScheme(s: Scheme): Palette {
            val c16 = intArrayOf(
                parseHex(s.black, 0), parseHex(s.red, 0), parseHex(s.green, 0), parseHex(s.yellow, 0),
                parseHex(s.blue, 0), parseHex(s.purple, 0), parseHex(s.cyan, 0), parseHex(s.white, 0),
                parseHex(s.brightBlack, 0), parseHex(s.brightRed, 0), parseHex(s.brightGreen, 0), parseHex(s.brightYellow, 0),
                parseHex(s.brightBlue, 0), parseHex(s.brightPurple, 0), parseHex(s.brightCyan, 0), parseHex(s.brightWhite, 0),
            )
            return Palette(
                c16,
                parseHex(s.foreground, 0xFFCCCCCC.toInt()),
                parseHex(s.background, 0xFF0C0C0C.toInt()),
                parseHex(s.cursorColor ?: s.foreground, 0xFFCCCCCC.toInt()),
                parseHex(s.selectionBackground ?: "#3A3A3A", SELECTION_DEFAULT),
            )
        }
    }
}

object StyleResolver {
    /**
     * inverse = สลับ fg/bg โดยค่า default ฝั่งตรงข้าม (xterm.js renderer: inverse แล้ว default fg/bg swap กัน);
     * B_PROTECT (512) ไม่ใช่ style — mask ทิ้งก่อนเทียบ (กัน run แตกเพราะ bit ที่มองไม่เห็น)
     */
    fun resolve(cell: Cell, pal: Palette): RenderStyle {
        val at = cell.attrs and 0x1FF
        val bold = at and Cell.B_BOLD != 0
        val inverse = at and Cell.B_INVERSE != 0
        // ≡ xterm.js canvas _getForegroundColor (device-proven 2026-11-03):
        // swap ก่อน (inverse) แล้ว brighten เฉพาะ fg ที่วาด เมื่อโหมด C16 idx<8 + bold (drawBoldTextInBrightColors=true)
        // C256/RGB ไม่แตะ — วัดจริง: [1;38;5;1m ≡ [38;5;1m บน webview
        val fgMode = if (inverse) cell.bgMode else cell.fgMode
        val fgIdx0 = if (inverse) cell.bg else cell.fg
        // เงื่อนไข brighten เดียวกับ xterm: C16 idx<8 + bold เท่านั้น (แยกตัวแปรไว้กันอ่านผิดเป็น "bold = +8 ทุกกรณี")
        val brighten = bold && fgMode == 1 shl 24 && fgIdx0 in 0..7
        // default (mode 0) resolves ตาม "ฝั่งที่มาจาก storage": inverse แล้ว fg ที่วาดมาจาก bg slot → defaultBg
        val fg = pal.fgOf(if (brighten) fgIdx0 + 8 else fgIdx0, fgMode,
            if (inverse) pal.defaultBg else pal.defaultFg)
        val bg = pal.fgOf(if (inverse) cell.fg else cell.bg, if (inverse) cell.fgMode else cell.bgMode,
            if (inverse) pal.defaultFg else pal.defaultBg)
        return RenderStyle(
            fg = fg, bg = bg,
            bold = at and Cell.B_BOLD != 0,
            italic = at and Cell.B_ITALIC != 0,
            underline = at and Cell.B_UNDERLINE != 0,
            strike = at and Cell.B_STRIKE != 0,
            overline = at and Cell.B_OVERLINE != 0,
            dimAlpha = at and Cell.B_DIM != 0,
        )
    }

    /** ค่า default ของ cell ว่าง (erase/space) — เร็วกว่า resolve ทั้งก้อน */
    fun defaultStyle(pal: Palette) = RenderStyle(pal.defaultFg, pal.defaultBg, false, false, false, false, false, false)
}
