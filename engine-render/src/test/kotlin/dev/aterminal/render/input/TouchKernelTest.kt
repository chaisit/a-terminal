package dev.aterminal.render.input

import org.junit.Assert.*
import org.junit.Test

/**
 * arbitration ≡ terminal.html touchSelect (round 11 T3) — geom: cell 8x16, 39x27, slop 12
 * ครอบคลุม: mouse-off regression (T1/T2 behavior เดิม — no mouse actions), slop ล้ม hold,
 * escape hatch ทุก level, dominant axis ครั้งเดียว, wheel notch/accum, tap pair, press/motion/release, cancel
 */
class TouchKernelTest {

    private val geom = KernelGeom(cellW = 8f, cellH = 16f, cols = 39, rows = 27)

    private fun k() = TouchKernel(geom)

    @Test fun `mouse off regression equals old view behavior - scroll select only`() {
        val t = k()
        assertTrue(t.onDown(100f, 100f, mouseOn = false, hadSel = false).isEmpty())
        assertTrue(t.onMove(103f, 104f).isEmpty()) // ใน slop = ไม่มีอะไร (T2 selMoved เดิม)
        // hold ลั่น = select (T2)
        assertEquals(GestureAction.SelStart(12, 6), t.onHold())
        assertEquals(listOf(GestureAction.SelExtend(20, 8)), t.onMove(160f, 130f))
        assertEquals(listOf(GestureAction.SelFinish(true)), t.onUp())
        // scroll: finger ลง (y เพิ่ม) = ใหม่ขึ้น = top เพิ่ม (≡ T1 distanceY rule)
        val u = k().apply { onDown(100f, 100f, false, false) }
        assertEquals(listOf(GestureAction.ScrollBy(2)), u.onMove(100f, 130f)) // 30px/16 → 2
        assertEquals(listOf(GestureAction.ScrollBy(-1)), u.onMove(100f, 122f)) // -8 + partial -2 = -10 → -1
        // mouse-off ไม่มี mouse actions เด็ดขาด (regression gate ของแผน T3)
        val v = k().apply { onDown(50f, 50f, false, true) }
        v.onMove(200f, 52f) // drag ยาวแนวตั้ง/นอนก็ไม่ report
        assertEquals(listOf(GestureAction.SelClear), v.onUp()) // tap hadSel = clear (T2)
        // และไม่มี GestureAction.Mouse*/Wheel ในทุกเคสข้างบน (type check ผ่าน listOf ข้ามบรรทัดไม่ได้ = by construction)
    }

    @Test fun `slop cancels hold, still holding fires at start cell`() {
        val t = k().apply { onDown(80f, 80f, false, false) }
        t.onMove(100f, 81f) // > slop
        assertNull(t.onHold())
        assertTrue(t.onMove(100f, 81f).contains(GestureAction.ScrollBy(0)) == false) // scroll 0 ไม่ emit
        val u = k().apply { onDown(80f, 80f, false, false) }
        assertEquals(GestureAction.SelStart(10, 5), u.onHold())
        assertNull(u.onUp().let { u.onHold() }) // after up = armed false
    }

    @Test fun `long press escape hatch works on mouse levels too`() {
        val t = k().apply { onDown(80f, 80f, mouseOn = true, hadSel = false) }
        assertEquals(GestureAction.SelStart(10, 5), t.onHold()) // ≡ JS: timer ทำงานแม้ mouseOn
        assertEquals(listOf(GestureAction.SelExtend(12, 5)), t.onMove(100f, 80f)) // move = extend ไม่ใช่ drag/wheel
        assertEquals(listOf(GestureAction.SelFinish(true)), t.onUp()) // ไม่มี MouseRelease หลุด (JS swallow)
    }

    @Test fun `mouse on tap emits press release pair at start cell`() {
        val t = k().apply { onDown(100f, 100f, true, false) }
        assertTrue(t.onMove(105f, 103f).isEmpty()) // ใน slop = ยัง tap
        val acts = t.onUp()
        assertEquals(listOf(GestureAction.MousePress(12, 6), GestureAction.MouseRelease(12, 6)), acts)
    }

    @Test fun `mouse on horizontal drag presses at start then motions on cell change`() {
        val t = k().apply { onDown(40f, 40f, true, false) }
        assertEquals(
            listOf(GestureAction.MousePress(5, 2), GestureAction.MouseMotion(15, 2)),
            t.onMove(121f, 43f),
        ) // dominant=drag (|dx|81>|dy|3): press ที่ start + motion ที่ cell ปัจจุบัน (≡ JS dispatch ใน move เดียว)
        assertEquals(listOf(GestureAction.MouseMotion(25, 2)), t.onMove(200f, 42f))
        assertTrue(t.onMove(203f, 42f).isEmpty()) // cell เดิม = ไม่ dispatch
        assertEquals(listOf(GestureAction.MouseRelease(25, 2)), t.onUp()) // release = last pos ไม่ใช่ start
    }

    @Test fun `dominant decided once at slop even if direction changes later`() {
        val t = k().apply { onDown(40f, 40f, true, false) }
        assertTrue(t.onMove(60f, 40f).first() is GestureAction.MousePress) // drag (|dx|20>=0)
        // เปลี่ยนเป็นแนวตั้งเต็มตัว = ก็ยังเป็น drag path (JS dominant lock ครั้งเดียว)
        assertTrue(t.onMove(62f, 200f).first() is GestureAction.MouseMotion)
        assertFalse(t.onMove(62f, 300f).any { it is GestureAction.Wheel })
    }

    @Test fun `mouse on vertical drag emits wheel notches with direction and accumulation`() {
        val t = k().apply { onDown(100f, 100f, true, false) }
        // นิ้วลากลง 41px = wheel-UP (JS: deltaY<0 = 64) — first delta นับจาก start (prevY ≡ pointerY)
        val a = t.onMove(101f, 141f)
        assertEquals(2, a.filterIsInstance<GestureAction.Wheel>().size)
        assertTrue(a.filterIsInstance<GestureAction.Wheel>().all { it.up })
        // ลากกลับขึ้น 32px: acc = 9 - 32 = -23 → 1 notch DOWN, เหลือ -7 (partial ค้าง)
        val b = t.onMove(101f, 109f)
        assertEquals(listOf(GestureAction.Wheel(12, 6, up = false)), b)
        // ไม่ press/send motion ใน wheel path และ UP = ไม่มี release (JS: moved && !pressSent)
        assertTrue(t.onUp().isEmpty())
    }

    @Test fun `sub row movement reports nothing - wheel floor gate`() {
        val t = k().apply { onDown(100f, 100f, true, false) }
        assertTrue(t.onMove(100f, 112f).isEmpty()) // 12px = SLOP boundary — hypot>12? 12 ไม่ > 12 = ไม่ moved
        val u = k().apply { onDown(100f, 100f, true, false) }
        assertTrue(u.onMove(100f, 113f).isEmpty()) // moved, dominant wheel, acc 13 < 16 = 0 notch (≡ getLinesScrolled floor)
    }

    @Test fun `hadSel with mouse on clears on up and on tap`() {
        val t = k().apply { onDown(100f, 100f, true, true) }
        val acts = t.onUp()
        assertEquals(
            listOf(GestureAction.MousePress(12, 6), GestureAction.MouseRelease(12, 6), GestureAction.SelClear),
            acts,
        )
    }

    @Test fun `cancel releases press keeps everything else`() {
        val t = k().apply { onDown(40f, 40f, true, false) }
        t.onMove(121f, 43f) // press
        t.onMove(200f, 44f) // motion → last = (25, 2)
        assertEquals(listOf(GestureAction.MouseRelease(25, 2)), t.onCancel())
        assertTrue(t.onUp().isEmpty()) // release ครั้งเดียว
    }

    @Test fun `wheel during selecting goes to extend not wheel`() {
        val t = k().apply { onDown(100f, 100f, true, false) }
        t.onHold()
        assertEquals(listOf(GestureAction.SelExtend(12, 10)), t.onMove(101f, 170f))
    }
}
