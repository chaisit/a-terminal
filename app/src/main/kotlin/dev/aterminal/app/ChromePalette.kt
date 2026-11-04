package dev.aterminal.app

import androidx.compose.ui.graphics.Color

/**
 * scheme → [ChromeColors] (round 23 task 9) — pure mapping, ตารางทดสอบ JVM (ChromePaletteTest)
 *
 * `Scheme` ไม่มี slot muted (Settings.kt — เฉพาะ fg/bg/selection/cursor/ANSI16) จึง derive muted chrome
 * ด้วยการ blend bg→fg: แถบทั้งชุดจึงกลับด้านตาม schemeสว่าง/มืดอัตโนมัติ ไม่มีค่าคงที่สีในนี้
 *  - barBg = bg เป๊ะ (hard anchor: แถบต้องละลายนี่งกับขอบ pane)
 *  - pillActive 10% / pillIdle 4% ของ fg ใน bg — ≡ โทน strip ยุค task 7 เมื่อป้อน Campbell
 *  - textIdle = fg ผสม bg 45% (muted แต่ต่าง bg ชัดทั้งสองทิศ light/dark)
 * input = ARGB Int จาก Palette (lookup เดียว paletteFor — ห้าม path ที่สอง)
 */
object ChromePalette {
    private const val PILL_ACTIVE_T = 0.10f
    private const val PILL_IDLE_T = 0.04f
    private const val TEXT_IDLE_T = 0.45f

    fun from(defaultBg: Int, defaultFg: Int): ChromeColors = ChromeColors(
        barBg = Color(defaultBg),
        pillActiveBg = Color(blend(defaultBg, defaultFg, PILL_ACTIVE_T)),
        pillIdleBg = Color(blend(defaultBg, defaultFg, PILL_IDLE_T)),
        textActive = Color(defaultFg),
        textIdle = Color(blend(defaultFg, defaultBg, TEXT_IDLE_T)),
    )

    /** per-channel linear mix (alpha ผสมด้วย — สองฝั่งทึบ → ทึบ) ; internal = ให้ตาราง test จุด endpoint */
    internal fun blend(from: Int, to: Int, t: Float): Int =
        (channel(from, to, 24, t) shl 24) or
            (channel(from, to, 16, t) shl 16) or
            (channel(from, to, 8, t) shl 8) or
            channel(from, to, 0, t)

    private fun channel(from: Int, to: Int, shift: Int, t: Float): Int {
        val a = (from ushr shift) and 0xFF
        val b = (to ushr shift) and 0xFF
        return a + Math.round((b - a) * t)
    }
}
