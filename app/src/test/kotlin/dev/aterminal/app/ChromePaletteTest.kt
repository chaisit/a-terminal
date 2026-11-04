package dev.aterminal.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ตาราง mapping scheme→chrome (round 23 task 9) — pure math ไม่มี View/Robolectric
 *  - Campbell: barBg ≡ terminal bg เป๊ะ (hard anchor: แถบละลายนี่งเนื้อ pane) + ฟิลด์ห้ามซ้ำกัน
 *  - light scheme (bg ขาว/fg ดำ): chrome ต้องกลับด้านตาม (แถบสว่าง ตัวหนังสือเข้ม) —
 *    proof ว่า mapping ไม่มี white hard-code ซ่อนอยู่
 */
class ChromePaletteTest {

    private val campbell = ChromePalette.from(defaultBg = 0xFF0C0C0C.toInt(), defaultFg = 0xFFCCCCCC.toInt())
    private val light = ChromePalette.from(defaultBg = 0xFFFFFFFF.toInt(), defaultFg = 0xFF000000.toInt())

    /** luminance by sRGB channel (น้ำหนัก ITU-R BT.601) — เพียงพอสำหรับเทียบ "_dimmer ว่า_" */
    private fun lum(c: Color): Float = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue

    @Test fun `barBg equals terminal bg exactly so the bar dissolves into the pane`() {
        assertEquals(Color(0xFF0C0C0C), campbell.barBg)
        assertEquals(Color(0xFFFFFFFF), light.barBg)
    }

    @Test fun `textActive equals scheme fg exactly`() {
        assertEquals(Color(0xFFCCCCCC), campbell.textActive)
        assertEquals(Color(0xFF000000), light.textActive)
    }

    @Test fun `every chrome field is distinct within a scheme`() {
        for (c in listOf(campbell, light)) {
            val fields = listOf(c.barBg, c.pillActiveBg, c.pillIdleBg, c.textActive, c.textIdle)
            assertEquals("chrome fields must not collide: $fields", fields.size, fields.toSet().size)
        }
    }

    @Test fun `textIdle is measurably dimmer than textActive (contrast hierarchy)`() {
        assertTrue(lum(campbell.textIdle) < lum(campbell.textActive))
        // light scheme: "dimmer" = เข้มกว่า bg? — เทียบกับ barBg แทน: idle text ต้องต่าง bg ชัดและอ่อนกว่า active
        assertTrue("idle text must stay readable on bar bg", lum(light.textIdle) > lum(light.textActive))
        assertTrue("idle text must be dimmer than active text", lum(light.textIdle) < lum(light.barBg))
    }

    @Test fun `pills sit between bg and fg - active stronger than idle`() {
        for (c in listOf(campbell, light)) {
            val bg = lum(c.barBg); val active = lum(c.pillActiveBg); val idle = lum(c.pillIdleBg)
            // active = blend ไกล bg กว่า idle — สองทิศ: dark scheme (bg มืด) / light scheme (bg สว่าง)
            if (bg < active) {
                assertTrue("active pill further from bg than idle", active > idle && idle > bg)
            } else {
                assertTrue("active pill further from bg than idle", active < idle && idle < bg)
            }
        }
    }

    @Test fun `light scheme produces light chrome with dark text - no hardcoded white`() {
        assertEquals(Color(0xFFFFFFFF), light.barBg)          // bg ขาว = ตาม scheme ไม่ใช่ค่าคงที่
        assertEquals(Color(0xFF000000), light.textActive)     // fg ดำ
        assertNotEquals(Color(0xFFFFFFFF), light.pillActiveBg)
        assertNotEquals(Color(0xFFFFFFFF), light.textIdle)
        assertTrue(lum(light.textActive) < lum(light.barBg))  // ตัวหนังสือเข้มกว่าพื้น = อ่านออก
    }

    @Test fun `blend is linear at the endpoints and opaque-preserving`() {
        val black = 0xFF000000.toInt(); val white = 0xFFE6E6E6.toInt()
        assertEquals(black, ChromePalette.blend(black, white, 0f))
        assertEquals(white, ChromePalette.blend(black, white, 1f))
        // alpha ผสมด้วย (ทั้งสองฝั่งทึบ → ทึบเสมอ — ห้าม stripe โปร่งใสหลุดเข้า view setter)
        val c = ChromePalette.from(0xFF0C0C0C.toInt(), 0xFFCCCCCC.toInt())
        for (f in listOf(c.barBg, c.pillActiveBg, c.pillIdleBg, c.textActive, c.textIdle)) {
            assertEquals("chrome colors must stay opaque", 1f, f.alpha, 0f) // Compose alpha = 0..1
        }
    }

    @Test fun `campbell blend constants land on the legacy strip tones`() {
        // 0x0C→0xCC @10% = 0x1F, @4% = 0x14 — ≡ literal ยุค task 7 (coincidence ที่ตรวจได้: mapping
        // ไม่ drift ตอนคนแก้ ratio โดยไม่ตั้งใจ); textIdle = 0xCC→0x0C @45% = 0x76 (task 9 intentionally
        // moves pixels — anchor เดียวที่ต้องเป๊ะคือ barBg)
        assertEquals(Color(0xFF1F1F1F), campbell.pillActiveBg)
        assertEquals(Color(0xFF141414), campbell.pillIdleBg)
        assertEquals(Color(0xFF767676), campbell.textIdle)
    }
}
