package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * guard สำหรับ JS ที่เรียก xterm API — บทเรียน round 7: เขียน `buffer.y` จาก memory
 * ทั้งที่ bundle 5.5 มี getter จริงคือ cursorX/cursorY (พังเงียบ = NaN) — ตรวจ API กับ bundle
 * เสมอ และล็อกการใช้ที่นี่ไว้ (ไฟล์ assets อ่านจาก module dir = app/)
 */
class AssetApiGuardTest {
    private fun asset(name: String): String = File("src/main/assets", name).readText()

    @Test
    fun `aterm-keys ใช้ cursorX-cursorY เท่านั้น ห้าม buffer-x-y ที่จำผิดมา`() {
        val js = asset("aterm-keys.js")
        assertTrue("posAbs ต้องอ่าน b.cursorX", "b.cursorX" in js)
        assertTrue("posAbs ต้องอ่าน b.cursorY", "b.cursorY" in js)
        assertFalse("ห้ามมี b.x (ไม่มีใน IBuffer 5.5)", Regex("""\bb\.x\b""").containsMatchIn(js))
        assertFalse("ห้ามมี b.y (ไม่มีใน IBuffer 5.5)", Regex("""\bb\.y\b""").containsMatchIn(js))
    }

    @Test
    fun `aterm-find ใช้ API ที่ verify กับ bundle แล้ว`() {
        val js = asset("aterm-find.js")
        for (api in listOf("getLine(", "translateToString(", "viewportY", "scrollLines(", "term.select(")) {
            assertTrue("aterm-find ต้องใช้ $api (verify กับ xterm.js bundle แล้ว)", api in js)
        }
    }

    @Test
    fun `boot marker ต้องตรงที่ gate G0 มองหา`() {
        assertTrue("A-Terminal — connecting…" in asset("terminal.html"))
    }

    // ---- round 15-HW (Honor V6): font bundling + touch/IME — 3 copies ของ stack ต้องตรงกัน ----
    @Test
    fun `bundled font มีจริง และ html อ้าง canonical path + ตัวเลือก metrics`() {
        assertTrue("fonts/terminal.ttf ต้องถูก commit (subset OFL)", File("src/main/assets/fonts/terminal.ttf").length() > 100_000)
        assertTrue("fonts/OFL.txt ต้องติด APK (OFL-1.1 requires license copy)", File("src/main/assets/fonts/OFL.txt").length() > 100)
        val html = asset("terminal.html")
        assertTrue("ต้องมี @font-face ชี้ canonical path", "@font-face" in html && "fonts/terminal.ttf" in html)
        assertTrue("lineHeight ต้องเปิด (ไทย 3 ระดับ — bundle 5.5 ไม่มี fontAscender)", "lineHeight" in html)
        assertTrue("setFontFace bridge ต้องมี (Kotlin push profile.fontFace)", "setFontFace" in html)
    }

    // ---- round 15: Unicode 11 ทั้ง webview และ engine (ตารางเดียวกัน) ----
    @Test
    fun `unicode11 addon — vendored, โหลดแบบ fail-soft, เปิด proposed API`() {
        assertTrue(File("src/main/assets/xterm/addon-unicode11.js").length() > 10_000)
        val html = asset("terminal.html")
        assertTrue("""<script src="xterm/addon-unicode11.js"></script>""" in html)
        assertTrue("allowProposedApi: true" in html)
        assertTrue(Regex("""try \{[\s\S]{0,200}term\.unicode\.activeVersion = '11'""").containsMatchIn(html))
    }

    // ---- round 15 publish: license ต้องติด APK และผู้ใช้เปิดอ่านได้ ----
    @Test
    fun `third-party notices ติด APK ครบ และเปิดจากแอปได้`() {
        val notices = asset("licenses/THIRD_PARTY_NOTICES.txt")
        for (needle in listOf("The xterm.js authors", "addon-unicode11", "Apache MINA SSHD", "Apache License", "QOS.ch", "CC BY 4.0", "SIL OPEN FONT LICENSE")) {
            assertTrue("notices ต้องมี $needle", needle.lowercase() in notices.lowercase())
        }
        val kt = File("src/main/kotlin/dev/aterminal/app/ConnectUi.kt").readText()
        assertTrue("ต้องมีเมนูเปิด notices", "licenses/THIRD_PARTY_NOTICES.txt" in kt)
    }

    // ---- round 15-HW-2 (Honor V6): canvas renderer — rescaleOverlappingGlyphs มี impl จริงแค่ใน
    // addon-canvas/webgl (verify src 5.5.0: BaseRenderLayer.allowRescaling; DOM renderer = no-op) ----
    @Test
    fun `canvas addon — vendored file + script tag + loadAddon fail-soft`() {
        assertTrue("xterm/addon-canvas.js ต้องถูก commit (vendor MIT 0.7.0)", File("src/main/assets/xterm/addon-canvas.js").length() > 50_000)
        val html = asset("terminal.html")
        assertTrue("script tag ต้องโหลด addon หลัง xterm.js", """<script src="xterm/addon-canvas.js"></script>""" in html)
        assertTrue("loadAddon ผ่าน namespace UMD (CanvasAddon.CanvasAddon)", "CanvasAddon.CanvasAddon" in html && "term.loadAddon(new CA())" in html)
        // addon ต้องโหลดหลัง open() (activate ต้องมี core renderer service) และอยู่ใน try (fail-soft)
        assertTrue("loadAddon ต้องใต้ try (asset พัง = ไม่พัง boot)", Regex("""try \{[\s\S]{0,400}term\.loadAddon\(new CA\(\)\)""").containsMatchIn(html))
        assertTrue("script tag ต้องอยู่ก่อน script ของ term open (addon global ต้องพร้อม)",
            html.indexOf("xterm/addon-canvas.js") < html.indexOf("term.open("))
        // option ที่เปิดไว้ตั้งแต่ round 15-HW — มี canvas renderer แล้วจึงมีความหมาย
        assertTrue("rescaleOverlappingGlyphs ต้อง set (active ภายใต้ canvas renderer)", "rescaleOverlappingGlyphs: true" in html)
    }

    @Test
    fun `fontFace default — kt เท่ากับ defaults-json และ family แรกคือ bundled family ใน HTML`() {
        val fromJson = org.json.JSONObject(asset("settings.defaults.json"))
            .getJSONObject("profiles").getJSONObject("defaults").getString("fontFace")
        val fromCore = dev.aterminal.core.Profile().fontFace
        val html = asset("terminal.html")
        val face = Regex("""@font-face\s*\{\s*font-family:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
        checkNotNull(face) { "ไม่พบ @font-face family ใน terminal.html" }
        assertTrue("json ≡ core: $fromJson vs $fromCore", fromJson == fromCore)
        assertTrue("family แรกของ stack ต้อง ≡ ชื่อใน @font-face: $fromJson", fromJson.startsWith(face))
    }

    // ---- round 15-HW-3 (Honor V6): Gboard word-predict แทนคำที่ commit แล้ว ทำให้ input เพี้ยน —
    @Test
    fun `bundled xterm — P5 keydown ล้าง guard ส่งซ้ำ ตัวซ้ำติดกันไม่หาย`() {
        val js = File("src/main/assets/xterm/xterm.js").readText()
        assertTrue("""this._keyDownSeen=!0,this._dataAlreadySent="",""" in js)
    }

    // P4 send-then-clear: ทุกจุดส่ง bytes ใน vendored xterm ต้องเคลียร์ helper textarea (ดู docs/xterm-umd-patch.md) ----
    @Test
    fun `bundled xterm — P4 send-then-clear ครบทุก send site + shim clearTimeout ก่อน clear`() {
        val js = File("src/main/assets/xterm/xterm.js").readText()
        assertTrue("P4a: _inputEvent (บน core) ส่งแล้ว clear ผ่าน _compositionHelper",
            """this._compositionHelper._isComposing||(clearTimeout(this._compositionHelper._textareaRetryTimer),this._compositionHelper._textarea.value="")""" in js)
        assertTrue("P4b: retry chain (P1) ส่งแล้ว clear (composing early-return ครอบอยู่แล้ว)",
            """this._coreService.triggerDataEvent(t,!0),this._textarea.value=""};""" in js)
        val guarded = Regex(Regex.escape("""this._isComposing||(clearTimeout(this._textareaRetryTimer),this._textarea.value="")"""))
        assertEquals("P4c+P4d: finalize 2 branch ต้องมี guard-clear ตรงกัน 2 จุด", 2, guarded.findAll(js).count())
        // shim ฝั่ง app = clear site ที่ 5 — ต้องฆ่า retry chain ด้วย (ไม่งั้น DEL เก้อ)
        assertTrue("shim ต้อง clearTimeout retry chain ก่อน clear", "clearTimeout(ch._textareaRetryTimer)" in asset("terminal.html"))
    }

    @Test
    fun `focus valve — html ปิดวาล์ว textarea-focus, Kotlin เปิด-ปิด ตาม gate`() {
        val html = asset("terminal.html")
        // HW-5: วาล์ว = accessor + `disabled` sync (wrap focus เดี่ยว ๆ กันได้แค่ path JS —
        // Chromium native touch-focus/long-press ไม่ผ่าน wrapper ต้อง kill ระดับ DOM/attribute)
        assertTrue("วาล์วต้องเป็น accessor บน window", "Object.defineProperty(window, '__atermAllowFocus'" in html)
        assertTrue("ปิดวาล์วต้อง sync ta.disabled (กัน vendor focus path)", "ta.disabled = !allow" in html)
        assertTrue("ต้อง wrap ta.focus", "ta.focus = function" in html)
        val kt = File("src/main/kotlin/dev/aterminal/app/MainActivity.kt").readText()
        assertTrue("revealIme ต้องเปิดวาล์วก่อน term.focus", "__atermAllowFocus=true" in kt)
        assertTrue("releaseImeHold (IME ปิด) ต้องปิดวาล์ว+blur", "__atermAllowFocus=false" in kt)
        // HW-5: editor-state lifetime —DOWN preempt + View-level gate (vendor อ่าน onCheckIsTextEditor)
        assertTrue("ACTION_DOWN ต้อง preempt เมื่อ IME ปิด", "preemptImeAtDown" in kt)
        assertTrue("ต้องมี ImeGateWebView (onCheckIsTextEditor=false ตอนปิด)", "onCheckIsTextEditor" in kt)
        assertTrue("IC ต้อง null ตอน gate ปิด", "onCreateInputConnection" in kt)
        // HW-5c: วาล์ว+restartInput ต้องครอบ ทุก tab (viewClicked-reopen ข้าม tab ของ vendor IMS)
        assertTrue("วาล์ว/preempt ต้องวนทุก tab (manager.ids)", Regex("""for \(id in manager\.ids\(\)\)""").containsMatchIn(kt))
    }
}
