package dev.aterminal.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard ของ history overlay input (tmux/08b — H1 FAIL บน Honor 11-10, build 0.5.13):
 * overlay ที่เปิดอยู่ = read-only menu surface — ทุกแหล่งกำเนิด byte ของ pane ต้อง yield:
 *  ⌨ (toggleKeyboard → revealIme = ทางเดียวที่คีย์บอร์ดเกิดได้, ADR 0001) และ
 *  keys bar (routeKey = bytes + "app:paste") — ส่วน open ต้องปิด IME ด้วย policy เอง
 *  (closeImeForMenus) ไม่พึ่ง onBeforeMenu ของ ⋮ ทางเดียว
 * root cause เดิม: overlay ป้องกันตัวด้วย requestFocus() ของ ComposeView อย่างเดียว ซึ่ง
 * ไม่กันทั้ง IME ที่ focus ค้างใน WebView และ bytes จาก bar — read-only จึงจริงแค่ตาเห็น
 * เป็น source-guard (แนว CloseImePolicyGuardTest) เพราะไม่มี Robolectric;
 * พฤติกรรมจริง = emulator gate: เปิด overlay แล้ว *พิมพ์จริง* ด้วย Gboard (บทเรียนรอบนี้:
 * matrix 08b วัดแค่ pull/anchor ไม่เคยพิมพ์ใส่ overlay = gate gap)
 */
class OverlayInputGuardTest {

    private fun src() = File("src/main/kotlin/dev/aterminal/app/MainActivity.kt").readText()

    @Test fun `the gate exists and reads overlay state only`() {
        // gate เดียวจุดเดียว — ทุก funnel อ้างตัวเดียวกัน (กันคนละ guard คนละ semantics)
        val s = src()
        assertTrue(
            "overlayHoldsInput ต้องมีจุดเดียวและอ่าน historyOverlay ตรง ๆ",
            Regex("""private fun overlayHoldsInput\(\): Boolean = historyOverlay != null""").containsMatchIn(s),
        )
        assertTrue("ต้องเป็น gate ที่ caller ใช้จริง (>= 3 sites: toggleKeyboard, routeKey, openHistoryOverlay ไม่ count)",
            Regex("overlayHoldsInput\\(\\)").findAll(s).count() >= 2)
    }

    @Test fun `keyboard button cannot birth the IME while the overlay is open`() {
        val body = funBody(src(), "private fun toggleKeyboard()")
        val guard = body.indexOf("overlayHoldsInput()")
        assertTrue("toggleKeyboard ต้องเช็ค overlay gate (⌨ คือทางเกิดเดียวของคีย์บอร์ด — ปิดทางนี้=ปิด IME path)", guard >= 0)
        val reveal = body.indexOf("revealIme(s)")
        assertTrue("guard ต้องอยู่ก่อน revealIme(s)", reveal >= 0 && guard < reveal)
    }

    @Test fun `keys bar bytes and app actions never reach the shell while the overlay is open`() {
        val body = funBody(src(), "private fun routeKey(key: String)")
        val guard = body.indexOf("overlayHoldsInput()")
        assertTrue("routeKey ต้องเช็ค overlay gate", guard >= 0)
        assertTrue("guard ต้องอยู่ก่อน paste (app action ส่งเข้า shell ได้)", guard < body.indexOf("pasteNow(fromClipboard = true)"))
        assertTrue("guard ต้องอยู่ก่อน PTY write (raw bytes)", guard < body.indexOf("s.controller.write(t)"))
    }

    @Test fun `openHistoryOverlay closes the IME surface itself before showing`() {
        val body = funBody(src(), "private fun openHistoryOverlay()")
        val close = body.indexOf("closeImeForMenus()")
        assertTrue("เปิด overlay ต้องปิด IME ด้วย menu policy เอง (overlay เป็น menu surface ด้วยตัวเอง)", close >= 0)
        assertTrue("ต้องปิดก่อน attach overlay (ov.open)", close < body.indexOf("ov.open()"))
    }
}
