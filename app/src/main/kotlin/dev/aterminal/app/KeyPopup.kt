package dev.aterminal.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * Popup Variant ของปุ่มที่มี key รอง (ADR 0002) — กดค้างปุ่มหลักแล้ว PopupWindow เด้งเหนือปุ่ม
 * แสดง [base, popup] เลือก item ใด = ยิง byte เดียวกับ tap ปุ่มหลักผ่าน `onKey` ปลายทางเดียวกัน
 * ใช้เป็น popup ของปุ่ม ⋯ ด้วย (fold task 3b): รายการยาวถึง ~16 entry ต้อง scroll ได้ —
 * คุมสูง ≤ 60% จอ ใน ScrollView (PopupWindow ห้ามยื่นออกนอกจอ)
 *
 * เกณฑ์ long-press = `setOnLongClickListener` มาตรฐาน + touch-slop/long-press timeout ของระบบ
 * (ห้ามมี custom gesture detector — ADR 0002 ปฏิเสธ swipe-popup เพราะ misfire)
 * popup เดียวเปิดทีละหนึ่ง (กัน tap เร็วสองปุ่มแล้ว overlay ซ้อน)
 * popup เป็น focusable (task 10): BACK = dismiss ไม่ใช่รั่วเข้า PTY (A2) และการฉก focus ของ window
 * ปิด IME ไปด้วย = นโยบาย "menu เปิด = IME ปิดก่อน" ของ ADR 0001 (ตั้งใจ ไม่ใช่ผลข้างเคียงที่ต้องแก้)
 */
internal object KeyPopup {

    /** item ใน popup: label สำหรับโชว์ + send = action เดียวที่ bar ใช้กับ tap ธรรมดา */
    class Entry(val label: String, val send: () -> Unit)

    private var open: PopupWindow? = null

    fun show(anchor: View, entries: List<Entry>) {
        dismiss() // re-entry: ปุ่มข้าง ๆ ถูกกดค้างตอน popup เดิมยังอยู่ = ย้าย popup ไม่ใช่ซ้อน
        val context = anchor.context
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(context, 14)
        for (e in entries) {
            val tv = TextView(context).apply {
                text = e.label
                typeface = Typeface.MONOSPACE
                textSize = 16f
                setTextColor(0xFFEAEAEA.toInt())
                setBackgroundColor(0xFF3A3A3A.toInt())
                setPadding(pad, pad, pad, pad)
                setOnClickListener { e.send(); dismiss() }
            }
            list.addView(
                tv,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // fold popup (task 3b) = รายการยาว scroll ได้: คุมสูง ≤ 60% จอ แล้วห่อ ScrollView —
        // WRAP_CONTENT ตรง ๆ ตอน 16 entry = popup ยื่นหลุดขอบบนจอ (วัด from measure ของ list ก่อน attach)
        val cappedHeight = minOf(
            list.measuredHeight,
            (context.resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).toInt(),
        )
        val content = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(list)
        }
        // popup กว้างอย่างน้อยเท่าปุ่ม (label "SHIFT" on glyph-clipped button = entry กว้างกว่าปุ่ม)
        val width = maxOf(anchor.width, list.measuredWidth)
        // focusable = true (round 23 task 10, A2 device-proven): popup เดิม focusable=false ไม่รับ key →
        // BACK รั่วผ่าน popup ที่เปิดอยู่ไป BackPolicy = ยิง ESC เข้า PTY ทั้งที่เมนูยังอยู่ = hole ของ ADR 0002
        // focusable แล้ว BACK = dismiss popup (PopupWindow standard) และไม่รั่วถึง activity
        // side effect ที่ตั้งใจ (นโยบายเดียวกับ closeImeForMenus, ADR 0001): popup window ฉก focus = IME ปิด —
        // "menu เปิด = IME ปิดก่อน" exactly; outside-touch dismiss คงเดิม (bg + isOutsideTouchable ด้านล่าง)
        val popup = PopupWindow(content, width, cappedHeight, true).apply {
            // background จำเป็นต่อ isOutsideTouchable (PopupWindow เงียบโดยไม่ warn ถ้าไม่มี) = tap นอกปิดได้
            // (javap android-37.2: PopupWindow มีแค่ setBackgroundDrawable — ไม่มี setBackground)
            @Suppress("DEPRECATION")
            setBackgroundDrawable(ColorDrawable(0xFF2B2B2B.toInt()))
            isOutsideTouchable = true
            isClippingEnabled = true
        }
        // showAsDropDown + offset ติดลบ = เหนือปุ่ม (bar อยู่ขอบล่างจอเสมอ — เหนือปุ่มไม่ชน status bar)
        // offset ต้องใช้ cappedHeight ไม่ใช่ measuredHeight ไม่งั้น popup ยื่นเกินบนจอเมื่อ list ยาว
        popup.showAsDropDown(anchor, 0, -cappedHeight - anchor.height)
        open = popup
    }

    /** เพดานสูง popup เทียบกับความสูงจอ (fold popup ~16 entry ต้อง scroll ได้ — task 3b) */
    internal const val MAX_HEIGHT_FRACTION = 0.6f

    fun dismiss() {
        open?.dismiss()
        open = null
    }

    private fun dp(context: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
