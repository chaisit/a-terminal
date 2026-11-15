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
 */
class TerminalViewInputConnectionTest {

    private fun tvSrc() = File("src/main/kotlin/dev/aterminal/render/TerminalView.kt").readText()

    /** replay harness: PTY bytes as written, ตามลำดับ — 1:1 กับ handler logic ใน TerminalView */
    private class Replay {
        val preEdit = PreEdit()
        val pty = ByteArrayOutputStream()
        var rawSentText = ""

        private fun feed(s: String) { pty.write(s.toByteArray(Charsets.UTF_8)) }
        private fun dels(n: Int) { repeat(n) { pty.write(0x7f) } }

        fun commitText(t: String) {
            val composingWasOpen = preEdit.active
            if (!composingWasOpen) {
                val delta = preEdit.correctionCommit(t)
                if (delta != null) {
                    rawSentText = ""
                    dels(delta.del)
                    if (delta.rest.isNotEmpty()) feed(delta.rest)
                    return
                }
            }
            val rest = stripWrittenPrefix(t)
            rawSentText = ""
            val fed = preEdit.commit(rest)
            if (fed != null) feed(fed)
        }

        fun setComposingText(t: String) {
            val delta = preEdit.correctionCompose(t)
            if (delta != null) {
                rawSentText = t
                dels(delta.del)
                if (delta.rest.isNotEmpty()) feed(delta.rest)
                return
            }
            if (rawSentText.isNotEmpty() && t.startsWith(rawSentText)) preEdit.setComposing(t.substring(rawSentText.length))
            else { rawSentText = ""; preEdit.setComposing(t) }
        }

        fun finishComposingText() {
            val fed = preEdit.finish()
            if (fed == null) preEdit.closeCorrection()
            feed0rNull(fed)
            rawSentText = ""
        }

        private fun feed0rNull(s: String?) { if (s != null) feed(s) }

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
            pty.write(0x7f)
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
        assertTrue("finish ต้อง feed ทันที (fed?.let { feedText(it) })", body.contains("fed?.let { feedText(it) }"))
        assertFalse("ห้ามมี Handler/postDelayed ใน finish (deferred flush)", body.contains("postDelayed") || body.contains("Handler"))
    }

    @Test fun `both deleteSurroundingText overloads are overridden and routed to one handler`() {
        val tv = tvSrc()
        // signature จริงถูก ground ด้วย compiler: `override` ผิดรูปกับ android-34 jar = compile fail
        // (javap blocked ใน session นี้ — จดใน ticket 12; ถ้า overload หายจาก jar = build แดงเอง)
        assertTrue(tv.contains("override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean ="))
        assertTrue(tv.contains("override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean ="))
        assertTrue(tv.contains("private fun deleteSurrounding(via: String, beforeLength: Int, afterLength: Int): Boolean"))
    }

    @Test fun `the (0,0) no-op branch precedes every DEL feed in the delete handler`() {
        val h = tvSrc().substringAfter("private fun deleteSurrounding(").substringBefore("override fun sendKeyEvent(")
        val noop = h.indexOf("if (beforeLength <= 0 && afterLength <= 0)")
        val legacy = h.indexOf("feedBytes(byteArrayOf(0x7f))")
        assertTrue("ต้องมี (0,0) no-op branch", noop >= 0)
        assertTrue("ต้องมาก่อน fall-through DEL (บั๊กเดิม = DEL ปลอมทุก call)", noop < legacy)
    }
}
