package dev.aterminal.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard ของ top bar v2 (round 23 task 7): คราวเดียวจบ — แถบ status เดียวกับ A−/A+/⚡
 * ต้องหายไปจริง (ห้ามเหลือเป็นแถวที่สอง) และทุก action ย้ายเข้า ⋮ overflow ของ ATerminalTabBar
 * เป็น source-guard (แนว ManualRevealGuardTest) เพราะไม่มี Robolectric; พฤติกรรมจริง = emulator gate ของรอบ
 */
class TopBarV2GuardTest {

    private fun src(name: String) = File("src/main/kotlin/dev/aterminal/app/$name").readText()

    @Test fun `status strip row is deleted — single-row top bar only`() {
        val s = src("MainActivity.kt")
        // แถบ status (name 82×58 + A−/A+/⚡) ห้ามกลับมาเป็น view ที่สองใต้ tab strip
        assertTrue("ห้ามมี root.addView(strip — status strip ยุคสองแถวห้าม resurrect",
            !s.contains("root.addView(strip"))
        assertTrue("stripButton (ตัวสร้าง A−/A+/⚡) ต้องถูกลบ — action ย้ายเข้า ⋮ ทั้งหมด",
            !s.contains("stripButton"))
    }

    @Test fun `ATerminalTabBar v2 owns the dropdown menus and overflow dispatch`() {
        val t = src("TabBar.kt")
        assertTrue("ATerminalTabBar ต้องรับ onOverflow (id → handler เดิมของ MainActivity)",
            t.contains("onOverflow: (OverflowItemId) -> Unit"))
        assertTrue("∨ new-tab menu + ⋮ overflow ต้องเป็น Compose DropdownMenu",
            t.contains("DropdownMenu("))
    }

    @Test fun `single-tab title strip long-press opens the pane menu`() {
        // review fix round 23: pane menu (Split/Find/MARK MODE/Close tab) เป็น touch entry เดียว —
        // single tab = default state ถ้า long-press ไม่เรียก onMenu = feature เข้าไม่ถึงด้วยนิ้ว
        val t = src("TabBar.kt")
        val start = t.indexOf("if (tabs.size <= 1)")
        val end = t.indexOf("} else {", start)
        assertTrue("ต้องเจอ single-tab branch ใน ATerminalTabBar", start >= 0 && end > start)
        val singleTab = t.substring(start, end)
        assertTrue("single-tab long-press ต้องเรียก onMenu(tabs.first().id) (review ruling)",
            singleTab.contains("onLongClick = { tabs.firstOrNull()?.let { onMenu(it.id) } }"))
        assertTrue("single-tab tap ยังเป็น onStatusTap (retry-on-error ไม่เปลี่ยน)",
            singleTab.contains("onClick = onStatusTap"))
        // stop-reconnect gesture ของ strip เดิมต้องมีที่ทาง: conditional item ใน pane menu
        assertTrue("pane menu ต้องมี item stop reconnect (เฉพาะตอน countdown) → statusLongPress",
            src("MainActivity.kt").contains("\"⏹  Stop reconnect\" to { statusLongPress() }"))
    }

    // ---- task 7b (device-FAIL fix round): pill row ชนะ status เรื่อง width + ∨/⋮ เป้า 44dp ----
    // guards ผูก STRUCTURE เท่านั้น (ตัวเลข 112/96/44 tunable ±16dp ตาม ruling — device คือ arbiter)

    @Test fun `multi-tab status yields width to the pill row`() {
        val t = src("TabBar.kt")
        val start = t.indexOf("} else {")
        val end = t.indexOf("// ∨ = new-tab picker", start)
        assertTrue("ต้องเจอ multi-tab branch ใน ATerminalTabBar", start >= 0 && end > start)
        val multi = t.substring(start, end)
        // R1 root cause: sibling น้ำหนักสองตัวถูก hard cap ทีละส่วนแบ่ง (RowColumnMeasurePolicy) → sliver
        assertTrue("status Text ห้ามกลับมา weight(1f) — ต้อง non-weighted ถึงจะ yield ที่ให้ pill row",
            !multi.contains("weight(1f)"))
        // cap ต้องผูกกับ "site ของ status" จริง (review Important-1): contains ทั่ว slice = vacuous
        // เพราะ active-title widthIn(max) ก็อยู่ใน slice เดียวกัน — ใช้ window technique เดียวกับ 44dp pin
        // parked (aa): หน้าต่าง 80 ตัวมี headroom ~21 ตัว — สอด modifier เพิ่มระหว่าง cap กับ combinedClickable
        // = guard ร้อง (conservative false-FAIL ที่ตั้งใจ: การ rearrange site ของ status ต้องมาจากเจตนา ไม่ใช่ slip)
        assertTrue("status ต้องมี width cap ผูกกับ modifier ของตัวมันเอง (R4: ellipsize แทนดันปุ่มหลุดจอ)",
            Regex("""widthIn\(max = \d+\.dp\)[\s\S]{0,80}?combinedClickable\(onClick = onStatusTap""")
                .containsMatchIn(multi))
        assertTrue("status ต้อง ellipsize (maxLines=1 + Ellipsis)",
            multi.contains("TextOverflow.Ellipsis"))
        // R3 pin (I2 เดิม): ห้ามลบทิ้ง — Boxes ที่วัดทีหลังจะได้ maxWidth 0 ตอน pill กว้าง
        assertTrue("pill row ต้องคง weight(1f, fill = false) (R3 pin — I2 ∨/⋮ zero-width ห้าม regen)",
            multi.contains("weight(1f, fill = false)"))
        assertTrue("title แบบ fix width(120.dp) ของ active pill ต้องหายไปแล้ว (ใช้ widthIn cap)",
            !t.contains("width(120.dp)"))
        // R1: viewport ของ pill row ต้อง auto-scroll มาที่ pill active
        assertTrue("ต้องมี auto-scroll ไปหา pill active (onGloballyPositioned + animateScrollTo)",
            multi.contains("onGloballyPositioned") && multi.contains("animateScrollTo"))
    }

    @Test fun `both menu glyph buttons sit in 44dp touch containers`() {
        val t = src("TabBar.kt")
        // ∨ และ ⋮ คนละ 1 กล่อง — count exactly 2 (site ใหม่เพิ่มต้องเป็น decision ไม่ใช่ accident)
        assertTrue("∨ กับ ⋮ ต้องมีกล่อง touch container widthIn(min = 44.dp) ครบสองจุดเท่านั้น",
            Regex("""widthIn\(min = 44\.dp\)""").findAll(t).count() == 2)
        // clickable ต้องอยู่บน Box (กล่อง ≥44) ไม่ใช่บน Text — กล่องต่างหากที่เป็น touch target
        assertTrue("∨: clickable ต้องอยู่บน Box 44dp เดียวกัน (call onBeforeMenu(); newMenu = true คงรูปเดิม)",
            Regex("""widthIn\(min = 44\.dp\)[\s\S]{0,80}?clickable \{ onBeforeMenu\(\); newMenu = true \}""")
                .containsMatchIn(t))
        assertTrue("⋮: clickable ต้องอยู่บน Box 44dp เดียวกัน (call onBeforeMenu(); overflowMenu = true คงรูปเดิม)",
            Regex("""widthIn\(min = 44\.dp\)[\s\S]{0,80}?clickable \{ onBeforeMenu\(\); overflowMenu = true \}""")
                .containsMatchIn(t))
        // glyph ต้องไม่มี space กันขอบใน textbox เดิม — centering เป็นหน้าที่ contentAlignment ของ Box
        // (ช่องว่างใน glyph คือต้นเหตุ visual center ≠ text box center = hit-zone anomaly)
        assertTrue("glyph ∨ ต้องไม่มี padding spaces ใน string literal",
            t.contains("\"∨\",") && !t.contains("\" ∨ \""))
        assertTrue("glyph ⋮ ต้องไม่มี padding spaces ใน string literal",
            t.contains("\"⋮\",") && !t.contains("\" ⋮ \""))
    }

    // ---- round 23 amendment (Update Check): ทางเข้าเมนู ⋮ + version line (menu = entry, logic อยู่ที่ Activity) ----

    @Test fun `update check entry points exist in the overflow menu with version line`() {
        val t = src("TabBar.kt")
        assertTrue("เมนู ⋮ ต้องมีทางเข้า Check for updates (dispatch ผ่าน onOverflow เดียว)",
            t.contains("OverflowEntry(\"🔄 Check for updates\", chrome) { overflowMenu = false; onOverflow(OverflowItemId.checkUpdates) }"))
        assertTrue("เมนู ⋮ ต้องมีสวิตช์ update checks (label On/Off อ่านจาก barSnap)",
            t.contains("🌐 Update checks: ") && t.contains("onOverflow(OverflowItemId.updateChecksToggle)"))
        assertTrue("บรรทัด version ท้ายเมนู (user เช็คสถานะได้โดยไม่ต้องต่อเน็ต)",
            t.contains("A-Terminal v\$appVersion"))
        val s = src("MainActivity.kt")
        assertTrue("manual (รวมที่ queue ซ้อน auto — F2) = ไม่เคารพ dismissed; auto = ผ่าน shouldNotify",
            s.contains("answered -> showUpdateNotice(newer)") &&
                s.contains("settings.updateChecks && UpdateCheck.shouldNotify(newer.first().tag, settings.dismissedUpdateTag)"))
        // Q2 + F6 (review): "ครั้งเดียวต่อ release" = ครั้งเดียวต่อ *การเห็นจริง* —
        // persist dismissedUpdateTag ต้องเกิดหลัง showGuarded และเฉพาะ dialog ที่ขึ้นจริง (dropped = ไม่ mark)
        val notice = s.substring(s.indexOf("private fun showUpdateNotice"))
        assertTrue("persist dismissedUpdateTag ต้องอยู่หลัง showGuarded && ใน if (shown != null)",
            notice.indexOf("showGuarded") in 0 until notice.indexOf("dismissedUpdateTag = top.tag") &&
                notice.contains("if (shown != null)"))
        // F1 (review): auto check ต้องอยู่ onResume (FGS ทำ process อยู่เป็นวัน — onCreate-only = ไม่เช็คซ้ำ)
        val resume = s.substring(s.indexOf("override fun onResume"))
        assertTrue("onResume ต้องเรียก shouldAutoCheck (throttle เป็น scheduler)",
            resume.substring(0, resume.indexOf("override fun onPause")).contains("shouldAutoCheck"))
        // F3 (review): versionName อ่านไม่ได้ =ห้ามตอบ "up to date"
        assertTrue("checkUpdates ต้อง guard unparseable appVersion ก่อน fetch",
            s.contains("parseVersion(appVersion) == null"))
    }
}
