package dev.aterminal.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * round 15-HW: alias map ของ font ที่ bundle — ส่วน pure (isBundled) ทดสอบบน JVM ได้เลย;
 * lookup (createFromAsset) = Android-only, coverage = device path
 */
class BundledFontTest {
    @Test
    fun `bundled family match แบบ case-insensitive และมี trim`() {
        assertTrue(BundledFont.isBundled("FiraCode Nerd Font"))
        assertTrue(BundledFont.isBundled("  firacode nerd FONT  "))
        assertTrue(BundledFont.isBundled("Aterm Mono"))
    }

    @Test
    fun `family ทั่วไปไม่เข้า bundle path — ปล่อย advance-test จัดการ`() {
        assertFalse(BundledFont.isBundled("Cascadia Mono"))
        assertFalse(BundledFont.isBundled("monospace"))
        assertFalse(BundledFont.isBundled("Noto Sans Thai")) // proportional — ต้อง NOT match
        assertFalse(BundledFont.isBundled("FiraCode Nerd Font Mono")) // Mono variant ไม่มีใน asset (กัน map กว้างเกิน)
    }
}
