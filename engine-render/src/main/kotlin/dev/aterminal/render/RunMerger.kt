package dev.aterminal.render

import dev.aterminal.engine.term.Cell

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
    fun merge(row: List<Cell>, pal: Palette): List<Run> {
        val out = ArrayList<Run>()
        var sb: StringBuilder? = null
        var st: RenderStyle? = null
        var start = 0
        var end = 0
        var next = -1 // col ถัดไปที่ ASCII run ปัจจุบันจะรับ join ได้
        for (i in row.indices) {
            val c = row[i]
            if (c.width == 0) continue
            val s = StyleResolver.resolve(c, pal)
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
        }
        sb?.let { b -> st?.let { out.add(Run(b.toString(), it, start, end)) } }
        return out
    }

    /** ทั้งแถว (TermLine → List<Cell>) — get(i) คืน DEFAULT singleton เมื่อยังไม่เขียน (read-only) */
    fun mergeLine(line: dev.aterminal.engine.term.TermLine, pal: Palette): List<Run> {
        val cells = ArrayList<Cell>(line.cols)
        for (i in 0 until line.cols) cells.add(line.get(i))
        return merge(cells, pal)
    }
}
