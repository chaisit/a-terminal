package dev.aterminal.render.input

import dev.aterminal.engine.term.TermLine

/**
 * BufferText — แปลง cells ของ :engine เป็นข้อความ copy ≡ xterm.js 5.5.0 getSelection (round 11 T2)
 *
 * mirror ตาม source ที่อ่านจริงจาก bundle/headless 5.5.0 (ห้ามจำ — ดู header ของ tools/vt/selection-gold.mjs):
 *  - translateToString(trimRight=true, from, to): end = min(to, getTrimmedLength())
 *    โดย getTrimmedLength = cell สุดท้ายที่มี code (≡ raw != null && ch.isNotEmpty()) +1
 *    — code0 blank ฝั่ง engine = 2 ร่างที่ ≡ กัน: raw null (ไม่เคยเขียน, get() ให้ DEFAULT " ")
 *      กับ cell ที่ erase แล้ว (ch "") — ทั้งคู่ "ไม่มี content" ตาม xterm (ต้องเช็คผ่าน raw(), ไม่ใช่ get())
 *  - cell กว้าง (w2) พิมพ์ ch แล้วข้าม dummy ของมันเอง (dummy ไม่เคยโผล่ในข้อความ)
 *  - code0 blank ใน clamp → พิมพ์ " "; space ที่พิมพ์จริง (ch=" " ≡ code32) = มี content → ไม่ trim
 *  - แถว isWrapped แนบ chunk ก่อนหน้า (ไม่มี newline); map(" "→" "); join "\n"
 *  - C0 ใน input ไม่เคยเข้า cell (parser execute เป็น control) — ไม่มีการ map ctrl
 *
 * คุมด้วย SelectionGoldData (gen จาก @xterm/headless 5.5.0 buffer จริง) — ทุกข้อ Above มี case ใน gold
 */
object BufferText {

    private val WORD = Regex("[\\w.~/:\\-@+#]") // ≡ WORD ใน terminal.html (= คลาส double-click ของ xterm, ไม่นับไทย)

    /** ≡ xterm getTrimmedLength (เช็คเฉพาะ code bits — สี/attrs ไม่ช่วย cell รอด) */
    private fun trimmedLength(line: TermLine): Int {
        for (i in line.cols - 1 downTo 0) if (line.raw(i)?.ch?.isNotEmpty() == true) return i + 1
        return 0
    }

    /** ≡ translateToString(trimRight=true, from, to) — col index ตรงกับ cell (คำ index ≈ col, quirk เดียวกับ JS) */
    fun lineText(line: TermLine, from: Int, to: Int): String {
        val end = minOf(to, line.cols, trimmedLength(line))
        if (end <= from) return ""
        val sb = StringBuilder()
        var i = maxOf(0, from)
        while (i < end) {
            val c = line.raw(i)
            when {
                c == null -> { sb.append(' '); i++ } // unwritten ≡ code0 w1 → " " (ใน clamp เท่านั้น)
                c.width >= 2 -> { sb.append(c.ch); i += 2 }
                c.width == 1 -> { sb.append(if (c.ch.isEmpty()) " " else c.ch); i++ }
                else -> i++ // dummy w0 — ปกติถูก w2 jump ข้ามไปก่อนแล้ว
            }
        }
        return sb.toString()
    }

    /** ข้อความทั้ง selection (≡ getSelection) — "" ถ้าไม่มี selection; col ขวาเป็น inclusive (ตาม SelectionModel) */
    fun extract(lines: List<TermLine>, sel: SelectionModel): String {
        if (!sel.active) return ""
        if (lines.isEmpty()) return ""
        val n = sel.normalized()
        // eviction defence: abs row เก่าอาจถูก scrollback กลืนระหว่าง gesture (xterm.js มี issue เดียวกัน —
        // mirror = ไม่ขยับ selection; ที่นี่แค่ clamp ไม่ให้ index พัง)
        val r0 = minOf(n[1], lines.size - 1); val r1 = minOf(n[3], lines.size - 1)
        val sb = StringBuilder()
        for (r in r0..r1) {
            if (r > r0 && !lines[r].isWrapped) sb.append('\n')
            val from = if (r == r0) n[0] else 0
            val to = if (r == r1) n[2] + 1 else lines[r].cols
            sb.append(lineText(lines[r], from, to))
        }
        return sb.toString().replace('\u00a0', ' ') // ≡ map(chunk.replace(p," ")) — ต่อ chunk หรือ global เท่ากัน (char-local)
    }

    /**
     * T6: map (strCol, strLen) ของ Finder (index ใน lineText) → cell range [first..lastInclusive]
     * rule เดียวกับ lineText: null/unwritten=w1 " ", w2 พิมพ์ 1 อักษรข้าม dummy, w0 dummy ข้าม
     * (JS ส่ง string offset ให้ select ตรง ๆ ≡ quirk เดิม — ที่นี่ map เป็น cell จริง = selection tint ตรง)
     * คืน null เมื่อ strCol เลย trimRight (match หายเพราะ buffer เปลี่ยนหลัง recompute ค้าง)
     */
    fun cellSpan(line: TermLine, strCol: Int, strLen: Int): IntRange? {
        val end = minOf(line.cols, trimmedLength(line))
        var i = 0
        var s = 0
        while (i < end && s < strCol) {
            val c = line.raw(i)
            val w = c?.width ?: 1
            if (w == 0) { i++; continue }
            i += if (w >= 2) 2 else 1
            s++
        }
        if (s < strCol) return null
        val first = i
        var consumed = 0
        var j = i
        while (j < end && consumed < strLen) {
            val c = line.raw(j)
            val w = c?.width ?: 1
            if (w == 0) { j++; continue }
            consumed++
            j += if (w >= 2) 2 else 1
        }
        if (consumed == 0) return null
        // inclusive = คอลัมน์สุดท้ายที่ covered รวม dummy ท้าย wide (tint ทับ wide cell ทั้งบาน ≡ xterm paint)
        return first..(j - 1)
    }

    /**
     * กติกา wordAt ≡ terminal.html: line = ข้อความ trimRight ทั้งแถว, c = min(col, len-1),
     * cell ไม่ใช่ WORD class → null; ขยายสองข้างด้วย WORD. คืน (start, endInclusive)
     */
    fun wordAt(lineText: String, col: Int): Pair<Int, Int>? {
        val c = minOf(col, lineText.length - 1)
        if (c < 0 || !WORD.matches(lineText[c].toString())) return null
        var a = c; var e = c
        while (a > 0 && WORD.matches(lineText[a - 1].toString())) a--
        while (e + 1 < lineText.length && WORD.matches(lineText[e + 1].toString())) e++
        return a to e
    }
}
