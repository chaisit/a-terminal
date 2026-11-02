package dev.aterminal.app

import android.view.View
import android.webkit.WebView
import dev.aterminal.core.Profile
import dev.aterminal.engine.TerminalEngine

/**
 * per-tab holder (round 4): หนึ่ง Compose tab = หนึ่ง TerminalSession = หนึ่ง renderer + หนึ่ง SessionController
 *  - terminal.html ถูกโหลด "ทั้งไฟล์" ต่อ WebView — layer IME/touch/mouse/clipboard ที่ verify บน device
 *    แล้วทั้งชุดทำงานเหมือนเดิมทุก tab (นี่คือเหตุผลที่เลือก WebView-per-tab แทน one-WebView-N-terms)
 *  - round 9 (engine="native"): webView = null, view = TerminalView (:engine-render), engine = :engine
 *    ทุก JS call ต้องผ่าน js() (no-op เมื่อ native) — path ของ webview ห้ามเปลี่ยน semantics
 *  - fontSize/cols/rows เป็นของต่อบน (hidden webview ยัง layout อยู่ → fit/onResize ทำงานตอน rotate ด้วย)
 *  - error: ผิดพลาดของ connect flow (probe/TOFU/missing host) ซึ่งไม่อยู่ใน controller.state
 */
// webView default = derive จาก view เสมอ — กัน call site สร้าง webview session แล้วลืมส่ง argument
// (เคยพลาดจริง round 9: TerminalSession(wv) → webView=null → native==true → CCE ที่ startConnect)
class TerminalSession(view: View, webView: WebView? = view as? WebView) {
    // var เพราะ renderer ตาย = WebView ตัวเก่าใช้ต่อไม่ได้ ต้องสลับตัวใหม่เข้า pane เดิม (session/SSH ยังอยู่)
    var view: View = view
    var webView: WebView? = webView
    /** true ระหว่างหน้าโหลดใหม่หลัง renderer ตาย: output ถูกเก็บใน rendererRecovery จนกว่า JS จะพร้อม */
    var recovering = false
    val rendererRecovery = RendererRecovery()

    lateinit var controller: SessionController
    var profile: Profile? = null
    var fontSize: Int = 14
    /** round 15-HW: profile.fontFace ที่ใช้จริง (set ตอน startConnect) — "" = ยังไม่มี profile, ปล่อย terminal.html ใช้ default ของมันเอง */
    var fontFace: String = ""
    var cols = 80
    var rows = 24
    var error: String? = null
    var pageReady = false

    /** native path เท่านั้น — สร้างตอน startConnect (ทราบ profile/history ตอนนั้น) */
    var engine: TerminalEngine? = null
    val native: Boolean get() = webView == null

    /** evaluateJavascript ปลอดภัยทุก engine: webview = เดิมทุกตัวอักษร, native = no-op */
    fun js(script: String) { webView?.evaluateJavascript(script, null) }

    /**
     * connect ที่เรียกก่อน JS พร้อม (tab ใหม่ loadUrl ยังไม่จบ) — เก็บไว้ flush ใน onPageFinished
     * (พบจริง round 4: banner ของ backend หาย เพราะ evalJs Aterm.onBytes ยิงตอน Aterm ยังไม่มี)
     */
    var pendingConnect: (() -> Unit)? = null

    /**
     * T3 (round 12): auto-reconnect ต่อ pane (user อนุมัติ = auto backoff + banner)
     *  - job = countdown coroutine ที่ lifecycleScope; null/ไม่ active = ไม่มีคิว dial
     *  - countdownSec = ตัวเลขบน banner (1s tick); ไม่ใช่ source of truth (job.isAlive ต่างหาก)
     */
    val reconnect = ReconnectPolicy()
    var reconnectJob: kotlinx.coroutines.Job? = null
    var countdownSec = 0

    /**
     * T4 (round 12): dial generation — ทุก dial (auto/manual) จับคู่กับ gen;
     * schedule ใหม่หรือ manual connect = gen++ ซึ่ง "ยกเลิก" dial เก่าที่ in-flight
     * (stale dial ห้าม openSession — เจอจริง: blackhole matrix สร้าง session เกือกม้า 2 ตัวจาก
     * probe ที่คาบเกี่ยวกันของ dial สองรุ่น)
     */
    var dialGen = 0

    /**
     * T4 (round 13): เคย CONNECTED สำเร็จอย่างน้อย once — ticket เดียวที่จะเข้า session-restore snapshot
     * (ไม่เคย connect เลย = ไม่มีอะไรให้ reconnect; set ครั้งเดียวที่ onSessionStatus CONNECTED)
     */
    var everConnected = false
}
