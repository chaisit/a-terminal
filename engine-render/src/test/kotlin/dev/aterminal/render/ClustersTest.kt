package dev.aterminal.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ===== forEach (perf 11-04) — range path ของ draw path ต้องแตก cluster เหมือน split เป๊ะ =====
    // (split = alloc list + substring; forEach = ให้ view วาด (start,count) โดยไม่ alloc —
    //  ถ้าสอง path ไม่ตรงกัน = grid เพี้ยนเฉพาะบนเครื่องจริง จึง lock เข้ากันด้วย test นี้)

    private fun ranges(s: String): List<String> {
        val out = ArrayList<String>()
        Clusters.forEach(s) { a, b -> out.add(s.substring(a, b)) }
        return out
    }

    @Test fun `forEach ranges equal split for every golden case`() {
        for (s in listOf(sawasdee, "", "abc", "เซ่", "क्ष", "का", "ế", "a😀b", "👨‍👩‍👧", "ัx", "ั", "plain")) {
            assertEquals("forEach ≠ split for «$s»", Clusters.split(s), ranges(s))
        }
    }

    @Test fun `forEach on empty text calls back never`() {
        var n = 0
        Clusters.forEach("") { _, _ -> n++ }
        assertEquals(0, n)
    }

    @Test fun `forEach ranges are contiguous and cover the whole string`() {
        var prev = 0
        Clusters.forEach(sawasdee) { a, b ->
            assertEquals("cluster gap", prev, a)
            assertTrue(b > a)
            prev = b
        }
        assertEquals(sawasdee.length, prev)
    }

    // ===== HW crash 11-04 (Honor, API 37): IndexOutOfBoundsException จาก Canvas.drawText(range) =====
    // draw path ส่ง (start, count) เข้า framework ตรง ๆ = ถ้า range หลุดขอบ/ว่าง/ขาดต่อเนื่อง/
    // ผ่ากลาง surrogate pair = เครื่องจะโยน IOOBE ทั้งที่ JVM test ธรรมดาไม่มีทางรู้
    // → exhaustive เหนือ alphabet ปองร้าย (lone surrogate สองฝั่ง, astral, marks, ZWJ, VS)

    private val nasty = listOf("a", "ั", "ำ", "ก", "́", "😀", "\uD83D", "\uDE00", "‍", "️")

    private fun hex(s: String) = s.map { it.code.toString(16) }.joinToString("-")

    @Test fun `forEach never emits an out-of-bounds, empty, or pair-splitting range`() {
        val cases = ArrayList<String>()
        for (a in nasty) {
            cases.add(a)
            for (b in nasty) {
                val ab = a + b
                cases.add(ab)
                for (c in nasty) cases.add(ab + c)
            }
        }
        assertEquals(nasty.size + nasty.size * nasty.size + nasty.size * nasty.size * nasty.size, cases.size)
        for (raw in cases) {
            val why = "«${hex(raw)}»"
            var prev = 0
            var seen = false
            Clusters.forEach(raw) { a, b ->
                assertTrue("$a..$b out of bounds (len=${raw.length}) $why", a in 0..raw.length && b <= raw.length)
                assertTrue("$a..$b is empty $why", b > a)
                if (seen) assertEquals("range gap before $a $why", prev, a)
                // ครึ่งล่างของ surrogate pair ห้ามขึ้นต้น cluster = pair ถูกผ่า (framework เกลียด)
                for (j in a until b) {
                    if (raw[j].isLowSurrogate() && j > 0 && raw[j - 1].isHighSurrogate()) {
                        assertTrue("surrogate pair split at cluster start $a $why", j > a)
                    }
                }
                seen = true
                prev = b
            }
            assertEquals("tail not covered $why", raw.length, prev)
        }
    }
}
