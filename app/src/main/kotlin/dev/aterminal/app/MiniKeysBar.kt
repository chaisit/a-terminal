package dev.aterminal.app

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.Button
import android.view.View
import android.widget.LinearLayout

/**
 * แถวคีย์ย่อสำหรับ landscape + IME เปิด (C12, round 5) — sibling ของ ExtraKeysBar (ไม่แตะ internals เดิม)
 *  - เหลือเฉพาะคีย์ฉุกเฉิน ESC / ⌃C + "⋯" เรียก bar เต็มกลับ (force จนกว่า IME ปิดหรือ rotate)
 *  - reuse escape constants จาก ExtraKeysBar — byte เดียวกันเป๊ะ (companion constants, ไม่มี logic)
 *  - Pinned zone (round 22, ADR 0001): ⌨ ⏎ นอก separator — mini bar โผล่เฉพาะตอน IME เปิด
 *    จึงเป็นจังหวะที่ user ต้องการ ⏎ ถูกต้อง (terminal CR) และทางซ่อน ⌨ มากที่สุด
 */
class MiniKeysBar(
    context: Context,
    onKey: (String) -> Unit,
    onRestore: () -> Unit,
    onKeyboardToggle: () -> Unit,
) :
    LinearLayout(context) {

    private var kbHl: ImeButtonHighlight? = null

    init {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        btn("ESC") { onKey(ExtraKeysBar.ESC) }
        btn("⌃C") { onKey(ExtraKeysBar.CTRL_C) }
        btn("⋯") { onRestore() }

        // ---- Pinned zone ≡ ExtraKeysBar: ⌨ ⏎ (⏎ ชิดขวาสุด) — spacer กดกลุ่มไปชิดขอบขวา (spec: right end) ----
        addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(View(context).apply { setBackgroundColor(ExtraKeysBar.SEPARATOR_COLOR) },
            LayoutParams(dp(1), dp(40)))
        pinnedBtn("⌨") { onKeyboardToggle() }.also { kbHl = ImeButtonHighlight(it) }
        pinnedBtn("⏎") { onKey(ExtraKeysBar.ENTER) }
    }

    /** ⌨ highlight ตามสถานะ IME จริง (KeyboardWatcher เดียวกับ bar เต็ม) */
    fun setImeUp(up: Boolean) {
        kbHl?.set(up)
    }

    /** pinned ≡ ExtraKeysBar — helper รวมของสอง bar (PinnedKeys) กัน shape แยกกันเพี้ยน */
    private fun pinnedBtn(label: String, action: () -> Unit): Button {
        val b = PinnedKeys.button(context, label, action)
        addView(b, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)))
        return b
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun btn(label: String, action: () -> Unit): Button {
        val b = Button(context).apply {
            text = label
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            minWidth = dp(56)
            setOnClickListener { action() }
        }
        addView(b, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)))
        return b
    }
}
