package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionManager — state machine ของ tabs (round 4) ทดสอบ JVM ล้วน
 * handle = String (จริง = TerminalSession; manager ไม่รู้จัก type นั้นเลย)
 */
class SessionManagerTest {

    @Test
    fun `open appends and activates with unique ids`() {
        val m = SessionManager<String>()
        assertTrue(m.ids().isEmpty())
        assertEquals(0L, m.activeId)

        m.open("a")
        m.open("b")
        m.open("c")
        assertEquals(listOf(1L, 2L, 3L), m.ids())           // id เพิ่ม monotonic, เรียงตามลำดับเปิด
        assertEquals(3L, m.activeId)
        assertSame("c", m.active())
        assertSame("a", m.get(1L))
        assertNull(m.get(99L))
    }

    @Test
    fun `switchTo moves active and rejects unknown id`() {
        val m = SessionManager<String>()
        m.open("a"); m.open("b")
        assertTrue(m.switchTo(1L))
        assertEquals(1L, m.activeId)
        assertSame("a", m.active())
        assertFalse(m.switchTo(42L))                        // id ไม่มีจริง = ไม่ขยับ
        assertEquals(1L, m.activeId)
    }

    @Test
    fun `close non-active keeps active and returns handle`() {
        val m = SessionManager<String>()
        m.open("a"); m.open("b"); m.open("c")               // active = c
        assertSame("a", m.close(1L))
        assertEquals(listOf(2L, 3L), m.ids())
        assertEquals(3L, m.activeId)                        // active ไม่เปลี่ยน
        assertNull(m.close(1L))                             // ปิดซ้ำ = null
    }

    @Test
    fun `close active moves to right neighbour then left`() {
        val m = SessionManager<String>()
        m.open("a"); m.open("b"); m.open("c")               // ids 1,2,3
        m.switchTo(2L)                                      // active = b
        assertSame("b", m.close(2L))
        assertEquals(3L, m.activeId)                        // ขวาที่เหลือ (สไตล์ WT)

        m.switchTo(3L)                                      // active = c (id 3) — c อยู่ขวาสุด
        assertSame("c", m.close(3L))
        assertEquals(1L, m.activeId)                        // ไม่มีขวา → ถอยซ้าย (id 1)

        m.open("d")                                         // ids 1,4 — active = d (ขวาของ c เดิม)
        // ปิด tab ที่มีขวาเหลือ: active ต้องไปขวา ไม่ใช่ซ้าย
        m.switchTo(1L)
        assertSame("a", m.close(1L))
        assertEquals(4L, m.activeId)
    }

    @Test
    fun `close last tab goes idle`() {
        val m = SessionManager<String>()
        m.open("a")
        assertSame("a", m.close(1L))
        assertEquals(0, m.size())
        assertEquals(0L, m.activeId)
        assertNull(m.active())
    }

    @Test
    fun `lru evicts oldest non-active beyond cap and returns handles`() {
        val m = SessionManager<String>(maxLive = 2)
        m.open("a")                                         // [a]*
        m.open("b")                                         // [a, b]* — ไม่evict เพราะ a inactive
        assertEquals(listOf(1L, 2L), m.ids())

        m.switchTo(1L)                                      // a ใหม่กว่า b
        val evicted = m.open("c")                           // เกิน cap → victim = b (inactive นานสุด)
        assertEquals(listOf("b"), evicted)
        assertEquals(listOf(1L, 3L), m.ids())
        assertEquals(3L, m.activeId)
    }

    @Test
    fun `active use counts as LRU touch and saves a tab from eviction`() {
        val m = SessionManager<String>(maxLive = 3)
        m.open("a")                                         // a=1
        m.open("b")                                         // b=2
        m.active()                                          // b ยัง active = ถูกนับว่า "เพิ่งใช้"
        m.open("c")                                         // ยังไม่เกิด evict (cap = 3)
        val evicted = m.open("d")                           // เกิน cap: victim = a (เก่าสุด)
        assertEquals(listOf("a"), evicted)                  // ถ้า b ไม่โดน touch มันจะเก่าสุดแทน — ถูกเก็บไว้
        assertEquals(listOf(2L, 3L, 4L), m.ids())
    }

    @Test
    fun `maxLive one evicts active itself`() {
        val m = SessionManager<String>(maxLive = 1)
        m.open("a")
        val evicted = m.open("b")
        assertEquals(listOf("a"), evicted)
        assertEquals(listOf(2L), m.ids())
        assertEquals(2L, m.activeId)
    }
}
