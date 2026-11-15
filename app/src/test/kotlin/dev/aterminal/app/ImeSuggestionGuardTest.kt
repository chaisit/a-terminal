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

    /** src ของ :engine-render (guard ข้าม module) — file missing = throw, test แดงพร้อม path ใน stack */
    private fun tvSrc() = File("../engine-render/src/main/kotlin/dev/aterminal/render/TerminalView.kt").readText()

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
        val tv = tvSrc()
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

    // hw-backlog/07: ก้อน multi-char ที่ถูกคัดทิ้งที่ onInput หายเงียบจาก log = อ่าน log แล้วยังต้องเดา
    // ผู้เขียน (เจอจริงบน Honor 11-05: 9 ก้อน len=12 ต้องตีความด้วย narrative + เลข SGR)
    @Test fun `rejected multi-char writes leave a skip line in the ime log`() {
        // (round-18-env/04: shared funBody cut ที่ @JavascriptInterface แล้ว — ไม่ต้องป้องกันตัวเองอีก)
        val body = funBody(src("MainActivity.kt"), "fun onInput(text: String, source: String?)")
        val line = body.lineSequence().firstOrNull { it.contains("imeSug skip") }
        assertTrue("ก้อน >1 ตัวอักษรที่ไม่เข้าเกณฑ์ต้องพิมพ์บรรทัด skip (0.5.10 ทำเงียบ = อ่าน log ไม่ออก)", line != null)
        assertTrue("len+src+ctrl ต้องครบบนบรรทัดเดียว = คนอ่านแยก protocol traffic จากคำที่ IME แทรกได้โดยไม่ต้องรู้ SGR",
            line!!.contains("len=\${text.length}") && line.contains("src=\$") && line.contains("ctrl=\$ctrl"))
        assertFalse("ห้าม put ตัว text ลง log — IME log ถูก user คัดลอกออกนอกเครื่องได้", line.contains("\$text"))
        assertFalse("src ที่ log ต้องผ่าน whitelist แล้ว (free text จาก JS ห้ามเข้า log ดิบ ๆ = flood/ skew ได้)",
            body.contains("imeSug skip") && !body.contains("else \"other\""))
    }

    // HW 11-11 (round 26): drag ~10 ครั้งบน Honor = 301 skip lines กลืน 400-ring ใน 67s จน judgement
    // lines ถูก eviction หมด — แผนสำรองที่ตั๋ว 07 เขียนรอไว้ = "บรรทัดแรกต่อ source ต่อ 500ms"
    @Test fun `skip lines are throttled to one per label per 500ms`() {
        val body = funBody(src("MainActivity.kt"), "fun onInput(text: String, source: String?)")
        val gateAt = body.indexOf("skipLoggedAt[src]")
        val logAt = body.indexOf("imeSug skip")
        assertTrue("บรรทัด skip ต้องถูก gate ด้วยหน้าต่างต่อป้าย (src) — drag รัวห้ามท่วม ring", gateAt in 0 until logAt)
        // pin ทั้งก้อนเงื่อนไข (review 11-11: assert แยก part ปล่อยให้ gate ด้วยตัวแปร global ผ่านได้
        // ทั้งที่ละเมิด "bar/paste ห้ามเงียบตาม ime" — และตัวเปรียบเทียบกลับทิศก็หลุด)
        assertTrue("gate ต้องเทียบ now กับ เวลาของ *ป้ายเดียวกัน* (`skipLoggedAt[src]`) ด้วย >= — ก้อนเดียวห้ามแยก assert",
            body.contains("now - (skipLoggedAt[src] ?: -SKIP_LOG_THROTTLE_MS) >= SKIP_LOG_THROTTLE_MS"))
        assertTrue("ต้องจำเวลาล่าสุดต่อ src (map keyed by label, ไม่ใช่ global = bar/paste ห้ามเงียบตาม ime)",
            body.contains("skipLoggedAt[src] = now"))
        assertTrue("บรรทัดแรกต้องผ่านเสมอ — sentinel ห้ามเป็น MIN_VALUE (ลบแล้ว overflow = กลืนบรรทัดแรก)",
            body.contains("?: -SKIP_LOG_THROTTLE_MS"))
        val s = src("MainActivity.kt")
        assertTrue("หน้าต่าง = 500ms ตามแผนที่ตั๋ว 07", s.contains("SKIP_LOG_THROTTLE_MS = 500L"))
    }

    @Test fun `composition counts once per region in both renderers`() {
        val s = src("MainActivity.kt")
        assertTrue("webview: update ของคำเดียวกันห้ามสะสม strike",
            s.substring(s.indexOf("fun onImeComposing(data: String)")).contains("if (composing) return"))
        val tv = tvSrc()
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

    // hw-backlog/09 (Samsung round 25): อักขระที่เข้าทาง sendKeyEvent → dispatchKey (unicodeChar feed)
    // ถึง PTY ครบแต่เงียบใน IME log = นับ log ↔ จอ ไม่ตรง เกือบนำไปสู่ verdict ผิด
    @Test fun `soft keyevent characters are reported to the host as codepoints`() {
        val tv = tvSrc()
        assertTrue("sendKeyEvent (src = soft keyevent) ต้องบอก dispatchKey ว่ามาจาก IME",
            tv.substringAfter("override fun sendKeyEvent(").substringBefore("\n            }")
                .contains("dispatchKey(event, fromIme = true)"))
        val feed = tv.substringAfter("if (event.unicodeChar != 0 && event.isPrintingKey) {")
        assertTrue("branch ที่ feed อักขระจาก unicodeChar ต้องรายงาน host (guard fromIme = กัน spam จาก physical key)",
            feed.take(200).contains("if (fromIme) onImeKeyFeed?.invoke(event.unicodeChar)"))
    }

    @Test fun `the imeKey log line carries only the hex codepoint, never the text`() {
        val body = funBody(src("MainActivity.kt"), "private fun configureNative(")
        val line = body.lineSequence().firstOrNull { it.contains("imeKey feed") }
        assertTrue("host ต้องเขียน imeKey ลง IME log (เกณฑ์ 09: บรรทัด feed ≡ codepoints บนจอ)", line != null)
        assertTrue("ต้องเป็น hex codepoint (0e48 ≡ ที่ user เห็นใน trace/lab)", line!!.contains("0x%04x"))
        assertFalse("ห้าม interpolate ตัวอักขระ/raw value ลง log — IME log ถูก copy ออกนอกเครื่องได้",
            line.contains("\$cp") || line.contains("char"))
    }

    // ===== hw-backlog/10 (round 26, Tab S10+ K2): ทุก feed ของ preedit lifecycle ต้องมีบรรทัด =====
    // commit ที่ตกขณะ region เปิดนับเป็น 1 ใน 4 path เงียบที่ verify กับโค้ด 11-11 — เป้าตั๋ว =
    // log-completeness ("บรรทัด ≡ อักขระที่จอ") ไม่ใช่การอธิบายเคส K2 (honeyboard = wontfix 08)

    @Test fun `commit while a composition region is open leaves a judgement line`() {
        val body = tvSrc().substringAfter("override fun commitText(").substringBefore("override fun setComposingText(")
        val line = body.lineSequence().firstOrNull { it.contains("commit-in-composition") }
        assertTrue("commitText ขณะ composingWasOpen ห้าม feed เงียบ (silent path 1 — K2 under-count)", line != null)
        assertTrue("บรรทัดต้องมี len ของสิ่งที่ feed (นับ code point ไม่ใช่ code unit — surrogate ปลอดภัย)",
            line!!.contains("len=\$") && line.contains("codePointCount"))
        assertTrue("region ปิดแบบไม่มี feed (commit คำว่าง) ก็ต้องมี close line — ห้ามเงียบ",
            body.contains("preedit close commit"))
    }

    @Test fun `finishComposingText feed leaves a line`() {
        val body = tvSrc().substringAfter("override fun finishComposingText(").substringBefore("override fun deleteSurroundingText(")
        val line = body.lineSequence().firstOrNull { it.contains("finish len=") }
        assertTrue("finish ที่ feed หาง preedit เคยไม่มี log เลย (silent path 3 — proposal ข้อ 1 ของตั๋ว)", line != null)
        assertTrue("len ของสิ่งที่ feed (code point)", line!!.contains("codePointCount"))
    }

    @Test fun `preempt treat-as-final feed and raw flush leave lines`() {
        val tv = tvSrc()
        // lesson review 11-12 (spec axis): substringAfter คืนทั้งไฟล์เมื่อ anchor หาย = scope เงียบหายได้ — pin anchor ก่อน scope
        assertTrue("anchor preempt branch ต้องมีจริง (ไม่งั้น scope = ทั้งไฟล์แล้ว test เขียวปลอม)",
            tv.contains("if (preeditEnabled && preemptComposition()) {"))
        val preempt = tv.substringAfter("if (preeditEnabled && preemptComposition()) {").substringBefore("if (!preeditEnabled) {")
        val line = preempt.lineSequence().firstOrNull { it.contains("preempt-feed len=") }
        assertTrue("preempt branch feed ทีละ codepoint เงียบทั้งก้อน (silent path 4) — ต้องมีบรรทัดรวม 1 บรรทัด/gesture", line != null)
        assertTrue("len = สิ่งที่ feed (code point, นับครั้งเดียวก่อนวน loop)", line!!.contains("codePointCount"))
        assertTrue("preempt ที่ clear region ค้างแบบไม่มี feed = close line ห้ามเงียบ",
            preempt.contains("preedit close preempt"))
        val flush = tv.substringAfter("fun flushPreeditRaw(").substringBefore("\n    /**")
        val fline = flush.lineSequence().firstOrNull { it.contains("flush-raw len=") }
        assertTrue("flushPreeditRaw feedBytes ทั้ง pending โดยเคยไม่มี log (silent path ที่ 5 ที่ audit เจอ) — ต้องมีบรรทัด", fline != null)
        assertTrue("len ของ pending ที่ feed", fline!!.contains("codePointCount"))
    }

    @Test fun `preedit region open and every non-feed close leave a transition line`() {
        val tv = tvSrc()
        // open: setComposingText path ปกติ (ไม่ใช่ preempt) — region inactive → active
        val setPath = tv.substringAfter("if (!preeditEnabled) { if (text.isNotEmpty())").substringBefore("override fun finishComposingText(")
        assertTrue("region เปิด (inactive → active) ต้องมี `preedit open` — user ตัดสิน 11-11 เอามาด้วย",
            setPath.contains("imeTrace(\"preedit open\")"))
        // update ที่ region ยุบตัวเอง (text ≡ rawSentText = setComposing("") — เคยเงียบ)
        assertTrue("region ยุบแบบไม่มี feed ผ่าน setComposing ต้องไม่เงียบ (close update)",
            setPath.contains("preedit close update"))
        // close แบบไม่ feed ที่เหลือ: backspace (2 ทาง) / esc / swap
        val del = tv.substringAfter("override fun deleteSurroundingText(").substringBefore("override fun sendKeyEvent(")
        assertTrue("backspace ที่ยุบ region จนว่าง = close line (นับ code point ใน PreEdit แล้ว)",
            del.contains("preedit close backspace"))
        assertTrue("DEL ขณะ composing (dispatchKey) ก็มีทางยุบจนว่าง — ต้องมี close เช่นกัน",
            tv.substringAfter("KeyEvent.KEYCODE_DEL ->").substringBefore("\n").contains("preedit close backspace"))
        assertTrue("ESC = ทิ้ง composition (lossy transition ที่คนอ่าน log ต้องเห็น)",
            tv.substringAfter("KeyEvent.KEYCODE_ESCAPE ->").substringBefore("\n").contains("preedit close esc"))
        val swap = tv.substringAfter("fun attachEngine(").substringBefore("\n    /**")
        assertTrue("session swap ทิ้ง composition ค้าง = close swap (ไม่ใช่ silent loss)",
            swap.contains("preedit close swap"))
        // lesson review 11-12: trace line ห้ามมาแทน `clear()` — pin ทั้ง transition + การทิ้งจริง (behavior ไม่ใช่แค่ label)
        assertTrue("swap ต้อง `preEdit.clear()` จริง (trace อย่างเดียว = composition ค้างข้าม session = feed ลง session ใหม่ได้)",
            swap.contains("preEdit.clear()"))
    }

    @Test fun `the trace gate is one condition and reaches the ime log unthrottled`() {
        val tv = tvSrc()
        val gate = funBody(tv, "private fun imeTrace(")
        // lesson f39387a (review 07): pin ทั้งก้อนเงื่อนไข — assert แยก part ปล่อยให้ gate ผิดทิศ/ขาด flag ผ่านได้
        // amendment 11-12 (user ตัดสิน B): trace = diagnostic ไม่ใช่ strike → ห้ามสืบทอด strict gate
        // (วัดจริง emulator 11-12: Gboard compose เฉพาะ non-strict — gate มี imeStrict เมื่อไหร่ repro ปิดไม่ได้ + trace บอด mode ที่ compose จริง)
        assertTrue("gate ต้องเป็น `preeditEnabled` ล้วนทั้งก้อนบนบรรทัดเดียว (rollback = ห้ามมี trace แปลกปลอม)",
            gate.contains("if (preeditEnabled) onImeTrace?.invoke(detail)"))
        val body = funBody(src("MainActivity.kt"), "private fun configureNative(")
        val line = body.lineSequence().firstOrNull { it.contains("onImeTrace") }
        assertTrue("host ต้อง wire onImeTrace → IME log (view ส่งมาไม่มีใครเขียน = เหยื่อรอบ 07 ซ้ำ)", line != null)
        assertTrue("เขียนตรง imeLog(\"imeSug …\") — ไม่มี throttle map ของ 07 (user ตัดสิน 11-11: judgment lines รอด 500ms)",
            line!!.contains("imeLog(\"imeSug"))
        assertFalse("configureNative ห้ามรู้จัก skipLoggedAt (trace ≠ skip line — กันคนเอา throttle มาครอบภายหลัง)",
            body.contains("skipLoggedAt"))
    }

    // ===== hw-backlog/10 round 27 (Tab S10+ honeyboard): ring ว่าง 0 บรรทัด ic ทั้งที่พิมพ์ไทยจริง —
    // callback ของ InputConnection ที่เราไม่ได้ override = ถึง PTY โดยไม่มีทางเห็นใน log เลย;
    // และ non-strict commit ตอน region ปิด = feed เงียบ (BURST ยิงเฉพาะ strict) เกณฑ์ = ทุก
    // callback ที่ IME เรียกต้องมี `ic <method>` เป็นบรรทัดแรก + ทุก feed มี len (นับ code point) =====

    @Test fun `every required InputConnection override exists and opens its body with an ic line`() {
        val tv = tvSrc()
        assertTrue("anchor onCreateInputConnection ต้องมีจริง (funBody/substringAfter คืนทั้งไฟล์เมื่อ anchor หาย)",
            tv.contains("override fun onCreateInputConnection("))
        val ic = funBody(tv, "override fun onCreateInputConnection(")
        // (b) รายชื่อ override ตาม CHANGE 1 — หายตัวไหน = แดง (callback ที่ IME เรียกได้แต่เราไม่ override = silent path)
        for (name in listOf("commitText", "setComposingText", "finishComposingText", "deleteSurroundingText",
            "sendKeyEvent", "commitCompletion", "commitCorrection", "replaceText", "setComposingRegion",
            "performEditorAction",
        )) {
            assertTrue("override $name() ต้องอยู่ใน InputConnection ของเรา (round 27 coverage): $name",
                ic.contains("override fun $name("))
        }
        // (a) positive lock: ทุก override ที่รับ CharSequence/KeyEvent ต้องเปิด body ด้วย imeTrace("ic <name>
        val sigs = Regex("override fun (\\w+)\\(([^)]*)\\)").findAll(ic).toList()
        assertTrue("ต้องเจอ override signature จริง (regex พลาด = เขียวปลอม): ${sigs.size} ตัว", sigs.isNotEmpty())
        var checked = 0
        for (m in sigs) {
            val name = m.groupValues[1]
            val params = m.groupValues[2]
            if (!params.contains("CharSequence") && !params.contains("KeyEvent")) continue
            checked++
            val afterParams = ic.substring(m.range.last + 1)
            val open = afterParams.indexOf('{')
            assertTrue("$name ต้องมี body ({) — signature ผิดรูป", open >= 0)
            val firstStmt = afterParams.substring(open + 1).lineSequence()
                .map { it.trim() }
                .first { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("/*") && !it.startsWith("*") }
            assertTrue("body ของ $name ต้องเปิดด้วย imeTrace(\"ic $name (round 27: entry ห้ามเงียบ; พบ: $firstStmt)",
                firstStmt.startsWith("imeTrace(\"ic $name"))
            if (name == "sendKeyEvent") {
                assertTrue("ic sendKeyEvent ต้องมี action (แยก DOWN/UP/MULTIPLE จาก log)", firstStmt.contains("action="))
            }
        }
        assertTrue("ต้องตรวจ override ที่รับ text จริงอย่างน้อย commitText/setComposingText/sendKeyEvent (พบ $checked)",
            checked >= 3)
        // CHANGE 1: ACTION_MULTIPLE ที่มี characters = trace อย่างเดียว ห้าม feed (behavior change รอ user อนุมัติ)
        val sk = ic.substringAfter("override fun sendKeyEvent(").substringBefore("\n            }")
        assertTrue("ACTION_MULTIPLE + characters ต้องมีบรรทัด multiple len (round 27)",
            sk.contains("KeyEvent.ACTION_MULTIPLE") && sk.lineSequence().any { it.contains("imeTrace(\"multiple len=") })
        assertFalse("ACTION_MULTIPLE ห้ามถูก feed ที่นี่ (รอ user อนุมัติ — ห้ามแอบเปลี่ยน delivery)",
            sk.contains("feedText") || sk.contains("feedBytes"))
    }

    @Test fun `the imeSug judgement line is recorded even when the hint is suppressed`() {
        val body = funBody(src("MainActivity.kt"), "internal fun noteImeAutocomplete(")
        val logAt = body.indexOf("imeLog(\"imeSug")
        val guardAt = body.indexOf("if (hintedFor != null && hintedFor == imeId) return@runOnUiThread")
        assertTrue("ต้องมีทั้งบรรทัด imeSug และ hint-suppression return: log=$logAt guard=$guardAt",
            logAt >= 0 && guardAt >= 0)
        // round 27: IME ที่เตือนไปแล้วเคยทำให้ ring บอดทั้งที่พิมพ์จริง (zero BURST/COMPOSITION lines)
        assertTrue("imeLog( ต้องอยู่ก่อน hintedFor early return (log ทุกครั้ง, dialog เงียบเหมือนเดิม)", logAt < guardAt)
        // dialog behavior เดิม: guard ยัง return ก่อน strikes/dialog และ remember ยังอยู่หลัง shouldHint — pin ลำดับ
        val order = listOf("if (hintedFor != null && hintedFor == imeId) return@runOnUiThread",
            "if (!counts) return@runOnUiThread", "imeStrikes++", "ImeAutocomplete.shouldHint(",
            "putString(PREF_IME_HINT", "showImeSuggestionHint()")
            .map { body.indexOf(it) }
        assertTrue("ทุก step ของ path dialog ต้องมี: $order", order.all { it >= 0 })
        assertTrue("ลำดับ dialog path ห้ามเปลี่ยน: $order", order == order.sorted())
    }

    @Test fun `the non-strict commit with no open region leaves a commit line`() {
        // (d) silent path ที่เหลืออยู่ (round 27): !strict = BURST ไม่ยิง, region ปิด = commit-in-composition ไม่ยิง
        val body = tvSrc().substringAfter("override fun commitText(").substringBefore("override fun setComposingText(")
        assertTrue("เงื่อนไข+label ต้องอยู่บน statement เดียวกัน (colocated — lesson review 11-11 ห้ามแยก assert)",
            body.contains("if (!imeStrict && !composingWasOpen && fed != null) imeTrace(\"commit len="))
        val line = body.lineSequence().first { it.contains("imeTrace(\"commit len=") }
        assertTrue("len = สิ่งที่ feed จริง (fed ไม่ใช่ raw text ทั้งก้อน)", line.contains("fed"))
    }

    @Test fun `the preeditEnabled rollback bodies stay byte-identical`() {
        // (e) emergency path (AGENTS: preeditEnabled=false = path เดิม byte-for-byte) — snapshot pin
        // ทั้งก้อน execute ของ rollback สองที่; trace ใดๆ ห้ามเล็ดลอดเข้ามาในตัว (imeTrace เองก็ gate ด้วย preeditEnabled)
        val tv = tvSrc()
        assertTrue("rollback commitText (strict BURST → feedText → return) ห้ามถูกแตะ — snapshot ทั้งก้อน",
            tv.contains("if (imeStrict) onImeAutocomplete?.invoke(ImeAutocomplete.Kind.BURST, text.toString())\n                    feedText(text.toString()); return true"))
        assertTrue("rollback setComposingText (feed ทั้งก้อน → return) ห้ามถูกแตะ — snapshot ทั้งบรรทัด",
            tv.contains("if (!preeditEnabled) { if (text.isNotEmpty()) feedText(text.toString()); return true }"))
    }

    // lesson review 11-12 (spec axis): blacklist ชื่อตัวแปร (`imeTrace(rest)` ฯลฯ) = fail-soft —
    // ตัวแปรชื่ออื่น (เช่น `imeTrace(t)` ใน preempt) หลุดเกณฑ์ "len เท่านั้น ห้าม $text" — pin ฝั่งบวก:
    // ทุก call site ของ imeTrace( ต้องเปิดด้วย string literal (`)`) ไม่มีข้อยกเว้น — กัน future edit ที่เพิ่ม path ใหม่
    @Test fun `every trace call site passes a literal label never a text variable`() {
        val tv = tvSrc()
        val def = "private fun imeTrace("
        val defIdx = tv.indexOf(def)
        assertTrue("ต้องมีนิยาม imeTrace (source-guard นี้ยึดมันเป็นตัววัด)", defIdx >= 0)
        var idx = tv.indexOf("imeTrace(")
        while (idx >= 0) {
            if (idx !in defIdx until defIdx + def.length) {
                val open = idx + "imeTrace(".length
                assertTrue(
                    "call site ของ imeTrace ต้องเริ่มด้วย `\"` (literal label) — ห้ามส่ง variable ที่อาจมีเนื้อหา text " +
                        "(IME log ถูก copy ออกนอกเครื่องได้); พบ: ${tv.substring(idx, minOf(tv.length, idx + 40))}",
                    tv[open] == '"',
                )
            }
            idx = tv.indexOf("imeTrace(", idx + 1)
        }
    }
}
