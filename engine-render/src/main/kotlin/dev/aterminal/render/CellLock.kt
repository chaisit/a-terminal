package dev.aterminal.render

import dev.aterminal.engine.term.Unicode

/**
 * CellLock — pure predicate for the API31+ draw path (round 23 rendering correctness).
 *
 * A run that contains any zero-width codepoint (combining mark) must be drawn
 * **anchored per cluster at each cluster's own column** (`Clusters.split` + one shape
 * per cluster), exactly like the API<31 Paint fallback. Shaping the whole run at once
 * and placing glyphs with run-relative shaper advances lets a cluster's advance drift
 * from cellW, so every following column shifts — the crooked `│` in Thai+box tables.
 *
 * Wide codepoints do NOT trigger the lock: RunMerger already isolates them into their
 * own single-cell run (the wide-single-run rule), where run-relative placement is exact.
 *
 * The zero-width rule alone is NOT sufficient (HW 11-05, Honor only, Tab S10+ clean): a run
 * of *full-width* codepoints that the resolved typeface cannot render also drifts, because
 * each fallback advance is its own number, not `cellW` — `นานๆ` with no combining mark
 * slipped past [needsPerCluster] and shoved the trailing `│` of that line ~8px left. That is
 * why the view gates on [placementExact] as well: measure the run's own advance sum against
 * the grid it must span, and anchor per cluster whenever they disagree.
 */
object CellLock {

    /** Below this the whole run is misaligned by less than half a pixel — invisible, not worth per-cluster draws. */
    const val MAX_DRIFT_PX = 0.5f

    /** True iff any codepoint in [text] has `Unicode.width(cp) == 0` (pure JVM, no Android). */
    fun needsPerCluster(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Unicode.width(cp) == 0) return true
            i += Character.charCount(cp) // surrogate-safe
        }
        return false
    }

    /**
     * True iff the run's own advance sum ([measuredPx] — `Paint.measureText`, the same layout
     * engine the shaper uses) spans exactly the grid the run occupies, [cells] x [cellW].
     *
     * Drift is judged on the **total**, not per cell: what the eye catches is the boundary
     * glyph (a `│` landing 8px off after 90 cells of 0.09px/cell), so a per-cell threshold
     * would let exactly that case through. A run measured exact cannot drift by construction,
     * whatever font the platform ended up falling back to — that makes the check device-agnostic
     * instead of guessing which codepoints the primary typeface covers.
     */
    fun placementExact(measuredPx: Float, cells: Int, cellW: Float): Boolean {
        if (cells <= 0 || cellW <= 0f || measuredPx.isNaN()) return false // unknown = anchor (slow but straight)
        return Math.abs(measuredPx - cells * cellW) <= MAX_DRIFT_PX
    }
}
