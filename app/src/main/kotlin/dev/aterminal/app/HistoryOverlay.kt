package dev.aterminal.app

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ticket tmux/08b: read-only history overlay (b1 = ทับ pane ชั่วคราว) — Compose like chrome, ไม่มี dependency ใหม่
 *
 * เหตุผลที่อยู่เหนือ renderer (design gap #1/#2 ของ 08): overlay เป็น view ของ *server history* ไม่แตะ twin ใด
 * (attach ได้) และ touch ทั้งหมดเป็นของ view ตัวเอง ไม่วิ่งผ่าน scroll machinery ของ pane/webview
 * (mouse mode ไม่เกี่ยว) — ทางเข้า 🕘 ใน ⋮ โผล่เมื่อ puller armed = แตะได้เสมอ
 *
 * การ pull ทั้งหมดออกทาง [onPull] (host เชื่อม OverlayPager/HistoryPuller) — view ไม่รู้ backend;
 * [onPull] จะถูกเรียกเฉพาะเมื่อ overlay ยังเปิด (guard ใน host) และหน้าแรก host เป็นคนยิงตอน open
 *
 * threading: ทุก method จาก main
 */
internal class HistoryOverlay(
    private val host: Context,
    private val container: FrameLayout,
    chrome: ChromeColors,
) {
    var onPull: suspend () -> Unit = {}
    var onWarn: (String) -> Unit = {}

    private val lines = mutableStateListOf<String>()
    private val title = mutableStateOf("tmux history")
    private var open = false
    private var nextId = 0 // generation: กัน stale pull กลับมาเขียนทับหลัง close/reopen — host guard ผ่าน idSnapshot()
    private val listState = LazyListState(0, 0)
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate)

    private val view = ComposeView(host).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        setContent {
            Column(Modifier.fillMaxSize().background(chrome.barBg)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(title.value, color = chrome.textActive, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("×", color = chrome.textActive, fontSize = 22.sp, modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable { close() })
                }
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(lines.size) { i ->
                            Text(
                                lines[i],
                                color = chrome.textActive,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private var backCallback: androidx.activity.OnBackPressedCallback? = null
    private var edgeWatch: kotlinx.coroutines.Job? = null

    fun open() {
        if (open) return
        open = true
        lines.clear(); nextId++
        listState.requestScrollToItem(0, 0)
        // gesture ที่ขอบบน = ทิศ "เก่าขึ้น" เดียวกับ 08 — value เปลี่ยนเฉพาะ *ขอบ* (ไม่ใช่ flag เดี่ยว)
        // จึงจับทั้ง drag ที่เริ่มที่ top และ drag/fling ที่ *เลื่อนมาถึง* top กลาง gesture (spec: "ปัดถึงขอบบน")
        // ping-pong เล็กน้อยกลาง drag = ยิงซ้ำได้: busy ของ puller กันไว้ = หนึ่ง page ต่อ gesture จริง
        // หนึ่ง watcher ต่อ open — close ต้อง cancel ไม่งั้น coroutine + listState ค้างต่อ overlay ที่โยนทิ้งแล้ว
        edgeWatch = uiScope.launch {
            snapshotFlow { listState.isScrollInProgress && !listState.canScrollBackward }
                .collect { atTopWhileDragging -> if (atTopWhileDragging) triggerPull() }
        }
        if (view.parent == null) {
            container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        view.visibility = android.view.View.VISIBLE
        if (backCallback == null) {
            // onBackPressedDispatcher/OnBackPressedDispatcherOwner = ของ ComponentActivity (MainActivity เป็น) — bare Activity ไม่มี
            val owner = host as? androidx.activity.ComponentActivity
            if (owner != null) backCallback = owner.onBackPressedDispatcher.addCallback(owner) { close() }
        }
        backCallback?.isEnabled = true
        view.requestFocus() // best-effort เท่านั้น (Honor 11-10: ComposeView ไม่ได้ focus จริง + ไม่กัน IME
        // ที่ค้างใน WebView) — read-only ที่แท้จริง = overlayHoldsInput() gate ใน MainActivity (H1 fix)
    }

    fun close() {
        if (!open) return
        open = false
        nextId++ // stale pull ที่แล่นอยู่ = ผลถูกทิ้ง (host เช็ค idSnapshot)
        edgeWatch?.cancel(); edgeWatch = null
        backCallback?.isEnabled = false
        (view.parent as? ViewGroup)?.removeView(view)
    }

    fun setTitle(text: String) { title.value = text }

    /** generation ปัจจุบัน — host ใช้ guard ผล pull ที่กลับมาหลัง close/reopen */
    fun idSnapshot(): Int = nextId

    /** older page (oldest-first) → prepend + anchor: user ยังมองขอบบนเดิม ขอบใหม่ต่อขึ้นข้างบน */
    fun prependPage(page: List<String>) {
        if (page.isEmpty()) return
        val anchor = listState.firstVisibleItemIndex + page.size // snapshot ก่อนแก้ list: item ที่ user มองอยู่เลื่อนไปเท่าขนาด page
        lines.addAll(0, page)
        uiScope.launch { listState.scrollToItem(anchor) } // offset 0 — ขอบบนเดิมไม่ว่า pull จะ land ตรง index ไหน (device gotcha 11-08)
    }

    /** trim tail (newest — user เห็นใน pane แล้ว): ไม่มี anchor ที่ tail จึงไม่มี compensate */
    fun trimBottom(count: Int) {
        repeat(minOf(count, lines.size)) { lines.removeAt(lines.size - 1) }
    }

    private fun triggerPull() {
        val pull = onPull
        uiScope.launch {
            try {
                pull()
            } catch (t: Throwable) { // fail-soft ทุกทาง — overlay ห้ามตายเพราะ pull (§Threading: swallow ต้องมี log = onWarn ของ host)
                onWarn("overlay pull failed: ${t.javaClass.simpleName}")
            }
        }
    }
}
