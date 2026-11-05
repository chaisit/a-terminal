package dev.aterminal.render

import dev.aterminal.engine.term.Cell
import dev.aterminal.engine.term.TermLine

/** 1 กลุ่ม cell ติดกันที่ style เดียวกัน วาดได้ด้วยการ draw ครั้งเดียว (startCol inclusive, endCol exclusive) */
data class Run(val text: String, val style: RenderStyle, val startCol: Int, val endCol: Int)

/**
 * run-merge (pure JVM — ทดสอบ level นี้): แถว cell → runs
 *  - dummy (w0) = ครึ่งขวาของ wide — ข้าม, แต่ห้ามให้ run ข้ามมันไป join (adjacency = i == nextCol)
 *  - wide (w2) = run ของตัวเองเสมอ (advance ≠ cellW — shaper/drawText ห้ามกินข้าม grid cell ผสม ASCII)
 *  - style เทียบ data-class equality (protect bit มองไม่เห็นใน RenderStyle แล้ว)
 *  - ch "" (erase blank/code0) → " " ตาม dump normalization
 */
object RunMerger {
    fun merge(row: List<Cell>, pal: Palette): List<Run> = mergeFrom(row.size, { row[it] }, pal)

    /**
     * ทั้งแถว — อ่าน cell ตรงจาก [TermLine] (`get(i)` คืน DEFAULT singleton เมื่อยังไม่เขียน, read-only)
     * perf 11-04: ไม่ build List<Cell> กลางอีก (แถวละ ArrayList 80 slots × ทุกแถว × ทุกเฟรม = garbage
     * ที่ view ต้องเก็บทุก 16ms) — lambda ของ mergeFrom ถูก inline จึงไม่มี allocation ทั้งสองอย่าง
     */
    fun mergeLine(line: TermLine, pal: Palette): List<Run> = mergeFrom(line.cols, { line.get(it) }, pal)

    /**
     * ลูป merge เดียวสำหรับทั้งสอง cell source (List = tests/อนาคต, TermLine = view)
     *
     * style cache: resolve เฉพาะเมื่อ input ของ style บน cell เปลี่ยน (fg/fgMode/bg/bgMode/attrs ที่
     * mask แล้ว) — resolve เป็น pure function ของ field ชุดนี้ + palette คงที่ตลอด call หนึ่งครั้ง
     * ⇒ ได้ RenderStyle ใบเดิมเป๊ะ ⇒ ผลลัพธ์ ≡ เดิมทุกบิต แต่จำนวน object ลดจาก **1 ต่อ cell**
     * (80×24 = 1920 ใบต่อเฟรม) เป็น 1 ต่อ run ซึ่งคือ garbage หลักของ onDraw
     */
    private inline fun mergeFrom(size: Int, cellAt: (Int) -> Cell, pal: Palette): List<Run> {
        val out = ArrayList<Run>()
        var sb: StringBuilder? = null
        var st: RenderStyle? = null // style ของ run ที่กำลัง build = style ของ cell ก่อนหน้า (null ก่อน cell แรก)
        var start = 0
        var end = 0
        var next = -1 // col ถัดไปที่ ASCII run ปัจจุบันจะรับ join ได้
        // input ของ style บน cell ก่อนหน้า — เทียบตรง ๆ 5 ช่อง (ไม่มี hash = ไม่มี collision)
        var pFg = 0
        var pFgMode = 0
        var pBg = 0
        var pBgMode = 0
        var pAttrs = 0
        var havePrev = false
        for (i in 0 until size) {
            val c = cellAt(i)
            if (c.width == 0) continue
            val attrs = c.attrs and StyleResolver.STYLE_ATTRS
            val prev = st
            val s = if (havePrev && prev != null && c.fg == pFg && c.fgMode == pFgMode &&
                c.bg == pBg && c.bgMode == pBgMode && attrs == pAttrs
            ) prev else StyleResolver.resolve(c, pal)
            // B_INVISIBLE (SGR 8) = glyph ซ่อนทั่ว body (≡ xterm — bg/width คง, cell ไม่หาย)
            val text = if (c.ch.isEmpty() || c.attrs and Cell.B_INVISIBLE != 0) " " else c.ch
            if (sb != null && c.width == 1 && s == st && i == next) {
                sb!!.append(text); end = i + 1; next = end
            } else {
                sb?.let { b -> st?.let { out.add(Run(b.toString(), it, start, end)) } }
                sb = StringBuilder(text); st = s; start = i
                end = i + (if (c.width == 2) 2 else 1)
                next = if (c.width == 2) -1 else end // wide = ปิด run เสมอ (advance ≠ cellW)
            }
            pFg = c.fg; pFgMode = c.fgMode; pBg = c.bg; pBgMode = c.bgMode; pAttrs = attrs
            havePrev = true
        }
        sb?.let { b -> st?.let { out.add(Run(b.toString(), it, start, end)) } }
        return out
    }
}
