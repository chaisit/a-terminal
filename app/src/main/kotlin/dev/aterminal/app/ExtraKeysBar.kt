package dev.aterminal.app

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ToggleButton

/**
 * แถวปุ่มพิเศษใต้ terminal (แนว Termux extra-keys row) — คีย์ที่ soft keyboard ทั่วไปไม่มี
 *  - ⇧/Ctrl lock: toggle — มีผลกับ "ปุ่มตัวอักษร" ตัวถัดไปใน bar (CTRL แล้ว C = Ctrl+C ฉุกเฉิน) แล้วปลดเอง
 *  - ESC/TAB/ลูกศร = escape sequence ตรง
 *
 * ทุก byte literal เขียนเป็น escape (`\u001B`) — ห้ามใส่ raw 0x1B ใน source (มองไม่เห็น, editor/format ลบเงียบได้)
 * constants อยู่ companion = ทดสอบ byte ตรงบน JVM ได้ไม่ต้องมี Context (`ExtraKeysBarTest`)
 */
class ExtraKeysBar(context: Context, private val onKey: (String) -> Unit) :
    HorizontalScrollView(context) {

    private var shiftLock = false
    private var ctrlLock = false

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    init {
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)))

        btn("ESC") { onKey(ESC) }
        btn("TAB") { onKey(TAB) }
        btn("⌃C") { onKey(CTRL_C) } // direct SIGINT — emergency key ต้องเห็นทันทีไม่ต้อง scroll
        toggle("⇧") { shiftLock = it }
        toggle("CTRL") { ctrlLock = it }
        btn("↑") { onKey(UP) }
        btn("↓") { onKey(DOWN) }
        btn("→") { onKey(RIGHT) }
        btn("←") { onKey(LEFT) }
        btn("A") { emitLetter('A') }
        btn("E") { emitLetter('E') }
        btn("L") { emitLetter('L') }
        btn("R") { emitLetter('R') }
        btn("U") { emitLetter('U') }
        btn("Z") { emitLetter('Z') }
        btn("|") { onKey("|") }
        btn("-") { onKey("-") }
        btn("/") { onKey("/") }
        btn("~") { onKey("~") }
    }

    /** อักษรเดี่ยว: Ctrl lock -> control code (A=0x01..Z=0x1A), Shift lock -> uppercase; ปลด lock หลังใช้ */
    private fun emitLetter(c: Char) {
        val out = when {
            ctrlLock -> { ctrlLock = false; shiftLock = false; ctrlOf(c) }
            shiftLock -> { shiftLock = false; c.toString() }
            else -> c.lowercase()
        }
        onKey(out)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun btn(label: String, action: () -> Unit) {
        row.addView(android.widget.Button(context).apply {
            text = label
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            minWidth = dp(40)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun toggle(label: String, onChange: (Boolean) -> Unit) {
        row.addView(ToggleButton(context).apply {
            text = label
            textOff = label
            textOn = "$label✓"
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    companion object {
        const val ESC = "\u001B"
        const val TAB = "\u0009"
        const val CTRL_C = "\u0003"
        const val UP = "\u001B[A"
        const val DOWN = "\u001B[B"
        const val RIGHT = "\u001B[C"
        const val LEFT = "\u001B[D"

        /** Ctrl + ตัวอักษร = control code (A→0x01 .. Z→0x1A) — เดียวกันกับที่ terminal คีย์บอร์ดจริงส่ง */
        internal fun ctrlOf(c: Char): String = String(byteArrayOf((c - 'A' + 1).toByte()))
    }
}
