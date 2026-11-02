package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImeHeuristic — logic fallback ของ KeyboardWatcher (C12) ทดสอบ JVM ล้วน
 *เลขตัวอย่าง approximate px ของ emulator 1080×2400 (nav bar/bar = ส่วนที่ root ถูก adjustResize หด)
 */
class KeyboardWatcherTest {

    @Test
    fun `first layout sets baseline without event, big shrink fires up`() {
        val h = ImeHeuristic()
        assertNull(h.onLayout(1080, 2200))                  // baseline ใหม่ = ไม่มี transition
        assertNull(h.onLayout(1080, 2150))                  // หดน้อยเกิน threshold (25%) = ยังไม่ใช่ IME
        assertEquals(true, h.onLayout(1080, 1500))          // Gboard + bar หดเกิน 25% → IME up
        assertTrue(h.imeUp)
    }

    @Test
    fun `return to baseline fires down once`() {
        val h = ImeHeuristic()
        h.onLayout(1080, 2200); h.onLayout(1080, 1500)      // up
        assertEquals(false, h.onLayout(1080, 2200))         // คีย์บอร์ดปิด = root คืนขนาด → down
        assertNull(h.onLayout(1080, 2200))                  // อยู่ baseline แล้ว ไม่ยิงซ้ำ
        assertEquals(false, h.imeUp)
    }

    @Test
    fun `width change resets baseline - portrait height never reads as keyboard in landscape`() {
        val h = ImeHeuristic()
        h.onLayout(1080, 2200)                              // portrait baseline
        assertEquals(true, h.onLayout(1080, 1500))          // IME เปิดใน portrait
        assertEquals(false, h.onLayout(2200, 1080))         // rotate = ปิด event เดิม + baseline ใหม่ (ไม่ใช่ false alarm)
        assertNull(h.onLayout(2200, 1000))                  // landscape เตี้ยว่า portrait มาก — ต้องไม่ dianggap up
        assertEquals(true, h.onLayout(2200, 600))           // IME เปิดใน landscape: 600 < 1080*0.75 = up จริง
    }

    @Test
    fun `baseline grows with taller layouts and garbage is ignored`() {
        val h = ImeHeuristic()
        assertNull(h.onLayout(0, 0))                        // layout pass ขยะก่อน attach
        h.onLayout(1080, 2000)
        assertNull(h.onLayout(1080, 2200))                  // โตขึ้น = baseline ใหม่ (เช่น nav bar ซ่อน)
        assertEquals(true, h.onLayout(1080, 1600))          // threshold อ้าง baseline ใหม่: 1600 < 2200*0.75 = up
    }
}
