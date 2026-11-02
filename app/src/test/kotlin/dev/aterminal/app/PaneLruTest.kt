package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PaneLru — memory valve ระดับ WebView (round 5) ทดสอบ JVM ล้วน
 * จุดต่างจาก SessionManagerTest: ตรงนี้ทดสอบ protected set — focused pane ห้ามโดน evict เด็ดขาด
 */
class PaneLruTest {

    @Test
    fun `evict beyond cap removes oldest unprotected`() {
        val l = PaneLru<String>(cap = 3)
        l.add("a"); l.add("b"); l.add("c")
        assertEquals(emptyList<String>(), l.evict(emptySet()))      // เท่า cap = ไม่ evict
        l.add("d")
        assertEquals(listOf("a"), l.evict(setOf("d")))
        assertEquals(3, l.size())
        assertEquals(listOf("b", "c", "d"), l.handles())
    }

    @Test
    fun `protected handles are never evicted even when oldest`() {
        val l = PaneLru<String>(cap = 2)
        l.add("a"); l.add("b"); l.add("c")                          // เกิน cap แล้ว
        // ทุกตัว protected = evict ไม่ได้เลย (caller ตัดสินใจเอง) — ห้ามเดี้ยงด้วยการเก็บกด "a"
        assertEquals(emptyList<String>(), l.evict(setOf("a", "b", "c")))
        assertEquals(3, l.size())
        // protect เฉพาะ a (เก่าสุด) → victim ต้องเป็น b ไม่ใช่ a
        assertEquals(listOf("b"), l.evict(setOf("a")))
        assertTrue(l.handles().contains("a"))
    }

    @Test
    fun `touch extends life - untouched older handle dies first`() {
        val l = PaneLru<String>(cap = 2)
        l.add("a"); l.add("b")
        l.touch("a")                                                // a กลับมาเป็น "เพิ่งใช้"
        l.add("c")
        assertEquals(listOf("b"), l.evict(emptySet()))              // b ตายทั้งที่ younger กว่า a เดิม
        assertEquals(listOf("a", "c"), l.handles())
    }

    @Test
    fun `remove drops handle from tracking and from future eviction`() {
        val l = PaneLru<String>(cap = 2)
        l.add("a"); l.add("b"); l.add("c")                          // เกิน cap (2) — a เก่าสุด
        l.remove("a")                                               // ถูกปิดไปเองแล้ว ต้องไม่ถูกนับเป็น victim อีก
        assertEquals(emptyList<String>(), l.evict(emptySet()))      // กลับเข้า cap พอดี = ไม่มีอะไรต้อง evict
        assertEquals(2, l.size())
        l.add("d")                                                  // เกินอีกครั้ง — b คือ victim ไม่ใช่ a (หายไปแล้ว)
        assertEquals(listOf("b"), l.evict(emptySet()))
    }

    @Test
    fun `multiple over-cap entries evict in age order leaving protected`() {
        val l = PaneLru<String>(cap = 2)
        l.add("a"); l.add("b"); l.add("c"); l.add("d")              // เกินทีเดียว 2
        val out = l.evict(setOf("b"))
        assertEquals(listOf("a", "c"), out)                         // เก่าสุดก่อน, b (protected) รอด
        assertEquals(listOf("b", "d"), l.handles())
    }
}
