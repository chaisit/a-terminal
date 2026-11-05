package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImeSuggestions — pure decision ของ settings `imeSuggestions`
 * (การ map เป็น InputType int อยู่ที่ view; test นี้คุมเฉพาะ "เมื่อไหร่Strict")
 */
class ImeSuggestionsTest {

    @Test fun `every advertised value round-trips through parse`() {
        for (v in ImeSuggestions.values) assertEquals(v, ImeSuggestions.parse(v))
    }

    @Test fun `unknown null and case-wrong values fall back to shipped behaviour`() {
        // ค่าแปลกห้ามเพิ่มพฤติกรรมใหม่ (โดยเฉพาะ strict ซึ่งเคยวัดว่าทำให้ IME หยุด compose)
        for (bad in listOf(null, "", "strictish", "STRICT", "Strict", "no_suggestions", "  ", "null")) {
            assertEquals("parse($bad)", ImeSuggestions.NONE, ImeSuggestions.parse(bad))
            assertFalse("strictNow($bad, hw=true)", ImeSuggestions.strictNow(bad, true))
        }
    }

    @Test fun `none never asks for strict`() {
        assertFalse(ImeSuggestions.strictNow(ImeSuggestions.NONE, false))
        assertFalse(ImeSuggestions.strictNow(ImeSuggestions.NONE, true))
    }

    @Test fun `strict is strict regardless of keyboard`() {
        assertTrue(ImeSuggestions.strictNow(ImeSuggestions.STRICT, false))
        assertTrue(ImeSuggestions.strictNow(ImeSuggestions.STRICT, true))
    }

    @Test fun `strictOnHardware follows the keyboard only`() {
        assertFalse(ImeSuggestions.strictNow(ImeSuggestions.STRICT_ON_HW, false))
        assertTrue(ImeSuggestions.strictNow(ImeSuggestions.STRICT_ON_HW, true))
    }

    @Test fun `cycling returns to the start after exactly one full turn`() {
        for (start in ImeSuggestions.values) {
            var m = start
            val visited = ArrayList<String>()
            repeat(ImeSuggestions.values.size) {
                m = ImeSuggestions.next(m)
                assertTrue("next produced an unknown value: $m", m in ImeSuggestions.values)
                visited.add(m)
            }
            assertEquals("cycle length != ${ImeSuggestions.values.size} from $start", start, m)
            assertEquals("cycle revisits before covering all (from $start)", visited.size, visited.toSet().size)
            assertEquals("cycle must pass through every value (from $start)", ImeSuggestions.values.toSet(), visited.toSet())
        }
    }

    @Test fun `next from garbage enters the cycle at the first switch`() {
        assertEquals(ImeSuggestions.STRICT, ImeSuggestions.next("garbage"))
    }

    @Test fun `short labels are one line and cover every value`() {
        for (v in ImeSuggestions.values) {
            val sh = ImeSuggestions.short(v)
            assertTrue("empty short label for $v", sh.isNotBlank())
            assertTrue("short label must fit the menu row: " + sh, sh.length <= 18 && sh.length > 0)
        }
        assertEquals("app default", ImeSuggestions.short("garbage")) // ค่าแปลก = แสดง shipped behaviour
    }

    @Test fun `label exists for every value and warns where composing is at risk`() {
        for (v in ImeSuggestions.values) {
            val l = ImeSuggestions.label(v)
            assertTrue("empty label for $v", l.isNotBlank())
            assertTrue("label must name the setting: $l", l.startsWith("IME suggestions:"))
        }
        // ค่าที่ IME อาจเลิก compose (ไทยพัง) ต้องมีคำเตือนอยู่ในข้อความเดียวกัน
        assertTrue(ImeSuggestions.label(ImeSuggestions.STRICT).contains("Thai"))
        assertFalse(ImeSuggestions.label(ImeSuggestions.NONE).contains("Thai"))
    }
}
