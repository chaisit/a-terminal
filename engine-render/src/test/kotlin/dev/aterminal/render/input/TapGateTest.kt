package dev.aterminal.render.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * round 15-HW (Honor V6): "scroll ≠ พิมพ์" — gate ต้องตัดสิน Tap/NoTap ถูกทุก gesture
 * (slop=12px, longPress=500ms — ค่า ballpark ของระบบ, test ใช้อันเดียวกัน)
 */
class TapGateTest {
    private fun gate() = TapGate(slopPx = 12f, longPressMs = 500L)

    @Test
    fun `tap เดียว = Tap`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        assertEquals(TapGate.Result.Tap, g.up(120L))
    }

    @Test
    fun `ลากเลย slop (scroll) = NoTap`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        g.move(105f, 100f)                       // ใน slop — ยัง tap ได้
        g.move(100f, 160f)                       // เลย slop = scroll
        assertEquals(TapGate.Result.NoTap, g.up(200L))
    }

    @Test
    fun `สองนิ้ว (scroll by user) = NoTap แม้ไม่ขยับ`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        g.pointerDown()                          // นิ้วที่สองลง (กรณีจริงนิ้วแรก DOWN ก่อนเสมอ)
        g.pointerUp()                            // นิ้วแรกยังอยู่ — multiTouch ไม่หลุด
        assertEquals(TapGate.Result.NoTap, g.up(150L))
    }

    @Test
    fun `กดค้างเกิน longPress = NoTap (long-press select ไม่เด้ง IME)`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        assertEquals(TapGate.Result.NoTap, g.up(600L))
    }

    @Test
    fun `CANCEL แล้ว gesture ถัดไปเริ่มใหม่สะอาด`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        g.pointerDown()
        g.cancel()
        g.down(100f, 100f, 1000L)                // gesture ใหม่ (นิ้วเดียว)
        assertEquals(TapGate.Result.Tap, g.up(1100L))
    }

    @Test
    fun `double-tap = สอง Tap (SHOW_IMPLICIT ซ้ำ = idempotent ฝั่ง caller)`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        assertEquals(TapGate.Result.Tap, g.up(80L))
        g.down(102f, 101f, 200L)
        assertEquals(TapGate.Result.Tap, g.up(260L))
    }

    @Test
    fun `up โดยไม่มี down (stream หัก) = NoTap`() {
        val g = gate()
        assertEquals(TapGate.Result.NoTap, g.up(50L))
    }

    // ---- round 15-HW-2 (Honor V6): busted = real-time "สิ้นสภาพ tap" สำหรับ early IME hide ----

    @Test
    fun `busted — ใน slop ยังไม่ bust, เลย slop = bust ทันที (ไม่ต้องรอ UP)`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        g.move(105f, 100f)
        assertEquals(false, g.busted(100L))      // ยังใน slop — ยังเป็น tap candidate
        g.move(100f, 160f)                       // เลย slop = scroll
        assertEquals(true, g.busted(110L))
    }

    @Test
    fun `busted — นิ้วที่สองลง = bust แม้ไม่ขยับ (scroll by user)`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        assertEquals(false, g.busted(50L))
        g.pointerDown()
        assertEquals(true, g.busted(60L))
    }

    @Test
    fun `busted — เลย longPressMs = bust (เวลาอย่างเดียวก็พอ) และก่อนนั้น false`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        assertEquals(false, g.busted(499L))
        assertEquals(true, g.busted(500L))       // ขอบ = >= (≡ up() ที่ตัดสิน NoTap)
    }

    // ---- round 15-HW-4 (Honor V6): expired = busted เฉพาะโหมด "นิ้วนิ่งนาน" (long-press select)
    // caller ยิงจาก timer (นิ้วนิ่ง = ไม่มี MOVE event ให้ eval busted) และ policy ต่าง: เก็บ IME แม้ user เปิดเอง ----

    @Test
    fun `expired — นิ้วนิ่งครบ longPress = true, ก่อนนั้น false`() {
        val g = gate()
        g.down(50f, 50f, 0L)
        assertEquals(false, g.expired(499L))
        assertEquals(true, g.expired(500L))      // ขอบ >= เดียวกับ busted/up
    }

    @Test
    fun `expired — ลากเลย slop แล้ว = false ตลอด (scroll ห้ามโดน policy ของ select)`() {
        val g = gate()
        g.down(50f, 50f, 0L)
        g.move(80f, 80f)
        assertEquals(false, g.expired(500L))
        assertEquals(false, g.expired(9999L))
    }

    @Test
    fun `expired — สองนิ้ว = false (scroll by user ไม่ใช่ select)`() {
        val g = gate()
        g.down(50f, 50f, 0L)
        g.pointerDown()
        assertEquals(false, g.expired(900L))
    }

    @Test
    fun `expired — จบ gesture (up cancel) แล้ว false + expired ไม่เปลี่ยน state (pure read-time query)`() {
        val g = gate()
        g.down(50f, 50f, 0L)
        assertEquals(true, g.expired(600L))
        assertEquals(true, g.expired(600L))      // เรียกซ้ำได้ผลเดิม (timer สอง tick)
        g.up(650L)
        assertEquals(false, g.expired(700L))     // inactive = กัน hold-hide ค้างหลัง UP
    }

    @Test
    fun `busted — จบ gesture (up cancel) แล้ว false เสมอ + gesture ใหม่ reset`() {
        val g = gate()
        g.down(100f, 100f, 0L)
        g.move(100f, 160f)
        assertEquals(true, g.busted(100L))
        g.up(200L)
        assertEquals(false, g.busted(300L))      // inactive = ไม่ bust (กัน early-hide ค้าง)
        g.down(100f, 100f, 400L)
        assertEquals(false, g.busted(450L))      // moved state ต้องไม่รั่นมา gesture ใหม่
        g.pointerDown()
        g.cancel()
        assertEquals(false, g.busted(500L))
        g.down(100f, 100f, 600L)
        assertEquals(false, g.busted(650L))      // multiTouch ก็ไม่รั่น
    }
}
