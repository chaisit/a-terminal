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
 */
object CellLock {

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
}
