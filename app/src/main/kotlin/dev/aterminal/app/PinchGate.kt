package dev.aterminal.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/**
 * round-23 task 8: pure decision core for pinch-to-zoom font. Fed the ACCUMULATED scale since
 * the two-finger anchor (GestureRoot owns the MotionEvents and accumulates scale factors —
 * controller ruling (a)); this class knows nothing about Android so the JVM gate can judge it.
 *
 * Model: band n = log_threshold(current). Crossing threshold^n fires +1 per new n (pinch out =
 * bigger font); crossing threshold^-n fires -1. Growth fires as soon as a band is crossed (floor),
 * the reverse only after the span fully returns to the previous band's edge (ceil) — the dead-band
 * between the two crossing points keeps a wobble at a crossed threshold from re-firing (ruling (b)).
 * Activation is symmetric on the first non-zero band: GestureRoot then cancels+swallows the child
 * stream until pointer count reaches 0. Two-finger straight scroll (scale ~ 1.0, the round-15-HW
 * Honor behavior) stays in band 0 and never activates the gate.
 */
class PinchGate(threshold: Float = 1.15f) {

    init {
        // parked (q): threshold <= 1 makes ln(threshold) <= 0 — band math degenerates
        // (division by zero or inverted direction) silently. Fail fast instead.
        require(threshold > 1f) { "PinchGate threshold must be > 1 (got $threshold)" }
    }

    /** one call per newly crossed band: +1 = bigger font, -1 = smaller (MainActivity maps to zoom(direction*2)) */
    var onStep: ((Int) -> Unit)? = null

    /** true from the first band crossed (either direction) until the last pointer is up */
    var active = false
        private set

    private val lnThreshold = ln(threshold.toDouble())
    private var band = 0

    /**
     * [current] = accumulated scale since gesture start.
     * @return true exactly once — on the event that flipped the gate from inactive to active.
     */
    fun onScale(current: Float): Boolean {
        if (current.isNaN() || current <= 0f) return false
        // parked (s): no consumer wired = the gate must never engage — activating without a zoom
        // callback would let GestureRoot cancel+swallow the child stream for a zoom that never happens
        if (onStep == null) return false
        val pos = ln(current.toDouble()) / lnThreshold
        // float-safe epsilon so an exact threshold hit counts as crossed
        val cand = if (pos >= band) floor(pos + EPS).toInt() else ceil(pos - EPS).toInt()
        if (cand == band) return false
        val becameActive = !active && cand != 0
        if (cand != 0) active = true // never de-activate mid-stream: children were already cancelled
        val step = if (cand > band) 1 else -1
        repeat(abs(cand - band)) { onStep?.invoke(step) }
        band = cand
        return becameActive
    }

    /** report pointers remaining after an UP/POINTER_UP/CANCEL; count 0 ends the gesture and resets bands */
    fun onPointerUp(count: Int) {
        if (count <= 0) {
            active = false
            band = 0
        }
    }

    private companion object {
        const val EPS = 1e-4
    }
}
