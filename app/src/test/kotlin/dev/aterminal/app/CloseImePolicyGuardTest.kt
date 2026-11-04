package dev.aterminal.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard ของนโยบาย dialog/IME (round 23 task 10):
 * เมนู/dialog ทุกตัวต้องปิด IME ก่อนเปิด (menu = user-requested IME close ตาม ADR 0001 —
 * จากนี้ IME เกิดได้ทาง revealIme หรือ user tap field เท่านั้น ห้าม auto-reopen)
 * และ KeyPopup ต้อง focusable เพื่อ BACK ปิด popup แทนรั่วไป BackPolicy → ESC (hole ที่ A2 พิสูจน์แล้ว)
 * เป็น source-guard (แนว KeysBarAutoHideGuardTest) เพราะไม่มี Robolectric; พฤติกรรมจริง = emulator gate ของรอบ
 */
class CloseImePolicyGuardTest {

    private fun src(name: String) = File("src/main/kotlin/dev/aterminal/app/$name").readText()

    @Test fun `every showGuarded call in ConnectUi is preceded by closeImeForMenus in the same function body`() {
        val lines = src("ConnectUi.kt").lines()
        // ขอบเขต fun = member fun indent 4 space เท่านั้น (nested fun/override ใน listener indent ลึกกว่า = ไม่นับ)
        val funOpen = Regex("""^ {4}(internal |private |suspend )*fun \w+""")
        val starts = lines.indices.filter { funOpen.containsMatchIn(lines[it]) } // anchored ^ ต่อ line = prefix match
        val sites = lines.indices.filter { lines[it].contains(".showGuarded(") }
        assertTrue("guard ต้อง scan เจอ showGuarded จริง (คาดหวัง >= 10 จุด, พบ ${sites.size})", sites.size >= 10)
        for (i in sites) {
            val body = starts.lastOrNull { it < i }
            assertTrue("showGuarded บรรทัด ${i + 1} ต้องอยู่ภายใน member fun (source-scan หา body ไม่เจอ)", body != null)
            val before = lines.subList(body!!, i).joinToString("\n")
            assertTrue(
                "showGuarded บรรทัด ${i + 1} (`${lines[i].trim()}`) ห้ามเปิด dialog โดยไม่ปิด IME ก่อน — " +
                    "ขาด closeImeForMenus() ทางเดียวกันใน fun เดียวกัน",
                before.contains("closeImeForMenus"),
            )
        }
    }

    @Test fun `toast stays explicitly excluded from the menu policy (not a dialog)`() {
        // `.show()` ตรงตัวที่เหลือได้ใน ConnectUi = Toast ที่ guard ด้วย canShowDialog เท่านั้น
        // (dialog ห้าม .show() ตรง — R5 funnel; toast ไม่ crash และไม่มี IME flow = ไม่นับเป็น menu)
        val lines = src("ConnectUi.kt").lines()
        val direct = lines.indices.filter { lines[it].contains(".show()") && !lines[it].contains(".showGuarded(") }
        assertEquals("ต้องมี .show() ตรง (toast) จุดเดียวใน ConnectUi", 1, direct.size)
        assertTrue("จุดนั้นต้องเป็น Toast ผ่าน canShowDialog", lines[direct[0]].contains("Toast.makeText"))
        assertTrue("toast ต้องยัง guard ด้วย canShowDialog (R5)", lines[direct[0]].contains("canShowDialog"))
    }

    @Test fun `closeImeForMenus is the composite of the established hide path and never opens the IME`() {
        val s = src("MainActivity.kt")
        val start = s.indexOf("internal fun closeImeForMenus()")
        assertTrue("closeImeForMenus ต้องเป็น internal fun บน MainActivity (caller = ConnectUi + TabBar call site)", start >= 0)
        val end = s.indexOf("\n    }\n", start)
        assertTrue("ต้องปิด body ของ closeImeForMenus", end > start)
        val body = s.substring(start, end)
        // composite ≡ ฝั่ง hide ของ toggleKeyboard + guard เดียวกับ closeImeForSelection (ADR-clean)
        assertTrue("menu = user สั่งปิด — protection ต้องสิ้นสุด (ห้าม auto-reopen, ADR 0001)",
            body.contains("imeUserRequested = false"))
        assertTrue("ต้องปิดวาล์วทุก pane ก่อน hide (HW-5c cross-tab hole)", body.contains("closeFocusValveAllPanes()"))
        assertTrue("ต้อง hide ด้วย window token จริง", body.contains("hideSoftInputFromWindow"))
        assertTrue("ต้อง restartInput ลบ viewClicked กัน vendor stack เปิดกลับ", body.contains("restartInput"))
        assertTrue("overlay ที่มี EditText focus (find bar/dialog field) = IME flow ของมัน ห้ามแย่ง",
            body.contains("currentFocus is android.widget.EditText"))
        assertTrue("fun นี้ปิด IME อย่างเดียว — ห้าม showSoftInput (ทางเปิดเดียว = revealIme)",
            !body.contains("showSoftInput"))
    }

    @Test fun `showPaneMenu (long-press menu in MainActivity) also closes IME first`() {
        // menu policy กินบริเวณทุก dialog menu — showPaneMenu เปิด AlertDialog ตรงใน MainActivity
        // (นอกรั้ว ConnectUi) จึงต้อง pin ตรงนี้ ไม่งั้นเป็น hole ที่ guard ชุดบนมองไม่เห็น
        val s = src("MainActivity.kt")
        val start = s.indexOf("private fun showPaneMenu(")
        assertTrue("ต้องพบ showPaneMenu", start >= 0)
        val body = s.substring(start, s.indexOf(".showGuarded(", start))
        assertTrue("showPaneMenu ห้ามเปิดเมนูทับ IME", body.contains("closeImeForMenus()"))
    }

    @Test fun `KeyPopup is focusable so BACK dismisses the popup instead of leaking ESC (A2)`() {
        val s = src("KeyPopup.kt")
        // A2 device-proven: ctor arg 4 (focusable) = false → BACK ทะลุ popup ไป BackPolicy = ESC ยิงเข้า PTY
        assertTrue("PopupWindow ต้องสร้างด้วย focusable = true",
            s.contains("PopupWindow(content, width, cappedHeight, true)"))
        assertTrue("ห้ามกลับไป focusable = false ที่ ctor", !s.contains("cappedHeight, false)"))
        // outside-touch dismiss ต้องคงอยู่ (ExtraKeysBar ⋯ depends) — จำเป็นต้องมี bg + outsideTouchable
        assertTrue("isOutsideTouchable ต้องคง (dismiss-on-entry ไม่ regress)", s.contains("isOutsideTouchable = true"))
        assertTrue("bg จำเป็นต่อ outside-touch (PopupWindow เงียบถ้าไม่มี)", s.contains("setBackgroundDrawable"))
    }

    @Test fun `both TabBar v2 menus close IME before opening, wired at the ComposeView call site`() {
        val tb = src("TabBar.kt")
        assertTrue("TabBar ต้องรับ onBeforeMenu (composable ไม่มี Activity ref — policy อยู่ที่ caller)",
            tb.contains("onBeforeMenu: () -> Unit"))
        // ทั้งสอง toggle ต้องเป็น compound — ข้างหนึ่งขาด = เมนูนั้นเป็น hole
        assertTrue("∨ picker ต้องเรียก onBeforeMenu ก่อน expanded=true",
            Regex("""onBeforeMenu\(\); newMenu = true""").containsMatchIn(tb))
        assertTrue("⋮ overflow ต้องเรียก onBeforeMenu ก่อน expanded=true",
            Regex("""onBeforeMenu\(\); overflowMenu = true""").containsMatchIn(tb))
        val s = src("MainActivity.kt")
        val start = s.indexOf("tabBar = ComposeView")
        val block = s.substring(start, s.indexOf("root.addView(tabBar", start))
        assertTrue("call site ต้อง wire onBeforeMenu → closeImeForMenus (policy เดียวกับ ConnectUi)",
            block.contains("onBeforeMenu = { closeImeForMenus() }"))
    }
}
