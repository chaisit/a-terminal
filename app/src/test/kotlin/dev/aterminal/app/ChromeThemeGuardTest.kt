package dev.aterminal.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard ของ theme-aware chrome (round 23 task 9): แถบ chrome ทุกแถวต้องตาม scheme ของ pane active —
 * ห้าม literal สี terminal-black ยุค hard-code (0xFF0C0C0C) กลับเข้ามาใน TabBar/บล็อก tabBar ของ MainActivity
 * และ chrome ต้องถูก *คำนวณเก็บใน snapshot* ห้ามเรียก mapping ใน lambda ที่วิ่งทุก recomposition (review note (o):
 * statusText recompose ทั้งแถบทุก ~1 Hz)
 * เป็น source-guard (แนว TopBarV2GuardTest) เพราะไม่มี Robolectric; visual จริง = emulator gate ของรอบ
 */
class ChromeThemeGuardTest {

    private fun src(name: String) = File("src/main/kotlin/dev/aterminal/app/$name").readText()

    @Test fun `TabBar contains no hardcoded terminal-black`() {
        assertTrue("TabBar.kt ต้องวาดจาก chrome param เท่านั้น (0xFF0C0C0C คือ bg hard-code ยุคก่อน task 9)",
            !src("TabBar.kt").contains("0xFF0C0C0C"))
    }

    @Test fun `tabBar ComposeView no longer paints hardcoded black`() {
        val s = src("MainActivity.kt")
        val start = s.indexOf("tabBar = ComposeView")
        val end = s.indexOf("root.addView(tabBar", start)
        assertTrue("ต้องพบบล็อก tabBar ใน MainActivity", start >= 0 && end > start)
        val block = s.substring(start, end)
        assertTrue("bg ของ ComposeView ต้องมาจาก scheme (applyChrome) ไม่ใช่ literal",
            !block.contains("0xFF0C0C0C"))
        assertTrue("chrome ต้องอ่านจาก barSnap (คำนวณครั้งเดียวต่อ snapshot) — ห้าม ChromeColors() ต่อ recomposition",
            block.contains("chrome = barSnap.chrome"))
    }

    @Test fun `chrome snapshot is computed once per refreshTabs from paletteFor`() {
        val s = src("MainActivity.kt")
        val start = s.indexOf("private fun refreshTabs()")
        val end = s.indexOf("\n    private fun ", start + 1)
        assertTrue("ต้องพบ refreshTabs", start >= 0)
        val body = s.substring(start, if (end >= 0) end else s.length)
        assertTrue("single scheme lookup เดียว — chrome ต้องผ่าน paletteFor (ห้าม lookup ที่สอง)",
            body.contains("paletteFor("))
        assertTrue("mapping ต้องเป็น ChromePalette (pure, มีตารางทดสอบ)",
            body.contains("ChromePalette.from("))
    }

    @Test fun `keys bar accepts chrome and keeps the pinned accent constant`() {
        val b = src("ExtraKeysBar.kt")
        assertTrue("ExtraKeysBar ต้องรับ ChromeColors (bar bg + button fill/fg ตาม scheme)",
            b.contains("fun setChrome("))
        assertTrue("PINNED_ACTIVE_COLOR ต้องคงเป็น constant จนกว่าจะมี scheme-aware accent (task 9 ruling)",
            b.contains("internal const val PINNED_ACTIVE_COLOR"))
    }

    @Test fun `keys bar buttons keep pressed feedback via state-list, pinned stays imperative`() {
        // review Important-1 (fix round): setBackgroundColor ราบเรียบ = คีย์ body ไม่มีการตอบสนอง
        // ต่อการแตะเลย (v.isPressed ไม่ render) — funnel ต้องทา state-list ให้ปุ่ม non-pinned
        val b = src("ExtraKeysBar.kt")
        val start = b.indexOf("private fun applyChromeToButtons()")
        val end = b.indexOf("\n    private fun ", start + 1)
        assertTrue("ต้องพบ applyChromeToButtons", start >= 0 && end > start)
        val funnel = b.substring(start, end)
        assertTrue("ปุ่ม body/⋯ ต้องได้ state-list bg จาก funnel เดียว (chrome pushes re-derive ทั้งสอง state)",
            funnel.contains("it.background = buttonBg(c)"))
        assertTrue("pressed = solid lift ไป pillActiveBg (ruling: คีย์ชั่ววูบต้อง instant, ห้าม fade)",
            b.contains("addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(c.pillActiveBg.toArgb()))"))
        assertTrue("default state = pillIdleBg",
            b.contains("addState(intArrayOf(), ColorDrawable(c.pillIdleBg.toArgb()))"))
        assertTrue("ห้าม Ripple ใน bar นี้ (ruling: solid feedback ไม่ใช่ ripple fade)",
            !b.contains("RippleDrawable"))
        assertTrue("pinned zone ต้องคง imperative paint (IME highlight rebase-order-sensitive — ห้าม statelist)",
            funnel.contains("pinnedButtons.forEach"))
    }

    @Test fun `keys bar separator derives from chrome, not the legacy constant`() {
        // minor 5 fix: เส้นคั่นบน bar ที่ themed แล้วต้องตาม scheme (constant เหลือเป็น fallback/MiniKeysBar)
        val b = src("ExtraKeysBar.kt")
        val start = b.indexOf("private fun applyChromeToButtons()")
        val end = b.indexOf("\n    private fun ", start + 1)
        val funnel = b.substring(start, end)
        assertTrue("funnel ต้องทา separator จาก blend(barBg→textActive, SEPARATOR_T)",
            funnel.contains("separator.setBackgroundColor(") && funnel.contains("SEPARATOR_T"))
    }
}
