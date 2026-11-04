package dev.aterminal.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard ของนโยบาย Manual Reveal (ADR 0001, round 22):
 * คีย์บอร์ดเกิดได้ทางเดียว = ปุ่ม ⌨ บน Keys Bar — test ประเภท source-guard (แนว ManifestGuardTest)
 * เพราะไม่มี Robolectric; พฤติกรรมจริงบนเครื่อง = emulator gate ของรอบ
 */
class ManualRevealGuardTest {

    private fun src(name: String) = File("src/main/kotlin/dev/aterminal/app/$name").readText()

    @Test fun `Pinned Zone has keyboard and enter buttons in both Keys Bars`() {
        for (f in listOf("ExtraKeysBar.kt", "MiniKeysBar.kt")) {
            val s = src(f)
            assertTrue("$f ต้องมีปุ่ม ⌨ (pinned zone)", s.contains("⌨"))
            assertTrue("$f ต้องส่ง Enter ผ่าน ExtraKeysBar.ENTER", s.contains("ENTER"))
        }
    }

    @Test fun `revealIme has a single caller — the keyboard button`() {
        val s = src("MainActivity.kt")
        // ไม่นับบรรทัดนิยาม `private fun revealIme(` — เหลือ call ได้จุดเดียวในทั้งไฟล์
        val calls = Regex("""(?<!fun )revealIme\(""").findAll(s).count()
        assertEquals("revealIme( ต้องถูกเรียกเฉพาะจาก toggleKeyboard (ADR 0001)", 1, calls)
    }

    @Test fun `auto-reveal helper focusAndReveal no longer exists`() {
        val s = src("MainActivity.kt")
        assertTrue("focusAndReveal = auto reveal ทุก caller เดิม — ห้าม resurrect (ADR 0001)",
            !s.contains("focusAndReveal"))
    }

    @Test fun `IME log export works in release too and has exactly one entry point`() {
        // user อนุมัติ 2026-11-04: export ออกจาก debug-only (เจอจริง: Honor ลง release → แตะแช่เงียบ,
        // ring เองก็ไม่ถูกบันทึกใน release) — round 23 task 7: status strip/A− ถูกลบ,
        // ทางเข้าเดียว ย้ายเป็น ⋮ overflow "Copy IME log" (brief pin เอง — policy "ซ่อน" เดิมถูก supersede)
        val s = src("MainActivity.kt")
        assertTrue("ring ห้ามถูก gate ทั้งก้อนด้วย debugImeLog — release ต้องมี log ให้ export",
            !s.contains("if (!debugImeLog) return"))
        assertTrue("export ห้ามถูก gate ด้วย debugImeLog — release ต้อง export ได้",
            !s.contains("if (debugImeLog) exportImeLog"))
        assertTrue("⋮ overflow ต้อง wired ไป exportImeLog (id copy_ime_log — ทางเข้าเดียว)",
            s.contains("OverflowItemId.copyImeLog -> exportImeLog()"))
        assertTrue("เมนู ⋮ ต้องมีรายการ Copy IME log",
            src("TabBar.kt").contains("Copy IME log"))
        // "exactly one entry point" ต้องวัดได้จริง: ไม่นับบรรทัดนิยาม = เหลือ call-site เดียวทั้งไฟล์
        val calls = Regex("""(?<!fun )exportImeLog\(""").findAll(s).count()
        assertEquals("exportImeLog( ห้ามมี door ที่สอง (call-site เดียว = ⋮ copy_ime_log)", 1, calls)
    }
}
