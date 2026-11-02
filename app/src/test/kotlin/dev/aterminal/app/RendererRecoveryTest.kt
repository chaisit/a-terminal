package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererRecoveryTest {
    @Test fun `allows three recoveries in a minute then gives up`() {
        val r = RendererRecovery()
        assertTrue(r.allow(0)); assertTrue(r.allow(1_000)); assertTrue(r.allow(2_000))
        assertFalse(r.allow(3_000))
    }

    @Test fun `old crashes leave the window`() {
        val r = RendererRecovery()
        assertTrue(r.allow(0)); assertTrue(r.allow(1_000)); assertTrue(r.allow(2_000))
        assertTrue(r.allow(61_000)) // 0 และ 1_000 พ้นหน้าต่างแล้ว
    }

    @Test fun `held output comes back in order, once`() {
        val r = RendererRecovery()
        r.hold("a"); r.hold("b")
        assertEquals(listOf("a", "b"), r.drain())
        assertEquals(emptyList<String>(), r.drain())
    }

    @Test fun `flood during reload drops oldest and stays within the cap`() {
        val r = RendererRecovery(maxHeldChars = 10)
        r.hold("aaaa"); r.hold("bbbb"); r.hold("cccc")
        assertEquals(listOf("bbbb", "cccc"), r.drain())
    }

    @Test fun `a single script larger than the cap is dropped rather than kept`() {
        val r = RendererRecovery(maxHeldChars = 3)
        r.hold("aaaa")
        assertEquals(emptyList<String>(), r.drain())
    }
}
