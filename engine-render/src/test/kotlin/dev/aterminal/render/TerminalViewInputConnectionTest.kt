package dev.aterminal.render

import dev.aterminal.render.input.PreEdit
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hw-backlog/12 (round 27 F-2, Honor + Gboard Thai) — replay regression ของ correction-dance.
 *
 * ไฟล์นี้ *ไม่เคยมีอยู่ก่อน* ตั๋วนี้ (created here; driver เรียกมัน "existing" — find ยืนยันว่าไม่มี)
 *
 * Seam note (diagnosing-bugs phase 5): TerminalView เป็น Android View — ไม่มี Robolectric ใน repo
 * จึง instantiate ตัว view จริงใน JVM test ไม่ได้; `Replay` ด้านล่างคือ mirror 1:1 ของ InputConnection
 * handlers (commitText/setComposingText/finishComposingText/deleteSurroundingText + rawSentText + danceFeed)
 * ที่เรียก decision machine จริง (PreEdit) — ตัว decision เป็น production code จริง, ส่วนที่ mirror คือ wiring
 * ซึ่งถูกล็อกด้วย source guards ท้ายไฟล์ (wiring เลื่อน = guard แดง)
 *
 * hw-backlog/13 (round 28 F-2b): เวอร์ชันก่อน *ไม่* model funnel ของ feedBytes
 * (`!danceFeed → closeCorrection`) — mirror จึงสวยเกินจริง: production ปิด window ที่ finish()
 * เพิ่งเปิดด้วย bytes ของตัวเอง, mirror ไม่ปิด = false green ส่ง 0.5.18 ทั้งที่ dance ยังซ้ำ
 * ตอนนี้ funnel อยู่ใน mirror แล้ว + replay เคส (k,0) จริงจาก r28-step2-ime-log.txt (F-2b)
 */
class TerminalViewInputConnectionTest {

    private fun tvSrc() = File("src/main/kotlin/dev/aterminal/render/TerminalView.kt").readText()

    /** replay harness: PTY bytes as written, ตามลำดับ — 1:1 กับ handler logic ใน TerminalView
     *  (ticket 13: model funnel ของ feedBytes ด้วย — feed ที่ไม่ใช่ของ dance ปิด correction window) */
    private class Replay {
        val preEdit = PreEdit()
        val pty = ByteArrayOutputStream()
        var rawSentText = ""

        /** ≡ TerminalView.feedBytes: bytes จากแหล่งนอก dance = PTY tail เลื่อนโดยไม่มี bookkeeping → ปิด window */
        private fun feedBytes(b: ByteArray, dance: Boolean) {
            if (!dance) preEdit.closeCorrection()
            pty.write(b)
        }
        private fun feedText(s: String, dance: Boolean = false) { feedBytes(s.toByteArray(Charsets.UTF_8), dance) }
        /** DEL ของ dance เอง (delta retreat / delete step) — ห้ามปิด window ของตัวเอง (≡ danceFeed ใน view) */
        private fun dels(n: Int) { feedBytes(ByteArray(n) { 0x7f.toByte() }, dance = true) }

        fun commitText(t: String) {
            val composingWasOpen = preEdit.active
            if (!composingWasOpen) {
                val delta = preEdit.correctionCommit(t)
                if (delta != null) {
                    rawSentText = ""
                    dels(delta.del)
                    if (delta.rest.isNotEmpty()) feedText(delta.rest, dance = true)
                    return
                }
            }
            val rest = stripWrittenPrefix(t)
            rawSentText = ""
            val fed = preEdit.commit(rest)
            if (fed != null) feedText(fed)
        }

        fun setComposingText(t: String) {
            val delta = preEdit.correctionCompose(t)
            if (delta != null) {
                rawSentText = t
                dels(delta.del)
                if (delta.rest.isNotEmpty()) feedText(delta.rest, dance = true)
                return
            }
            if (rawSentText.isNotEmpty() && t.startsWith(rawSentText)) preEdit.setComposing(t.substring(rawSentText.length))
            else { rawSentText = ""; preEdit.setComposing(t) }
        }

        fun finishComposingText() {
            val fed = preEdit.finish()
            if (fed == null && preEdit.correctionOpen) preEdit.closeCorrection() // ≡ stray-finish branch
            // ticket 13: flush ของ finish = dance feed ของตัวเอง — ถ้า mirror ปิด window ตรงนี้
            // มันจะ ≡ wiring เก่าของ view (F-2b) และ replay ข้างล่างจะแดง
            if (fed != null) feedText(fed, dance = true)
            rawSentText = ""
        }

        /** returns bytes written by this call (for per-step 0x7f assertions) — slice จริงจาก PTY, ไม่ใช่ size stub */
        fun deleteSurroundingText(before: Int, after: Int): ByteArray {
            val before0 = pty.size()
            deleteSurroundingUnit(before, after)
            return pty.toByteArray().copyOfRange(before0, pty.size())
        }

        /** ≡ shape ของ production: delegating override → shared handler (guard ตรวจฝั่ง view) */
        private fun deleteSurroundingUnit(before: Int, after: Int) {
            if (before <= 0 && after <= 0) return // (0,0) = no-op ตาม contract (ticket 12)
            if (before > 0 && preEdit.shrinkBefore(before)) return
            val del = preEdit.correctionDelete(before)
            if (del >= 0) { dels(del); return }
            feedBytes(byteArrayOf(0x7f), dance = false) // fall-through = DEL ปกติ ผ่าน funnel (≡ view)
        }

        fun closeCorrection() { preEdit.closeCorrection() } // mirror: dispatchKey/sendKeyEvent entry

        private fun stripWrittenPrefix(t: String): String {
            if (rawSentText.isEmpty() || !t.startsWith(rawSentText)) { rawSentText = ""; return t }
            return t.substring(rawSentText.length)
        }

        /** สิ่งที่ shell (zsh) เห็นจริง: 0x7f = line-editor erase code point ก่อนหน้า (iterate ด้วย code point — surrogate ปลอดภัย) */
        fun visible(): String {
            val out = StringBuilder()
            val text = String(pty.toByteArray(), Charsets.UTF_8)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (cp == 0x7F) { if (out.isNotEmpty()) out.deleteCharAt(out.length - 1) } else out.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            return out.toString()
        }

        fun delCount(): Int = pty.toByteArray().count { it == 0x7f.toByte() }
    }

    /** composing grow-steps ตาม contract (IME ส่งทั้งคำทุกครั้ง): prefix 1..n นับเป็น code point */
    private fun cpPrefixes(s: String): List<String> {
        val n = s.codePointCount(0, s.length)
        return (1..n).map { s.substring(0, s.offsetByCodePoints(0, it)) }
    }

    /**
     * replay จาก ime-log-0517-step4c-appdefault.txt (timestamps 532700268–532745439):
     * word1 composing 1..4 → commit(5) [เคสที่ถูกอยู่เดิม — ห้ามพัง]
     * word2 composing 1..8 → finish → deleteSurroundingText(1,0) → commitText(3) → composing 5..10 → commitText(11)
     * (legacy trace พิมพ์ len=0 hardcode = before/after แยกไม่ออกจาก log; oracle ที่ `นี้ต้อง` ครบ 7 cp
     *  ชี้ before=1 — DEL ตัวเดียวที่ code เก่าส่งคือสิ่งที่ลบ `ป` ตัวที่ 8 พอดี; test ครอบคลุมทั้ง (1,0) และ (0,0))
     */
    @Test fun `correction dance replay - no duplicated word reaches the PTY`() {
        val r = Replay()
        for (s in listOf("a", "ab", "abc", "abcd")) r.setComposingText(s)
        r.commitText("abcde") // prediction tap = commit-in-composition (replace region) — path ที่ถูกต้องแล้ว
        assertEquals("abcde", r.visible())

        for (s in listOf("w", "wo", "wor", "word", "word2", "word2a", "word2ab", "word2abc")) r.setComposingText(s)
        r.finishComposingText() // flush ตามน้ำ: `word2abc` เข้า PTY + เปิด window

        val deleteBytes = r.deleteSurroundingText(1, 0)
        assertEquals("deleteSurroundingText(1,0) ต้องส่ง 0x7f แค่ 1 ตัว (exactly one for the delete)", 1, deleteBytes.count { it == 0x7f.toByte() })

        r.commitText("wor") // Gboard แก้คำที่เพิ่ง flush = replace, ไม่ใช่ append
        for (s in listOf("ldxyz", "ldxyza", "ldxyzab", "ldxyzabc", "ldxyzabcd", "ldxyzabcde")) r.setComposingText(s)
        r.commitText("ldxyzabcde ") // len=11 ≡ log (region 10 + trailing)

        // ไม่มีคำซ้ำ: `wor` (prefix ของคำที่ถูกแก้ซ้ำในบั๊กเดิม) ต้องโผล่ครั้งเดียว และ `word2` หายจาก buffer
        assertEquals("visible buffer ต้อง ≡ word1 + คำแก้แล้ว (no duplicated word)", "abcdewor" + "ldxyzabcde ", r.visible())
        assertFalse("คำ/เศษคำต้องไม่ซ้ำใน PTY", r.visible().contains("word2"))
        assertEquals("wor ต้องเกิดครั้งเดียว", 1, Regex("wor").findAll(r.visible()).count())
        // DEL ทั้ง session = 1 (delete) + 4 (delta retreat จาก `word2ab` → `wor`) — ไม่มี DEL อื่นซ่อน
        assertEquals(5, r.delCount())
    }

    /**
     * hw-backlog/13 (F-2b) — replay จาก r28-step2-ime-log.txt (public 0.5.18, Honor, app default,
     * Gboard latin, timestamps 555340116–555363839): word1 composing 1..4 → commit(5) [in-composition]
     * → composing 1..8 → finish → **deleteSurroundingText(8,0)** (W1 พิสูจน์แล้วว่าเป็น (k,0) จริง)
     * → commit(3) → composing 5..10 → commit(10) [in-composition]
     * DECIDER (oracle zsh, r28-step2-oracle-dup.jpg): buffer ≡ `พรุ่่งนี้ต้องประชุม` — `นี้ต้อง` ต้องโผล่ครั้งเดียว
     * บั๊กเดิม: finish flush ผ่าน funnel โดยไม่ mark danceFeed → funnel ปิด window ที่ finish() เพิ่งเปิด
     * → delete(8,0) ตก fall-through ส่ง DEL เงียบตัวเดียว (เหลือ `นี้ต้อง` ค้าง) → commit 3+10 = คำซ้ำ ×2
     * (test นี้แดงกับ wiring เก่า — ดู driver-log-round28-ticket13-f2b.md ประกอบ)
     */
    @Test fun `captured F-2b dance - finish then full delete of the flushed word leaves no duplicate`() {
        val r = Replay()
        for (p in listOf("พ", "พร", "พรุ", "พรุ่")) r.setComposingText(p)
        r.commitText("พรุ่่ง") // commit-in-composition len=5 — word1 (path ที่ถูกอยู่แล้ว ห้ามพัง)

        for (p in cpPrefixes("นี้ต้องพ")) r.setComposingText(p) // composing 1..8 (S8 8 cp)
        r.finishComposingText() // flush len=8 + เปิด window (window ต้อง *รอด* จาก flush ของตัวเอง)

        val del8 = r.deleteSurroundingText(8, 0)
        assertEquals("delete(8,0) หลัง finish = ลบทั้งคำที่ flush — ต้องส่ง 0x7f ครบ 8 ตัว ไม่ใช่ DEL เงียบตัวเดียว",
            8, del8.count { it == 0x7f.toByte() })

        r.commitText("นี้") // commit len=3 (window ถูก full-delete ปิดแล้ว = append ปกติ ถูกต้อง)
        val full = "นี้ต้องประชุม"
        for (i in 5..10) r.setComposingText(full.substring(0, full.offsetByCodePoints(0, i))) // recompose 5..10
        r.commitText("ต้องประชุม") // commit-in-composition len=10

        assertEquals("DECIDER: zsh buffer ต้อง ≡ คำเต็ม ไม่มีคำซ้ำ", "พรุ่่งนี้ต้องประชุม", r.visible())
        assertEquals("`นี้ต้อง` ต้องเกิดครั้งเดียว (oracle = นี้ต้อง ×2 บน 0.5.18)", 1, Regex("นี้ต้อง").findAll(r.visible()).count())
        assertEquals("DEL ทั้ง session = 8 (full delete) ไม่มี delta อื่นซ่อน", 8, r.delCount())
    }

    /** variant: Gboard ใช้ (0,0) เป็น marker แล้ว commit-replace ทันที (ไม่มี delete จริง) — ต้อง replace ไม่ใช่ append */
    @Test fun `marker delete with zero lengths then commit replaces the flushed word not appends`() {
        val r = Replay()
        r.setComposingText("นี้ต้อง")
        r.finishComposingText() // window เปิด + รอดจาก flush ของตัวเอง (ticket 13)
        assertEquals("(0,0) ห้ามเขียน byte", 0, r.deleteSurroundingText(0, 0).size)
        r.commitText("พรุ") // แก้ทั้งคำ: common prefix = 0 → DEL retreat 7 แล้วเขียนใหม่
        assertEquals("พรุ", r.visible())
        assertEquals(7, r.delCount())
        assertFalse(r.preEdit.correctionOpen)
    }

    /** variant: delete บางส่วน (before < length) แล้ว commit-replace — retreat ถึง common prefix เท่านั้น */
    @Test fun `partial delete then replace commit retreats only to the common prefix`() {
        val r = Replay()
        r.setComposingText("นี้ต้อง")
        r.finishComposingText()
        assertEquals("delete(2,0) ต้องส่ง 0x7f 2 ตัว (หด window `นี้ต้อง` 7cp เหลือ `นี้ต` 5cp)", 2, r.deleteSurroundingText(2, 0).size)
        assertTrue("delete บางส่วน = window คงเปิด (Gboard ลบแล้วแก้ต่อ)", r.preEdit.correctionOpen)
        r.commitText("นี้") // common prefix `นี้` = 3cp → DEL retreat 5-3 = 2 ปิดท้าย
        assertEquals("นี้", r.visible())
        assertEquals(4, r.delCount()) // 2 (delete) + 2 (delta retreat)
    }

    /**
     * variant: delete *ก่อน* finish (region ยังเปิด) = shrink preedit เงียบ ๆ, finish เก็บส่วนที่เหลือ
     *
     * re-gate round 28 (Korra 183 tests): expectation แรกของ test นี้ผิด (เขียน `นี้ต` = cut ที่
     * syllable) — semantics จริงที่ล็อกไว้ตั้งแต่ PreEditTest (ไม้โท U+0E49 นับแยก cp) คือ
     * **cut ที่ code-point boundary**: `นี้ต้อง` 7cp (mark ทุกตัวคือ cp ของมันเอง), delete(2,0) = ตัด 2cp ท้าย
     * (`ง`,`อ`) ไม่ใช่ grapheme cluster → เศษ = `นี้` + `ต` + U+0E49 (5cp, mark ยังเกาะ base ตัวเอง
     * ไม่ใช่ orphan) — ตรง units ของ Gboard ใน capture จริง: composing 1..8 ของ `นี้ต้องพ`
     * (mark = step ของมันเอง) และ delete(8,0) = 8 cp ทั้งคำ; app ลบเท่าที่ IME สั่ง = PTY byte
     * ซื่อตรงกับ keystroke — production ถูก, test ผิด (test-bug ไม่ใช่ production-bug)
     * assert ด้วย codePointCount / codePointAt ตรง ๆ ไม่ใช่เทียบ literal อย่างเดียว (บทเรียน PreEditTest L53)
     */
    @Test fun `delete while region open shrinks preedit silently and finish feeds only the remainder`() {
        val r = Replay()
        r.setComposingText("นี้ต้อง")
        assertEquals("delete ขณะ region เปิด = ยุบ preedit ห้ามเขียน byte", 0, r.deleteSurroundingText(2, 0).size)
        val after2 = r.preEdit.text
        assertEquals("เศษหลังตัด 2cp = 5cp พอดี (`นี้` + `ต` + ไม้โท — mark ไม่ถูกลบฟรี)",
            5, after2.codePointCount(0, after2.length))
        assertEquals("cp ที่ 5 (ท้าย) ต้องเป็น ไม้โท U+0E49 — ตัดแค่ `ง`,`อ`",
            0x0E49, after2.codePointAt(after2.offsetByCodePoints(0, 4))) // ASCII cp assertion = zero typing-risk
        assertEquals("delete ต่ออีก 1 = ปลดไม้โทท้าย (มันคือ cp จริง ไม่ใช่ส่วนเกินของ cluster)", 0, r.deleteSurroundingText(1, 0).size)
        r.finishComposingText() // เศษ 4cp = `นี้` (3cp) + `ต` → flush ครั้งเดียว
        assertEquals("เหลือ `นี้` + `ต` ตรง code point", "นี้ต", r.visible())
        assertEquals("path shrink preedit ห้ามมี DEL เข้า PTY", 0, r.delCount())
    }

    @Test fun `deleteSurroundingText with zero lengths writes nothing (dance marker, not DEL)`() {
        val r = Replay()
        for (s in listOf("x", "xy", "xyz")) r.setComposingText(s)
        r.finishComposingText()
        val bytes = r.deleteSurroundingText(0, 0)
        // บั๊กเดิม (side-finding ของ F-2): fall-through ส่ง 0x7f ทุก call — log จริงมี (0,0)/(?,0) ตรงนี้
        assertEquals("(0,0) = no-op ตาม contract — ห้ามมี byte เข้า PTY", 0, bytes.size)
        assertEquals("xyz", r.visible())
        assertTrue("window ต้องคงเปิดหลัง (0,0) (Gboard ลบ 0 แล้ว commit ตาม = dance)", r.preEdit.correctionOpen)
    }

    @Test fun `window closes on real events - commit and explicit close only append after`() {
        // commit ธรรมดา (เหตุการณ์จริง) ปิด window: commit ถัดไป = append ปกติ ห้าม DEL retreat
        val r = Replay()
        for (s in listOf("a", "ab")) r.setComposingText(s)
        r.commitText("abc")
        r.setComposingText("de")
        r.finishComposingText() // PTY = abcde, window เปิด
        r.closeCorrection() // = key จริง/IME teardown มาก่อน
        r.commitText("fg")
        assertEquals("after close = append ตรงๆ ไม่มี delta", "abcdefg", r.visible())
        assertEquals("ปิด window แล้วต้องไม่มี DEL", 0, r.delCount())
    }

    // ===== PreEdit window semantics (production decision machine — ตัวที่ view เรียกจริง) =====

    @Test fun `finish opens window and correctionCommit consumes with delta against common prefix`() {
        val p = PreEdit()
        p.setComposing("word2abc")
        assertEquals("word2abc", p.finish())
        assertTrue(p.correctionOpen)
        val d = p.correctionCommit("wor")!!
        assertEquals(8 - 3, d.del) // DEL retreat ถึง common prefix
        assertEquals("", d.rest)
        assertFalse("commit consume แล้ว window ต้องปิด", p.correctionOpen)
    }

    @Test fun `correctionCompose keeps window open for the next dance step`() {
        val p = PreEdit()
        p.setComposing("word2abc")
        p.finish()
        val d1 = p.correctionCompose("wor")!!
        assertEquals(5, d1.del)
        assertEquals("", d1.rest)
        assertTrue(p.correctionOpen)
        assertEquals("lastFinished = ทั้งก้อน (bytes อยู่ PTY แล้ว)", "wor", p.lastFinished)
        val d2 = p.correctionCompose("word")!!
        assertEquals("common prefix คราวนี้ = ทั้ง wor → ไม่ DEL", 0, d2.del)
        assertEquals("d", d2.rest)
    }

    @Test fun `correctionDelete shrinks window and keeps it open and no window hands back -1`() {
        val p = PreEdit()
        p.setComposing("word2abc")
        p.finish()
        assertEquals(1, p.correctionDelete(1))
        assertEquals("word2ab", p.lastFinished)
        assertTrue(p.correctionOpen)
        // "word2ab" = 7 code points → clamp ต้องคืน 7 (DEL ที่ view ส่งจริง) ไม่ใช่ 2 — 2 = PTY กับ
        // bookkeeping เพี้ยนกัน 5 ตัว (ตรงข้าม invariant ของ ticket 12; assertion เดิมคือเลขผิดของฉันเอง)
        assertEquals(7, p.correctionDelete(9)) // clamp ถึงหมด
        assertEquals("", p.lastFinished)
        assertEquals(-1, p.correctionDelete(1)) // window ปิด = view ใช้ path เดิม
    }

    @Test fun `delta is codepoint-safe across surrogate pairs`() {
        val p = PreEdit()
        p.setComposing("ab😀cd") // 5 code points
        p.finish()
        val d = p.correctionCommit("ab😀xy")!! // common = ab😀 = 3 cp → DEL = 2
        assertEquals(2, d.del)
        assertEquals("xy", d.rest)
    }

    @Test fun `empty commit closes window without feeding`() {
        val p = PreEdit()
        p.setComposing("x")
        p.finish()
        assertEquals(null, p.correctionCommit(""))
        assertFalse(p.correctionOpen)
    }

    // ===== source guards: wiring ที่ Replay mirror ต้อง ≡ production (wiring เลื่อน = แดง) =====

    @Test fun `view wires the window into every dance-capable callback`() {
        val tv = tvSrc()
        assertTrue("commitText ต้องลอง correctionCommit ก่อน path ปกติ (region-ปิด)",
            tv.substringAfter("override fun commitText(").substringBefore("override fun setComposingText(").contains("preEdit.correctionCommit("))
        assertTrue("setComposingText ต้องลอง correctionCompose (หลัง preempt/rollback)",
            tv.substringAfter("override fun setComposingText(").substringBefore("override fun finishComposingText(").contains("preEdit.correctionCompose("))
        assertTrue("deleteSurroundingText handler ต้องเรียก correctionDelete",
            tv.substringAfter("private fun deleteSurrounding(").substringBefore("override fun sendKeyEvent(").contains("preEdit.correctionDelete("))
        assertTrue("sendKeyEvent (เหตุการณ์จริง) ต้องปิด window",
            tv.substringAfter("override fun sendKeyEvent(").substringBefore("override fun commitCompletion(").contains("preEdit.closeCorrection()"))
        assertTrue("dispatchKey (physical key) ต้องปิด window",
            tv.substringAfter("private fun dispatchKey(").contains("preEdit.closeCorrection()"))
        assertTrue("feedBytes ต้องปิด window เมื่อไม่ใช่ feed ของ dance เอง",
            tv.substringAfter("fun feedBytes(b: ByteArray) {").contains("!danceFeed) preEdit.closeCorrection()"))
    }

    @Test fun `finish flushes immediately - deferred flush is forbidden by design`() {
        // user ตัดสิน (ticket 12): flush ต้องตามน้ำ — deferred = คำหายเมื่อกด Enter/backspace โดยไม่พิมพ์ซ้ำ
        val body = tvSrc().substringAfter("override fun finishComposingText(").substringBefore("override fun deleteSurroundingText(")
        assertTrue("finish ต้อง feed ทันที (feedText(fed) call มีอยู่จริงใน body)", body.contains("feedText(fed)"))
        assertFalse("ห้ามมี Handler/postDelayed ใน finish (deferred flush)", body.contains("postDelayed") || body.contains("Handler"))
    }

    /**
     * ticket 13 (F-2b): funnel (`feedBytes` L548) ปิด correction window สำหรับ feed ที่ไม่ใช่ของ dance —
     * flush ของ finish เองคือ bytes ที่ *สร้าง* PTY tail ≡ lastFinished จึงต้องถูก mark danceFeed;
     * wiring เก่า (unwrapped) = window ตายตอนเปิด = dance ทั้ง danceหาย → คำซ้ำบน 0.5.18 (oracle zsh)
     */
    @Test fun `the finish flush is marked danceFeed so the funnel cannot kill its own window`() {
        val body = tvSrc().substringAfter("override fun finishComposingText(").substringBefore("override fun deleteSurroundingText(")
        assertTrue("finish ต้อง set danceFeed = true ก่อน feedText(fed) ของตัวเอง (F-2b: funnel ปิด window ที่เพิ่งเปิด)",
            Regex("danceFeed\\s*=\\s*true[\\s\\S]{0,80}?feedText\\(fed\\)").containsMatchIn(body))
        assertTrue("ต้อง reset danceFeed = false ใน finally (เปิดค้าง = ทุก feed ถัดไปรอด funnel = window ผี)",
            Regex("finally\\s*\\{\\s*danceFeed\\s*=\\s*false\\s*\\}").containsMatchIn(body))
    }

    @Test fun `both deleteSurroundingText overloads are overridden and routed to one handler`() {
        val tv = tvSrc()
        // signature จริงถูก ground ด้วย compiler: `override` ผิดรูปกับ android-34 jar = compile fail
        // (javap blocked ใน session นี้ — จดใน ticket 12; ถ้า overload หายจาก jar = build แดงเอง)
        assertTrue(tv.contains("override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean ="))
        assertTrue(tv.contains("override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean ="))
        assertTrue(tv.contains("private fun deleteSurrounding(via: String, beforeLength: Int, afterLength: Int): Boolean"))
    }

    /**
     * round 28 watch item 1 (RETRO-round27): legacy ic trace พิมพ์ len=0 hardcode = ดู log แล้ว
     * แยกไม่ออกว่า dance จริงคือ (0,0) หรือ (k,0)+commit — ตรวจซ้ำบนเครื่องจริงไม่ได้
     * trace เป็น lambda ใน InputConnection ของ Android View = JVM test เรียกตรงไม่ได้ (ไม่มี Robolectric)
     * จึงล็อก *format ตรง ๆ ที่ source ของ production* (guard แดงถ้าใครกลับไป hardcode) +
     * behavior (0,0) vs (1,0) ครอบคลุมแล้วใน replay สอง test บน
     */
    @Test fun `the delete trace prints real before and after lengths, never a hardcoded zero`() {
        val tv = tvSrc()
        val h = tv.substringAfter("private fun deleteSurrounding(").substringBefore("override fun sendKeyEvent(")
        // format เดียวที่ host/log ต้องเห็น: ic <via> before=N after=M (สั้น ≡ สไตล์ imeSug, ห้ามมีเนื้อหา text)
        assertTrue("handler ต้อง log before/after จริง (ic \$via before=\$beforeLength after=\$afterLength)",
            h.contains("imeTrace(\"ic \$via before=\$beforeLength after=\$afterLength\")"))
        // ตรวจเฉพาะ *string ของ trace call* (`len=0")`) — comment ของ ticket 12 มีคำว่า "len=0" อยู่แล้ว,
        // assert แบบ substring เดี่ยวจะแดงปลอม ทั้งที่ production ถูก
        assertFalse("ห้ามมี trace call ที่ hardcode len=0 ใน delete handler (legacy = แยก dance ไม่ออก)",
            h.contains("len=0\")"))
        // trace ต้องมาก่อน (0,0) early-return — ไม่งั้นเคส (0,0) which the watch item ถามถึง จะเงียบจาก log
        val trace = h.indexOf("imeTrace(\"ic \$via")
        val noop = h.indexOf("if (beforeLength <= 0 && afterLength <= 0)")
        assertTrue("trace ต้อง fire ทุก call (0,0) รวมด้วย — มันคือเคสที่ watch item ให้แยก", trace in 0 until noop)
    }

    @Test fun `each delete overload forwards real lengths and its own name as the via label`() {
        val tv = tvSrc()
        // delegating override ห้ามย่อ length เป็น literal/สลับ label — log ต้องชี้ถูก overload + ตัวเลขจริง
        val plain = tv.substringAfter("override fun deleteSurroundingText(beforeLength: Int, afterLength: Int)")
            .substringBefore("override fun deleteSurroundingTextInCodePoints(")
        assertTrue("deleteSurroundingText ต้องส่ง beforeLength, afterLength จริง + label ตรงชื่อ",
            plain.contains("deleteSurrounding(\"deleteSurroundingText\", beforeLength, afterLength)"))
        val codePoints = tv.substringAfter("override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int)")
            .substringBefore("private fun deleteSurrounding(")
        assertTrue("InCodePoints ต้องเข้า handler เดียวกัน + label ของตัวมันเอง (IME ยุคใหม่เรียกตัวนี้ — 0.5.17 คือ silent path)",
            codePoints.contains("deleteSurrounding(\"deleteSurroundingTextInCodePoints\", beforeLength, afterLength)"))
    }

    @Test fun `the (0,0) no-op branch precedes every DEL feed in the delete handler`() {
        val h = tvSrc().substringAfter("private fun deleteSurrounding(").substringBefore("override fun sendKeyEvent(")
        val noop = h.indexOf("if (beforeLength <= 0 && afterLength <= 0)")
        val legacy = h.indexOf("feedBytes(byteArrayOf(0x7f))")
        assertTrue("ต้องมี (0,0) no-op branch", noop >= 0)
        assertTrue("ต้องมาก่อน fall-through DEL (บั๊กเดิม = DEL ปลอมทุก call)", noop < legacy)
    }
}
