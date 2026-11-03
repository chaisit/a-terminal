package dev.aterminal.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round 20 T04: overline band thickness (cosmetic parity with underline).
 * The band must match the font's own underline metric (what `isUnderlineText`
 * draws), bounded so a broken font can never make the line invisible or eat
 * the cell. Pure formula — the visible outcome is closed by device pixels.
 */
class OverlineBandTest {

    @Test
    fun bandMatchesFontUnderlineMetricAtParity() {
        // A plausible healthy metric well inside floor..cap must pass through
        // unchanged (strict parity). NB: the bundled FiraCode measures 0.94px
        // on-device (see thinRealWorldMetric test) — 2.6px is the generic
        // healthy-font case, not this font's measured value.
        assertEquals(2.6f, overlineThicknessPx(2.6f, 41f), 0.0001f)
    }

    @Test
    fun bandNeverInvisibleWhenFontMetricIsBroken() {
        // Fallback/last-resort fonts can report 0 (or garbage) — a solid 2px
        // floor (user-approved 2026-11-03) keeps the band perceivable.
        assertEquals(2f, overlineThicknessPx(0f, 41f), 0.0001f)
        assertEquals(2f, overlineThicknessPx(-3f, 41f), 0.0001f)
        assertEquals(2f, overlineThicknessPx(Float.NaN, 41f), 0.0001f)
        assertEquals(2f, overlineThicknessPx(Float.POSITIVE_INFINITY, 41f), 0.0001f)
    }

    @Test
    fun thinRealWorldMetricGetsTheTwoPixelFloor() {
        // Device-measured (round 20): bundled FiraCode post.underlineThickness
        // = 50/1950em ≈ 0.94px at fs14/2.625dpr — strict parity drew the same
        // hard hairline as before the fix, so the floor raises thin metrics.
        assertEquals(2f, overlineThicknessPx(0.94f, 41f), 0.0001f)
    }

    @Test
    fun bandNeverEatsMoreThanQuarterOfCell() {
        // A lying finite metric (30px in a 41px cell) must clamp, not cover
        // the glyphs. 41/4 = 10.25 — worked example, not recomputed.
        assertEquals(10.25f, overlineThicknessPx(30f, 41f), 0.0001f)
        // normal metrics stay untouched below the cap
        assertEquals(4f, overlineThicknessPx(4f, 16f), 0.0001f)
    }

    @Test
    fun tinyCellCollapsesFloorOntoCap() {
        // cellH/4 < 2px floor (cellH 4 → both bounds 1px): the minOf guard keeps
        // floor <= cap so coerceIn cannot throw, and the band takes the cap.
        assertEquals(1f, overlineThicknessPx(2.6f, 4f), 0.0001f)
        assertEquals(1f, overlineThicknessPx(0f, 4f), 0.0001f)
    }
}
