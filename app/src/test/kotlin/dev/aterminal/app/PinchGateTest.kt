package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * round-23 task 8: PinchGate is the pure decision core of pinch-to-zoom.
 * `onScale(current)` takes the ACCUMULATED scale since the two-finger anchor
 * (controller ruling a) — GestureRoot accumulates, the gate only judges.
 * Threshold 1.15 (plan, verbatim); steps fire once per threshold band crossed (ruling b).
 */
class PinchGateTest {
    private fun gate(): Pair<PinchGate, MutableList<Int>> {
        val steps = mutableListOf<Int>()
        val g = PinchGate()
        g.onStep = { steps += it }
        return g to steps
    }

    @Test
    fun `scale 1_05 not active`() {
        val (g, steps) = gate()
        assertFalse(g.onScale(1.05f))
        assertFalse(g.active)
        assertEquals(emptyList<Int>(), steps)
    }

    @Test
    fun `scale crosses 1_15 → active=true once`() {
        val (g, steps) = gate()
        assertTrue(g.onScale(1.16f))          // became active on this event
        assertFalse(g.onScale(1.20f))         // still active, no second "became active"
        assertTrue(g.active)
        assertEquals(listOf(1), steps)        // exactly one +1 step for band n=1
    }

    @Test
    fun `pointer count 0 → inactive`() {
        val (g, _) = gate()
        g.onScale(1.30f)
        assertTrue(g.active)
        g.onPointerUp(1)                      // one finger lifted, one stays — still ours
        assertTrue(g.active)
        g.onPointerUp(0)                      // last finger up → release
        assertFalse(g.active)
    }

    @Test
    fun `two-finger straight scroll (scale stays 1_0) never activates`() {
        // round-15-HW Honor behavior: straight two-finger scroll jitters scale around 1.0
        val (g, steps) = gate()
        for (c in floatArrayOf(1.0f, 1.01f, 0.99f, 1.004f, 0.996f, 1.008f, 0.992f, 1.001f, 1.0f)) {
            assertFalse("scale $c must not activate", g.onScale(c))
        }
        assertFalse(g.active)
        assertEquals(emptyList<Int>(), steps)
    }

    // ---- ruling (b): stepped zoom with dead-band hysteresis ----

    @Test
    fun `each 1_15 band crossed fires one step`() {
        val (g, steps) = gate()
        g.onScale(1.15f * 1.15f)              // jumps two bands in one event
        assertEquals(listOf(1, 1), steps)
        g.onScale(1.15f * 1.15f)              // same band again — no re-fire
        assertEquals(listOf(1, 1), steps)
        g.onScale(1.15f * 1.15f * 1.15f)      // band n=3 → one more
        assertEquals(listOf(1, 1, 1), steps)
    }

    @Test
    fun `pinch in shrinks symmetrically across the inverse bands`() {
        val (g, steps) = gate()
        g.onScale(1f / 1.15f)                 // pinch-in from the start → -1
        assertEquals(listOf(-1), steps)
        assertTrue(g.active)                  // activation is symmetric (children must stop too)
        g.onScale(1f / (1.15f * 1.15f))       // band -2 → one more shrink
        assertEquals(listOf(-1, -1), steps)
    }

    @Test
    fun `wobble at a crossed band edge does not re-fire (dead-band)`() {
        val (g, steps) = gate()
        g.onScale(1.16f)
        // fingers hover right around the 1.15 crossing — the band must not flip-flop
        for (c in floatArrayOf(1.14f, 1.16f, 1.145f, 1.155f, 1.15f, 1.148f)) g.onScale(c)
        assertEquals(listOf(1), steps)
    }

    @Test
    fun `exact threshold hit counts as crossed (forward boundary)`() {
        // parked (r): documented boundary semantics = an exact threshold hit counts as crossed
        // (the EPS in PinchGate.onScale + the reverse twin already pinned at 1f/1.15f above).
        // This pins the FORWARD direction: band 1 must fire on exactly 1.15f, not only past it.
        val (g, steps) = gate()
        assertTrue("activation flips on the exact-threshold event itself", g.onScale(1.15f))
        assertEquals(listOf(1), steps)
        assertTrue(g.active)
    }

    @Test
    fun `no zoom callback wired = gate never engages`() {
        // parked (s): GestureRoot contract — with no consumer, onScale must not activate the gate
        // (activation would cancel+swallow the children's stream for a zoom that never happens)
        val g = PinchGate()
        assertFalse(g.onScale(1.30f))
        assertFalse(g.onScale(2f))
        assertFalse("gate must stay inactive without an onStep consumer", g.active)
        // the guard reads live state, not construction: wiring a consumer later re-enables judging
        val steps = mutableListOf<Int>()
        g.onStep = { steps += it }
        assertTrue(g.onScale(1.30f))
        assertEquals(listOf(1), steps)
    }

    @Test
    fun `gesture end resets bands so next gesture starts clean`() {
        val (g, steps) = gate()
        g.onScale(1.16f)
        g.onPointerUp(0)
        // next gesture starts at accumulated 1.0 — must not fire a compensating -1
        assertFalse(g.onScale(1.0f))
        assertFalse(g.onScale(1.02f))
        assertEquals(listOf(1), steps)
        assertTrue(g.onScale(1.16f))          // fresh gesture can activate again
        assertEquals(listOf(1, 1), steps)
    }
}
