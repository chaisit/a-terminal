package dev.aterminal.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * แถวปุ่มพิเศษใต้ terminal (แนว Termux extra-keys row) — คีย์ที่ soft keyboard ทั่วไปไม่มี
 *  - CTRL/⇧ = modifier จริง (round 21, `KeyModifierState`): กดค้าง = มีผลระหว่างนิ้วแตะ, tap = latch
 *    มีผลกับ input *ทุกทาง* (bar + soft keyboard ผ่าน funnel ที่ host แปลง) — ไม่ใช่ lock ใน_bar อีกแล้ว
 *    label ขึ้น ✓ เมื่ latch อยู่; ระบบ pressed-state วาดตอนกดค้างเอง
 *  - ESC/TAB/ลูกศร = escape sequence ตรง (encode กับ modifier ไม่ได้ = fail-soft ส่งตรง)
 *  - ตัวอักษร A/E/L/R/U/Z ส่ง raw — host แปลงผ่าน mods เดียวกับที่ Gboard ใช้
 *
 * ทุก byte literal เขียนเป็น escape (`\u001B`) — ห้ามใส่ raw 0x1B ใน source (มองไม่เห็น, editor/format ลบเงียบได้)
 * constants อยู่ companion = ทดสอบ byte ตรงบน JVM ได้ไม่ต้องมี Context (`ExtraKeysBarTest`)
 */
class ExtraKeysBar(
    context: Context,
    private val mods: KeyModifierState,
    private val onKey: (String) -> Unit,
) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val modifierButtons = ArrayList<Pair<Button, Pair<ModifierKey, String>>>()

    init {
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)))

        btn("ESC") { onKey(ESC) }
        btn("TAB") { onKey(TAB) }
        btn("⌃C") { onKey(CTRL_C) } // direct SIGINT — emergency key ต้องเห็นทันทีไม่ต้อง scroll
        modifierBtn("SHIFT", ModifierKey.SHIFT)
        modifierBtn("CTRL", ModifierKey.CTRL)
        btn("↑") { onKey(UP) }
        btn("↓") { onKey(DOWN) }
        btn("→") { onKey(RIGHT) }
        btn("←") { onKey(LEFT) }
        btn("A") { onKey("a") } // raw — host แปลงผ่าน mods (latch/hold) เดียวกับ path Gboard
        btn("E") { onKey("e") }
        btn("L") { onKey("l") }
        btn("R") { onKey("r") }
        btn("U") { onKey("u") }
        btn("Z") { onKey("z") }
        btn("|") { onKey("|") }
        btn("-") { onKey("-") }
        btn("/") { onKey("/") }
        btn("~") { onKey("~") }

        // fire() อาจถูกเรียกจาก JavaBridge thread (Droid.onInput → transform) — setText จากนอก main
        // = CalledFromWrongThreadException ซึ่ง WebView กลืนเงียบ → คีย์ที่ consumed แล้วหายไปเลย
        // (root cause "CTRL พังบ้างไม่พังบ้าง" — วัดจริง 2026-11-04) → post เข้า main เสมอ
        mods.onChange = { post { refreshModifiers() } }
        refreshModifiers()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun btn(label: String, action: () -> Unit) {
        row.addView(Button(context).apply {
            text = label
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            minWidth = dp(40)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * modifier = touch-driven (DOWN=press, UP=release/click, CANCEL=ยกเลิก) — ToggleButton ใช้ไม่ได้:
     * มัน fire ตอน release อย่างเดียว จึงไม่มี concept "ระหว่างค้าง" (= bug เดิม user รายงาน 2026-11-04)
     * consume ทุก event (return true) กัน parent scroll ฉก ACTION_DOWN กลางคัน → CANCEL = state ไม่ค้าง
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun modifierBtn(label: String, key: ModifierKey) {
        val b = Button(context).apply {
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            minWidth = dp(56)
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { mods.press(key); v.isPressed = true; true }
                    MotionEvent.ACTION_UP -> {
                        mods.release(key); v.isPressed = false; v.performClick(); true
                    }
                    MotionEvent.ACTION_CANCEL -> { mods.cancel(key); v.isPressed = false; true }
                    else -> true
                }
            }
        }
        modifierButtons += b to (key to label)
        row.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun refreshModifiers() {
        for ((b, kl) in modifierButtons) {
            val (key, label) = kl
            b.text = if (mods.isLatched(key)) "$label✓" else label
        }
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
