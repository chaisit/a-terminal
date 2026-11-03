package dev.aterminal.render

import dev.aterminal.core.Scheme
import dev.aterminal.engine.term.Cell
import org.junit.Assert.assertEquals
import org.junit.Test

class StyleResolverTest {
    private val pal = Palette(
        colors16 = IntArray(16) { TerminalView.DEFAULT16[it] },
        defaultFg = 0xFF112233.toInt(), defaultBg = 0xFF445566.toInt(), cursor = 0xFFEEFF00.toInt(),
    )
    private val C16 = 1 shl 24
    private val C256 = 1 shl 25
    private val RGB = 3 shl 24

    @Test fun defaultCellUsesPaletteDefaults() {
        val s = StyleResolver.resolve(Cell("a"), pal)
        assertEquals(0xFF112233.toInt(), s.fg); assertEquals(0xFF445566.toInt(), s.bg)
    }

    @Test fun c16AndC256IndexTable() {
        assertEquals(TerminalView.DEFAULT16[4], StyleResolver.resolve(Cell("a", 1, 4, C16), pal).fg)
        // C256 index 196 = cube: v=180 → r=lv[5]=255, g=lv[0]=0? v/36=5,g=(180%36)/6=0,b=0 → #FF0000
        assertEquals(0xFFFF0000.toInt(), StyleResolver.resolve(Cell("a", 1, 196, C256), pal).fg)
        // grayscale 238 = 8 + 10*6 = 68 → #444444
        assertEquals(0xFF444444.toInt(), StyleResolver.resolve(Cell("a", 1, 238, C256), pal).fg)
    }

    @Test fun rgbTrueColor() {
        assertEquals(0xFFABCDEF.toInt(), StyleResolver.resolve(Cell("a", 1, 0xABCDEF.toInt(), RGB), pal).fg)
    }

    @Test fun inverseSwapsFgBg() {
        val s = StyleResolver.resolve(Cell("a", 1, attrs = Cell.B_INVERSE), pal)
        assertEquals(0xFF445566.toInt(), s.fg); assertEquals(0xFF112233.toInt(), s.bg)
        // inverse + fg สี → bg เป็นสีนั้น
        val s2 = StyleResolver.resolve(Cell("a", 1, 2, C16, attrs = Cell.B_INVERSE), pal)
        assertEquals(0xFF445566.toInt(), s2.fg); assertEquals(TerminalView.DEFAULT16[2], s2.bg)
    }

    @Test fun protectBitInvisibleInStyle() {
        val a = StyleResolver.resolve(Cell("x", 1, attrs = Cell.B_PROTECT), pal)
        val b = StyleResolver.resolve(Cell("x", 1), pal)
        assertEquals(b, a) // run ไม่แตกเพราะ protect
    }

    @Test fun attrFlagsMap() {
        val s = StyleResolver.resolve(Cell("x", 1, attrs = Cell.B_BOLD or Cell.B_ITALIC or Cell.B_DIM or Cell.B_UNDERLINE or Cell.B_STRIKE or Cell.B_OVERLINE), pal)
        assertEquals(true, s.bold); assertEquals(true, s.italic); assertEquals(true, s.dimAlpha)
        assertEquals(true, s.underline); assertEquals(true, s.strike); assertEquals(true, s.overline)
    }

    // round 19 D4: xterm.js drawBoldTextInBrightColors (default true, ≡ device sample 2026-11-03)
    // bold + ANSI basic (C16 idx<8) → ใช้ palette[idx+8]; C256/RGB ไม่แตะ (fixture 1;38;5;1 = ไม่สว่าง, วัดจริงแล้ว)
    @Test fun boldBrightensC16Below8() {
        val s = StyleResolver.resolve(Cell("a", 1, 1, C16, attrs = Cell.B_BOLD), pal)
        assertEquals(TerminalView.DEFAULT16[9], s.fg)
    }

    @Test fun boldDoesNotBrightenC16Above7() {
        val s = StyleResolver.resolve(Cell("a", 1, 9, C16, attrs = Cell.B_BOLD), pal)
        assertEquals(TerminalView.DEFAULT16[9], s.fg)
    }

    @Test fun boldDoesNotBrightenC256OrRgb() {
        assertEquals(TerminalView.DEFAULT16[1], StyleResolver.resolve(Cell("a", 1, 1, C256, attrs = Cell.B_BOLD), pal).fg)
        assertEquals(0xFF000000.toInt() or 1, StyleResolver.resolve(Cell("a", 1, 1, RGB, attrs = Cell.B_BOLD), pal).fg)
    }

    @Test fun boldDoesNotBrightenWhenInverseMovesC16ToBackground() {
        // canvas renderer = swap ก่อน แล้ว brighten เฉพาะ fg ที่วาด — inverse fg C16 idx1 → bg ปกติ (not +8)
        val s = StyleResolver.resolve(Cell("a", 1, 1, C16, attrs = Cell.B_BOLD or Cell.B_INVERSE), pal)
        assertEquals(TerminalView.DEFAULT16[1], s.bg)
        assertEquals(0xFF445566.toInt(), s.fg) // defaultBg ที่ swap เข้า fg: ไม่ใช่ C16 → ไม่ brighten
    }

    @Test fun boldBrightensInverseMovedColorIntoForeground() {
        // bg C16 idx1 + inverse → fg ที่วาดคือ idx1 (โหมด C16) → ต้อง +8
        val s = StyleResolver.resolve(Cell("a", 1, bg = 1, bgMode = C16, attrs = Cell.B_BOLD or Cell.B_INVERSE), pal)
        assertEquals(TerminalView.DEFAULT16[9], s.fg)
    }

    @Test fun schemeHexParsing() {
        val p = Palette.fromScheme(Scheme(name = "t", foreground = "#fff", background = "#000000"))
        assertEquals(0xFFFFFFFF.toInt(), p.defaultFg) // #fff → 3-digit expand (17×f)
        assertEquals(0xFF000000.toInt(), p.defaultBg)
        assertEquals(0xFFCCCCCC.toInt(), Palette.parseHex("junk", 0xFFCCCCCC.toInt())) // bad input = fallback
        assertEquals(0xFFCCCCCC.toInt(), Palette.parseHex("#ccc", 0xFF000000.toInt())) // #ccc == #CCCCCC
    }
}
