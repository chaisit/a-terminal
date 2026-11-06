package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/04: snippet generator ของ tmux preset — exact string (user copy ไปใส่ `.tmux.conf` เอง,
 * app ห้ามเขียนไฟล์ user; Q4: section ของ ticket 07 มาแบบ comment วันนี้)
 */
class TmuxPresetTest {

    @Test
    fun `snippet contains the three core lines verbatim`() {
        val s = TmuxPreset.snippet
        // tmux config syntax — quoting ตรงตัว (single quotes = tmux ไม่ expand ก่อนส่งให้ format)
        assertTrue(s, s.contains("set -g set-titles on"))
        assertTrue(s, s.contains("set -g set-titles-string '#S:#I:#W'"))
        assertTrue(s, s.contains("set -g mouse on"))
    }

    @Test
    fun `future alert hooks ship commented so pasting today is valid config`() {
        val s = TmuxPreset.snippet
        // ทุกบรรทัด non-empty ต้อง valid หรือ comment — comment ของ 07 ต้องนำด้วย #
        s.lines().filter { it.isNotBlank() && !it.trim().startsWith("#") }
            .forEach { assertTrue("บรรทัด active ต้องเป็น set -g: $it", it.startsWith("set -g ")) }
        assertTrue("ต้องมี section 07 แบบ comment", s.contains("# alert"))
    }

    @Test
    fun `commandline default matches ticket 01 continuity recipe`() {
        assertEquals("tmux new -A -s main", TmuxPreset.commandline)
    }

    // ---- ticket tmux/05: quick-key row ขณะ prefix pending (ชุดที่ user อนุมัติ Q4; ขยาย 11-06
    // ตาม tmux usage research 2026-11-06 (primary sources: tmux key-bindings.c + man + public configs) = เพิ่ม c [ , s — user อนุมัติชุด+ลำดับ fold-priority) ----

    @Test
    fun `quick keys are exactly the approved set in order`() {
        assertEquals(
            listOf("%", "c", "d", "z", "n", "p", "w", "x", "[", "s", ",", "←", "↑", "↓", "→"),
            TmuxPreset.quickKeys.map { it.label },
        )
    }

    @Test
    fun `quick key bytes are the plain tmux command keys, arrows are full CSI`() {
        val byLabel = TmuxPreset.quickKeys.associate { it.label to it.afterPrefix }
        assertEquals("%", byLabel["%"])
        assertEquals("c", byLabel["c"])
        assertEquals(",", byLabel[","])
        assertEquals("[", byLabel["["])
        assertEquals("s", byLabel["s"])
        assertEquals("z", byLabel["z"])
        assertEquals("d", byLabel["d"])
        assertEquals("\u001B[D", byLabel["←"])
        assertEquals("\u001B[A", byLabel["↑"])
        assertEquals("\u001B[B", byLabel["↓"])
        assertEquals("\u001B[C", byLabel["→"])
    }

    @Test
    fun `every quick key has bytes and a glyph-short label`() {
        for (k in TmuxPreset.quickKeys) {
            assertTrue(k.afterPrefix.isNotEmpty())
            assertTrue("label ต้องสั้นพอสำหรับ fit math: " + k.label, k.label.length <= 2)
        }
    }
}
