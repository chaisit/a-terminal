package dev.aterminal.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Clusters golden (round 11 T5) — splitting ต้อง ≡ การตัด cell ของ engine (xterm UnicodeV6 width rule)
 * ตัวแทน case = G8 ladder สวัสดี (7 codepoints → 4 cells/clusters) — escape literals กัน NFC normalization
 */
class ClustersTest {

    // สวัสดี = ส-ว-ั-ส-ด-ี-้ (U+0E2A 0E27 0E31 0E2A 0E14 0E35 0E49 — ตรง cps ของ g8-ladder L3)
    // escape literal: กัน editor/normalization ทำ ไม้โท (U+0E49) หล่น — บทเรียนรอบ test แรก
    private val sawasdee = "\u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35\u0E49"

    @Test fun `empty and ascii`() {
        assertEquals(emptyList<String>(), Clusters.split(""))
        assertEquals(listOf("a", "b", "c"), Clusters.split("abc"))
    }

    @Test fun `thai sawasdee is four clusters matching cells`() {
        // [ส][วั][ส][ดี] — marks (Mn) ติด base cell เดียว ≡ engine
        assertEquals(
            listOf("\u0E2A", "\u0E27\u0E31", "\u0E2A", "\u0E14\u0E35\u0E49"),
            Clusters.split(sawasdee),
        )
    }

    @Test fun `thai leading vowel is own cell marks join consonant`() {
        // เ (U+0E40, width 1) = cell ของมันเอง; ซ+่ = cluster เดียว
        assertEquals(listOf("เ", "ซ่"), Clusters.split("เซ่"))
    }

    @Test fun `devanagari conjunct and spacing mark split like engine cells`() {
        // क + virama(Mn) + ष: engine ให้ 2 cells [क्][ष] — BreakIterator (ไม่มี GB9c) ก็ 2 = ตรง engine
        assertEquals(listOf("क्", "ष"), Clusters.split("क्ष"))
        // ा (U+093E, Mc — spacing = width 1 ใน UnicodeV6) = cell ใหม่ ≠ BreakIterator modern ที่จะรวม
        assertEquals(listOf("क", "ा"), Clusters.split("का"))
    }

    @Test fun `emoji surrogate and zwj follow engine cell rule`() {
        assertEquals(listOf("😀"), Clusters.split("😀"))
        // ZWJ family: engine (per-cp width) = man|ZWJ → cell, woman → cell, girl → cell = 3 cells
        // → clusters ต้อง 3 ด้วย (grid ตรงกัน by construction; glyph fidelity = เรื่อง API31+ shaper)
        assertEquals(3, Clusters.split("👨‍👩‍👧").size)
    }

    @Test fun `leading standalone mark is its own cluster like its own cell`() {
        assertEquals(listOf("ั", "x"), Clusters.split("ัx"))
        assertEquals(listOf("ั"), Clusters.split("ั"))
    }

    @Test fun `reassembly is lossless`() {
        for (s in listOf(sawasdee, "a😀b", "क्ष", "का", "ế", "plain", "ัx")) {
            assertEquals(s, Clusters.split(s).joinToString(""))
        }
    }
}
