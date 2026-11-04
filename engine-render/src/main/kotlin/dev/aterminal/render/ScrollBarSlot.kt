package dev.aterminal.render

/**
 * Scrollbar slot (round 23 T5): the native scrollbar thumb (4px, T1) used to paint at
 * `width - 4` regardless of where the grid ends — with cellW ceil'd, the leftover right of
 * `cols * cellW` can be 0–4px, so the thumb sat on top of the last column's glyph area
 * (field symptom: "last chars of a status line look eaten").
 *
 * Fix = a dedicated slot: the column count is computed from `viewWidth - thumbW`, which
 * guarantees at least thumbW of unused margin right of the grid (except the degenerate
 * 1-column view), and thumbX clamps to the grid's right edge so a transient geometry can
 * never put the thumb over painted columns either.
 */
object ScrollBarSlot {
    /** Thumb (and reserved slot) width in px — same visual weight as the old overlay. */
    const val THUMB_W = 4f

    /** Grid columns from the view width, floor over the width left after the thumb slot. */
    fun gridCols(viewWidthPx: Float, cellWPx: Float, thumbW: Float = THUMB_W): Int =
        maxOf(1, ((viewWidthPx - thumbW) / cellWPx).toInt())

    /**
     * Thumb left edge x: right-aligned inside the view's unused right margin; clamped to
     * `gridWidthPx` so the thumb may be clipped by the view edge but never consumes a column.
     */
    fun thumbX(viewWidthPx: Float, gridWidthPx: Float, thumbW: Float = THUMB_W): Float =
        maxOf(viewWidthPx - thumbW, gridWidthPx)
}
