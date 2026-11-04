package dev.aterminal.render

import kotlin.math.ceil

/**
 * Mark Headroom (round 23): extra vertical pixels added to every native cell so Thai upper/lower
 * marks (U+0E31, U+0E34–0E3A, U+0E47–0E4E …) of the bundled + fallback fonts clear neighboring
 * box-drawing rules instead of colliding with them.
 *
 * Single tunable constant — one number, both ends of the row grow around the centered baseline.
 * Chosen so the marks clear neighboring rules at the app's default sizes; the goal is rendering
 * *prettier* than Windows Terminal (ADR 0003), not merely equal to it.
 *
 * Pixels only: grid/column semantics are untouched (`:engine` dump does not see this).
 * The webview twin is `lineHeight: 1.33` in `terminal.html`.
 */
object MarkHeadroom {
    /** Extra clearance around the normal line box, in px, as a function of font size. */
    fun clearancePx(fontSizePx: Float): Float = ceil(fontSizePx * 0.18f)
}
