package dev.aterminal.app

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout

/**
 * แถวคีย์ย่อสำหรับ landscape + IME เปิด (C12, round 5) — sibling ของ ExtraKeysBar (ไม่แตะ internals เดิม)
 *  - เหลือเฉพาะคีย์ฉุกเฉิน ESC / ⌃C + "⋯" เรียก bar เต็มกลับ (force จนกว่า IME ปิดหรือ rotate)
 *  - reuse escape constants จาก ExtraKeysBar — byte เดียวกันเป๊ะ (companion constants, ไม่มี logic)
 */
class MiniKeysBar(context: Context, onKey: (String) -> Unit, onRestore: () -> Unit) :
    LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        btn("ESC") { onKey(ExtraKeysBar.ESC) }
        btn("⌃C") { onKey(ExtraKeysBar.CTRL_C) }
        btn("⋯") { onRestore() }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun btn(label: String, action: () -> Unit) {
        addView(Button(context).apply {
            text = label
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            minWidth = dp(56)
            setOnClickListener { action() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)))
    }
}
