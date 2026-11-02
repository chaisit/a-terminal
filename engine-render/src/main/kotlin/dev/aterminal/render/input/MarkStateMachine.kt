package dev.aterminal.render.input

/**
 * MarkStateMachine — semantics ≡ markMode ใน `aterm-keys.js:80–151` (WT spec #4993, round 11 T7)
 *
 *  - เข้า = selection cell เดียวที่ cursor (≡ enterMark+posAbs: col = min(cursorX, cols-1))
 *  - arrows/page/home/end เลื่อน cur (ขยาย selection — anchor ค้าง), linear col wrap ก่อน clamp (JS line order)
 *  - ENTER = copy+exit, ESC = clear+exit, key อื่น = exit แล้วปล่อย byte (unmatched exits ≡ WT)
 *  - pure JVM — selection paint/reveal เป็นของ view (≡ paintMark เรียก term.select + scrollLines)
 */
sealed interface MarkKey {
    object Off : MarkKey               // mode ปิด — caller ส่ง byte ตามปกติ
    object Passthrough : MarkKey       // เปิดแต่ unmatched — caller ต้อง exit(clear) แล้วส่ง byte
    object Exit : MarkKey              // ESC = clear+exit (consume)
    object CopyExit : MarkKey          // \r หรือ \n = copy+clear+exit (consume)
    data class Move(val name: String) : MarkKey // up/down/left/right (consume)
}

class MarkStateMachine {
    var active = false
        private set
    var anchorRow = 0
        private set
    var anchorCol = 0
        private set
    var curRow = 0
        private set
    var curCol = 0
        private set

    /** ≡ posAbs + enterMark: cursorAbsRow = baseY + cursorY (host คำนวณ — view เท่านั้นที่รู้ engine) */
    fun enter(cursorAbsRow: Int, cursorX: Int, cols: Int) {
        anchorRow = cursorAbsRow; anchorCol = minOf(cursorX, cols - 1)
        curRow = anchorRow; curCol = anchorCol
        active = true
    }

    /** ≡ exitMark — clearSel (selection) เป็นของ view; state = ล้าง flag */
    fun exit() { active = false }

    /** ≡ markMove — true = handled (view paint ต่อ); ชื่อ ≡ ตาราง ChordParser/canonicalName */
    fun move(name: String, cols: Int, lastRow: Int, rows: Int): Boolean {
        if (!active) return false
        when (name) {
            "left" -> curCol -= 1
            "right" -> curCol += 1
            "up" -> curRow -= 1
            "down" -> curRow += 1
            "pageup" -> curRow -= rows
            "pagedown" -> curRow += rows
            "home" -> { curRow = 0; curCol = 0 }
            "end" -> { curRow = lastRow; curCol = cols - 1 }
            else -> return false
        }
        // wrap แบบ linear buffer ก่อน แล้วจึง clamp (ลำดับตาม JS — clamp ก่อน wrap จะต่างผลที่ขอบ)
        if (curCol < 0) { curCol += cols; curRow -= 1 }
        else if (curCol >= cols) { curCol -= cols; curRow += 1 }
        curRow = curRow.coerceIn(0, lastRow)
        curCol = curCol.coerceIn(0, cols - 1)
        return true
    }

    /** ≡ paintMark: linear normalize (cur สลับ anchor ได้) → (startCol,startRow,endColInclusive,endRow) */
    fun selectionSpan(cols: Int): IntArray {
        val a = anchorRow * cols + anchorCol
        val c = curRow * cols + curCol
        val s: Int; val e: Int
        if (c < a) { s = c; e = a } else { s = a; e = c }
        return intArrayOf(s % cols, Math.floorDiv(s, cols), e % cols, Math.floorDiv(e, cols))
    }

    /** ≡ consumeMarkData — byte table เดียวกับ JS (ExtraKeysBar bytes, D6) */
    fun consume(data: String): MarkKey = when {
        !active -> MarkKey.Off
        data == "\u001b" -> MarkKey.Exit
        data == "\r" || data == "\n" -> MarkKey.CopyExit
        data == "\u001b[A" -> MarkKey.Move("up")
        data == "\u001b[B" -> MarkKey.Move("down")
        data == "\u001b[C" -> MarkKey.Move("right")
        data == "\u001b[D" -> MarkKey.Move("left")
        else -> MarkKey.Passthrough
    }
}
