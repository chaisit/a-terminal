package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fit-to-width math (ADR 0002, round 23) — ห้ามมี Android/Context (pure Int dp จึงรันบน JVM ได้)
 * invariant: non-overflow = ส่วนเท่ากันทุกปุ่ม, ≥ minDp, รวม ≤ body
 * fold (task 3b, ADR 0002 Consequences): preset ที่ปุ่มเยอะเกินบนจอแคบ = ยุบ key ไป popup ⋯
 * (ห้ามแก้ด้วย scroll) — k = max(1, floor(body/minDp)) slot; ถ้า count > k → visible =
 * (k-1 ปุ่ม head จาก priority order) + ⋯ หนึ่งปุ่ม, overflow = count - (k-1)
 */
class KeysBarFitTest {

    @Test fun ten_buttons_in_900dp_get_90dp_each() {
        val fit = KeysBarFit.widths(10, 900)
        assertEquals(List(10) { 90 }, fit.widths)
        assertFalse("plenty of room must not force glyph-only", fit.glyphOnly)
        assertEquals("no fold when count fits k", 0, fit.overflow)
    }

    @Test fun twenty_buttons_at_exactly_min_in_900dp_goes_glyph_only_min_respected() {
        // 20 × 45 = 900 = พอดีพื้น → เหลือที่แค่ glyph เดียวต่อปุ่ม (ADR 0002: ยุบ label ไม่ใช่ใส่ scroll)
        val fit = KeysBarFit.widths(20, 900, minDp = 45)
        assertTrue("share == min means labels cannot fit → glyphOnly", fit.glyphOnly)
        assertEquals(List(20) { 45 }, fit.widths)
        assertTrue("min 44 respected", fit.widths.all { it >= 44 })
        assertTrue("sum <= 900", fit.widths.sum() <= 900)
        assertEquals("count == k = ยังไม่ fold", 0, fit.overflow)
    }

    // ---- fold table (task 3b: device-gate ruling บน 411dp portrait, body ≈ 275dp) ----

    @Test fun seventeen_in_275dp_folds_to_six_slots_overflow_12() {
        // k = floor(275/44) = 6 → 5 ปุ่มจริง + ⋯ = 6 slot, overflow = 17 - 5 = 12
        val fit = KeysBarFit.widths(17, 275)
        assertEquals("widths size only the visible row incl. synthetic ⋯", 6, fit.widths.size)
        assertEquals(12, fit.overflow)
        // fold ≠ glyphOnly: share 45 > minDp 44 → ปุ่มที่เห็นยังใส่ label เต็ม (kill "fold ⇒ glyphOnly" mutant)
        assertEquals(false, fit.glyphOnly)
        assertTrue("all >= 44: ${fit.widths}", fit.widths.all { it >= 44 })
        assertTrue("sum <= 275: ${fit.widths.sum()}", fit.widths.sum() <= 275)
    }

    @Test fun seventeen_in_88dp_folds_to_two_slots_overflow_16() {
        val fit = KeysBarFit.widths(17, 88)
        assertEquals(2, fit.widths.size)
        assertEquals(16, fit.overflow)
        assertTrue(fit.widths.all { it >= 44 })
    }

    @Test fun seventeen_in_43dp_degrades_to_single_overflow_slot() {
        // k clamp ที่ 1 → แถว = ⋯ ปุ่มเดียว, ยุบทั้ง 17 (share 43dp < min เป็น degenerate ที่ยอมรับ)
        val fit = KeysBarFit.widths(17, 43)
        assertEquals(1, fit.widths.size)
        assertEquals(17, fit.overflow)
    }

    @Test fun compact_preset_never_folds_on_phone_or_wider() {
        assertEquals(0, KeysBarFit.widths(7, 900).overflow)
        assertEquals(0, KeysBarFit.widths(7, 400).overflow) // k=9 ≥ 7
        assertEquals(0, KeysBarFit.widths(7, 308).overflow) // count == k = ขอบนอกสุดที่ยังไม่ fold
    }

    @Test fun sliver_case_now_folds_instead_of_rendering_min_dp_slivers() {
        // Honor portrait-class เดิม (17 ปุ่มใน 222dp) = glyphOnly + minDp ×17 ที่รวมกันเกิน body;
        // task 3b: เคสนี้ต้อง fold แทน — k = floor(222/44) = 5 slot, overflow = 17 - 4
        val fit = KeysBarFit.widths(17, 222)
        assertEquals(5, fit.widths.size)
        assertEquals(13, fit.overflow)
        assertEquals(List(5) { 44 }, fit.widths)
        assertTrue("share == min → glyphOnly on the visible slots (popup shows full labels)", fit.glyphOnly)
    }

    @Test fun count_zero_is_empty() {
        val fit = KeysBarFit.widths(0, 900)
        assertEquals(emptyList<Int>(), fit.widths)
        assertFalse(fit.glyphOnly)
        assertEquals(0, fit.overflow)
    }

    @Test fun non_overflow_never_drops_below_min_and_never_overflows_body() {
        for (count in 1..24) {
            val body = 900
            val fit = KeysBarFit.widths(count, body)
            if (fit.overflow == 0) {
                assertEquals(count, fit.widths.size)
                assertTrue("all >= 44: $fit", fit.widths.all { it >= 44 })
                assertTrue("sum <= body: $fit", fit.widths.sum() <= body)
                assertEquals("equal shares", 1, fit.widths.distinct().size)
            }
        }
    }

    @Test fun fold_invariants_hold_across_grid_of_counts_and_widths() {
        for (count in 1..24) for (body in listOf(43, 88, 132, 222, 275, 400, 900)) {
            val fit = KeysBarFit.widths(count, body)
            val k = maxOf(1, body / 44)
            if (count > k) {
                assertEquals("slots == k ($count/$body)", k, fit.widths.size)
                assertEquals("overflow = count - (k-1)", count - (k - 1), fit.overflow)
                assertEquals("ทุกปุ่มถูกนับ: slot จริง + ⋯ + folded = count + 1", count + 1, fit.widths.size + fit.overflow)
                assertTrue("sum <= body: $fit", fit.widths.sum() <= body)
                if (body >= 44) assertTrue("slots >= min touch: $fit", fit.widths.all { it >= 44 })
            } else {
                assertEquals("no fold at $count/$body", 0, fit.overflow)
                assertEquals(count, fit.widths.size)
            }
        }
    }
}
