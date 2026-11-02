package dev.aterminal.render

import dev.aterminal.engine.TerminalEngine
import dev.aterminal.render.input.ScrollState
import org.junit.Assert.*
import org.junit.Test

/**
 * OPEN #3 (round 12) — integration test: pinned viewport ต้อง content-lock ใต้ eviction
 *
 * จำลอง drain loop จริง (MainActivity nativeSink): capture (baseY, evictedTotal) ก่อน write
 * → write → compute delta (เฉพาะ buffer เดียวกัน) → ScrollState.onOutput — ห้ามจำ semantics, gold คือ:
 *   xterm probe (evidence/round-11/xterm-sticky-probe.txt): flood 6 → ydisp 20→14, viewport content เดิม, stable:true
 * และก่อน fix: viewport content เลื่อน +6 ต่อ output rate (bug — test นี้ต้อง FAIL ถ้า revert)
 *
 * purity: ใช้เฉพาะ API สาธารณะของ :engine — engine เปลี่ยนแล้ว (evictedTotal — user อนุมัติ OPEN #3)
 */
class EvictionLockTest {

    // engine: rows=5, history=8 → cap = lines ≤ 13, baseY max = 8 (เลื่อน test ตาม constructor จริง)
    private fun engine() = TerminalEngine(20, 5, 8)

    private fun absText(e: TerminalEngine, absRow: Int): String =
        (0 until e.cols).joinToString("") { e.buf.lines[absRow].get(it).ch }.trim()

    /** ข้อความ viewport ทั้ง 5 rows ที่ top ปัจจุบัน — เทียบ exact-string (≡ xterm probe getLine) */
    private fun viewText(e: TerminalEngine, top: Int): List<String> =
        (0 until e.rows).map { absText(e, top + it) }

    /** drain ≡ MainActivity: คืน evicted delta ของ buffer เดียวกันเท่านั้น */
    private fun drain(e: TerminalEngine, s: ScrollState, bytes: ByteArray): Int {
        val bufRef = e.buf
        val prev = bufRef.baseY
        val prevEvict = bufRef.evictedTotal
        e.write(bytes)
        val evicted = if (e.buf === bufRef) e.buf.evictedTotal - prevEvict else 0
        s.onOutput(prev, e.buf.baseY, evicted)
        return evicted
    }

    @Test fun `counter evictedTotal counts head trims`() {
        val e = engine()
        assertEquals(0, e.buf.evictedTotal)
        // LF 5 ครั้งแรกเลื่อน cursor (no scroll) → scrolls = 16 → lines 5+16=21; cap 13 → evicted = 8
        for (i in 1..20) e.write("L$i\r\n".toByteArray())
        assertEquals(8, e.buf.evictedTotal)
        assertEquals(8, e.buf.baseY) // lines capping ที่ 13
    }

    @Test fun `pinned viewport content-lock across flood at cap`() {
        val e = engine()
        val s = ScrollState()
        for (i in 1..20) drain(e, s, "L$i\r\n".toByteArray()) // sticky ที่ bottom ตลอด
        assertEquals(8, s.top)

        // user scroll ขึ้นดูอดีต: 20 → 6 (≡ probe ที่ scrollLines(-10) แล้ว viewportY 20)
        s.scrollBy(-2, 8) // top = 6 — viewport = abs 6..10
        val before = viewText(e, 6)

        // flood 3 บรรทัดใหม่ (cap แล้ว → evicted 3, baseY คง 8)
        var totalEvicted = 0
        for (i in 21..23) totalEvicted += drain(e, s, "L$i\r\n".toByteArray())
        assertEquals(3, totalEvicted)
        assertEquals(8, e.buf.baseY) // cap: baseY ไม่ขยับ

        assertEquals("pinned ต้อง ≡ content เดิม (xterm stable:true)", 6 - 3, s.top)
        assertEquals(before, viewText(e, s.top)) // exact strings — row แรก/ทั้ง viewport ไม่เลื่อน
    }

    @Test fun `bottom eviction does not move tail-sticky view`() {
        val e = engine()
        val s = ScrollState()
        for (i in 1..20) drain(e, s, "L$i\r\n".toByteArray())
        for (i in 21..25) drain(e, s, "L$i\r\n".toByteArray()) // sticky ผ่าน cap
        // bottom เสมอ: viewport = baseY 8 → abs 8..12 = L21..L25 (+blank)
        assertEquals(e.buf.baseY, s.top)
        assertEquals("L25", absText(e, e.buf.lines.size - 2)) // tail content (บรรทัดสุดท้าย = blank ของ cursor)
    }
}
