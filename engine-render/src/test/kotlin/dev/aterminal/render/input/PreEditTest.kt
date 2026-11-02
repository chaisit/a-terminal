package dev.aterminal.render.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PreEdit golden (round 11 T5) — semantics ≡ xterm composition hold (G8 g8-ladder.json):
 * intermediate composition ไม่ถึง backend, commit = feed ครั้งเดียว, backspace ยุบ preedit ก่อน DEL
 */
class PreEditTest {

    @Test fun `composing is held not fed`() {
        val p = PreEdit()
        p.setComposing("ส")
        assertTrue(p.active)
        p.setComposing("สว") // Gboard ส่งมาใหม่ทั้งก้อนเสมอ — replace ไม่ใช่ append
        assertEquals("สว", p.text)
        // ยังไม่ finish/commit = ไม่มีอะไร feed (class นี้คืน string ให้ view feed — ยังไม่เคยคืน)
        assertFalse(PreEdit().active)
    }

    @Test fun `commit replaces composing region and feeds once`() {
        val p = PreEdit()
        p.setComposing("สะกด")
        assertEquals("สวัสดี", p.commit("สวัสดี")) // candidate tap = t มาแทนทั้ง region
        assertFalse(p.active)
        assertNull(p.finish()) // จบแล้ว — finish ซ้ำต้องไม่ feed twice
        assertNull(p.commit("")) // commit ว่าง = เคลียร์อย่างเดียว
    }

    @Test fun `finish feeds held composition exactly once`() {
        val p = PreEdit()
        p.setComposing("กก")
        assertEquals("กก", p.finish())
        assertFalse(p.active)
        assertNull(p.finish())
        assertNull(PreEdit().finish()) // ไม่มีอะไรค้าง = null
    }

    @Test fun `empty setComposing clears region without feed`() {
        val p = PreEdit()
        p.setComposing("ก")
        p.setComposing("") // IME ยกเลิก composing region (compositionend ว่าง)
        assertFalse(p.active)
        assertNull(p.finish())
    }

    @Test fun `shrinkBefore collapses preedit then empty hands over to DEL`() {
        val p = PreEdit()
        // "สวัสดี" — 7 code points (ไม้โท U+0E49 นับแยก; escape กัน normalization หล่น — บทเรียนรอบแรก)
        p.setComposing("\u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35\u0E49") // 7 code points = g8-ladder L3 cps (escape = กัน normalization ทำ U+0E49 หล่น)
        assertTrue(p.shrinkBefore(1))
        assertEquals(6, p.text.codePointCount(0, p.text.length))
        assertTrue(p.shrinkBefore(6)) // ยุบหมด
        assertFalse(p.active)
        assertFalse(p.shrinkBefore(1)) // preedit ว่าง = view ส่ง DEL path เดิม
    }

    @Test fun `shrinkBefore counts code points not code units`() {
        val p = PreEdit()
        p.setComposing("a😀") // 😀 = 2 code units 1 code point
        assertTrue(p.shrinkBefore(1))
        assertEquals("a", p.text) // ลบเป็น emoji ทั้งตัว ไม่ใช่เหลือ orphan surrogate
    }

    @Test fun `shrinkBefore beyond length clamps to empty not crash`() {
        val p = PreEdit()
        p.setComposing("x")
        assertTrue(p.shrinkBefore(5))
        assertFalse(p.active)
    }

    @Test fun `clear discards without feed (ESC semantics)`() {
        val p = PreEdit()
        p.setComposing("_ABC")
        p.clear()
        assertFalse(p.active)
        assertNull(p.finish()) // discard = ไม่ feed composition ที่ค้าง
        assertEquals("y", p.commit("y")) // แต่พิมพ์ใหม่ (commit ธรรมดา) ยัง feed ได้ปกติ
    }
}
