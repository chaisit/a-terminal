package dev.aterminal.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard ของนโยบาย HW-keyboard auto-hide (round 23 task 5):
 * toggle `hideKeysBarOnHardwareKeyboard` (default OFF) เมื่อ ON + มี physical keyboard → Keys Bars GONE
 * เป็น source-guard (แนว ManualRevealGuardTest) เพราะไม่มี Robolectric;
 * พฤติกรรมจริงบนเครื่อง = emulator gate ของรอบ (emulator รายงาน QWERTY = hwKeyboardPresent จริง)
 */
class KeysBarAutoHideGuardTest {

    private fun src(name: String) = File("src/main/kotlin/dev/aterminal/app/$name").readText()

    /** เนื้อหา fun ตั้งแต่ signature ถึงก่อน `private fun` ถัดไป (source-scan อย่างเดียว — ไม่มี Robolectric) */
    private fun funBody(s: String, signature: String): String {
        val start = s.indexOf(signature)
        assertTrue("ต้องพบ $signature ใน MainActivity.kt", start >= 0)
        val next = s.indexOf("\n    private fun ", start + 1)
        return s.substring(start, if (next >= 0) next else s.length)
    }

    @Test fun `applyKeysBars hides bars when toggle on and hardware keyboard present`() {
        val s = src("MainActivity.kt")
        val body = funBody(s, "private fun applyKeysBars()")
        assertTrue("applyKeysBars ต้องอ่าน toggle (การซ่อนอยู่ *ใน* funnel นี้ — ทุก caller ได้ผลตาม)",
            body.contains("settings.hideKeysBarOnHardwareKeyboard"))
        assertTrue("applyKeysBars ต้องตัดสินใจผ่าน hwKeyboardPresent()",
            body.contains("hwKeyboardPresent()"))
        // helper body เทียบ NOKEYS ≡ เกณฑ์ hwKb ของ back-as-escape (คำเดียวกันในไฟล์) —
        // emu รายงาน QWERTY = present, ถูกต้องตามนั้น ห้าม special-case
        assertTrue("hwKeyboardPresent ต้องเทียบ Configuration.KEYBOARD_NOKEYS",
            s.contains("Configuration.KEYBOARD_NOKEYS"))
    }

    @Test fun `onConfigurationChanged re-applies keys bars on keyboard plug or unplug`() {
        // manifest declare keyboard|keyboardHidden → เสียบ/ถอด BT keyboard ไม่ recreate → ต้อง re-apply เอง
        val body = funBody(src("MainActivity.kt"), "override fun onConfigurationChanged(")
        assertTrue("config change ห้ามทิ้ง bar ค้าง (เสียบ/ถอดคีย์บอร์ดแล้วต้อง hide/show ทันที)",
            body.contains("applyKeysBars()"))
    }
}
