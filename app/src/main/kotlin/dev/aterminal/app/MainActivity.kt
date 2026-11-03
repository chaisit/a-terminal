package dev.aterminal.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.platform.ComposeView
import dev.aterminal.backend.ssh.KeyCheck
import dev.aterminal.backend.ssh.SshBackend
import dev.aterminal.backend.termux.TermuxBackend
import dev.aterminal.core.BackendError
import dev.aterminal.core.CloseReason
import dev.aterminal.core.Command
import dev.aterminal.core.CommandCodec
import dev.aterminal.core.KeybindingResolver
import dev.aterminal.core.Profile
import dev.aterminal.core.ResolvedTable
import dev.aterminal.core.doubleArg
import dev.aterminal.core.intArg
import dev.aterminal.core.strArg
import dev.aterminal.core.Settings
import dev.aterminal.core.SettingsLoader
import dev.aterminal.core.SettingsSync
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend
import dev.aterminal.core.Scheme
import dev.aterminal.engine.TerminalEngine
import dev.aterminal.render.Palette
import dev.aterminal.render.TerminalView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A-Terminal — multi-tab / split-pane SSH terminal (round 5)
 *
 * layout: [Compose tab strip] [status strip + A−/A+/⚡] [per-tab container] [ExtraKeysBar]
 *  - หนึ่ง tab = 1–2 pane, pane = หนึ่ง WebView + หนึ่ง SessionController (terminal.html โหลดเดิมทั้งไฟล์)
 *  - container ต่อ tab = LinearLayout [wrapper0 | divider | wrapper1] — สร้างครั้งเดียวต่อ tab,
 *    split/ปิด pane = สลับ visibility + weight เท่านั้น **ไม่ re-parent WebView เลย** (InputConnection ไม่หลุด)
 *  - memory valve ตัวเดียว = PaneLru (WebView ~60–70MB/ตัว); SessionManager tab-cap 4 เป็น double guard
 *  - focus routing ทั้ง app ผ่าน activePane() = tab active → focused pane (เดิม manager.active() ตัวเดียว)
 *  - limitation เท่าเดิม: session เป็น activity-scoped (rotate/process-death = session หาย)
 */
class MainActivity : ComponentActivity() {
    private var connectionDiagnostics: ConnectionDiagnostics? = null
    private var lastConfig: android.content.res.Configuration? = null

    internal lateinit var secrets: SecretsStore
    internal lateinit var connectUi: ConnectUi
    internal lateinit var settingsUi: SettingsUi
    private var settingsWatcher: SettingsWatcher? = null
    private var lastSettingsHash: String? = null   // กัน watcher reload ไฟล์ที่เพิ่ง persistSettings เอง
    // round 7: ตาราง keybinding ที่ resolve+normalize แล้ว (grammar อยู่ core — D1); JS แค่ lookup
    private var keyTableJson: String = "[]"
    private var keyTable: ResolvedTable = ResolvedTable(emptyList()) // T4: native views ใช้ object เดียวกัน (ไม่ parse ซ้ำ)
    // pane ที่ markMode on อยู่ (JS เป็นเจ้าของ state — Kotlin จำไว้ divert keysbar bytes — D6)
    private val markOn = java.util.Collections.synchronizedSet(mutableSetOf<TerminalSession>())
    private lateinit var root: LinearLayout
    // round 15-HW (Honor V6): "scroll two fingers ≠ พิมพ์" — gesture gate ระดับ window (ดู GestureRoot/observeGesture)
    private lateinit var tapGate: dev.aterminal.render.input.TapGate
    private var gesturePane: TerminalSession? = null
    private var imeUpAtDown = false                     // snapshot ตอน DOWN — non-tap ที่เริ่มจากปิด = ห้ามเด้ง
    private var imeServedPane: TerminalSession? = null // pane ล่าสุดที่ได้ revealIme — จุด release focus ตอน IME ปิด
    // round 15-HW-2 (Honor V6): early-hide — IME ของ Honor ลอบเด้ง *กลาง* gesture (ไม่ใช่แค่หลัง DOWN 5ms)
    private var imeBustedSeen = false                  // edge flag: gesture นี้เห็น busted แล้ว (กัน spam ทุก MOVE)
    private var imeHideGen = 0L                        // generation ของ pending retry — DOWN ใหม่ = invalidate
    // round 15-HW-3: "IME เปิดอยู่" ต้องแยก user สั่ง (revealIme) vs ระบบ/IME ลอบเปิดค้าง —
    // แบบหลังไม่ใช่ของ user ห้าม protect; revealIme=true, IME up→down=false
    private var imeUserRequested = false
    // round 15-HW-4 (Honor V6): long-press = โหมดเลือกข้อความ → hold-timer เก็บ IME แม้ user เปิดเอง;
    // flag = gesture นี้ hold-hide แล้ว → UP/CANCEL ต้อง routed ผ่าน closeImeForSelection (vendor re-show ห้าม reopen)
    private var imeHoldHidden = false
    private var imeLongPressMs = 500L                  // ค่าระบบจริง (ViewConfiguration) — กำหนด hold-timer
    private val debugImeLog by lazy { (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private lateinit var termContainer: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var tabBar: ComposeView
    private lateinit var keysBar: ExtraKeysBar
    private lateinit var miniBar: MiniKeysBar
    private lateinit var keyboardWatcher: KeyboardWatcher
    private var imeUp = false
    private var forceFullKeys = false     // "⋯" บน mini bar — บังคับ bar เต็มจน IME ปิด/rotate

    // manager unit = tab (1–2 panes); pane = WebView = หน่วย memory จริง → PaneLru คือวาล์วหลัก
    private val manager = SessionManager<TerminalTab<TerminalSession>>()
    private val lru = PaneLru<TerminalSession>(cap = 5)
    private val tabViews = HashMap<Long, TabViews>()

    // snapshot ที่ Compose tab strip read — refreshTabs() เป็นผู้เขียนอย่างเดียว
    private var tabSnap by mutableStateOf(listOf<TabInfo>())
    private var activeTabId by mutableStateOf(0L)

    // divider drag state (pointer เดียว — field ง่ายกว่า tag)
    private var dragFrac0 = 0.5f
    private var dragAxis0 = 0f

    internal var settings = Settings()
    // T4 (round 13): snapshot write debounce — force-stop ไม่เรียก onDestroy เลย, debounce บ่อยคือ gate จริง
    private val restoreWrite = Runnable { flushSnapshotNow() }
    private val restoreHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var imm: InputMethodManager? = null
    @Volatile private var lastCopiedByApp: String? = null   // กัน clipboard listener paste กลับสิ่งที่ copy จาก terminal
    @Volatile private var lastSetClip: String? = null       // fallback เมื่อ system clip โดนล้าง (ดู armClipboard)
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastClipTimestamp: Long? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this) // ก่อนทุกอย่าง: crash ระหว่าง onCreate ก็ต้องมี report
        ConnectionLog.init(this)
        ConnectionLog.d("activity onCreate pid=${android.os.Process.myPid()} restored=${savedInstanceState != null} task=$taskId")
        connectionDiagnostics = ConnectionDiagnostics(this).also { it.start() }
        SessionKeepAliveService.onDisconnectAll = { disconnectAllSessions() }
        lastConfig = android.content.res.Configuration(resources.configuration)
        super.onCreate(savedInstanceState)
        secrets = SecretsStore(this) {
            // เรียกจาก thread ไหนก็ได้ (lazy) และ TOFU จะถามใหม่เหมือนเชื่อมต่อครั้งแรก
            runOnUiThread { Toast.makeText(this, "Saved passwords, passphrases and host keys could not be read and were reset", Toast.LENGTH_LONG).show() }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { secrets.migrateKeys() }
                .onSuccess { if (it.migrated + it.failed > 0) ConnectionLog.d("key migration migrated=${it.migrated} failed=${it.failed}") }
                .onFailure { ConnectionLog.d("key migration failed ${it.javaClass.name}") }
        }
        SshBackend.setHomeFolder(filesDir) // MINA บน Android ไม่มี user.home — ต้อง set ก่อน client แรก
        connectUi = ConnectUi(this)
        settingsUi = SettingsUi(this)
        settings = loadSettings()
        // reload-on-change (round 6): ไฟล์ถูกแก้จากข้างนอก (run-as/import ภายนอก) = reparse, session เดิมไม่ reconnect
        settingsWatcher = SettingsWatcher(File(filesDir, "settings.json")) { text ->
            when (SettingsSync.decide(text, lastSettingsHash)) {
                SettingsSync.Decision.Reload -> {
                    runCatching { SettingsLoader.parse(text) }.onSuccess {
                        settings = it
                        refreshTabs()
                        renderActiveStatus()
                        rebuildKeybindings()   // chord table ใหม่ถึงทุก WebView ทันที (gate G5)
                        // T5: view-level flags (preeditEnabled kill switch ฯลฯ) ต้อง reconfigure — refreshTabs ไม่แตะ pane
                        for (id in manager.ids()) manager.get(id)?.let { tab ->
                            for (p in tab.panes) if (p.native) configureNative(p, p.profile?.fontFace)
                        }
                        toast("settings reloaded")
                    }
                }
                SettingsSync.Decision.Invalid -> toast("settings.json is invalid, keeping the current settings")
                SettingsSync.Decision.Ignore -> Unit   // self-write
            }
        }
        settingsWatcher?.start()
        rebuildKeybindings()   // สร้างตารางแรก (ยังไม่ WebView = set field อย่างเดียว; onPageFinished จะ push)
        imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        run { // slop/longpress ของระบบ = เกณฑ์ TapGate (≡ viewConfiguration ที่ TouchKernel ฝั่ง native ใช้)
            val vc = android.view.ViewConfiguration.get(this)
            imeLongPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong() // HW-4: hold-timer ใช้ค่าเดียวกัน
            tapGate = dev.aterminal.render.input.TapGate(
                vc.scaledTouchSlop.toFloat(),
                imeLongPressMs,
            )
        }

        root = GestureRoot(this) { ev -> observeGesture(ev) }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0C0C0C.toInt())
        }

        // ComposeView ต้องการ owners บน view tree (เราไม่ใช้ activity-compose setContent ทั้งจอ)
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)

        tabBar = ComposeView(this).apply {
            setBackgroundColor(0xFF0C0C0C.toInt())
            isFocusable = false
            isFocusableInTouchMode = false
            setContent {
                ATerminalTabBar(
                    tabs = tabSnap,
                    activeId = activeTabId,
                    onSelect = { switchTab(it) },
                    onNew = { connectUi.showProfileList() },
                    onClose = { closeTab(it) },
                    onMenu = { showPaneMenu(it) },
                )
            }
        }
        root.addView(tabBar, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))

        statusView = TextView(this).apply {
            text = "≡ new tab — tap to connect"
            setTextColor(0xFF9A9A9A.toInt())
            setBackgroundColor(0xFF1F1F1F.toInt())
            setPadding(dp(10), dp(6), dp(6), dp(6))
            textSize = 12f
            setOnClickListener {
                val s = activePane()
                val p = s?.profile
                // IDLE + มี profile = กด Disconnect all ไว้ แตะแล้วต่อ pane เดิม ไม่เปิด tab ใหม่
                if (s != null && p != null && (s.error != null || s.controller.state == SessionController.State.DISCONNECTED ||
                        s.controller.state == SessionController.State.IDLE)) {
                    // T3: tap ระหว่าง countdown = "retry ตอนนี้" — ยกเลิกคิวกัน dial ซ้ำสองรอบ
                    cancelReconnect(s)
                    startConnect(s, p) // tap = retry บน pane เดิม (ไม่เปิดใหม่)
                } else connectUi.showProfileList()
            }
            // T3: long-press = ปิด auto-reconnect ของ pane นี้จน CONNECTED ใหม่ (user อนุมัติ)
            setOnLongClickListener {
                val s = activePane() ?: return@setOnLongClickListener false
                cancelReconnect(s)
                s.reconnect.userStop()
                if (s === activePane()) renderActiveStatus()
                true
            }
        }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val zoomOutBtn = stripButton("A−") { zoom(-2) }
        // HW-5b (debug-only): user เครื่อง dev เป็น SSH node ไม่มี USB/วง network เดียวกับมือถือ =
        // adb logcat เข้าไม่ถึง → long-press "A−" = export ATermIme ring → clipboard (paste ส่งได้เลย)
        if (debugImeLog) zoomOutBtn.setOnLongClickListener { exportImeLog(); true }
        strip.addView(zoomOutBtn)
        strip.addView(stripButton("A+") { zoom(2) })
        strip.addView(stripButton("⚡") { pasteNow(fromClipboard = true) })
        root.addView(strip, LinearLayout.LayoutParams(-1, -2))

        termContainer = FrameLayout(this)
        root.addView(termContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        keysBar = ExtraKeysBar(this) { key -> routeKey(key) }
        root.addView(keysBar)
        // C12: landscape + IME เปิด → หดเหลือ mini bar (portrait / IME ปิด = layout เดิม 100%)
        miniBar = MiniKeysBar(this,
            onKey = { key -> routeKey(key) },   // seam เดียวกับ bar เต็ม (round 7: markMode divert ใน routeKey)
            onRestore = { forceFullKeys = true; applyKeysBars() })
        miniBar.visibility = View.GONE
        root.addView(miniBar)
        keyboardWatcher = KeyboardWatcher(root)
        keyboardWatcher.onImeChanged = { up ->
            // HW-5 instrumentation (debug-only): จังหวะ IME up/down เทียบ timebase เดียวกับ DOWN/UP
            // (uptimeMillis) — ATermIme ชุดเดียวตอบได้ว่า vendor show ก่อน/หลัง preempt เท่าไหร่
            imeLog("IME ${if (up) "UP" else "DOWN"} @${android.os.SystemClock.uptimeMillis()}")
            val wasUp = imeUp
            imeUp = up
            if (!up) {
                forceFullKeys = false                       // "⋯" force มีผลจน IME ปิด
                // ปล่อย focus เฉพาะ transition up→down จริง — watcher (insets path) ยิง false ซ้ำได้ทุกครั้งที่
                // insets ถูก apply; false ระหว่าง revealIme กับช่วง IME จะโผล่ = blur connection ที่เพิ่งให้ = tap พัง
                if (wasUp) {
                    imeUserRequested = false // IME ที่ user สั่ง ปิดแล้ว — การ protect สิ้นสุด (ปล่อยเฉพาะ transition จริง กัน spurious false กลาง reveal)
                    releaseImeHold()         // round 15-HW: ดูคำอธิบายเต็มในฟังก์ชัน
                }
            }
            applyKeysBars()
        }
        keyboardWatcher.start()
        setContentView(root)

        // clipboard: gesture-free paste (copy = selection -> Bridge.copyToClipboard)
        // เก็บ reference (M2.2): listener เดิม never removed — leak across activity restart
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            // Android แจ้ง listener ซ้ำเมื่อ text classification ของ clip เดิมเสร็จ (วัดบน API35: status 1→3, timestamp เดียวกัน)
            // ไม่กันตรงนี้ = copy ครั้งเดียว paste 2 ชุด
            val clipTs = clipboard().primaryClipDescription?.timestamp
            if (clipTs != null && clipTs == lastClipTimestamp) return@OnPrimaryClipChangedListener
            lastClipTimestamp = clipTs
            // R6: toggle ใหม่ (settings.autoPasteClipboard, default false ตั้งแต่ 0.2.0: เปิดเองเท่านั้น เพราะ copy จากแอปอื่นในจอแบ่งจะเข้า shell ทันที) — OFF = clip ภายนอกไม่ paste เข้า pane
            if (!settings.autoPasteClipboard) return@OnPrimaryClipChangedListener
            // R6: F-4-class เดียวกัน — clip change landing ตอน activity ไม่ foreground (dialog/lock/screen-off) = paste เข้าจอที่ user ไม่เห็น
            // guard นี้ติดไว้ independent ของ toggle (กัน inject ตอน background)
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@OnPrimaryClipChangedListener
            val text = clipboard().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text == null || text == lastCopiedByApp) return@OnPrimaryClipChangedListener
            val s = activePane()
            if (s?.controller?.activeBackend != null) pasteNow(fromClipboard = false, text = text)
        }
        clipListener = listener
        clipboard().addPrimaryClipChangedListener(listener)

        // T4 (round 13): snapshot รอด + toggle ON = กู้โครง tab + auto-reconnect (ห้ามเด้ง TOFU จาก path นี้)
        if (!restoreSessionsFromFile()) {
            openTab() // boot: tab ว่างตัวแรก — onPageFinished จะเปิด profile list เอง
        }
        showPendingCrashReport()
    }

    /** report จากการปิดตัวครั้งก่อน: แสดงครั้งเดียว ให้ user เลือกคัดลอกเอง (ไม่ส่งออกจากเครื่อง) */
    private fun showPendingCrashReport() {
        val report = runCatching { CrashLog.consume(this) }
            .onFailure { Log.w("AtermCrash", "read report failed", it) }.getOrNull() ?: return
        root.postDelayed({ // หลัง boot dialog เพื่อให้อยู่ด้านบน
            android.app.AlertDialog.Builder(this)
                .setTitle("A-Terminal closed unexpectedly last time")
                .setMessage(report)
                .setPositiveButton("Copy") { _, _ -> copyTextToClipboard(report) }
                .setNegativeButton("Close", null)
                .showGuarded(this)
        }, 1500L)
    }

    override fun onActionModeStarted(mode: android.view.ActionMode) {
        // M3: Chromium WebView เปิด selection menu (Translate/Cut/Copy, type=FLOATING) ตอน long-press
        // selection ของเราทำใน JS หมดแล้ว — finish ทิ้งเฉพาะ floating (TYPE_PRIMARY ของ EditText ใน
        // dialog ยังได้ menu ตามปกติ)
        if (mode.type == android.view.ActionMode.TYPE_FLOATING) {
            runCatching { mode.menu.clear() }
            runCatching { mode.finish() }
        }
        super.onActionModeStarted(mode)
    }

    override fun onStart() {
        super.onStart()
        ConnectionLog.d("activity onStart sessions=${connectedPaneCount()}")
        connectionDiagnostics?.onForeground()
        syncKeepAlive() // service ที่เริ่มจาก background ไม่ได้ (เช่น reconnect) เริ่มตรงนี้
    }

    override fun onResume() {
        super.onResume()
        ConnectionLog.d("activity onResume")
    }

    override fun onPause() {
        ConnectionLog.d("activity onPause finishing=$isFinishing")
        super.onPause()
    }

    override fun onStop() {
        ConnectionLog.d("activity onStop finishing=$isFinishing config=$isChangingConfigurations sessions=${connectedPaneCount()}")
        connectionDiagnostics?.onBackground()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        ConnectionLog.d("activity onUserLeaveHint")
        super.onUserLeaveHint()
    }

    override fun onTrimMemory(level: Int) {
        ConnectionLog.d("activity onTrimMemory level=$level")
        super.onTrimMemory(level)
    }

    private fun connectedPaneCount(): Int = tabViews.values.sumOf { tv ->
        tv.slots.count { it?.controller?.state == SessionController.State.CONNECTED }
    }

    /** menu "Copy connection log": ให้ user ส่งเองเมื่อ session หลุด บนเครื่องที่ไม่มี adb */
    internal fun copyConnectionLog() {
        ConnectionLog.dump { text ->
            runOnUiThread { if (text.isEmpty()) toast("Connection log is empty") else copyTextToClipboard(text) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) activePane()?.let { focusAndReveal(it) }
    }

    // configChanges ใน Manifest เก็บ rotation ไว้ที่ activity (session ไม่หาย — C11) จึง hookตรงนี้ได้อิสระ
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ConnectionLog.d("activity onConfigurationChanged diff=0x${Integer.toHexString(lastConfig?.diff(newConfig) ?: 0)}")
        lastConfig = android.content.res.Configuration(newConfig)
        forceFullKeys = false   // rotate = reset การ force (mini bar ตัดสินใหม่ตาม orientation ใหม่)
        applyKeysBars()
        // R9 foldable: activity ไม่ recreate (manifest declare ครบ) → ไม่มี onCreate มา re-configure ให้
        // จอใน↔จอนอก scaledDensity ต่างกัน = cellW/cellH เก่าค้าง — push configure ใหม่ทุก native pane (idempotent; webview path ไม่เกี่ยว)
        // grid จริงไหลตามเอง: configure → requestLayout → TerminalView.onSizeChanged → engine.resize + onGridResize
        for (id in manager.ids()) manager.get(id)?.let { tab ->
            for (p in tab.panes) if (p.native) configureNative(p, p.profile?.fontFace)
        }
    }

    /** กฎ C12 (ผู้ใช้เคาะ): mini bar เฉพาะ landscape + IME เปิด && ไม่ได้กด "⋯" — portrait ไม่มีวันเปลี่ยน */
    private fun applyKeysBars() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val mini = imeUp && landscape && !forceFullKeys
        keysBar.visibility = if (mini) View.GONE else View.VISIBLE
        miniBar.visibility = if (mini) View.VISIBLE else View.GONE
    }

    /**
     * round 15-HW: จุดเดียวที่ส่ง font เข้า webview — fontSize + fontFace (เดิม face ไม่เคยข้าม bridge
     * = terminal.html hardcode ตายตัว, กับดักเดียวกับ round-11 ฝั่ง native) — setFontFace ฝั่ง JS fail-soft
     */
    private fun pushFont(s: TerminalSession) {
        val face = if (s.fontFace.isNotEmpty()) " window.Aterm && Aterm.setFontFace(${JSONObject.quote(s.fontFace)});" else ""
        s.js("window.Aterm && Aterm.setFont(${s.fontSize});$face")
    }

    /**
     * round 15-HW: focus ≡ เดิมแต่ **ไม่แตะ IME** — การ pop คีย์บอร์ดแยกไป revealIme
     * (bug Honor V6: DOWN ของทุก gesture รวม scroll สองนิ้ว เคยยิง showSoftInput ก่อนรู้ว่า gesture คืออะไร)
     */
    private fun focusTerminal(s: TerminalSession) {
        // JS `term.focus()` ถูกถอดจาก DOWN path — บทเรียน Honor V6 รอบนี้: Chromium WebView
        // ยก IME เองเมื่อ editable (helper textarea) ได้ focus *ระหว่างนิ้วแตะอยู่* = scroll ก็เด้ง
        // แม้ Kotlin ไม่ showSoftInput แล้ว — JS focus จึงย้ายไป revealIme (เฉพาะ tap ที่ gate ผ่าน)
        if (s.webView != null) s.webView?.requestFocusFromTouch()
        else s.view.requestFocusFromTouch() // TerminalView = input target ตัวเอง (onCheckIsTextEditor)
    }

    /**
     * IME + JS focus — เรียกเมื่อ gesture ตัดสินแล้วว่าเป็น tap (observeGesture) หรือ caller non-touch
     * JS term.focus() (textarea รับ keystrokes) ต้องมาก่อน/คู่กับ showSoftInput — native ไม่มีชั้น JS
     */
    private fun revealIme(s: TerminalSession) {
        val target = s.webView ?: s.view
        imeServedPane = s // จำไว้ปล่อย focus ตอน IME ปิด (ดู releaseImeHold)
        imeUserRequested = true // ตั้งแต่นี้ไป IME ที่เปิดอยู่ = ของ user, closeImeIfSneaked ห้ามแตะ
        // HW-5: ยก gate ฝั่ง View ก่อน — accessor ฝั่ง JS จะ un-disable textarea ให้เอง (sync ใน setter)
        (s.webView as? ImeGateWebView)?.editorEnabled = true
        s.js("window.__atermAllowFocus=true;window.term && term.focus();") // no-op เมื่อ native (js() guard)
        imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        imeLog("revealIme ${s.hashCode()}")
    }

    /** focus+IME ≡ พฤติกรรม focusTerminal เดิม — ใช้ที่ caller non-touch ทั้ง 5 จุด (tab switch/restore/menu) */
    private fun focusAndReveal(s: TerminalSession) {
        focusTerminal(s)
        revealIme(s)
    }

    /**
     * round 15-HW: window-level gesture observer — root container เห็น **ทุก** event ก่อนลูก
     * (OnTouchListener บน s.view เชื่อ stream เต็มไม่ได้: พอ WebView ลูก consume แล้ว MOVE/UP อาจ never reach)
     * DOWN = จับ candidate ด้วย hit-test; UP = ถ้า Tap (นิ้วเดียว+ไม่ลาก+ก่อน longpress) → revealIme
     * ห้าม hideSoftInput ตรงนี้ — IME เปิดค้างตอน user scroll review output = พฤติกรรมถูกต้อง
     */
    private fun observeGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturePane = paneUnder(ev.x, ev.y)   // root-space ≡ window-space (root คือ content view ทั้งจอ)
                tapGate.down(ev.x, ev.y, ev.eventTime)
                imeUpAtDown = imeUp // backstop snapshot — ดู closeImeIfSneaked
                imeBustedSeen = false
                imeHoldHidden = false // HW-4: gesture ใหม่ = hold-hide state เริ่มใหม่
                imeHideGen++        // เพิกถอน pending retry/hold-timer ของ gesture ก่อน (กันชน revealIme ของ tap ใหม่)
                imeLog("DOWN imeUp=$imeUp pane=${gesturePane != null}")
                // round 15-HW-5 (Honor V6, policy user "press-up ไม่ใช่ press-down"): IME ปิดอยู่ + แตะลง
                // = ลบสถานะ text-editor ทุกชั้นทันที (disabled/onCheckIsTextEditor/IC) — *hide* ตอน DOWN สายไป
                // จริง (Chromium show ที่ DOWN+5ms — measured api35, บทเรียน HW-1) แต่ *prevent* ทัน:
                // การตัดสินใจ long-press-editor ของ Chromium/IMS เกิดที่ ~500ms หลัง DOWN — preempt (sync
                // ณ dispatch) + restartInput ลบมันจากโลกก่อนแล้ว คีย์บอร์ดเหลือทางเกิดเดียว = revealIme (UP tap)
                // หมายเหตุ: auto-reopen ตอน keyboard *เปิด* ยังกันที่ releaseImeHold (transition up→down) ตามเดิม
                gesturePane?.let { if (!imeUp) preemptImeAtDown(it) }
                // round 15-HW-4 (Honor V6): นิ้ว*นิ่ง*หลัง DOWN = stream เงียบ → busted edge (eval ตอน event)
                // ไม่เคยยิง ขณะที่ long-press timer ของ Chromium/JS (~500ms, queue ตัวเอง) ทำงานตามเวลาจริง
                // → ยิง timer ฝั่งเราถามเวลาเองผ่าน TapGate.expired; tick ที่ 2 (+400ms) เก็บ vendor ที่ re-show กลาง hold
                val holdGen = imeHideGen
                gesturePane?.let { s ->
                    for (delay in longArrayOf(imeLongPressMs, imeLongPressMs + 400)) {
                        s.view.postDelayed({
                            if (holdGen == imeHideGen && gesturePane === s &&
                                tapGate.expired(android.os.SystemClock.uptimeMillis())
                            ) {
                                closeImeForSelection(s)
                            }
                        }, delay)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapGate.pointerDown() // นิ้วที่สอง = gesture นี้ไม่ใช่ tap แล้ว
            MotionEvent.ACTION_POINTER_UP -> tapGate.pointerUp()
            MotionEvent.ACTION_MOVE -> if (gesturePane != null) tapGate.move(ev.x, ev.y)
            MotionEvent.ACTION_UP -> {
                val s = gesturePane
                gesturePane = null
                imeHideGen++ // HW-4: เพิกถอน hold-timer ที่ pending *ทุก*กรณี (quick tap = reveal แล้วห้ามมี timer ตามมาปิด)
                val tap = tapGate.up(ev.eventTime) == dev.aterminal.render.input.TapGate.Result.Tap
                // tap ลง overlay ที่มี EditText ของตัวเอง (find bar/dialog) = IME flow ของมัน ห้ามไปแย่ง
                if (tap && s != null && currentFocus !is android.widget.EditText) {
                    if (s.native) (s.view as? TerminalView)?.textEditorEnabled = true // คืน *ก่อน* reveal — input connection ต้องพร้อม
                    revealIme(s)
                } else if (s != null) {
                    // non-tap: gesture ที่เริ่มจาก IME ปิด ต้องจบแบบปิด (backstop — ดูฟังก์ชัน)
                    // HW-4: ถ้า gesture นี้คือ long-press select (imeHoldHidden) → ห้าม closeImeIfSneaked
                    // (guard ของมัน protect user-requested = vendor re-show หลัง UP จะรอด) — คีย์บอร์ดต้องปิดอยู่
                    if (imeHoldHidden) closeImeForSelection(s) else closeImeIfSneaked(s)
                    scheduleSneakRetry(s) // round 15-HW-2: Honor stack เปิด *หลัง* UP (viewClicked-style) — ยิงซ้ำอีกครั้ง
                    imeLog("UP tap=false (retry scheduled)")
                } else imeLog("UP tap=$tap pane=null")
            }
            MotionEvent.ACTION_CANCEL -> {
                tapGate.cancel()
                imeHideGen++ // HW-4: เพิกถอน hold-timer เช่นเดียวกับ UP
                gesturePane?.let {
                    if (imeHoldHidden) closeImeForSelection(it) else closeImeIfSneaked(it)
                    scheduleSneakRetry(it)
                }
                gesturePane = null
                imeLog("CANCEL")
            }
        }
        // round 15-HW-2 (Honor V6): early-hide — ทันทีที่ gesture สิ้นสภาพ tap (นิ้วสอง/ลาก/longpress)
        // เก็บ IME ที่ลอบเด้ง *กลาง* gesture ทันที ไม่ต้องรอ UP (keyboard ทับจุดที่นิ้วแตะ = scroll ตาย — tmux retest)
        if (!imeBustedSeen && gesturePane != null && tapGate.busted(ev.eventTime)) {
            imeBustedSeen = true
            gesturePane?.let { closeImeIfSneaked(it) }
            imeLog("busted@${ev.actionMasked} — early hide")
        }
    }

    /**
     * deferred backstop (round 15-HW-2): some IME stacks show *after* our UP-time hide; cancel by next DOWN (imeHideGen)
     * round 15-HW-3 (Honor V6 tmux retest): retry 200ms เดียวสั้นเกิน — Honor stack มี reopen ที่แลนช้ากว่านั้น
     * → decay chain 150/400/900ms (ทุก step check gen; DOWN ใหม่ = imeHideGen++ เพิกถอนทั้ง chain)
     * round 15-HW-4: ยืดถึง 1600ms (vendorIMS ที่ reopen ช้ากว่า 900 — หลัง UP selection UI เปิดอยู่แล้ว, hide ซ้ำไร้ผลข้างเคียง)
     */
    private fun scheduleSneakRetry(s: TerminalSession) {
        val gen = imeHideGen
        val hold = imeHoldHidden // snapshot: flag เปลี่ยนได้ที่ DOWN ถัดไป = ซึ่ง gen++ เพิกถอน chain นี้ไปก่อนแล้ว
        for (delay in intArrayOf(150, 400, 900, 1600)) {
            s.view.postDelayed({ if (gen == imeHideGen) { if (hold) closeImeForSelection(s) else closeImeIfSneaked(s) } }, delay.toLong())
        }
    }

    // HW-5b: ring ของ ATermIme (debug-only, cap ~400) — export ผ่าน long-press "A−"
    private val imeRing = ArrayDeque<String>()

    private fun imeLog(msg: String) {
        if (!debugImeLog) return
        android.util.Log.d("ATermIme", msg)
        val line = "${android.os.SystemClock.uptimeMillis()} $msg" // timebase เดียวกับ IME UP/DOWN @ms
        synchronized(imeRing) {
            if (imeRing.size >= 400) imeRing.removeFirst()
            imeRing.addLast(line)
        }
    }

    /** HW-5b: ส่งออก ATermIme ทั้ง ring → clipboard (กัน autoPaste ด้วย guard เดียวกับ copy ปกติ) */
    private fun exportImeLog() {
        val text = synchronized(imeRing) { imeRing.joinToString("\n") }
        if (text.isEmpty()) { toast("No ATermIme log yet"); return }
        val n = synchronized(imeRing) { imeRing.size }
        imeLog("export → clipboard ($n lines)") // บรรทัดท้าย = หลักฐานว่า export เกิดจริง (ติดใน clip ที่ copy ข้างบนไม่ได้ — ตั้งใจ: clip คือ snapshot ก่อนบรรทัดนี้)
        copyTextToClipboard(text)
        toast("IME log copied ($n lines)")
    }

    /**
     * round 15-HW backstop: ถ้า gesture เริ่มตอน IME *ปิด* แล้วระบบ/IME ลอบเปิดกลาง gesture
     * (บน Honor, IME มี stack ของมันเอง — กัน path ที่ยังไม่รู้จัก) ให้ปิดคืนตอนจบ gesture
     * - round 15-HW-3: protect ≡ `imeUpAtDown && imeUserRequested` — "user เปิดไว้ตั้งแต่ก่อน gesture" เท่านั้น
     *   (เดิม imeUpAtDown เดี่ยว ๆ = คุ้มครองแม้ IME ที่ระบบเด้งเองค้าง → รอดจาก hide ทุก gesture เรื่อยไป)
     * - ข้ามเมื่อ focus เป็น EditText (find bar/dialog มี IME flow ของตัวเอง — window token เดียวกัน, hide = ปล้นกัน)
     * - ปกติ __atermAllowFocus gate (terminal.html) กันการ sneak ได้แล้ว — ตัวนี้คือ net ชั้นสอง ไม่ใช่ทางหลัก
     */
    private fun closeImeIfSneaked(s: TerminalSession) {
        // round 15-HW-3: guard เดิม (imeUpAtDown เดี่ยว ๆ) เปิดช่อง IME ที่ระบบลอบเปิด *ค้าง* ตั้งแต่ก่อน DOWN
        // ให้รอด (IME เด้งเองจาก tmux แล้ว user scroll ทับ) — ตอนนี้ protect เฉพาะเคส user สั่งเปิดจริง (imeUserRequested)
        if ((imeUpAtDown && imeUserRequested) || currentFocus is android.widget.EditText) return
        // blur + ปิดวาล์วก่อน hide: ไม่มี focused editor = Chromium ImeAdapter ไม่มี path เรียก show
        // (วาล์วเดิมกันแค่ JS focus(), path native ไม่ผ่าน) — canvas selection ไม่สะเทือน (SelectionService ไม่ใช่ DOM)
        closeFocusValveAllPanes()
        val token = (s.webView ?: s.view).windowToken ?: return
        runCatching { imm?.hideSoftInputFromWindow(token, 0) }
        runCatching { imm?.restartInput(s.webView ?: s.view) } // ลืม viewClicked เก่า (แนว releaseImeHold — stack Honor จำความเดิม)
        imeLog("hide(+blur) ${s.hashCode()}")
    }

    /**
     * round 15-HW-4 (Honor V6, user policy): long-press = โหมดเลือกข้อความ → เก็บ IME ให้อัตโนมัติ
     * **แม้ user เพิ่งเปิดเอง** (user ตัดสิน 2026-11-01: คีย์บอร์ดเด้ง/ค้างตอนกดค้าง = บัง selection UI)
     * ต่าง closeImeIfSneaked ตรงข้าม guard imeUpAtDown&&imeUserRequested — คงไว้เฉพาะ EditText guard
     * (find bar/dialog มี IME flow ของตัวเอง) และตั้ง imeHoldHidden ให้ UP/CANCEL+retry chain ปิดต่ออย่างต่อเนื่อง
     * (vendor IMS ที่ re-show หลัง UP ต้องไม่รอดด้วยสถานะ "user เปิดอยู่")
     * zero copy-regression: copy flow ไม่ต้องการ DOM focus เลย — term.getSelection + ClipboardManager (FINDINGS H)
     */
    private fun closeImeForSelection(s: TerminalSession) {
        if (currentFocus is android.widget.EditText) return
        imeHoldHidden = true
        closeFocusValveAllPanes()
        val token = (s.webView ?: s.view).windowToken ?: return
        runCatching { imm?.hideSoftInputFromWindow(token, 0) }
        runCatching { imm?.restartInput(s.webView ?: s.view) }
        imeLog("hold-hide ${s.hashCode()}")
    }

    /**
     * round 15-HW-4 (candidate 2): วาล์ว __atermAllowFocus/ focus เป็น per-WebView แต่ imeServedPane จำได้ทีละ pane
     * → pane อื่น (split) อาจค้าง DOM-focused + วาล์วเปิด → Chromium in-process showSoftInput ข้าม gate ได้
     * ทุกครั้งที่เก็บ IME จึงปิดวาล์ว+blur **ทุก pane ของ tab ที่ active** (selection/copy ไม่ต้องการ focus)
     */
    // HW-5c: วน **ทุก tab** — vendor IMS (Honor) มี viewClicked-reopen บน view ที่ *เคย* เป็น editor
    // แม้ view นั้นอยู่ tab ที่มองไม่เห็น; valve ค้างที่ tab เก่า = target สำรองให้ IMS เด้งคีย์บอร์ดข้าม tab
    private fun closeFocusValveAllPanes() {
        for (id in manager.ids()) {
            val tab = manager.get(id) ?: continue
            for (p in tab.panes) {
                if (p.native) (p.view as? TerminalView)?.textEditorEnabled = false
                else {
                    // HW-5: วาล์ว accessor จะ sync ta.disabled=true ให้ (กัน Chromium native touch-focus ซึ่งไม่ผ่าน wrapper)
                    // + ImeGateWebView: onCheckIsTextEditor=false, IC=null — vendor ไม่มี target ให้ auto-show
                    (p.webView as? ImeGateWebView)?.editorEnabled = false
                    p.js("window.__atermAllowFocus=false;window.term && term.blur();")
                }
            }
        }
    }

    /**
     * round 15-HW-5 (Honor V6): ลบสถานะ text-editor ทุกชั้น *ตั้งแต่แตะลง* เมื่อ IME ปิดอยู่ —
     * DOM disabled/focus (วาล์ว accessor) + View.onCheckIsTextEditor + InputConnection + restartInput
     * (IMS re-query EditorInfo = ลบ viewClicked memory ของ Honor)
     * ผล = คีย์บอร์ด "เกิด" ได้ทางเดียว: revealIme ตอนยกนิ้ว tap (policy user: press-up ไม่ใช่ press-down);
     * Chromium long-press timer / vendor IMS กลางกดค้างไม่มี editor target เหลือเลย
     * - keyboard เปิดอยู่ = ข้ามทั้งบล็อก (caller เช็ค !imeUp) → scroll keep-open policy ของ HW-3 คงเดิม
     * - HW keyboard จริง (release): ข้ามเหมือน releaseImeHold — พิมพ์ physical keys ต้องการ textarea focusable
     */
    private fun preemptImeAtDown(s: TerminalSession) {
        if (currentFocus is android.widget.EditText) return // find bar/dialog มี IME flow ของตัวเอง
        val hwKb = resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
        val dbg = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (hwKb && !dbg) return
        closeFocusValveAllPanes() // HW-5c: valve-off ทุก pane **ทุก tab** (viewClicked-reopen ข้าม tab)
        if (!s.native) s.js("document.activeElement && document.activeElement.blur && document.activeElement.blur();") // เก็บ focus ค้างที่ element อื่น
        // HW-5c: restartInput ทุก view ที่เคยเป็น editor (ims ลบ EditorInfo/viewClicked memory ของ Honor ต่อ view —
        // restartInput เฉพาะ pane เดียว = tab เก่าที่ user เคยพิมพ์ยังค้างใน memory)
        for (id in manager.ids()) {
            val t = manager.get(id) ?: continue
            for (p in t.panes) runCatching { imm?.restartInput(p.webView ?: p.view) }
                .onFailure { imeLog("restartInput fail tab=$id: ${it.message}") } // convention: ห้าม swallow เงียบ
        }
        imeLog("preempt@DOWN ${s.hashCode()}")
    }

    /**
     * round 15-HW (Honor V6, measured บน api35+Gboard): หลัง IME เคยเปิดแล้วถูกปิด (BACK/ปิดเองของ IME),
     * touch ครั้งถัดไปบน pane เดิม — **รวม scroll** — ทำให้คีย์บอร์ดเด้งเองจากฝั่งระบบ:
     * Chromium (in-process) เห็น editable ยัง focus ระหว่าง touch จึงเรียก showSoftInput เอง
     * (measured: ORIGIN_CLIENT ~5ms หลัง DOWN — xterm SelectionService refocus textarea ทุก mousedown,
     * ตรวจกับ bundle) = ข้าม TapGate เราทั้งหมด
     * → วิธีที่ได้ผล: **วาล์ว __atermAllowFocus** (terminal.html wrap textarea.focus) + ปล่อย focus ทันทีที่
     * IME ปิด (blur DOM / ปิด onCheckIsTextEditor) — DOWN ถัดไปจึงไม่มี target ให้ reopen;
     * tap ถัดไป revealIme เปิดวาล์ว+focus คืนเอง — latency เท่า design ที่ user อนุมัติ
     * - ห้ามเรียกตอน IME เปิด (InputConnection หลุด = คีย์บอร์ดหายกลางพิมพ์) — caller = onImeChanged up→down เท่านั้น
     * - HW keyboard จริง (configuration.keyboard≠NOKEYS): พิมพ์ผ่าน DOM focus ตรง ๆ ไม่มี gesture pop → ห้ามปล่อย
     *   (debug build ข้ามให้ทดสอบบน emulator — emulator report QWERTY เสมอ แม้ hw.keyboard=no;
     *    release: อุปกรณ์มี keyboard จริงจะคงพฤติกรรมเดิม = ยอมรับได้, จด FINDINGS)
     */
    private fun releaseImeHold() {
        val hwKb = resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
        val dbg = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (hwKb && !dbg) return
        val s = imeServedPane ?: return
        // ปิดวาล์วก่อน blur — กัน xterm refocus (mousedown) ไม่ให้ Chromium มี target show ตอน DOWN ถัดไป
        // HW-4: ทำทุก pane ของ tab (single-slot imeServedPane = ช่องโหว่ split — ดู closeFocusValveAllPanes)
        closeFocusValveAllPanes()
        // round 15-HW-2: บังคับ IME re-query EditorInfo ของ view (ตอนนี้ไม่ใช่ text editor แล้ว) —
        // stack ที่จำ viewClicked เก่า (Honor) จะได้ไม่ reopen ด้วยความจำเดิม
        runCatching { imm?.restartInput(s.webView ?: s.view) }
        imeLog("releaseImeHold ${s.hashCode()}")
    }

    /** pane ใดของ tab active ที่ครอบจุด (x,y) — window coords (getGlobalVisibleRect = พิกัด window) */
    private fun paneUnder(x: Float, y: Float): TerminalSession? {
        val tab = manager.get(manager.activeId) ?: return null
        val rect = android.graphics.Rect()
        for (p in tab.panes) {
            if (p.view.getGlobalVisibleRect(rect) && rect.contains(x.toInt(), y.toInt())) return p
        }
        return null
    }

    // ---------------- tabs & panes ----------------

    /** view tree ของหนึ่ง tab — สร้างครั้งเดียว, WebView ไม่ถูก ย้ายข้าม wrapper/parent อีกเลย */
    private class TabViews(val container: LinearLayout, val w0: FrameLayout, val divider: View, val w1: FrameLayout) {
        /** slot == wrapper (0/1) — ไม่ bind กับ index ใน TerminalTab.panes (ปิด pane 0 แล้ว pane 1 อยู่ w1 ต่อ) */
        val slots = arrayOfNulls<TerminalSession>(2)
        /** orientation ที่ applyLayout จัด dims ให้แล้ว (null = ยังไม่เคย) — container default = HORIZONTAL เลย trust ไม่ได้ */
        var appliedHor: Boolean? = null
    }

    private fun activeTab(): TerminalTab<TerminalSession>? = manager.active()
    private fun activePane(): TerminalSession? = activeTab()?.focusedPane()

    private fun tabIdOf(s: TerminalSession): Long? {
        for (id in manager.ids()) manager.get(id)?.let { if (it.indexOf(s) >= 0) return id }
        return null
    }

    private fun tabOf(s: TerminalSession): TerminalTab<TerminalSession>? = tabIdOf(s)?.let { manager.get(it) }

    /**
     * สร้าง per-pane session: WebView (config เดียวกับ round 3/4 ทุกบรรทัด) + SessionController ของตัวเอง
     * — bridge/SessionController/pageReady เป็น per-WebView อยู่แล้ว จึง per-pane ได้ทันที (JS layer 0 การแก้)
     */
    // round-14 T4 (bug จริง): xterm 5.5 เรียก Element.replaceChildren (Chromium 86+) —
    // WebView เก่ากว่านั้น (API30 stock = 83) = xterm throw ตั้งแต่ boot → จอดำสนิทไม่มี warn
    // → นโยบาย: WebView major <86 บังคับ native engine เสมอ (user อนุมัติ 2026-10-31)
    private val webviewTooOld: Boolean by lazy {
        val major = WebView.getCurrentWebViewPackage()?.versionName
            ?.substringBefore('.')?.toIntOrNull() ?: 0
        major < 86
    }
    private var webviewWarned = false

    @SuppressLint("SetJavaScriptEnabled")
    private fun createSession(): TerminalSession {
        if (settings.engine == "native" || webviewTooOld) {
            if (webviewTooOld && settings.engine != "native") {
                Log.i("AtermEngine", "WebView major <86 (replaceChildren ไม่พอ) — fallback native engine")
                if (!webviewWarned) { webviewWarned = true; toast("WebView is too old, using the native engine") }
            }
            return createNativeSession()
        }
        val holder = arrayOfNulls<TerminalSession>(1)
        val wv = createTerminalWebView { holder[0]!! }
        val s = TerminalSession(wv, wv) // ต้องส่ง webView ด้วย — native = (webView == null)
        s.controller = SessionController(
            // ผ่าน s เสมอ ห้าม capture wv: WebView ถูกสลับได้เมื่อ renderer ตาย
            evalJs = { script -> if (s.recovering) s.rendererRecovery.hold(script) else s.js(script); lruTouch(s) }, // byte ผ่าน = pane มี activity
            scope = lifecycleScope,
            onUiStatus = { state, message -> onSessionStatus(s, state, message) },
        )
        holder[0] = s
        return s
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTerminalWebView(session: () -> TerminalSession): WebView {
        return ImeGateWebView(this).apply { // HW-5: ดูคำอธิบายเต็มที class (gate onCheckIsTextEditor)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            // บทเรียน round 3: WebView disk cache ทำ terminal.html/xterm.js เวอร์ชันเก่า
            // ถูกเสิร์ฟหลัง reinstall — ทุกการทดสอบ asset ต้อง line นี้จริงก่อน (LOAD_NO_CACHE)
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            setBackgroundColor(0xFF0C0C0C.toInt())
            isFocusableInTouchMode = true          // defect (a): ต้อง touch-focus ถึงจะมี InputConnection
            // round 15-HW (Honor V6): auto-reopen ของ IME (Chromium ยกเองตอน touch ถ้า editable ยัง focus)
            // กันที่ releaseImeHold — ปล่อย focus ตอน IME ปิด, DOWN ถัดไปจึงไม่มี target ให้ reopen
            // — View.setShowSoftInputOnFocus เป็น hidden API บน View (javap: มีแค่ TextView) จึงไม่ใช่ทาง
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.INVISIBLE
            addJavascriptInterface(SessionBridge(session), "Droid")
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    // false = ระบบปิดทั้งแอป ทุก session หลุด — true ได้ก็ต่อเมื่อเลิกใช้ view นี้ทันที
                    val dead = view ?: return true
                    val s = session()
                    Log.w("AtermRenderer", "renderer gone didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()} current=${s.webView === dead}")
                    if (s.webView === dead && !isDestroyed) recoverRenderer(s, dead, detail)
                    else { (dead.parent as? ViewGroup)?.removeView(dead); dead.destroy() }
                    return true
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    val s = session()
                    pushFont(s)
                    // WebView ใหม่ต้องได้ตาราง chord ปัจจุบัน (รวมที่ push ตอน reload ทั้งรอบ — กันตารางเก่า/D7)
                    view?.evaluateJavascript("window.AtermKeys && AtermKeys.setTable(${JSONObject.quote(keyTableJson)});", null)
                    val wv = view ?: return
                    s.pageReady = true
                    if (s.recovering) { finishRendererRecovery(s); return }
                    // flush connect ที่แข่งมาก่อน JS พร้อม (pane ใหม่ — ไม่งั้น bytesแรกของ backend หาย)
                    s.pendingConnect?.let { s.pendingConnect = null; it() }
                    // focus เฉพาะ pane ที่ user กำลังมอง — WebView ที่ load ช้ากว่าห้ามฉก IME
                    if (s === activePane()) focusAndReveal(s)
                    // boot dialog เฉพาะ tab แรก pane เดียวที่ยังว่าง (เหมือน round 4) — split/pane ใหม่ไม่เด้งซ้ำ
                    val tab = tabOf(s)
                    if (s.profile == null && tab != null && tab.panes.size == 1 &&
                        manager.size() == 1 && activePane() === s
                    ) {
                        // R5 (F-4): 400ms นี้ถ้า activity ตาย (relaunch/F-1) = showGuarded ทิ้ง — ไม่ใช่ BadTokenException
                        view.postDelayed({ connectUi.showProfileList() }, 400)
                    }
                }
            }
            loadUrl("file:///android_asset/terminal.html")
        }
    }

    /**
     * renderer ตาย (เช่น ระบบฆ่าตอน memory ต่ำ): สลับ WebView ใหม่เข้าที่เดิม โดย backend/SSH ไม่รู้เรื่อง
     * สิ่งที่หายคือจอและ scrollback ซึ่งอยู่ใน renderer — แจ้ง user ตอนหน้าโหลดเสร็จ
     * renderer ตายซ้ำเกินนโยบาย = ปิด tab แทนการวนสร้างไม่รู้จบ
     */
    private fun recoverRenderer(s: TerminalSession, dead: WebView, detail: android.webkit.RenderProcessGoneDetail?) {
        val parent = dead.parent as? ViewGroup
        val index = parent?.indexOfChild(dead) ?: -1
        val lp = dead.layoutParams
        val visibility = dead.visibility
        parent?.removeView(dead)
        dead.destroy()
        markOn.remove(s) // mark mode อยู่ใน JS ของหน้าเก่า
        if (parent == null || !s.rendererRecovery.allow(android.os.SystemClock.elapsedRealtime())) {
            runCatching { CrashLog.record(this, "webview renderer keeps dying didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}") }
            s.webView = null // ตัวเก่า destroy แล้ว กัน disposeSession เรียกซ้ำ
            toast("Terminal view keeps crashing, closing this tab")
            tabIdOf(s)?.let { closeTab(it) }
            return
        }
        val fresh = createTerminalWebView { s }
        s.pageReady = false
        s.recovering = true
        s.view = fresh
        s.webView = fresh
        fresh.visibility = visibility
        parent.addView(fresh, index, lp)
        attachPaneTouch(s)
    }

    private fun finishRendererRecovery(s: TerminalSession) {
        s.recovering = false
        val connected = s.controller.activeBackend != null
        val note = if (connected) "Terminal view restarted. The session is still connected, but earlier output was lost."
            else "Terminal view restarted. Earlier output was lost."
        s.js("window.term && term.writeln(${JSONObject.quote("\u001b[2m[$note]\u001b[0m")});")
        s.rendererRecovery.drain().forEach { s.js(it) }
        s.pendingConnect?.let { s.pendingConnect = null; it() }
        if (s === activePane()) focusTerminal(s) // ไม่ใช้ focusAndReveal: renderer ตายเองไม่ควรทำให้คีย์บอร์ดเด้ง
        if (connected) {
            // ขนาดเท่าเดิมไม่เกิด SIGWINCH: ขยับ 1 แถวแล้วคืน ให้ tmux/vim วาดจอใหม่ โดยไม่ส่งอะไรเข้า shell
            s.view.postDelayed({
                if (s.rows > 1) s.controller.resize(s.cols, s.rows - 1)
                s.view.postDelayed({ s.controller.resize(s.cols, s.rows) }, 150)
            }, 300)
        }
    }

    /**
     * round 9 N7: native pane = TerminalView (:engine-render) + engine ที่สร้างช้าตอน startConnect (ทราบ historySize)
     *  - bytes: controller.drain (Main) → nativeSink → engine.write + invalidate — ไม่มี JS/base64
     *  - grid: view.onSizeChanged → onGridResize → s.cols/rows + controller.resize (≡ webview fit/onResize)
     *  - boot dialog condition เดียวกับ onPageFinished ฝั่ง webview (tab แรก pane ว่าง) — native ไม่มี page load
     */
    private fun createNativeSession(): TerminalSession {
        val holder = arrayOfNulls<TerminalSession>(1)
        val session = { holder[0]!! }
        val tv = TerminalView(this).apply {
            setBackgroundColor(0xFF0C0C0C.toInt())
            visibility = View.INVISIBLE
        }
        val s = TerminalSession(tv, null)
        s.controller = SessionController(
            evalJs = { _ -> lruTouch(s) }, // native ไม่ eval JS — lambda นี้ถูกเรียกเฉพาะ path status (ไม่ใช้)
            scope = lifecycleScope,
            onUiStatus = { state, message -> onSessionStatus(s, state, message) },
            nativeSink = { bytes ->
                // T1: capture baseY ก่อนเขียน → engineOutput ตัดสิน sticky(follow)/pinned ≡ xterm
                // OPEN #3: evicted delta — วัดจาก buffer เดิมเท่านั้น (alt-swap กลาง write → 0, clamp เดิมดูแล)
                val eng = session().engine
                val bufRef = eng?.buf
                val prev = bufRef?.baseY ?: 0
                val prevEvict = bufRef?.evictedTotal ?: 0
                eng?.write(bytes)
                val evicted = if (eng != null && eng.buf === bufRef) eng.buf.evictedTotal - prevEvict else 0
                (session().view as? TerminalView)?.let { it.engineOutput(prev, evicted); it.invalidate() }
                lruTouch(s) // byte ผ่าน = pane มี activity
            },
        )
        tv.onFeed = { bytes -> s.controller.write(bytes) }
        tv.onCopy = { text -> copyTextToClipboard(text) } // T2: selection copy ≡ Droid.copyToClipboard ฝั่ง webview
        tv.keymap = keyTable // T4
        tv.onAppAction = { runAction(it) } // chord APP-target ≡ Droid.onAction → runAction เดิม (fail-soft toast ในนั้น)
        tv.onGridResize = { c, r ->
            s.cols = c; s.rows = r
            s.controller.resize(c, r)
            runOnUiThread { if (s === activePane()) renderActiveStatus() }
        }
        holder[0] = s
        s.pageReady = true // ไม่มี page load — พร้อม connect ได้ทันที (pendingConnect จะ flush เอง)
        tv.post {
            val tab = tabOf(s)
            if (s.profile == null && tab != null && tab.panes.size == 1 &&
                manager.size() == 1 && activePane() === s
            ) tv.postDelayed({ connectUi.showProfileList() }, 400) // R5: self-guard ผ่าน showGuarded เหมือนฝั่ง webview
        }
        return s
    }

    /** palette จาก settings (lookup เดียวกับ colorSchemeJson ของ bridge — ห้ามหลุดกันสองทาง) */
    private fun paletteFor(p: Profile?): Palette {
        val scheme = settings.schemes.firstOrNull { it.name == (p?.colorScheme ?: "Campbell") }
            ?: Scheme(name = "Campbell")
        return Palette.fromScheme(scheme)
    }

    /** size view ปัจจุบัน (sp→px) — path native อย่างเดียว */
    private fun configureNative(s: TerminalSession, fontFace: String?) {
        val tv = s.view as? TerminalView ?: return
        tv.copyOnSelect = settings.copyOnSelect // ≡ Droid.copyOnSelect() ที่ JS ถามต่อ gesture — native อ่านตอน configure
        tv.preeditEnabled = settings.preeditEnabled // T5 kill switch: false = IME path เดิม byte-for-byte
        tv.palette = paletteFor(s.profile)
        tv.configure(s.fontSize * resources.displayMetrics.scaledDensity, fontFace)
        // T6 HUD ≡ Droid.findStatus (status strip เดียวกับ webview path; bar = overlay ของ wrapper)
        tv.onFindStatus = { q, i, n ->
            runOnUiThread {
                if (q == null) renderActiveStatus()
                else setStatus("⌕ $q  $i/$n", 0xFF9A9A9A.toInt())
            }
        }
        // T7 HUD ≡ Droid.onMarkMode (ข้อความเดียวกับ JS bridge) + markOn สำหรับ routeKey divert (D6)
        tv.onMarkMode = { active ->
            if (active) markOn.add(s) else markOn.remove(s)
            runOnUiThread {
                if (s === activePane()) {
                    if (active) setStatus("⌖ MARK MODE — ↑↓←→ select, ENTER copy, ESC exit", PANE_FOCUS)
                    else renderActiveStatus()
                }
            }
        }
    }

    // ---------------- find bar (T6 native ≡ aterm-find.js overlay — input/Aa/▲/▼/✕) ----------------

    /** ≡ AtermFind.open(): เปิดซ้ำ = focus ซ้ำ + recompute (ไม่ปิด — close = ✕/ESC เดียวกับ JS) */
    private fun openFind(tv: TerminalView) {
        val wrapper = tv.parent as? FrameLayout ?: return
        (wrapper.findViewWithTag<View>("aterm-find"))?.let {
            it.findViewWithTag<EditText>("aterm-find-input")?.let { e ->
                e.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
                if (tv.finder.query.isNotEmpty()) tv.findQuery(tv.finder.query)
            }
            return
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1F1F1F.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
            tag = "aterm-find"
        }
        val input = EditText(this).apply {
            hint = "find…"
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0xFF0C0C0C.toInt())
            setTextColor(0xFFE6E6E6.toInt())
            setHintTextColor(0xFF777777.toInt())
            isSingleLine = true
            tag = "aterm-find-input"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> tv.findNext(); true } // Enter = findNext (≡ keydown Enter ฝั่ง JS — shift+Enter ไม่มีบน IME mobile, มี ▼/▲)
            setOnKeyListener { _, keyCode, ev ->
                if (keyCode == KeyEvent.KEYCODE_ESCAPE && ev.action == KeyEvent.ACTION_DOWN) {
                    wrapper.removeView(bar); tv.closeFind(); tv.requestFocus(); true
                } else false
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { tv.findQuery(s?.toString() ?: "") } // ≡ JS input listener → recompute(true)
        })
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var caseOn = false
        val caseBtn = TextView(this).apply {
            text = "Aa"; setTextColor(0xFFDDDDDD.toInt()); textSize = 14f
            setBackgroundColor(0xFF333333.toInt()); setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
            layoutParams = lp
            setOnClickListener {
                caseOn = !caseOn
                setBackgroundColor(if (caseOn) 0xFF0037DA.toInt() else 0xFF333333.toInt()) // ≡ .af-on
                tv.finder.caseSensitive = caseOn
                tv.findQuery(input.text.toString()) // ≡ recompute(true)
            }
        }
        bar.addView(caseBtn)
        for ((label, act) in listOf("▲" to { tv.findPrev() }, "▼" to { tv.findNext() }, "✕" to {
            wrapper.removeView(bar); tv.closeFind(); tv.requestFocus() // ≡ close() + term.focus()
        })) {
            bar.addView(TextView(this).apply {
                text = label; setTextColor(0xFFDDDDDD.toInt()); textSize = 14f
                setBackgroundColor(0xFF333333.toInt()); setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
                setOnClickListener { act() }
            })
        }
        wrapper.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        input.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        if (tv.finder.query.isNotEmpty()) tv.findQuery(tv.finder.query) // ≡ open(): if (query) recompute(true) — เปิดซ้ำ retains query (JS ไม่ล้าง)
    }

    /** PaneLru ไม่ thread-safe — evalJs/bridge อาจมาจาก io thread เลย pull กลับ main ก่อน touch */
    private fun lruTouch(s: TerminalSession) {
        if (Looper.myLooper() == Looper.getMainLooper()) lru.touch(s) else root.post { lru.touch(s) }
    }

    /**
     * round 7 (D6): keysbar byte bypass DOM จึงไม่ผ่าน custom key handler —
     * pane ที่ markMode on อยู่ = divert ทุก byte ให้ JS ตัดสิน (match = move/copy/exit, unmatched = exit แล้วส่งกลับ)
     * routeKey คืนทางเดิมเมื่อ mode ปิด — ExtraKeysBar/MiniKeysBar internals ไม่โดนแตะ (seam lambda เดิม)
     */
    private fun routeKey(key: String) {
        val s = activePane() ?: return
        val tv = if (markOn.contains(s)) s.view as? TerminalView else null
        if (tv != null) {
            // native (T7): state = MarkStateMachine ใน view — unmatched = view exit(clear) เอง (onMarkMode ถอด markOn) แล้วเรากลับ path write
            if (!tv.markKey(key)) s.controller.write(key)
        } else if (markOn.contains(s)) {
            s.js("window.Aterm && Aterm.markKey(${JSONObject.quote(key)});")
        } else s.controller.write(key)
    }

    /** grammar/merge/validate อยู่ core ทั้งหมด (D1) — warning ลง log แค่ fail-soft; ตารางถูก push ทุก WebView */
    private fun rebuildKeybindings() {
        val r = KeybindingResolver.resolve(settings)
        r.warnings.forEach { android.util.Log.d("ATermKeys", it) }
        keyTableJson = r.table.toJsJson()
        keyTable = r.table
        for (id in manager.ids()) {
            val tab = manager.get(id) ?: continue
            for (p in tab.panes) if (p.pageReady) {
                (p.view as? TerminalView)?.keymap = r.table // T4: native path — جدولเดียวกับการ push JS
                p.js("window.AtermKeys && AtermKeys.setTable(${JSONObject.quote(keyTableJson)});")
            }
        }
    }

    private fun openTab(): TerminalSession {
        val tab = TerminalTab<TerminalSession>(0L)   // model id ไม่ใช้ — key จริงคือ SessionManager id
        val s = createSession()
        tab.split(s)
        manager.open(tab).forEach { disposeEvictedTab(it) } // tab-cap 4 = double guard (pane cap ยิงก่อนเสมอ)
        val tid = manager.activeId
        tabViews[tid] = buildTabViews(tid)
        attachPane(tid, s)
        lru.add(s)
        evictPanes()
        showActive()
        scheduleSnapshotWrite() // T4: โครง tab เปลี่ยน
        return s
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTabViews(tabId: Long): TabViews {
        val c = LinearLayout(this)
        val w0 = FrameLayout(this).apply {
            setBackgroundColor(0xFF0C0C0C.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -1, 1f)
        }
        val w1 = FrameLayout(this).apply {
            setBackgroundColor(0xFF0C0C0C.toInt())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -1, 1f)
        }
        val dv = View(this).apply {
            setBackgroundColor(0xFF3F3F46.toInt())
            visibility = View.GONE
            // drag = resizePane (WT); divider เป็น View ของตัวเอง ไม่ทับ WebView → gesture ไม่ชน long-press copy
            setOnTouchListener { _, e -> onDividerTouch(tabId, e) }
        }
        c.addView(w0); c.addView(dv); c.addView(w1)
        termContainer.addView(c, FrameLayout.LayoutParams(-1, -1))
        return TabViews(c, w0, dv, w1)
    }

    /** place WebView ลง wrapper ที่ว่าง (slot) ครั้งเดียว — จากนี้ไปไม่ย้ายอีก */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachPane(tabId: Long, s: TerminalSession) {
        val tv = tabViews[tabId] ?: return
        // slot แรกที่ว่าง = pane แรกได้อันดับซ้าย/บน (WT); กรณีสวนทาง: ปิด pane 0 แล้ว slot 0 ว่าง =
        // pane ใหม่จากการ split ครั้งถัดไปกลับเข้าซ้ายเสมอ (pane เดิมค้าง w1 ขวา — ไม่ re-parent)
        val slot = if (tv.slots[0] == null) 0 else 1
        tv.slots[slot] = s
        val wrapper = if (slot == 0) tv.w0 else tv.w1
        // WebView VISIBLE เสมอ — การซ่อนระดับ "ทั้ง container" เท่านั้น (container INVISIBLE = subtree
        // ยัง layout → tab ซ่อนยัง fit ตอน rotate ตาม C11); round 5 bug: showActive เดิมลืม set ตัวนี้
        // กลับจาก INVISIBLE ของ createSession = terminal ไม่ draw เลย (จับด้วย CDP visible:false)
        s.view.visibility = View.VISIBLE
        val m = dp(2)
        wrapper.addView(s.view, FrameLayout.LayoutParams(-1, -1).apply { setMargins(m, m, m, m) })
        attachPaneTouch(s)
    }

    private fun attachPaneTouch(s: TerminalSession) {
        s.view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onPaneTouch(s)
            false   // return false = view ได้ event เต็ม (long-press copy / mouse round 3–4 คงเดิม)
        }
    }

    /** tap = ย้าย pane focus (WT moveFocus ด้วย pointer) — ไม่ consume event */
    private fun onPaneTouch(s: TerminalSession) {
        val tid = tabIdOf(s) ?: return
        val tab = manager.get(tid) ?: return
        val idx = tab.indexOf(s)
        if (idx < 0) return
        android.util.Log.d("ATermTouch", "pane DOWN tab=$tid idx=$idx")   // device-gate E5 (drag vs focus)
        tab.setFocused(idx)
        lru.touch(s)
        applyPaneVisuals(tid)
        focusTerminal(s)
        renderActiveStatus()
        scheduleSnapshotWrite() // T4: focused pane เปลี่ยน = snapshot ต้องตาม
    }

    /** จุดเดียวที่เขียน orientation/weights/visibility — divider drag ก็เข้ามาทางนี้ (WT resizePane) */
    private fun applyLayout(tabId: Long) {
        val tv = tabViews[tabId] ?: return
        val tab = manager.get(tabId) ?: return
        val hor = tab.orientation == SplitOrientation.HORIZONTAL
        val occ0 = tv.slots[0] != null
        val occ1 = tv.slots[1] != null
        val two = occ0 && occ1
        // modify LayoutParams เดิม (ไม่ replace object): drag ยิงทุก MOVE — same-orientation = แตะ weight อย่างเดียว
        val sizeChanged = tv.appliedHor != hor
        if (sizeChanged) {
            tv.appliedHor = hor
            tv.container.orientation = if (hor) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        tv.w0.visibility = if (occ0) View.VISIBLE else View.GONE
        tv.w1.visibility = if (occ1) View.VISIBLE else View.GONE
        tv.divider.visibility = if (two) View.VISIBLE else View.GONE
        val d = dp(12)
        val dp0 = tv.divider.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(-1, -1).also { tv.divider.layoutParams = it }
        if (sizeChanged || dp0.width != (if (hor) d else -1)) {
            dp0.width = if (hor) d else -1
            dp0.height = if (hor) -1 else d
        }
        // weight = fraction ต่อ pane (clamp 0.25–0.75 อยู่ใน model) — WebView เล็กลง → fit() → onResize เอง
        val p0 = tv.w0.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(-1, -1, 1f).also { tv.w0.layoutParams = it }
        val p1 = tv.w1.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(-1, -1, 1f).also { tv.w1.layoutParams = it }
        if (sizeChanged) {
            p0.width = if (hor) 0 else -1; p0.height = if (hor) -1 else 0
            p1.width = if (hor) 0 else -1; p1.height = if (hor) -1 else 0
        }
        val w0 = if (occ0) (if (two) tab.fraction else 1f) else 0f
        val w1 = if (occ1) (if (two) 1f - tab.fraction else 1f) else 0f
        if (p0.weight != w0 || p1.weight != w1 || sizeChanged) {
            p0.weight = w0
            p1.weight = w1
            tv.container.requestLayout()
        }
    }

    /** pane focused = สี wrapper โผล่ผ่าน margin 2dp ของ WebView (กรอบ accent สไตล์ WT, 0 การแก้ JS) */
    private fun applyPaneVisuals(tabId: Long) {
        val tv = tabViews[tabId] ?: return
        val tab = manager.get(tabId) ?: return
        val two = tab.panes.size == 2
        val foc = tab.focusedPane()
        tv.w0.setBackgroundColor(if (two && tv.slots[0] === foc) PANE_FOCUS else 0xFF0C0C0C.toInt())
        tv.w1.setBackgroundColor(if (two && tv.slots[1] === foc) PANE_FOCUS else 0xFF0C0C0C.toInt())
    }

    private fun onDividerTouch(tabId: Long, e: MotionEvent): Boolean {
        val tv = tabViews[tabId] ?: return false
        val tab = manager.get(tabId) ?: return false
        if (tab.panes.size < 2) return false
        val hor = tab.orientation == SplitOrientation.HORIZONTAL
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragFrac0 = tab.fraction
                dragAxis0 = if (hor) e.rawX else e.rawY
                android.util.Log.d("ATermTouch", "divider DOWN tab=$tabId f=$dragFrac0")   // device-gate E5
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val total = (if (hor) tv.container.width else tv.container.height).coerceAtLeast(1)
                val cur = if (hor) e.rawX else e.rawY
                tab.setFraction(dragFrac0 + (cur - dragAxis0) / total)
                // device-gate E5: log ทุก ~10 MOVE กัน spam — ดู end fraction
                if ((cur - dragAxis0).toInt() % 120 == 0)
                    android.util.Log.d("ATermTouch", "divider MOVE f=${tab.fraction} total=$total")
                applyLayout(tabId)
                return true
            }
            else -> {
                scheduleSnapshotWrite() // T4: divider settle = fraction เปลี่ยน (เขียนเฉพาะตอนจบ gesture)
                return true             // UP/CANCEL: เก็บ gesture ไว้กับ divider
            }
        }
    }

    /** memory valve ตัวเดียวของ app: victim = inactive นานสุด นอกเสียจากเป็น focused pane ของ tab ที่มองอยู่ */
    private fun evictPanes() {
        val guard = activePane()
        val out = lru.evict(if (guard != null) setOf(guard) else emptySet())
        for (s in out) {
            val tid = tabIdOf(s)
            if (tid != null) removePane(tid, s)
            else { lru.remove(s); disposeSession(s) }   // shouldn't happen — fallback กัน leak
        }
        if (out.isNotEmpty()) showActive()
    }

    /** ปิด pane หนึ่งตัว (ยังมีใน model) — tab ว่างเปล่า = ปิดทั้ง tab */
    private fun removePane(tabId: Long, s: TerminalSession) {
        val tv = tabViews[tabId] ?: return
        val tab = manager.get(tabId) ?: return
        val slot = tv.slots.indexOfFirst { it === s }
        val idx = tab.indexOf(s)
        if (slot < 0 || idx < 0) return
        tv.slots[slot] = null
        tab.closePane(idx)
        lru.remove(s)
        disposeSession(s)   // disposeSession removeView จาก wrapper ให้เอง
        if (tab.panes.isEmpty()) {
            closeTab(tabId)
        } else {
            applyLayout(tabId); applyPaneVisuals(tabId)
            if (tabId == manager.activeId) {
                activePane()?.let { focusAndReveal(it) }
                renderActiveStatus()
            }
            refreshTabs()
        }
        scheduleSnapshotWrite() // T4: pane หาย (closeTab branch ด้านในเขียนซ้ำเอง — debounce รวมรอบ)
    }

    /** closeOtherPanes (WT): เก็บเฉพาะ focused — model เด็ดก่อน แล้วเราเก็บกวาด view/lru */
    private fun closeOtherPanes(tab: TerminalTab<TerminalSession>) {
        val tid = manager.activeId
        tab.closeOthers().forEach { s ->
            val tv = tabViews[tid] ?: return@forEach
            val slot = tv.slots.indexOfFirst { it === s }
            if (slot >= 0) tv.slots[slot] = null
            lru.remove(s)
            disposeSession(s)
        }
        showActive()
        scheduleSnapshotWrite() // T4: closeOtherPanes = โครง pane เปลี่ยน
    }

    /**
     * WT splitPane + splitMode "duplicate": copy profile ของ pane focused ลง pane ใหม่
     * pane ต้นทางยังว่าง = เปิด dialog — pane ใหม่มัน focus อยู่แล้ว connectProfile จะลงถูกตัว
     */
    private fun splitPane(o: SplitOrientation) {
        val tab = activeTab() ?: return
        splitPaneWith(o, tab.focusedPane()?.profile)
    }

    /**
     * T4 (round 13): splitPane ที่ระบุ profile เป้าหมายเองได้ (restore = ไม่ต้อง duplicate จาก source)
     * p = null + tab ว่าง = เปิด dialog (ความเดิม round 5)
     */
    private fun splitPaneWith(o: SplitOrientation, p: Profile?, autoConnect: Boolean = true) {
        val tab = activeTab() ?: return
        val tabId = manager.activeId
        if (tab.panes.size >= TerminalTab.MAX_PANES) return
        tab.setOrientation(o)
        val s2 = createSession()
        if (p != null) s2.profile = p // attach ทันที = profile-list boot dialog self-suppress
        tab.split(s2)
        lru.add(s2)
        attachPane(tabId, s2)
        evictPanes()
        showActive()
        scheduleSnapshotWrite()
        // autoConnect=false = caller (restore) จัด dial เอง ผ่าน admission rule — ห้าม dial ข้าม gate
        if (p != null && autoConnect) startConnect(s2, p, interactive = false)
        else if (p == null) connectUi.showProfileList()
    }

    /** long-press pill → เมนู (trigger ที่ user เคาะ); ชื่อทุกข้อ trace WT schema (Panes.kt) */
    private fun showPaneMenu(tabId: Long) {
        if (manager.get(tabId) == null) return
        switchTab(tabId)                        // สลับไป tab ที่ user ชี้ก่อน แล้วค่อยออกคำสั่งกับมัน
        val tab = activeTab() ?: return
        val two = tab.panes.size == 2
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (!two) {
            items += "⇄  Split right" to { splitPane(SplitOrientation.HORIZONTAL) }
            items += "⇅  Split down" to { splitPane(SplitOrientation.VERTICAL) }
        } else {
            items += "Close other panes" to { closeOtherPanes(tab) }
        }
        // round 7: mobile substitute ของ chord — เป้าหมายคือ pane ที่ focus ใน tab นี้ (terminal-target execute ใน JS)
        items += "⌕  Find" to {
            val s = activePane()
            if (s?.native == true) (s.view as? TerminalView)?.let { openFind(it) } // T6 native path
            else s?.js("window.AtermFind && AtermFind.open({});")
        }
        items += "⌖  MARK MODE  (markMode)" to {
            val s = activePane()
            if (s?.native == true) (s.view as? TerminalView)?.toggleMark() // T7 native path
            else s?.js("window.AtermKeys && AtermKeys.toggle();")
        }
        items += "Close tab" to { closeTab(tabId) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Pane — ${tab.focusedPane()?.profile?.name ?: "new tab"}")
            .setItems(items.map { it.first }.toTypedArray()) { d, w -> d.dismiss(); items[w].second() }
            .showGuarded(this)
    }

    private fun switchTab(id: Long) {
        if (manager.switchTo(id)) {
            showActive()
            activePane()?.let { lru.touch(it) }
            scheduleSnapshotWrite() // T4: focused tab เปลี่ยน
        }
        refreshTabs()
    }

    private fun closeTab(id: Long) {
        val tab = manager.close(id) ?: return
        tabViews.remove(id)?.let { tv ->
            runCatching { (tv.container.parent as? ViewGroup)?.removeView(tv.container) }
            for (p in tv.slots) p?.let { lru.remove(it); disposeSession(it) }
        }
        if (manager.size() == 0) {
            // ปิด tab สุดท้าย = กลับ idle — อย่าเรียก showProfileList ตรง ๆ:
            // blank tab ใหม่จะ auto-เปิด dialog เองที่ onPageFinished (เรียกซ้ำ = dialog เด้งสองรอบ — round 4 bug)
            openTab()
        } else showActive()
        refreshTabs()
        scheduleSnapshotWrite() // T4: โครง tab เปลี่ยน
    }

    /** tab ที่ manager.open evict มา (entry หายจาก manager แล้ว) — หา TabViews ผ่าน identity ของ pane */
    private fun disposeEvictedTab(tab: TerminalTab<TerminalSession>) {
        val key = tabViews.entries.firstOrNull { e ->
            e.value.slots.any { it != null && tab.panes.contains(it) }
        }?.key ?: return
        tabViews.remove(key)?.let { tv ->
            runCatching { (tv.container.parent as? ViewGroup)?.removeView(tv.container) }
            for (p in tv.slots) p?.let { lru.remove(it); disposeSession(it) }
        }
    }

    private fun disposeSession(s: TerminalSession) {
        cancelReconnect(s) // T3: คิว dial ห้ามรอดไป connect pane ที่ตายแล้ว
        runCatching { (s.view.parent as? ViewGroup)?.removeView(s.view) }
        markOn.remove(s)   // round 7: pane ตายตอน markMode on = อย่าค้าง set (กัน routeKey ส่งเข้า WebView ที่ destroy)
        s.controller.close()
        s.webView?.destroy() // native view = TerminalView (ไม่มี destroy contract — detach จาก tree พอ)
    }

    /** สลับ container ทั้ง tab ด้วย visibility (ไม่ re-parent — กัน InputConnection หลุดตาม round 4) */
    private fun showActive() {
        for (id in manager.ids()) {
            val tv = tabViews[id] ?: continue
            tv.container.visibility = if (id == manager.activeId) View.VISIBLE else View.INVISIBLE
            applyLayout(id)
            applyPaneVisuals(id)
        }
        activePane()?.let { focusAndReveal(it) }
        renderActiveStatus()
        refreshTabs()
    }

    private fun refreshTabs() {
        tabSnap = manager.ids().mapNotNull { id ->
            val tab = manager.get(id) ?: return@mapNotNull null
            val foc = tab.focusedPane()
            val states = tab.panes.map { it.controller.state }
            TabInfo(
                id = id,
                title = foc?.profile?.tabTitle ?: foc?.profile?.name ?: "new tab",
                // pill = state รวมทั้ง tab: pane หลัง connect อยู่ก็เห็นเขียว (dot ของ tab ไม่ใช่ของ focused pane)
                state = when {
                    SessionController.State.CONNECTED in states -> SessionController.State.CONNECTED
                    SessionController.State.CONNECTING in states -> SessionController.State.CONNECTING
                    else -> foc?.controller?.state ?: SessionController.State.IDLE
                },
                error = tab.panes.any { it.error != null },
            )
        }
        activeTabId = manager.activeId
        window.decorView.keepScreenOn = tabViews.values.any { tv ->
            tv.slots.any { it?.controller?.state == SessionController.State.CONNECTED }
        }
        syncKeepAlive()
    }

    private fun syncKeepAlive() {
        val panes = tabViews.values.flatMap { it.slots.filterNotNull() }.filter { it.profile?.type != "echo" }
        val state = { w: SessionController.State -> panes.count { it.controller.state == w } }
        val decision = KeepAlivePolicy.decide(
            connected = state(SessionController.State.CONNECTED),
            connecting = state(SessionController.State.CONNECTING),
            // ไม่ดู reconnectJob: ระหว่าง probe มันเป็น null ทั้งที่ยัง reconnect อยู่ ถ้า service หยุดตรงนั้นจะเริ่มใหม่จาก background ไม่ได้
            reconnecting = panes.count {
                it.controller.state == SessionController.State.DISCONNECTED && it.error == null &&
                    !it.reconnect.stoppedByUser && isRetryable(it)
            },
            keepCpuAwake = settings.keepCpuAwake,
        )
        SessionKeepAliveService.sync(this, decision, lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        if (decision.run) askNotificationPermissionOnce()
    }

    /** ไม่อนุญาตก็ยังใช้งานได้ แค่ไม่เห็น notification จึงถามครั้งเดียวแล้วไม่รบกวนอีก */
    private fun askNotificationPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    internal fun toggleKeepCpuAwake() {
        settings = settings.copy(keepCpuAwake = !settings.keepCpuAwake)
        persistSettings()
        syncKeepAlive()
        toast(if (settings.keepCpuAwake) "CPU stays awake while connected. Uses more battery." else "CPU may sleep while the screen is off")
    }

    /** ปุ่มบน notification: ปิดทุก pane แต่คง tab ไว้ ให้แตะ status bar เพื่อต่อใหม่ได้ */
    private fun disconnectAllSessions() {
        for (tv in tabViews.values) for (p in tv.slots) p?.let {
            cancelReconnect(it)
            it.controller.close()
            it.everConnected = false // user ตัดเอง: เปิดแอปครั้งหน้าห้ามต่อกลับอัตโนมัติ
        }
        scheduleSnapshotWrite()
        refreshTabs()
        renderActiveStatus()
    }

    private fun onSessionStatus(s: TerminalSession, state: SessionController.State, message: String?) {
        // ไม่ใช้ message: มี host ติดมา ใช้เฉพาะ code/ชนิดของเหตุ
        val close = s.controller.lastCloseReason
        ConnectionLog.d("pane ${paneTag(s)} $state code=${s.controller.lastError?.code} " +
            "close=${when (close) { is CloseReason.Normal -> "normal"; is CloseReason.Error -> "error"; null -> null }} retryable=${(close as? CloseReason.Error)?.retryable}")
        if (state == SessionController.State.CONNECTED || state == SessionController.State.CONNECTING) s.error = null
        if (state == SessionController.State.IDLE) s.error = null
        // T3 (round 12): auto-reconnect hook — ตัดสินจาก state transition เท่านั้น
        when (state) {
            SessionController.State.CONNECTED -> {
                cancelReconnect(s)
                s.reconnect.noteConnected() // ปลดทั้ง backoff count และ user-stop
                s.manualDial = false
                s.dialGen++ // T4: ปิดยุครุ่นเดิม — dial in-flight ที่เหลือ (ถ้ามี) ตายเงียบ
                if (!s.everConnected) { s.everConnected = true; scheduleSnapshotWrite() } // T4 restore: ผู้มีสิทธิ์ reconnect เกิดแล้ว
                Log.d("AtermRC", "connected gen=${s.dialGen} (policy reset)")
            }
            SessionController.State.IDLE -> cancelReconnect(s) // close()/retry ของ user = คิวเก่ายกเลิก
            SessionController.State.DISCONNECTED -> {
                // จำผิดไว้ = ถามใหม่ไม่ได้อีกเลย จึงลืมเฉพาะกรณีนี้ แตะ status bar แล้วจะถามใหม่
                if (s.controller.lastError?.code == BackendError.Code.KEY_PASSPHRASE) s.profile?.keyAlias?.let { secrets.setKeyPassphrase(it, null) }
                // re-pin (round 16): mismatch จาก manual dial = ผู้ใช้ยืนรอคำตอบอยู่ — เสนอยืนยัน fingerprint ใหม่
                // (manualDial = false ทันที = ผล manual ถูก consume แล้ว, DISCONNECTED ซ้ำจะไม่ stack dialog)
                if (RepromptPolicy.shouldPrompt(s.controller.lastError?.code, s.manualDial)) {
                    s.manualDial = false
                    offerReprompt(s)
                }
                maybeScheduleReconnect(s)
            }
            SessionController.State.CONNECTING -> Unit
        }
        refreshTabs()
        if (s === activePane()) renderActiveStatus()
    }

    private fun isRetryable(s: TerminalSession): Boolean = s.controller.lastError?.retryable
        ?: (s.controller.lastCloseReason as? CloseReason.Error)?.retryable ?: false

    private fun paneTag(s: TerminalSession): String = Integer.toHexString(System.identityHashCode(s))

    /** T3: ยกเลิก countdown ที่ค้าง (tab/pane จะตาย หรือ user ยึดการควบคุมเอง) */
    private fun cancelReconnect(s: TerminalSession) {
        s.reconnectJob?.cancel()
        s.reconnectJob = null
        s.countdownSec = 0
    }

    /**
     * T3: DISCONNECTED → คิว dial ใหม่ตาม backoff ของ pane (user อนุมัติ: auto + banner)
     * กติกา MITM เด็ดขาด: retryable=false (AUTH_FAILED / HOST_KEY_MISMATCH / CloseReason.Normal)
     * → policy คืน null = หยุดถาวร ห้าม dial ซ้ำเงียบ; long-press stop ก็คืน null เช่นกัน
     * (auto path ผ่าน startConnect(interactive=false) — ห้ามเด้ง SAF/password/TOFU จาก countdown)
     */
    private fun maybeScheduleReconnect(s: TerminalSession) {
        val p = s.profile ?: return
        if (s.error != null) return // connect-flow error (TOFU refused ฯลฯ) = ไม่เข้า state machine
        val retryable = isRetryable(s)
        s.controller.lastError?.takeIf { retryable && s.manualDial }?.let { e ->
            // เหมือนตอนยังมี probe: ต่อเองแล้วไม่ได้ = แจ้งแล้วหยุด ไม่ retry ไปเรื่อยจน notification ค้าง
            s.manualDial = false
            renderError(s, e.message)
            return
        }
        val delayMs = s.reconnect.nextDelayMs(retryable)
        cancelReconnect(s) // คิวเก่า (ถ้ามี) ต้องตายก่อน — กัน dial ซ้อน
        if (delayMs == null) return // fatal หรือ user-stop → banner แดงเดิม (+ "auto off")
        // T4: gen ใหม่ = dial/race เก่าที่ in-flight ถูก invalidate (stale dial ห้าม openSession)
        val gen = ++s.dialGen
        Log.d("AtermRC", "schedule delay=${delayMs}ms attempts=${s.reconnect.attempts} retryable=$retryable gen=$gen")
        s.reconnectJob = lifecycleScope.launch {
            var remain = delayMs
            while (remain > 0 && isActive) {
                s.countdownSec = ((remain + 999) / 1000).toInt()
                if (s === activePane()) renderActiveStatus()
                val tick = minOf(1000L, remain)
                delay(tick)
                remain -= tick
            }
            if (!isActive) return@launch // ถูก cancel (tap/IDLE/dispose) ระหว่างทาง
            s.reconnectJob = null
            s.countdownSec = 0
            if (gen != s.dialGen) { Log.d("AtermRC", "countdown superseded gen=$gen cur=${s.dialGen}"); return@launch }
            Log.d("AtermRC", "dial now gen=$gen")
            startConnect(s, p, interactive = false, autoRetry = true, gen = gen)
        }
    }

    // ---------------- connect ----------------

    /**
     * entry ของ ConnectUi: pane focused ที่ "ว่างเปล่า" อยู่ = ใช้ตัวเดิม (blank tab เดียวก็ case นี้),
     * ถ้าไม่ว่าง = เปิด tab ใหม่ (ความหมายปุ่ม "+" เท่ากับ round 4 — split ไม่ผ่าน dialog)
     */
    internal fun connectProfile(p: Profile) {
        val cur = activePane()
        val s = if (cur != null && cur.profile == null && cur.error == null &&
            cur.controller.state == SessionController.State.IDLE
        ) cur else openTab()
        startConnect(s, p)
    }

    /**
     * interactive=true = user เป็นคน tap/connect เอง → เด้ง SAF/password ได้ทันที
     * interactive=false = auto จาก splitPane duplicate / reconnect countdown — key หายต้อง fail-soft เป็น error
     * (round 5 observation: SAF เด้งกลางคันตอน split = รบกวนมาก)
     * autoRetry=true = dial มาจาก reconnect countdown — probe fail (network ยังไม่กลับ) = ต่อ countdown
     * ไม่ใช่ error จบวง; split duplicate ใช้ autoRetry=false (fail-soft เหมือนเดิม)
     */
    private fun startConnect(s: TerminalSession, p: Profile, interactive: Boolean = true, autoRetry: Boolean = false, gen: Int = -1) {
        // T4: manual connect (gen<0) = รุ่นใหม่ทันที (นับรุ่นเก่า + countdown ทิ้ง); auto dial = ใช้ gen ของ countdown
        val myGen = if (gen >= 0) gen else ++s.dialGen
        if (gen >= 0 && gen != s.dialGen) return // superseded ก่อน countdown จะทันถูก cancel
        s.profile = p
        s.error = null
        s.fontSize = p.fontSize
        s.fontFace = p.fontFace
        pushFont(s)
        if (s.native) {
            configureNative(s, p.fontFace)
            // engine ถูกสร้างช้าตรงนี้ (ทราบ historySize จาก profile) — grid = view ปัจจุบัน
            if (s.engine == null) {
                val tv = s.view as TerminalView
                s.engine = TerminalEngine(tv.gridCols, tv.gridRows, p.historySize).also { tv.attachEngine(it) }
                s.cols = tv.gridCols; s.rows = tv.gridRows
                s.view.postInvalidate()
            }
        }
        refreshTabs()
        when (p.authMethod) {
            "password" -> {
                val saved = p.guid?.let { secrets.password(it) }
                if (saved == null && !interactive) {
                    // T3: auto path ห้ามเด้ง dialog — fail-soft, user tap เมื่อพร้อม
                    renderError(s, "No saved password. Tap the status bar to enter it.")
                    return
                }
                if (saved != null) startSession(s, p, saved, null, null, autoRetry, myGen)
                else connectUi.promptPassword(p) { pw, remember ->
                    if (remember) p.guid?.let { secrets.setPassword(it, pw) }
                    startSession(s, p, pw, null, null, autoRetry, myGen)
                }
            }
            "key" -> {
                val alias = p.keyAlias?.takeIf { secrets.hasKey(it) }
                if (alias == null && !interactive) {
                    renderError(s, "Key not found on this device. Tap the status bar to import it.")
                    return
                }
                if (alias == null) {
                    importKeyThenConnect(s, p, autoRetry, myGen) // key หาย/ยังไม่ import
                    return
                }
                connectWithKey(s, p, alias, interactive, autoRetry, myGen)
            }
            else -> startSession(s, p, null, null, null, autoRetry, myGen)   // echo/none
        }
    }

    private fun importKeyThenConnect(s: TerminalSession, p: Profile, autoRetry: Boolean, gen: Int) {
        connectUi.pickKey { alias ->
            val p2 = p.copy(keyAlias = alias)
            saveProfile(p2)
            connectWithKey(s, p2, alias, interactive = true, autoRetry, gen)
        }
    }

    /** ตรวจไฟล์ก่อนต่อ เพื่อถาม passphrase ก่อนเปิด connection และไม่ให้ไฟล์เสียเข้า backoff */
    private fun connectWithKey(s: TerminalSession, p: Profile, alias: String, interactive: Boolean, autoRetry: Boolean, gen: Int) {
        lifecycleScope.launch {
            val check = withContext(Dispatchers.IO) {
                runCatching { secrets.loadKey(alias).let { b -> SshBackend.inspectKey(b).also { b.fill(0) } } }
                    .onFailure { ConnectionLog.d("key load failed ${it.javaClass.name}") }
                    .getOrDefault(KeyCheck.INVALID)
            }
            if (gen != s.dialGen) return@launch
            val source: () -> ByteArray = { secrets.loadKey(alias) }
            when (check) {
                KeyCheck.OK -> startSession(s, p, null, source, null, autoRetry, gen)
                KeyCheck.INVALID ->
                    if (interactive) { toast("This key can't be read here. Import it again."); importKeyThenConnect(s, p, autoRetry, gen) }
                    else renderError(s, "This key can't be read on this device. Tap the status bar to import it again.")
                KeyCheck.NEEDS_PASSPHRASE -> {
                    val saved = secrets.keyPassphrase(alias)
                    when {
                        saved != null -> startSession(s, p, null, source, saved, autoRetry, gen)
                        !interactive -> renderError(s, "Key passphrase needed. Tap the status bar to enter it.")
                        else -> connectUi.promptPassphrase { pw, remember ->
                            if (remember) secrets.setKeyPassphrase(alias, pw)
                            startSession(s, p, null, source, pw, autoRetry, gen)
                        }
                    }
                }
            }
        }
    }

    private fun startSession(
        s: TerminalSession, p: Profile, password: String?, privateKey: (() -> ByteArray)?, keyPassphrase: String?,
        autoRetry: Boolean = false, gen: Int = -1,
    ) {
        if (p.type == "termux" && packageManager.getLaunchIntentForPackage("com.termux") == null) {
            renderError(s, "Termux not installed — type=termux needs Termux + sshd on 127.0.0.1:8022")
            return
        }
        val host: String?
        val port: Int
        when (p.type) {
            "echo" -> { openSession(s, EchoBackend(), p); return }
            "termux" -> { host = "127.0.0.1"; port = 8022 }
            else -> {
                host = p.host ?: run { renderError(s, "profile missing host"); return }
                port = p.port
            }
        }
        // TOFU two-phase เฉพาะ host ที่ยังไม่มี pin: probe (IO) -> UI decide (main) -> real connect
        // ห้ามให้ dialog รออยู่บน MINA ioThread (เคยเป็น InterruptedException บน device)
        // มี pin แล้วไม่ probe: verifier ตรวจตอน connect จริงอยู่แล้ว (ไม่ตรง = HOST_KEY_MISMATCH ไม่ retry)
        lifecycleScope.launch {
            if (secrets.hostPin(host, port) == null) {
                val probed = runCatching { withContext(Dispatchers.IO) { SshBackend.probeHostKey(host, port) } }
                if (probed.isFailure) {
                    // T3: dial จาก countdown และ network ยังไม่กลับ = ต่อ countdown (banner เหลือง) ไม่ใช่ error จบวง
                    if (autoRetry) {
                        if (gen == s.dialGen) maybeScheduleReconnect(s) // superseded = ปล่อยให้คิวรุ่นใหม่ดูแลเอง
                        else Log.d("AtermRC", "stale dial dropped (probe fail) gen=$gen cur=${s.dialGen}")
                        return@launch
                    }
                    renderError(s, probed.exceptionOrNull()?.message ?: "host-key probe failed")
                    return@launch
                }
                val (algo, key) = probed.getOrThrow()
                val fp = SshBackend.fingerprint(key)
                if (gen != s.dialGen) { // T4: กัน dialog/แดง ของ dial ที่ถูกรุ่นใหม่แทนแล้ว
                    Log.d("AtermRC", "stale dial dropped (post-probe) gen=$gen cur=${s.dialGen}"); return@launch
                }
                val ok = withContext(Dispatchers.Main) { connectUi.tofuDialog(host, port, algo, fp) }
                if (!ok) {
                    s.error = "connection refused (host key not accepted)"
                    refreshTabs(); renderActiveStatus()
                    return@launch
                }
                secrets.putHostPin(host, port, RepromptPolicy.pinValue(algo, fp))
            }
            // real connect: compare pin — synchronous + cheap, ไม่มี UI ข้าม thread
            val verifier: (java.net.SocketAddress, java.security.PublicKey) -> Boolean =
                { _, k -> secrets.hostPin(host, port) == RepromptPolicy.pinValue(SshBackend.sshKeyType(k), SshBackend.fingerprint(k)) }
            val tag = paneTag(s)
            val diag: (String) -> Unit = { ConnectionLog.d("ssh $tag $it") }
            val backend = if (p.type == "termux")
                // round 6: type=termux = TermuxBackend (contract) + delegate SshBackend ตัวเดิมผ่าน factory
                TermuxBackend(port) { h, pt -> SshBackend(h, pt, p.user, privateKey, password, verifier, diagnostics = diag, keyPassphrase = keyPassphrase) }
            else SshBackend(host, port, p.user, privateKey, password, verifier, diagnostics = diag, keyPassphrase = keyPassphrase)
            if (gen != s.dialGen) { // T4 gate สำคัญ: ห้ามเปิด shell รุ่นเก่า (probe ชนะแข่งตอน network หายไป)
                Log.d("AtermRC", "stale dial dropped (pre-open) gen=$gen cur=${s.dialGen}"); return@launch
            }
            s.manualDial = !autoRetry
            openSession(s, backend, p)
        }
    }

    /**
     * re-pin (round 16): host ที่ pin แล้ว key เปลี่ยน (server ลงใหม่/rotate key) ผู้ใช้ติดตาย — ไม่มีทางล้าง pin
     * จึง probeหากุญแจจริงของ server แล้วให้ผู้ใช้ยืนยัน old/new fingerprint (RepromptPolicy — มี test):
     *  - Accept → เปลี่ยน pin + startConnect รุ่นใหม่ (เทียบเท่าแตะ status bar เอง, auth ครบผ่าน flow เดิม)
     *  - Reject / dialog ไม่ขึ้น / probe fail → คง error แดง (policy หยุดถาวรอยู่เองเพราะ retryable=false)
     * ห้ามเรียกจาก auto countdown — shouldPrompt ตรวจ manualDial ให้แล้ว (กฎเดิม: countdown ห้ามเด้ง dialog)
     */
    private fun offerReprompt(s: TerminalSession) {
        val p = s.profile ?: return
        val host: String?
        val port: Int
        when (p.type) {
            "termux" -> { host = "127.0.0.1"; port = 8022 } // key ของ sshd ใน Termux เปลี่ยนได้จริงเมื่อ reinstall
            else -> { host = p.host; port = p.port }
        }
        if (host == null) return
        val oldPin = secrets.hostPin(host, port) ?: return // ไม่มี pin = ไม่ใช่กรณี re-pin (TOFU คุมอยู่)
        val old = RepromptPolicy.parsePin(oldPin)
            ?: run { ConnectionLog.d("reprompt skipped: corrupt pin record for $host:$port"); return }
        val gen = s.dialGen
        lifecycleScope.launch {
            val probed = runCatching { withContext(Dispatchers.IO) { SshBackend.probeHostKey(host, port) } }
            if (probed.isFailure) {
                // network/-server ขัดข้องต่อ = คง error แดงเดิม ไม่เพิ่ม dialog ผู้ใช้ tap ใหม่เมื่อพร้อม
                ConnectionLog.d("reprompt probe failed: ${probed.exceptionOrNull()?.message}")
                return@launch
            }
            val (algo, key) = probed.getOrThrow()
            if (gen != s.dialGen) { // T4-style guard: ผู้ใช้ tap dial ใหม่ระหว่าง probe = ปล่อยรุ่นใหม่ดูแล
                Log.d("AtermRC", "stale reprompt dropped (post-probe) gen=$gen cur=${s.dialGen}")
                return@launch
            }
            val fp = SshBackend.fingerprint(key)
            val ok = withContext(Dispatchers.Main) { connectUi.rePinDialog(host, port, old.first, old.second, algo, fp) }
            if (gen != s.dialGen) {
                Log.d("AtermRC", "stale reprompt dropped (post-dialog) gen=$gen cur=${s.dialGen}")
                return@launch
            }
            if (ok) {
                secrets.putHostPin(host, port, RepromptPolicy.pinValue(algo, fp))
                ConnectionLog.d("reprompt accepted $host:$port $algo $fp")
                startConnect(s, p, interactive = true) // gen ใหม่ = ล้างแดง, auth (password/key) ผ่าน flow เดิม
            } else {
                ConnectionLog.d("reprompt rejected $host:$port")
                s.error = "connection refused (host key change not accepted)" // เหมือน pattern tofu-refuse: ไม่เข้า state machine
                refreshTabs()
                renderActiveStatus()
            }
        }
    }

    private fun openSession(s: TerminalSession, backend: TerminalBackend, p: Profile) {
        val go = {
            s.controller.connect(
                backend,
                StartupArgs(p.commandline, p.startingDirectory, s.cols, s.rows, p.environment),
            ) {
                // ESC c = full reset ≡ term.reset (probe ris)
                if (s.native) s.engine?.write(byteArrayOf(0x1b, 0x63)) else s.js("window.term && term.reset();")
            }
        }
        // pane ใหม่: user tap เร็วกว่า loadUrl จบ ได้ — อย่าทิ้ง bytes แรกของ backend (round 4 bug)
        if (s.pageReady) go() else s.pendingConnect = go
    }

    // ---------------- T4: session restore (round 13) ----------------
    // ขอบเขต = โครง tab/pane + profile + auto-reconnect (ไม่ใช่ scrollback — out of scope ตามแผน)
    // MITM rule ผูกอยู่ที่นี่: admission ต้องมี pin ใน SecretsStore — pin หาย = TOFU เป็น human decision ห้าม dial เงียบ

    private fun snapshotFile() = File(filesDir, "session-restore.json")
    private fun restoreAttemptFile() = File(filesDir, "session-restore-attempt")

    /** debounce ~500ms — โครงเปลี่ยนถี่ (drag divider) ห้าม write ทุก event */
    private fun scheduleSnapshotWrite() {
        if (!settings.restoreSessions) return
        restoreHandler.removeCallbacks(restoreWrite)
        restoreHandler.postDelayed(restoreWrite, 500)
    }

    private fun flushSnapshotNow() {
        restoreHandler.removeCallbacks(restoreWrite)
        runCatching {
            if (!settings.restoreSessions) { snapshotFile().delete(); return }
            val s = collectSnapshot()
            if (s == null) snapshotFile().delete() else AtomicText.write(snapshotFile(), SessionRestore.encode(s))
        }.onFailure { Log.w("AtermRestore", "snapshot write failed: ${it.message}") }
    }

    /** เก็บเฉพาะ pane everConnected + guid ครบ; tab ที่ไม่มี pane ผ่านเกณฑ์ = หาย; รวมเกิน cap = trim ท้าย */
    private fun collectSnapshot(): SessionRestore.Snapshot? {
        val tabs = ArrayList<SessionRestore.RestoreTab>()
        var focusedTab = 0
        for (id in manager.ids()) {
            val tab = manager.get(id) ?: continue
            val keep = tab.panes.filter { it.everConnected && it.profile?.guid != null }
            if (keep.isEmpty()) {
                if (id == manager.activeId) focusedTab = tabs.size.coerceAtLeast(0)
                continue
            }
            val slots = keep.map { it.profile!!.guid!! }
            val foc = keep.indexOf(tab.focusedPane()).coerceAtLeast(0)
            tabs.add(SessionRestore.RestoreTab(slots, tab.orientation, tab.fraction, foc))
            if (id == manager.activeId) focusedTab = tabs.size - 1
        }
        if (tabs.isEmpty()) return null
        return SessionRestore.trimToPaneCap(SessionRestore.Snapshot(tabs, focusedTab))
    }

    /** TOFU-safe admission: host ที่ auto-dial ต้องเคย pin ไว้แล้ว (echo ไม่มี host = ปลอดภัยโดย structure) */
    private fun autoDialAdmissible(p: Profile): Boolean = when (p.type) {
        "echo" -> true
        "termux" -> secrets.hostPin("127.0.0.1", 8022) != null
        else -> { val h = p.host; h != null && secrets.hostPin(h, p.port) != null } // local: smart cast ข้าม module ไม่ได้
    }

    /**
     * คืน true = boot จาก snapshot (ข้าม blank tab) — ไฟล์ one-shot: ลบทิ้งก่อนเสมอ กัน repeat ถ้า restore พัง
     * crash-loop breaker: ถ้า boot ต่อเนื่อง < 15s จาก attempt ก่อน = ข้าม restore ครั้งนึง (กัน dial storm วน)
     */
    private fun restoreSessionsFromFile(): Boolean {
        if (!settings.restoreSessions) return false
        val f = snapshotFile()
        val text = runCatching { if (f.isFile) f.readText() else null }.getOrNull()
        f.delete()
        val snap = text?.let { SessionRestore.decode(it) } ?: return false
        val af = restoreAttemptFile()
        val now = System.currentTimeMillis()
        val last = runCatching { af.readText().toLongOrNull() ?: 0L }.getOrDefault(0L)
        if (now - last in 0 until CRASH_LOOP_WINDOW_MS) {
            Log.i("AtermRestore", "crash-loop breaker: boot ${now - last}ms หลัง attempt ก่อน → boot ปกติ")
            return false
        }
        runCatching { af.writeText(now.toString()) }
        var panes = 0
        var order = 0
        var focusTabId = 0L
        for ((idx, t) in snap.tabs.withIndex()) {
            val guids = t.slots.mapNotNull { g -> settings.profiles.list.firstOrNull { it.guid == g } }
            if (guids.size != t.slots.size) {
                Log.i("AtermRestore", "tab dropped/หด (guid หายจาก settings): ${t.slots} → ${guids.map { it.guid }}")
            }
            if (guids.isEmpty()) continue
            val s0 = openTab()
            s0.profile = guids[0] // ทันที = auto profile-list (:onPageFinished) self-suppress
            if (guids.size >= 2) splitPaneWith(t.orientation, guids[1], autoConnect = false)
            val tid = manager.activeId
            val tab = manager.get(tid) ?: continue
            tab.setFraction(t.fraction)
            tab.setFocused(t.focused.coerceIn(0, tab.panes.size - 1))
            applyLayout(tid); applyPaneVisuals(tid)
            if (idx == snap.focusedTab) focusTabId = tid
            for ((k, p) in guids.take(tab.panes.size).withIndex()) {
                val pane = tab.panes[k]
                if (!autoDialAdmissible(p)) {
                    renderError(pane, "no host pin — not auto-connecting (TOFU = human decision) — tap to choose")
                    continue
                }
                panes++
                restoreHandler.postDelayed({
                    if (pane.profile === p) startConnect(pane, p, interactive = false, autoRetry = true)
                }, 100L * (++order)) // stagger กัน dial/storm พร้อมกันทุก pane
            }
        }
        if (order == 0 && panes == 0) return false
        if (focusTabId != 0L) switchTab(focusTabId)
        Log.i("AtermRestore", "restored: tabs=${snap.tabs.size} dialQueued=$panes focusTab=${snap.focusedTab}")
        return true
    }

    // ---------------- settings persistence ----------------

    private fun loadSettings(): Settings {
        val f = File(filesDir, "settings.json")
        return try {
            // ไฟล์ว่าง = ค้างจากการเขียนแบบเก่าที่ถูกขัดจังหวะ: ไม่มีอะไรให้กู้ เริ่มจากค่าเริ่มต้นดีกว่าแอปไม่มี profile
            if (!f.exists() || f.length() == 0L) assets.open("settings.defaults.json").bufferedReader().use { AtomicText.write(f, it.readText()) }
            SettingsLoader.parse(f.readText())
        } catch (e: Exception) {
            Settings() // settings พังอย่าให้ app ตาย (forward-compat แบบ WT)
        }
    }

    private fun persistSettings() {
        runCatching {
            val text = SettingsLoader.render(settings)
            lastSettingsHash = SettingsSync.sha256(text)   // ต้อง set ก่อน watcher เห็นไฟล์
            AtomicText.write(File(filesDir, "settings.json"), text)
        }
    }

    /** ผู้ใช้สั่ง import เอง (SettingsUi): validate ก่อนแตะของเดิม, ผ่าน = normalize กลับลงไฟล์ */
    internal fun applyImportedSettings(text: String) {
        when (SettingsSync.decide(text, null)) {
            SettingsSync.Decision.Invalid -> toast("Import rejected: settings.json is invalid. Your current settings are unchanged.")
            else -> {
                settings = SettingsLoader.parse(text)
                persistSettings()
                refreshTabs()
                renderActiveStatus()
                rebuildKeybindings()
                toast("imported settings.json")
            }
        }
    }

    internal fun saveProfile(p: Profile) {
        settings = settings.copy(profiles = settings.profiles.copy(
            list = settings.profiles.list.filterNot { it.guid == p.guid } + p))
        persistSettings()
        refreshTabs()
    }

    internal fun deleteProfile(p: Profile) {
        settings = settings.copy(profiles = settings.profiles.copy(
            list = settings.profiles.list.filterNot { it.guid == p.guid }))
        persistSettings()
        p.guid?.let { secrets.setPassword(it, null) }
        p.keyAlias?.let { secrets.deleteKey(it) }
        refreshTabs()
    }

    // ---------------- clipboard ----------------

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun pasteNow(fromClipboard: Boolean, text: String? = null) {
        val s = activePane() ?: return
        // อ่าน system clip ก่อน; ถ้าถูกล้าง (ดู armClipboard) ก็ใช้สำเนาล่าสุดที่ app copy ไว้
        val sys = clipboard().primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        val t = text ?: sys ?: lastSetClip?.takeIf { s.controller.activeBackend != null }
            ?: run { toast("Clipboard empty"); return }
        val out = if (settings.trimPaste) t.trimEnd(' ', '\n', '\r') else t
        if (s.native) {
            // bracketed paste รับผิดชอบที่ input path (xterm ทำใน JS paste handler) — engine มี mode แล้ว
            val wrap = s.engine?.modes?.bracketedPaste == true
            s.controller.write(if (wrap) "\u001b[200~$out\u001b[201~" else out)
        } else s.js("window.Aterm && Aterm.paste(${JSONObject.quote(out)});")
    }

    // ---------------- status / helpers ----------------

    private fun renderActiveStatus() {
        val tab = manager.active()
        val s = tab?.focusedPane()
        if (s == null) { setStatus("≡ no session", 0xFF9A9A9A.toInt()); return }
        val paneTag = if (tab != null && tab.panes.size == 2) " [${tab.focused + 1}/${tab.panes.size}]" else ""
        val name = s.profile?.name ?: "new tab"
        val err = s.error
        when {
            err != null -> setStatus("✗ $err — tap to retry$paneTag", 0xFFFF6B6B.toInt())
            s.profile == null -> setStatus("≡ new tab — tap to connect$paneTag", 0xFF9A9A9A.toInt())
            else -> when (s.controller.state) {
                SessionController.State.IDLE -> setStatus("≡ not connected — tap to connect$paneTag", 0xFF9A9A9A.toInt())
                SessionController.State.CONNECTING -> setStatus("… connecting to $name$paneTag", 0xFFFFD76A.toInt())
                SessionController.State.CONNECTED -> setStatus("● $name  (${s.cols}×${s.rows})$paneTag", 0xFF6ADF7A.toInt())
                SessionController.State.DISCONNECTED -> {
                    val detail = s.controller.lastError?.let { "${it.code}: ${it.message}" } ?: "disconnected"
                    when {
                        s.reconnectJob?.isActive == true -> setStatus(
                            "↻ lost — retry in ${s.countdownSec}s (attempt ${s.reconnect.attempts}) — tap=now, long-press=stop$paneTag",
                            0xFFFFD76A.toInt())
                        s.reconnect.stoppedByUser -> setStatus(
                            "✗ $detail — auto-reconnect off, tap to retry$paneTag", 0xFFFF6B6B.toInt())
                        else -> setStatus("✗ $detail — tap to retry$paneTag", 0xFFFF6B6B.toInt())
                    }
                }
            }
        }
    }

    private fun renderError(s: TerminalSession, msg: String) {
        s.error = msg
        runOnUiThread {
            refreshTabs()
            if (s === activePane()) renderActiveStatus()
        }
    }

    private fun setStatus(text: String, color: Int) = runOnUiThread {
        statusView.text = text
        statusView.setTextColor(color)
    }

    private fun zoom(delta: Int) {
        val s = activePane() ?: return
        s.fontSize = (s.fontSize + delta).coerceIn(8, 28)
        pushFont(s)
        if (s.native) configureNative(s, s.profile?.fontFace)
        // M2.3: persist เข้า profile ของ pane นี้ — restart แล้ว font คงเดิม
        s.profile?.let { saveProfile(it.copy(fontSize = s.fontSize)) }
    }

    private fun stripButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(0xFFCCCCCC.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        textSize = 13f
        gravity = Gravity.CENTER
        setBackgroundColor(0xFF1F1F1F.toInt())
        setOnClickListener { action() }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    // R5: like ConnectUi.toast — don't toast onto a dying activity
    internal fun toast(msg: String) {
        if (canShowDialog(isFinishing, isDestroyed)) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ConnectUi.REQ_KEY) connectUi.onActivityResult(requestCode, resultCode, data)
        else settingsUi.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        ConnectionLog.d("activity onDestroy finishing=$isFinishing config=$isChangingConfigurations changing=0x${Integer.toHexString(changingConfigurations)} sessions=${connectedPaneCount()}")
        connectionDiagnostics?.stop()
        connectionDiagnostics = null
        SessionKeepAliveService.onDisconnectAll = null
        flushSnapshotNow() // T4: graceful kill (back/swipe) = เก็บ snapshot รุ่นล่าสุดก่อนตาย
        settingsWatcher?.stop()
        settingsWatcher = null
        clipListener?.let { clipboard().removePrimaryClipChangedListener(it) } // M2.2
        clipListener = null
        for (id in manager.ids().toList()) {
            val tab = manager.close(id) ?: continue
            tabViews.remove(id)?.let { tv ->
                runCatching { (tv.container.parent as? ViewGroup)?.removeView(tv.container) }
                for (p in tv.slots) p?.let { disposeSession(it) }
            }
        }
        // session ตายไปกับ Activity แล้ว ไม่เหลืออะไรให้ service ดูแล
        SessionKeepAliveService.sync(this, KeepAlivePolicy.decide(0, 0, 0, false), canStart = false)
        super.onDestroy()
    }

    // ---------------- keybinding dispatch (round 7) ----------------

    /**
     * APP-target ตาม ActionCatalog — เรียก method เดิมของ pane/tab (ไม่เพิ่ม behaviour ใหม่บน model)
     * TERMINAL-target (scroll/selectAll/copy/find/findMatch/markMode/sendInput/clearBuffer) วิ่งใน JS เอง (aterm-keys.js)
     */
    private fun runAction(c: Command) {
        val name = Command.nameOf(c) ?: return toast("Invalid command")
        val tab = activeTab()
        val tabId = manager.activeId
        when (name) {
            "newTab" -> { openTab(); connectUi.showProfileList() }
            "closeTab" -> closeTab(manager.activeId)
            "nextTab", "prevTab" -> {
                val ids = manager.ids()
                if (ids.size > 1) {
                    val i = ids.indexOf(manager.activeId)
                    switchTab(ids[(i + if (name == "nextTab") 1 else ids.size - 1) % ids.size])
                }
            }
            "switchToTab" -> { // WT: index 0-based
                val i = c.intArg("index") ?: return toast("switchToTab needs an index")
                manager.ids().getOrNull(i)?.let { switchTab(it) }
            }
            "splitPane" -> {
                splitPane(if (c.strArg("split") == "down") SplitOrientation.VERTICAL else SplitOrientation.HORIZONTAL)
                c.doubleArg("size")?.let { sz ->    // WT splitPane.size = สัดส่วน pane ใหม่ → fraction
                    activeTab()?.let { t -> t.setFraction(sz.toFloat()); applyLayout(tabId) }
                }
            }
            "closePane" -> activePane()?.let { removePane(tabId, it) }
            "closeOtherPanes" -> tab?.let { closeOtherPanes(it) }
            "toggleSplitOrientation" -> { tab?.toggleOrientation(); applyLayout(tabId) }
            "moveFocus" -> tab?.let { t ->
                // mobile 2-pane cap: left/right/up/down/previous = สลับอีก pane เท่ากัน (trace: WT FocusDirection subset)
                if (t.panes.size == 2 && t.setFocused(1 - t.focused)) {
                    t.focusedPane()?.let { lru.touch(it); focusAndReveal(it) }
                    applyPaneVisuals(tabId)
                    renderActiveStatus()
                }
            }
            "resizePane" -> tab?.let { t ->
                val dir = c.strArg("direction")
                val d = if (dir == "left" || dir == "up") -0.1f else 0.1f
                t.setFraction(t.fraction + d)
                applyLayout(tabId)
            }
            "duplicateTab" -> { // WT duplicateTab = เปิด tab ด้วย profile ของ pane focused (fail-soft like split)
                val p = activePane()?.profile
                val s2 = openTab()
                if (p != null) startConnect(s2, p, interactive = false) else connectUi.showProfileList()
            }
            "adjustFontSize" -> zoom(c.intArg("delta") ?: 1)
            "resetFontSize" -> activePane()?.let { zoom(settings.profiles.defaults.fontSize - it.fontSize) }
            "paste" -> pasteNow(fromClipboard = true)
            "scrollUp", "scrollDown", "scrollUpPage", "scrollDownPage", "scrollToTop", "scrollToBottom" -> {
                // TERMINAL-target scroll ops — native path = ScrollState (T1); webview ทำใน JS เองผ่าน chord ไม่เคยถึงที่นี่
                (activePane()?.view as? TerminalView)?.scrollOp(name)
            }
            "find" -> { // T6: native = overlay bar ใน wrapper; webview = AtermFind (เดิม)
                val s = activePane()
                if (s?.native == true) (s.view as? TerminalView)?.let { openFind(it) }
                else s?.js("window.AtermFind && AtermFind.open({});")
            }
            "findMatch" -> { // native: direction ≡ WT FindMatchAction; webview ทำใน JS ไม่ถึง branch นี้
                val s = activePane()
                if (s?.native == true) {
                    (s.view as? TerminalView)?.let { if (c.strArg("direction") == "prev") it.findPrev() else it.findNext() }
                }
            }
            "markMode" -> { // T7 native = MarkStateMachine ใน view; webview execute ใน JS เอง (chord ไม่ถึง branch นี้)
                val s = activePane()
                if (s?.native == true) (s.view as? TerminalView)?.toggleMark()
                else s?.js("window.AtermKeys && AtermKeys.toggle();")
            }
            "clearBuffer" -> {
                val what = c.strArg("clear") ?: "all"
                val s = activePane()
                if (s?.native == true) { if (what != "scrollback") s.engine?.write(byteArrayOf(0x1b, 0x63)) }
                else s?.js("window.Aterm && Aterm.clearBuffer(${JSONObject.quote(what)});")
            }
            else -> toast("Not supported yet: $name")   // UNSUPPORTED = fail-soft (catalog D3)
        }
    }

    // ---------------- JS -> Kotlin ----------------

    /**
     * bridge ต่อ WebView (JS: Droid) — ชื่อ JS เดียวกับ round 3/4 ทุกตัว (terminal.html ไม่รู้ว่ามีหลาย pane)
     * session ส่งเป็น getter เพราะ bridge ถูก attach ตอน createSession ก่อน TerminalSession มี controller
     */
    inner class SessionBridge(private val session: () -> TerminalSession) {
        @JavascriptInterface
        fun onInput(text: String) { lruTouch(session()); session().controller.write(text) }

        @JavascriptInterface
        fun onBinaryBytes(b64: String) {
            // R4: mouse report X10/VT200 (ไม่มี 1006) มาทาง term.onBinary = latin1 byte >127 พังผ่าน
            // String bridge — JS base64 มา, decode เป็น bytes แล้ว write ตรง (SGR/1006 ไม่ผ่านทางนี้)
            val bytes = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull() ?: return
            session().controller.write(bytes)
        }

        @JavascriptInterface
        fun onResize(cols: Int, rows: Int) {
            val s = session()
            s.cols = cols; s.rows = rows
            s.controller.resize(cols, rows)
            runOnUiThread { if (s === activePane()) renderActiveStatus() }
        }

        @JavascriptInterface
        fun colorSchemeJson(): String {
            val scheme = settings.schemes.firstOrNull { it.name == (session().profile?.colorScheme ?: "Campbell") }
                ?: return "{}"
            return schemeJson(scheme)
        }

        @JavascriptInterface
        fun copyOnSelect(): Boolean = settings.copyOnSelect

        @JavascriptInterface
        fun copyToClipboard(text: String) = copyTextToClipboard(text)

        @JavascriptInterface
        fun backendType(): String = session().profile?.type ?: "none"

        @JavascriptInterface
        fun openConnect() { runOnUiThread { connectUi.showProfileList() } }

        // ---------- round 7: chord dispatch + markMode + find status ----------
        /** JS match แล้ว target=app/unsupported → ส่ง cmdJson (shape เดียวกับตารางที่ inject ลงไป) กลับมา */
        @JavascriptInterface
        fun onAction(cmdJson: String) {
            val c = CommandCodec.parseCommandJson(cmdJson)
            runOnUiThread { runAction(c) }
        }

        /** JS เป็นเจ้าของ state — Kotlin จำเฉพาะ "pane ไหน mark on" เพื่อ divert keysbar bytes (routeKey) */
        @JavascriptInterface
        fun onMarkMode(active: Boolean) {
            val s = session()
            if (active) markOn.add(s) else markOn.remove(s)
            runOnUiThread {
                if (s !== activePane()) return@runOnUiThread
                if (active) setStatus("⌖ MARK MODE — ↑↓←→ select, ENTER copy, ESC exit", PANE_FOCUS)
                else renderActiveStatus()
            }
        }

        /** {q,i,n} จาก find bar; "" = ปิด find — status strip เดิม (ไม่เพิ่ม view ใหม่) */
        @JavascriptInterface
        fun findStatus(json: String) {
            runOnUiThread {
                if (json.isEmpty()) { renderActiveStatus(); return@runOnUiThread }
                val o = runCatching { JSONObject(json) }.getOrNull() ?: return@runOnUiThread
                setStatus("⌕ ${o.optString("q")}  ${o.optInt("i")}/${o.optInt("n")}", 0xFF9A9A9A.toInt())
            }
        }
    }

    /**
     * setPrimaryClip + re-arm: บน emulator ที่ WebView provider = com.android.chrome,
     * renderer (uid ของ chrome, ไม่มี window focus) จะ probe clipboard ทุก event รอบ ๆ
     * (long-press/selection/menu) → ClipboardService "Denying ... Clearing clipboard" =
     * system clip โดนล้างทันทีหลังเรา set (ตรวจพบ round 3: copy แล้ว clip หายทั้งก้อน)
     * — re-arm เฉพาะเมื่อ clip กลายเป็นว่าง (ว่าง = ระบบล้าง; ถ้ามี text อื่น = user copy จาก app อื่น ห้ามทับ)
     */
    /** clipboard entry เดียวกันของสอง path: JS bridge (webview) + TerminalView.onCopy (native, T2) */
    private fun copyTextToClipboard(text: String) {
        lastCopiedByApp = text
        lastSetClip = text
        runOnUiThread { armClipboard(text) }
        toast("Copied")
    }

    private fun armClipboard(text: String, attempt: Int = 0) {
        clipboard().setPrimaryClip(ClipData.newPlainText("A-Terminal", text))
        if (attempt >= 3) return
        root.postDelayed({
            val cur = clipboard().primaryClip
            if (cur == null || cur.itemCount == 0) armClipboard(text, attempt + 1)
        }, 300L * (attempt + 1))
    }

    private fun schemeJson(s: dev.aterminal.core.Scheme): String = JSONObject().apply {
        put("foreground", s.foreground); put("background", s.background)
        put("cursorColor", s.cursorColor ?: ""); put("selectionBackground", s.selectionBackground ?: "")
        put("black", s.black); put("red", s.red); put("green", s.green); put("yellow", s.yellow)
        put("blue", s.blue); put("purple", s.purple); put("cyan", s.cyan); put("white", s.white)
        put("brightBlack", s.brightBlack); put("brightRed", s.brightRed)
        put("brightGreen", s.brightGreen); put("brightYellow", s.brightYellow)
        put("brightBlue", s.brightBlue); put("brightPurple", s.brightPurple)
        put("brightCyan", s.brightCyan); put("brightWhite", s.brightWhite)
    }.toString()

    companion object {
        private const val REQ_NOTIFICATIONS = 4101
        private const val PANE_FOCUS = 0xFF3DA0FF.toInt()   // กรอบ focused pane (accent เดียวกับ theme)
        private const val CRASH_LOOP_WINDOW_MS = 15_000L    // T4: boot ใหม่ไวเกินไปหลัง restore attempt = ข้าม restore ครั้งเดียว
    }
}

/** profile test ของเราเอง — echo กลับทุก byte, ไม่ต้องมี network (scaffold demo) */
class EchoBackend : TerminalBackend {
    override val type = "echo"
    override var onData: (ByteArray) -> Unit = {}
    override var onClosed: (CloseReason) -> Unit = {}
    override suspend fun connect(startup: StartupArgs) {
        onData("A-Terminal echo backend — type anything (${startup.cols}x${startup.rows})\r\n".toByteArray())
    }
    // echo = display ไม่ใช่ PTY จริง: xterm ส่ง Enter เป็น CR เดี่ยว ๆ → raw echo ทำให้ทุกบรรทัดทับ col 0
    // (ONLCR ของ termios ไม่มีใน scaffold) — normalize CR → CRLF ระดับ byte (safe กับ multibyte UTF-8)
    override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        val out = java.io.ByteArrayOutputStream(data.size + data.size / 8 + 2)
        for (b in data) { out.write(b.toInt()); if (b == 13.toByte()) out.write(10) }
        onData(out.toByteArray())
    }
    override fun resize(cols: Int, rows: Int) {}
    override fun close() { onClosed(CloseReason.Normal) }
}

/**
 * round 15-HW: root container + window-level touch observer — dispatchTouchEvent ของ parent
 * วิ่งก่อนลูกเสมอจึงเห็นทุก event ครบ stream (รวม POINTER_* และ UP ที่ลูก consume ไปแล้ว);
 * observe-only — ส่งต่อ super ทุกตัวอักษร, gesture machine ไม่มีสิทธิ์ consume
 */
private class GestureRoot(
    context: android.content.Context,
    private val onGesture: (android.view.MotionEvent) -> Unit,
) : LinearLayout(context) {
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        onGesture(ev)
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * round 15-HW-5 (Honor V6, policy user: "คีย์บอร์ดเกิดได้เฉพาะตอนปล่อยนิ้ว tap" ≡ press-up ไม่ใช่ press-down):
 * vendor IMS/Chromium ตัดสิน auto-show (long-press editor UI) จาก onCheckIsTextEditor ของ view ที่ touch ลง
 * — ไม่ผ่าน JS ของเราเลย จึงกันด้วย focus valve (terminal.html) ไม่ครบ → gate ระดับ View เพิ่ม:
 * editorEnabled=false (เขียนที่ preempt@DOWN / closeFocusValveAllPanes — เปิดคืนเฉพาะ revealIme)
 * = view ไม่ใช่ text editor + ไม่มี InputConnection ให้ IMS เกาะ → คีย์บอร์ด *เกิด* ได้ทางเดียวคือ revealIme
 * gesture/select/copy ของ terminal ไม่อ่าน flag นี้เลย (≡ บทเรียน textEditorEnabled ฝั่ง native ที่ proof แล้ว HW-4)
 */
private class ImeGateWebView(context: android.content.Context) : WebView(context) {
    @Volatile var editorEnabled = true
    override fun onCheckIsTextEditor(): Boolean = editorEnabled && super.onCheckIsTextEditor()
    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? =
        if (editorEnabled) super.onCreateInputConnection(outAttrs) else null
}
