package dev.aterminal.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * guard ของ partial-range `Canvas.drawText` — บทเรียน HW 11-04 (Honor PNM-N49, API 37):
 * `drawRun` ของ run ที่มี cell lock ถูกเปลี่ยนให้วาด `(start, count)` ต่อ cluster เพื่อเลี่ยง
 * TextShaper ต่อ cluster →เครื่องโยน `IndexOutOfBoundsException` **ไม่มี message** กลาง onDraw
 *
 * สาเหตุ (อ่าน AOSP `BaseRecordingCanvas` แล้ว): parameter ตัวที่สามของ
 * `drawText(String, start, X, x, y, paint)` **docs เขียนว่า count แต่ AOSP เป็น exclusive end** —
 * guard ของมันคือ `(start | end | end - start | text.length() - end) < 0` → `end - start` ติดลบ
 * ทันทีเมื่อ start > count = ทุก cluster หลังตัวแรกของ run; สองความหมายแยกกันเฉพาะ start > 0
 * (ซึ่งโค้ดทั้งแอปก่อนหน้านั้นวาดแต่ทั้ง string = start 0 → ไม่เคยชน)
 * `javap` ช่วยไม่ได้: มันให้ types เท่านั้น ไม่ใช่ semantics
 *
 * → ห้ามใช้ overload ที่มี partial range ใน :engine-render ทั้ง module (วาด substring ผ่าน
 * `drawText(text, x, y, paint)` ซึ่งมีความหมายเดียวทุก API level)
 */
class DrawApiGuardTest {

    /** ลบ comment ก่อนสแกน — ไม่งั้น guard จะฟ้อง *ข้อความที่อธิบายว่าทำไมห้ามเรียก* (เจอจริงรอบนี้) */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                c == '/' && n == '/' -> { while (i < src.length && src[i] != '\n') i++; out.append('\n') }
                c == '/' && n == '*' -> { val e = src.indexOf("*/", i + 2); i = if (e < 0) src.length else e + 2; out.append(' ') }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** call ของ `drawText(` ที่มี argument > 4 ตัว (= overload ที่มี range) พร้อม text ของมัน */
    private fun partialRangeCalls(src: String): List<String> {
        val hits = ArrayList<String>()
        var i = 0
        while (true) {
            i = src.indexOf("drawText(", i)
            if (i < 0) return hits
            var depth = 0
            var commas = 0
            var j = i + "drawText".length
            while (j < src.length) {
                when (src[j]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) break }
                    ',' -> if (depth == 1) commas++
                }
                j++
            }
            if (commas > 3) hits.add(src.substring(i, minOf(j + 1, src.length)))
            i = j + 1
        }
    }

    @Test fun `detector จับ partial-range call ได้จริงและไม่พังกับวงเล็บซ้อน`() {
        // guard ที่ตรวจไม่เจออะไร = guard ที่ไม่มีค่า → ทดสอบตัวตรวจเองก่อน
        assertEquals(
            1,
            partialRangeCalls("c.drawText(run.text, s, e - s, cx, baseline, p)").size,
        )
        assertTrue(partialRangeCalls("c.drawText(text, x, baseline, p)").isEmpty())
        assertTrue(partialRangeCalls("c.drawText(run.text.substring(s, e), cx, baseline, p)").isEmpty())
        assertTrue(partialRangeCalls("c.drawTextRun(text, s, e, 0, text.length, false, x, y, p)").isEmpty()) // ไม่ใช่ drawText(
        // comment ต้องไม่ถูกนับ (KDoc อธิบาย signature แบบเต็ม =เจอรอบนี้จริง)
        assertTrue(partialRangeCalls(stripComments("// c.drawText(t, s, e, x, y, p)\nc.drawText(t, x, y, p)")).isEmpty())
        assertEquals(1, partialRangeCalls(stripComments("/* drawText(t, s, e, x, y, p) */ c.drawText(t, s, e, x, y, p)")).size)
        // ตัว comment เองต้องถูกถอดออกจริง (ไม่ใช่แค่ทำให้ query หาไม่เจอ)
        val stripped = stripComments("// hidden: c.drawText(t, s, e, x, y, p)\nc.drawText(t, x, y, p)")
        assertTrue("comment ยังเหลือใน stripped: «$stripped»", "hidden" !in stripped)
        assertTrue("โค้ดจริงต้องยังอยู่: «$stripped»", "c.drawText(t, x, y, p)" in stripped)
    }

    @Test fun `module ห้ามเรียก drawText แบบ partial range`() {
        val srcRoot = File("src/main/kotlin")
        assertTrue("หา src ของ module ไม่เจอ (run จาก module dir): ${srcRoot.absolutePath}", srcRoot.isDirectory)
        val offenders = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> partialRangeCalls(stripComments(f.readText())).map { "${f.name}: $it" } }
            .toList()
        assertTrue(
            "partial-range drawText ถูกห้าม (semantics ของ arg ตัวที่ 3 = exclusive end ใน AOSP " +
                "ไม่ใช่ count ตาม docs → IOOBE บนเครื่องจริงเมื่อ start > 0; ดู KDoc ไฟล์นี้): $offenders",
            offenders.isEmpty(),
        )
    }
}
