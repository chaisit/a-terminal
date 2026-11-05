package dev.aterminal.core

import dev.aterminal.core.ImeAutocomplete.Kind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImeAutocomplete — เกณฑ์จับ "IME ไม่เชื่อ flag" (HW 11-05)
 * สิ่งที่ต้องคุมคือ *ขอบเขตการกล่าวหา*: จับหลวมไป = เด้งพาไปตั้งค่าโดย user ไม่ได้ทำอะไรผิด
 * (เคส paste/คีย์ค้าง 12 ตัวอักษรที่วัดจริงจาก IME log ของ Honor อยู่ในกลุ่มนั้น)
 */
class ImeAutocompleteTest {

    private fun sugg(
        kind: Kind,
        text: String,
        strict: Boolean = true,
        composing: Boolean = false,
        clip: String = "",
        since: Double = 0.0,
    ) = ImeAutocomplete.isSuggestion(kind, strict, text, composing, clip, since)

    @Test fun `wordLike accepts only short printable ASCII with a letter`() {
        for (ok in listOf("a", "the", "the ", "cd1", "ls -la", "git status")) {
            assertTrue("wordLike($ok)", ImeAutocomplete.wordLike(ok))
        }
        for (bad in listOf("", "   ", "123", "\n", "a\n", "สวัสดี", "naïve", "café", "\t")) {
            assertFalse("wordLike($bad)", ImeAutocomplete.wordLike(bad))
        }
    }

    @Test fun `wordLike rejects anything longer than a word an IME would insert`() {
        val edge = "a".repeat(ImeAutocomplete.MAX_BURST_CELLS)
        assertTrue(ImeAutocomplete.wordLike(edge))
        assertFalse(ImeAutocomplete.wordLike(edge + "b")) // paste, not a suggestion
    }

    @Test fun `nothing is a violation unless we asked for strict`() {
        for (kind in Kind.values()) {
            assertFalse(kind.name, sugg(kind, "the", strict = false))
        }
    }

    @Test fun `composition of ascii while strict means the ime ignored password mode`() {
        assertTrue(sugg(Kind.COMPOSITION, "k"))
        assertTrue(sugg(Kind.COMPOSITION, "wor"))
        // non-ASCII = composition เป็นของปกติของภาษานั้น (ไทยต้องไม่ถูกกล่าวหา)
        assertFalse(sugg(Kind.COMPOSITION, "สวัสดี"))
        assertFalse(sugg(Kind.COMPOSITION, ""))
    }

    @Test fun `burst counts only outside composition and only for word-like text`() {
        assertTrue(sugg(Kind.BURST, "the"))
        assertFalse("กำลัง compose = IME ส่งคำที่ user พิมพ์เองทีละตัว", sugg(Kind.BURST, "the", composing = true))
        assertFalse("ตัวเลขล้วนไม่ใช่คำที่ strip เติมให้", sugg(Kind.BURST, "1234"))
        assertFalse("ENTER ที่ bar ส่ง = control byte", sugg(Kind.BURST, "a\n"))
    }

    @Test fun `a one-character burst is the keystroke the user actually pressed`() {
        // IME ที่เชื่อ flag commit ทีละตัว = ทุกการพิมพ์ปกติต้องไม่สะสม strike จนเด้ง
        assertFalse(sugg(Kind.BURST, "a"))
        assertFalse(sugg(Kind.BURST, "z"))
        assertTrue(sugg(Kind.BURST, "ab"))
        assertTrue("composition คนละกรณี: region เดียวตอน strict = หลักฐานพอแล้ว", sugg(Kind.COMPOSITION, "a"))
    }

    @Test fun `a burst that came from the clipboard is a paste, not a suggestion`() {
        assertFalse(sugg(Kind.BURST, "git status", clip = "git status"))
        assertFalse(sugg(Kind.BURST, "git status .", clip = "git status"))
        assertTrue(sugg(Kind.BURST, "git status", clip = "unrelated"))
    }

    @Test fun `a burst only counts while it can still be the tail of a composition`() {
        // เคสที่วัดจริงบน Honor: paste / คีย์ค้าง / Keys Bar ยิงข้อความยาวเป็นระยะๆ ทั้งที่ IME ไม่ได้อะไร
        assertTrue(sugg(Kind.BURST, "git status", since = 0.4))
        assertTrue("ขอบหน้าต่างยังนับ", sugg(Kind.BURST, "git status", since = ImeAutocomplete.BURST_WINDOW_S))
        assertFalse("นานแล้ว = ไม่ใช่หางของคำที่ IME เติม", sugg(Kind.BURST, "git status", since = 9.0))
        assertFalse("ไม่เคยเห็น composition เลย (NaN) = ไม่มีอะไรให้ผูกด้วย", sugg(Kind.BURST, "git status", since = Double.NaN))
        assertFalse("เวลาถอยหลัง/ค่าเสีย = ห้ามตีความเป็น 0", sugg(Kind.BURST, "git status", since = -1.0))
        assertFalse("burst ยาวเกินหน้าต่างของคำ (len 25 ที่เห็นใน log) = paste", sugg(Kind.BURST, "a".repeat(25)))
    }

    @Test fun `composition ignores the burst window because it is the window`() {
        assertTrue(sugg(Kind.COMPOSITION, "the", since = Double.NaN))
        assertTrue(sugg(Kind.COMPOSITION, "the", since = 9999.0))
    }

    @Test fun `protocol traffic is not something a keyboard inserted`() {
        // วัดจริงจาก IME log ของ Honor: ลากนิ้วบน tmux/Claude (mouse mode 1006) สร้าง motion report
        // len=12 จำนวน 9 ก้อนติดกันผ่าน funnel เดียวกับการพิมพ์ — wordLike ปฏิเสธอยู่แล้ว แต่
        // เกณฑ์นี้ตัดก่อนถึง log ให้คนอ่านไม่ต้องเดาว่าก้อนไหนคือคำ
        val wire = listOf("\u001b[<32;42;12M", "\u001b[<0;42;12M", "\u001b[?62;1;6;22c", "\u001b[A", "a\r", "ls -la\r")
        for (w in wire) assertFalse("protocol bytes: $w", ImeAutocomplete.plausibleImeText(w))
        for (typed in listOf("the", "git status", "hello ", "สวัสดี")) {
            assertTrue("keyboard text: $typed", ImeAutocomplete.plausibleImeText(typed))
        }
        assertTrue("ว่าง = เกณฑ์ความยาวจัดการเอง", ImeAutocomplete.plausibleImeText(""))
        assertFalse("DEL กลางคำ ยังเป็น control", ImeAutocomplete.plausibleImeText("a\u007fb"))
    }

    @Test fun `hint needs the threshold and never repeats for the same ime`() {
        assertFalse(ImeAutocomplete.shouldHint(1, null, "gboard"))
        assertTrue(ImeAutocomplete.shouldHint(2, null, "gboard"))
        assertFalse(ImeAutocomplete.shouldHint(9, "gboard", "gboard")) // เคยเตือนแล้ว = เงียบตลอด
        assertTrue(ImeAutocomplete.shouldHint(2, "swiftkey", "gboard")) // เปลี่ยน IME = ดื้อคนละตัว
    }

    @Test fun `an unknown ime identity must not trigger a nag we cannot suppress`() {
        for (unknown in listOf(null, "", "   ")) {
            assertFalse(ImeAutocomplete.shouldHint(5, null, unknown))
        }
    }
}
