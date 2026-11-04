package dev.aterminal.app

/**
 * fit-to-width math ของ Keys Bar (ADR 0002, round 23) — preset ทุกชุดต้องพอดีเนื้อbar ไม่มี scroll
 *
 * pure dp-Int ไม่มี Android dependency → gate ด้วย JVM test ได้ (`KeysBarFitTest`)
 * invariant (non-overflow): ส่วนเท่ากันทุกปุ่ม, แต่ละปุ่ม ≥ minDp, รวม ≤ bodyWidthDp
 * glyphOnly (ส่วนเฉลี่ย ≤ minDp): label ยาววาดไม่ทัน → caller ยุบ label เป็น glyph เดียว
 *
 * overflow (count > k): preset ที่ปุ่มเยอะเกินบนจอแคบ (device-gate 2026-11-04: 17 ปุ่มใน 275dp
 * = sliver ~16dp/ปุ่ม) → **fold** = ยุบ key หางแถวไป popup ⋯ (ADR 0002 Consequences — ห้ามแก้
 * ด้วยการใส่ scroll) — k = max(1, floor(bodyWidthDp / minDp)) คือจำนวน slot สูงสุดที่แตะได้;
 * visible = (k-1 ปุ่ม head ตาม priority order) + ⋯ สังเคราะห์ 1 = k slot พอดี, overflow = count-(k-1)
 * (degenerate k==1 → แถว = ⋯ ปุ่มเดียว, overflow = count)
 */
object KeysBarFit {

    /**
     * @param widths    ขนาด px-dp ของ **รายการที่เห็นในแถวเท่านั้น** (รวม ⋯ สังเคราะห์ตอน fold)
     * @param glyphOnly true = ที่เนื้อนี้เหลือที่แค่ glyph เดียวต่อปุ่ม (share ≤ minDp)
     * @param overflow  จำนวนปุ่มที่ถูกยุบไป popup (0 = แสดงครบ)
     */
    data class Fit(val widths: List<Int>, val glyphOnly: Boolean, val overflow: Int)

    /**
     * @param count   จำนวนปุ่มใน body (pinned zone ⌨⏎ ไม่เกี่ยว — อยู่นอกการคำนวณ)
     * @param bodyWidthDp เนื้อที่จริงของแถว ( Gesture Gutter/separator ถูก caller หักแล้ว)
     * @param minDp   ค่าเริ่มต้น 44dp ≡ min touch target (Android HIG)
     */
    fun widths(count: Int, bodyWidthDp: Int, minDp: Int = 44): Fit {
        if (count <= 0) return Fit(emptyList(), false, 0)
        val k = maxOf(1, bodyWidthDp / minDp) // floor → slot แตะได้ที่มากที่สุด; clamp 1 กัน body < minDp
        return if (count > k) {
            // fold: head (k-1) + ⋯ = k slot ส่วนเท่ากัน (⋯ นับเป็นหนึ่ง slot ตามใบสั่ง controller)
            // share ≥ minDp เสมอเมื่อ body ≥ minDp (body = k·minDp + r → share = minDp + floor(r/k))
            val share = bodyWidthDp / k
            Fit(List(k) { share }, share <= minDp, count - (k - 1))
        } else if (count * minDp >= bodyWidthDp) {
            // พอดี/เกิน min ทุกปุ่ม = ไม่มีที่พอให้ text label → glyph-only ตามใบสั่ง ADR
            Fit(List(count) { minDp }, true, 0)
        } else {
            val share = bodyWidthDp / count // floor → ผลรวม ≤ body เสมอ, ทุกปุ่ม ≥ minDp (เงื่อนไข if การันตี)
            Fit(List(count) { share }, false, 0)
        }
    }
}
