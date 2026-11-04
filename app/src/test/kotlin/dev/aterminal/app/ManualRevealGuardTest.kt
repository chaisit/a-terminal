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

    @Test fun `IME log export works in release too but stays hidden`() {
        // user อนุมัติ 2026-11-04: A− export ออกจาก debug-only (เจอจริง: Honor ลง release → แตะแช่เงียบ,
        // ring เองก็ไม่ถูกบันทึกใน release) — แต่ทางเข้าเดียวคือ long-press ซ่อน ห้ามโผล่ visible ใน UI
        val s = src("MainActivity.kt")
        assertTrue("ring ห้ามถูก gate ทั้งก้อนด้วย debugImeLog — release ต้องมี log ให้ export",
            !s.contains("if (!debugImeLog) return"))
        assertTrue("long-press A− ห้ามถูก gate — release ต้องกดได้",
            !s.contains("if (debugImeLog) zoomOutBtn.setOnLongClickListener"))
        assertTrue("A− ต้อง wired อยู่ (ซ่อน — ห้ามย้ายเข้าเมนู visible)",
            s.contains("zoomOutBtn.setOnLongClickListener"))
    }
}
