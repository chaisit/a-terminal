package dev.aterminal.app

import androidx.compose.ui.graphics.Color

/**
 * สี chrome (top bar v2, round 23 task 7) — ค่า default = สี hard-code ยุคสองแถวเป๊ะ ๆ
 * ใช้เป็น fallback ตอน boot ก่อน pane แรกเท่านั้น; ตั้งแต่ task 9 ค่าจริงมาจาก [ChromePalette]
 * (scheme ของ pane active → barBg/pills/text) แล้ว MainActivity ส่งเข้าทั้ง TabBar และ Keys Bar
 * — จึงแยกไฟล์นี้จาก TabBar.kt เพราะมีผู้Consuming สองฝั่ง
 */
data class ChromeColors(
    val barBg: Color = Color(0xFF0C0C0C),      //BgTab เดิม
    val pillActiveBg: Color = Color(0xFF1F1F1F), //BgActive เดิม (+ menuBg — โทน strip เดิม)
    val pillIdleBg: Color = Color(0xFF141414),  //BgIdle เดิม
    val textActive: Color = Color(0xFFFFFFFF),  //TextActive เดิม
    val textIdle: Color = Color(0xFF9A9A9A),    //TextIdle เดิม
)
