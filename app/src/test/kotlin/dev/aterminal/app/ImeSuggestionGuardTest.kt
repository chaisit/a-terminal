package dev.aterminal.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard ของ `settings.imeSuggestions` (HW 11-05) — source-guard แนว KeysBarAutoHideGuardTest
 * เพราะไม่มี Robolectric และสิ่งที่ต้องคุมคือ "ทั้งสอง renderer ต้องถูกแตะ + ค่า default ห้ามเปลี่ยน
 * พฤติกรรมที่ไทยเคยผ่าน gate" ส่วนผลจริง (strip หาย/ไทยยังพิมพ์ได้) = ตา user บนเครื่องจริง
 */
class ImeSuggestionGuardTest {

    private fun src(name: String) = File("src/main/kotlin/dev/aterminal/app/$name").readText()

    private fun funBody(s: String, signature: String): String {
        val start = s.indexOf(signature)
        assertTrue("ต้องพบ $signature", start >= 0)
        val next = s.indexOf("\n    private fun ", start + 1)
        val nextInternal = s.indexOf("\n    internal fun ", start + 1)
        val cut = listOf(next, nextInternal).filter { it >= 0 }.minOrNull() ?: s.length
        return s.substring(start, cut)
    }

    @Test fun `applyImeSuggestions drives both renderers from one decision`() {
        val body = funBody(src("MainActivity.kt"), "private fun applyImeSuggestions()")
        assertTrue("ต้องตัดสินผ่าน ImeSuggestions.strictNow +(hwKeyboardPresent()) ไม่ใช่ cache ค่าอื่น",
            body.contains("ImeSuggestions.strictNow(settings.imeSuggestions, hwKeyboardPresent())"))
        assertTrue("native pane ต้องได้ flag (TerminalView.imeStrict)", body.contains("imeStrict = strict"))
        assertTrue("webview pane ต้องได้ flag (ImeGateWebView.strictIme) — ห้ามลืม path ที่สอง", body.contains("strictIme = strict"))
    }

    @Test fun `every path that can change the decision re-applies it`() {
        val s = src("MainActivity.kt")
        assertTrue("เสียบ/ถอด physical keyboard (onConfigurationChanged) ต้อง re-apply",
            funBody(s, "override fun onConfigurationChanged(").contains("applyImeSuggestions()"))
        assertTrue("external edit ของ settings.json ต้อง re-apply (watcher reload)",
            s.contains("applyImeSuggestions() // HW 11-05: webview pane ไม่ผ่าน configureNative จึงต้องวนเอง"))
        assertTrue("configureNative ต้องส่ง flag ให้ view ทุกครั้งที่ reconfigure (invariant: view flags อยู่ที่ funnel เดียว)",
            funBody(s, "private fun configureNative(").contains("tv.imeStrict ="))
    }

    @Test fun `toggle persists before applying and refreshes the menu label`() {
        val body = funBody(src("MainActivity.kt"), "internal fun toggleImeSuggestions()")
        assertTrue("ค่าต้องผ่าน cycle ของ ImeSuggestions (ห้าม hardcode ลำดับเอง)",
            body.contains("ImeSuggestions.next(settings.imeSuggestions)"))
        val order = listOf("persistSettings()", "applyImeSuggestions()", "refreshTabs()")
            .map { body.indexOf(it) }
        assertTrue("ทุก step ต้องมี: $order", order.all { it >= 0 })
        assertTrue("ลำดับต้องเป็น persist → apply → label (${order})", order == order.sorted())
    }

    @Test fun `menu dispatches both new ids to handlers and holds no logic itself`() {
        val s = src("MainActivity.kt")
        val whenBlock = s.substring(s.indexOf("private fun runOverflow("))
        assertTrue(whenBlock.contains("OverflowItemId.imeSuggestions -> toggleImeSuggestions()"))
        assertTrue(whenBlock.contains("OverflowItemId.keyboardSettings -> openKeyboardSettings()"))
        val menu = src("TabBar.kt")
        assertTrue("เมนูต้อง dispatch เท่านั้น (ห้ามมี logic ใหม่ใน TabBar)",
            menu.contains("onOverflow(OverflowItemId.imeSuggestions)") && menu.contains("onOverflow(OverflowItemId.keyboardSettings)"))
    }

    @Test fun `native view keeps the shipped inputType unless strict is asked`() {
        val f = File("../engine-render/src/main/kotlin/dev/aterminal/render/TerminalView.kt")
        assertTrue("หา src ของ :engine-render ไม่เจอ (run จาก module dir): ${f.absolutePath}", f.isFile)
        val s = f.readText()
        val body = s.substring(s.indexOf("override fun onCreateInputConnection("))
        assertTrue("default ต้องเป็น NO_SUGGESTIONS (= พฤติกรรมที่ shipped มาตลอด, path ไทยผ่าน gate แล้ว)",
            body.contains("TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS"))
        assertTrue("โหมด strict = visible-password (IME เลิกเดา) เท่านั้นที่แตะ compose semantics",
            body.contains("TYPE_TEXT_VARIATION_VISIBLE_PASSWORD"))
        assertTrue("strict ต้องอ่าน flag จาก host ไม่ใช่ hardcode", body.contains("if (imeStrict)"))
        assertTrue("เปลี่ยน flag กลาง session ต้องให้ IME อ่าน EditorInfo ใหม่",
            s.contains("imm.restartInput(this)"))
    }

    @Test fun `webview gate only overrides what chromium reported when strict`() {
        val s = src("MainActivity.kt")
        val gate = s.substring(s.indexOf("private class ImeGateWebView"))
        assertTrue("gate เดิม (editorEnabled = null connection) ห้ามหาย — บทเรียน HW-5",
            gate.contains("if (editorEnabled) super.onCreateInputConnection(outAttrs) else null"))
        assertTrue("ไม่แตะ inputType เลยถ้า user ไม่ได้ขอ strict", gate.contains("if (ic != null && strictIme)"))
        assertTrue("strict ต้องเป็น visible-password เหมือน native (สอง renderer ต้องเหมือนกัน)",
            gate.contains("TYPE_TEXT_VARIATION_VISIBLE_PASSWORD"))
    }

    @Test fun `default policy is strict so a fresh install never suggests`() {
        val s = File("../terminal-core/src/main/kotlin/dev/aterminal/core/Settings.kt").readText()
        assertTrue("default ของ imeSuggestions ต้องเป็น STRICT (user ตัดสิน 11-05)",
            s.contains("val imeSuggestions: String = ImeSuggestions.STRICT,"))
        val boot = src("MainActivity.kt")
        assertTrue("ค่า boot ของ menu label ต้องอ่านจาก Settings ไม่ใช่ hardcode ค่าที่สอง",
            boot.contains("Settings().imeSuggestions)"))
    }

    @Test fun `both renderers report suspected autocomplete to one counter`() {
        val s = src("MainActivity.kt")
        assertTrue("native = callback ของ TerminalView ต้องถูกติดตอน configure",
            funBody(s, "private fun configureNative(").contains("tv.onImeAutocomplete ="))
        assertTrue("webview = burst วัดที่ PTY write (Chromium เป็นเจ้าของ InputConnection)",
            funBody(s, "fun onInput(text: String, source: String?)").contains("noteImeAutocomplete(ImeAutocomplete.Kind.BURST"))
        assertTrue("webview = composition events ต้องมี path ข้าม bridge",
            s.contains("fun onImeComposing(data: String)"))
        val tv = File("../engine-render/src/main/kotlin/dev/aterminal/render/TerminalView.kt").readText()
        assertTrue("native ต้องรายงานทั้งสองสัญญาณ",
            tv.contains("ImeAutocomplete.Kind.COMPOSITION") && tv.contains("ImeAutocomplete.Kind.BURST"))
        val js = File("src/main/assets/terminal.html").readText()
        assertTrue("JS ห้ามลืมส่ง composition = ฝั่ง webview จะไม่เคยเตือนเลย", js.contains("Droid.onImeComposing"))
    }

    @Test fun `the autocomplete nudge fires once per ime and remembers before showing`() {
        val body = funBody(src("MainActivity.kt"), "internal fun noteImeAutocomplete(")
        assertTrue("การตีความสัญญาณอยู่ที่ ImeAutocomplete เท่านั้น",
            body.contains("ImeAutocomplete.isSuggestion(") && body.contains("ImeAutocomplete.shouldHint("))
        assertTrue("ต้องวัด strict ปัจจุบันใหม่ (ห้าม cache ข้าม toggle)",
            body.contains("ImeSuggestions.strictNow(settings.imeSuggestions, hwKeyboardPresent())"))
        val remember = body.indexOf("putString(PREF_IME_HINT")
        val shown = body.indexOf("showImeSuggestionHint()")
        assertTrue("ต้องมีทั้งสอง step: remember=$remember shown=$shown", remember >= 0 && shown >= 0)
        assertTrue("จำก่อน show — crash/ dismissal กลาง dialog ห้ามทำให้เด้งซ้ำ", remember < shown)
    }

    @Test fun `the nudge dialog names the switches and shows the way out`() {
        val body = funBody(src("MainActivity.kt"), "private fun showImeSuggestionHint()")
        assertTrue("บอกสองสวิตช์ที่ต้องปิด (app ปิดให้ไม่ได้)",
            body.contains("Auto-replace") && body.contains("Show suggestion strip"))
        assertTrue("ต้องปิด IME ก่อนแสดง dialog (policy task 10)", body.contains("closeImeForMenus()"))
        assertTrue("ปุ่มเดียวที่พาไปจริงคือ path ของ ⌨️ Keyboard settings…", body.contains("openKeyboardSettings()"))
        assertTrue("dialog ทุกตัวต้องผ่าน guard (round 9 BadToken)", body.contains("showGuarded(this)"))
    }

    @Test fun `each judgement is recorded in the IME log without the text itself`() {
        val body = funBody(src("MainActivity.kt"), "internal fun noteImeAutocomplete(")
        assertTrue("ต้องมีบันทึกการตัดสิน (Honor ไม่เด้งเลย = ต้องแยก \"IME ไม่ดื้อ\" จาก \"เราวัดไม่ถึง\")",
            body.contains("imeLog(\"imeSug"))
        assertTrue("record อะไรที่ filter = ต้องเห็น strict/verdict/strikes",
            body.contains("strict=\$strict") && body.contains("verdict=") && body.contains("strikes="))
        assertTrue("ความยาวพอเป็นหลักฐาน", body.contains("len=\${text.length}"))
        assertFalse("ห้าม put ตัว text ลง log — IME log ถูก user คัดลอกออกนอกเครื่องได้", body.contains("\$text"))
    }

    @Test fun `a burst is tied to the composition that produced it`() {
        val body = funBody(src("MainActivity.kt"), "internal fun noteImeAutocomplete(")
        assertTrue("ต้องบันทึกเวลาของ signal composition ล่าสุด", body.contains("lastCompositionAt = now"))
        assertTrue("ไม่เคย compose เลย = NaN (ห้ามแปลเป็น 0 แล้วเปิดหน้าต่างตลอด)",
            body.contains("if (lastCompositionAt == 0L) Double.NaN"))
        assertTrue("เกณฑ์ต้องได้รับหน้าต่างนี้ด้วย", body.contains("isSuggestion(kind, strict, text, composing, clipText(), since)"))
        assertTrue("log ต้องมี since = ครั้งหน้าไม่ต้องเดา", body.contains("since=\$since"))
    }

    @Test fun `the webview funnel labels who produced each write`() {
        // funnel เดียวกันรับ upstream bytes ทุกก้อน (พิมพ์ / Keys Bar / paste) = ถ้าไม่ติดป้าย
        // เราจะนับ mouse report ของการลากนิ้วเป็น "คำที่ IME เติม" (เจอจริงใน IME log ของ Honor)
        val body = funBody(src("MainActivity.kt"), "fun onInput(text: String, source: String?)")
        assertTrue("ต้องเชื่อป้ายจาก JS เท่านั้น (null = ime = ค่า default ที่ปลอดภัย)",
            body.contains("source ?: ImeAutocomplete.SRC_IME") && body.contains("src == ImeAutocomplete.SRC_IME"))
        assertTrue("control bytes = protocol traffic = ห้าม even เข้าเกณฑ์", body.contains("ImeAutocomplete.plausibleImeText(text)"))
        assertTrue("ป้ายต้องโผล่ใน log ด้วย ไม่งั้นอ่าน log แล้วไม่รู้แหล่งที่มา", body.contains("src=\$src"))
        val html = File("src/main/assets/terminal.html").readText()
        val keys = File("src/main/assets/aterm-keys.js").readText()
        assertTrue("paste ต้องถูกป้าย (⚡/clip = user สั่งเอง)", html.contains("AtermSrc.set('paste')"))
        assertTrue("preset ของ Keys Bar ต้องถูกป้าย (aterm-keys sendInput → term.input → onData = ผ่าน funnel เดียวกัน)",
            keys.contains("AtermSrc.set('bar')"))
        assertTrue("Keys Bar bytes ที่ Kotlin divert เข้า JS ก็ต้องถูกป้าย", keys.contains("Droid.onInput(data, 'bar')"))
        assertTrue("onData ต้องส่งป้ายที่ take() มาได้ (กัน dedup กลืนก้อนแล้วป้ายค้าง)", html.contains("Droid.onInput(d, src)"))
        for ((name, js) in listOf("terminal.html" to html, "aterm-keys.js" to keys)) {
            val oneArg = Regex("Droid\\.onInput\\([^,)]*\\)").findAll(js).map { it.value }.toList()
            assertTrue("$name ต้องเรียก onInput แบบสอง arg เสมอ (JS bridge ไม่มี overload = TypeError กลางการพิมพ์): $oneArg",
                oneArg.isEmpty())
        }
    }

    @Test fun `composition counts once per region in both renderers`() {
        val s = src("MainActivity.kt")
        assertTrue("webview: update ของคำเดียวกันห้ามสะสม strike",
            s.substring(s.indexOf("fun onImeComposing(data: String)")).contains("if (composing) return"))
        val tv = File("../engine-render/src/main/kotlin/dev/aterminal/render/TerminalView.kt").readText()
        assertTrue("native: เกณฑ์เดิมคือ region ใหม่ (= !preEdit.active)",
            tv.contains("imeStrict && preeditEnabled && !preEdit.active"))
    }

    @Test fun `the webview proves the funnel is alive and syncs composing from input events`() {
        val s = src("MainActivity.kt")
        assertTrue("ต้องมีหลักฐานว่า onInput ของ bridge วิ่งจริง (ถ้าไม่มี = IME log จะเงียบเท่ากันสองกรณี)",
            funBody(s, "fun onInput(text: String, source: String?)").contains("funnelProven"))
        val js = File("src/main/assets/terminal.html").readText()
        assertTrue("input event ต้อง sync isComposing ให้ Kotlin (end ที่หลุดหาย = กลืน burst ทั้งก้อน)",
            js.substring(js.indexOf("function reportIme")).contains("e.isComposing"))
    }

    @Test fun `keyboard settings shortcut has a fallback chain and tells the user what to flip`() {
        val body = funBody(src("MainActivity.kt"), "private fun openKeyboardSettings()")
        assertTrue("ต้องมี fallback (vendor บางรายไม่มีหน้า subtype)",
            body.contains("ACTION_INPUT_METHOD_SUBTYPE_SETTINGS") && body.contains("ACTION_INPUT_METHOD_SETTINGS"))
        assertTrue("แต่ละ step ต้องครอบ runCatching (ActivityNotFound ห้าม crash)", body.contains("runCatching"))
        assertTrue("บอก user ด้วยว่าต้องปิดอะไรใน IME (app สั่งไม่ได้)", body.contains("Text correction"))
    }
}
