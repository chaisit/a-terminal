package dev.aterminal.app

import dev.aterminal.core.BackendError
import dev.aterminal.core.CloseReason
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * เจ้าของ session ปัจจุบัน: backend ↔ WebView
 *  - ขา output: onData (thread ของ MINA) → queue → loop 16ms รวม batch เป็น base64 หนึ่ง call ต่อ frame
 *    (evaluateJavascript ต่อ chunk ดิบ = จุกจิก; batch ลด jank ตอน `seq 1 2000`)
 *  - สถานะ: State -> status strip ฝั่ง native + Aterm.onStatus
 *  - evalJs: รัน JS บน WebView — ผู้เรียกต้อง guarantee main thread (ทุก call ในคลาสนี้อยู่ใน Dispatchers.Main แล้ว)
 *    (inject แทน WebView ตรง ๆ = ทดสอบ JVM-level ได้ ไม่ต้องมี Robolectric)
 */
class SessionController(
    private val evalJs: (String) -> Unit,
    private val scope: CoroutineScope,
    private val onUiStatus: (State, String?) -> Unit,
    // inject ได้ = unit test รัน deterministic บน dispatcher เดียว (ค่าจริง = IO)
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // round 9 (ADDITIVE): native engine — เมื่อ set, batch bytes ส่งเข้า sink ตรง (main thread) ไม่ผ่าน JS/base64
    private val nativeSink: ((ByteArray) -> Unit)? = null,
    // R9: clock ของ coalescer — inject ได้เพื่อ test deterministic (ค่าจริง System.nanoTime, monotonic)
    private val nanoClock: () -> Long = { System.nanoTime() },
) {
    enum class State { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    @Volatile var state: State = State.IDLE
        private set

    /** null เมื่อไม่มี backend ใช้งานได้อีกต่อไป — ทุก terminal path ต้อง null ก่อน/พร้อม setState */
    @Volatile var activeBackend: TerminalBackend? = null
        private set
    @Volatile var lastError: BackendError? = null
        private set

    /**
     * T3 (round 12): เหตุผลปิดล่าสุดจากฝั่ง backend (onClosed path) — ผู้ใช้ auto-reconnect
     * ต้องแยก "retryable" (channel/heartbeat ตาย) จาก fatal (เราปิดเอง = ไม่เข้า path นี้)
     * สัญญา: path onClosed → set reason + ล้าง lastError (reason คือความจริงล่าสุด);
     *         path connect fail → set lastError (reason = null ถ้าปิดก่อน connect สำเร็จ)
     */
    @Volatile var lastCloseReason: CloseReason? = null
        private set

    // R4 (F-1): เพดาน 4MB — producer ช้าลงแทนคิวบาน (yes flood = OOM round 9)
    private val pending = BoundedByteQueue()
    private val draining = AtomicBoolean(false)
    private var drainJob: Job? = null
    private var beforeFirstOutput: (() -> Unit)? = null

    @Volatile private var closingByUs = false

    /** beforeFirstOutput: ล้างจอตอน output แรกของ session นี้ ไม่ใช่ตอนเริ่ม dial — retry ที่ยังต่อไม่ได้ต้องไม่ลบข้อความเดิม */
    fun connect(backend: TerminalBackend, startup: StartupArgs, beforeFirstOutput: (() -> Unit)? = null) {
        close()
        closingByUs = false
        this.beforeFirstOutput = beforeFirstOutput
        lastError = null // T3: กัน stale decision (retryable ของรอบก่อน)
        lastCloseReason = null
        activeBackend = backend
        setState(State.CONNECTING, null)
        backend.onData = { bytes ->
            pending.put(bytes) // MAY block เมื่อ backlog ≥ HIGH (= sshd pump หยุด read = window ไม่ release = server throttle)
            startDrain()
        }
        backend.onClosed = { reason ->
            // close() ของเราเอง = อย่าทับสถานะ IDLE ที่กำลังจะ set (race UI)
            if (!closingByUs) {
                scope.launch(Dispatchers.Main) {
                    if (activeBackend === backend) activeBackend = null // remote close = backend ตายจริง ห้าม write ต่อ
                    lastCloseReason = reason // T3: ผู้เรียก reconnect อ่าน flag retryable จากนี่
                    lastError = null
                    setState(State.DISCONNECTED, when (reason) {
                        is CloseReason.Normal -> "connection closed"
                        is CloseReason.Error -> reason.message
                    })
                }
            }
        }
        scope.launch(ioDispatcher) {
            try {
                backend.connect(startup)
                if (activeBackend !== backend) { // T4: ถูกแทนกลาง connect = shell ที่เปิดสำเร็จแล้วต้องถูกปิดด้วย ไม่งั้น session หลุด (orphan)
                    runCatching { backend.close() }
                    return@launch
                }
                lastError = null
                lastCloseReason = null // T3: connected = decision input ต้องเริ่มจากศูนย์
                setState(State.CONNECTED, null)
            } catch (e: BackendError) {
                lastError = e
                scope.launch(Dispatchers.Main) {
                    if (activeBackend === backend) activeBackend = null // bug round 2: backend fail ค้าง → write → throw กลาง main = crash
                    setState(State.DISCONNECTED, "${e.code}: ${e.message}")
                }
            } catch (t: Throwable) {
                // Throwable ไม่ใช่ Exception — class-init/Error จาก lib ต้องไม่ kill app (เคย crash ด้วย ExceptionInInitializerError)
                lastError = BackendError(BackendError.Code.UNREACHABLE, t.toString(), t)
                scope.launch(Dispatchers.Main) {
                    if (activeBackend === backend) activeBackend = null
                    setState(State.DISCONNECTED, t.toString())
                }
            }
        }
    }

    /** terminal ที่ disconnect แล้วต้อง "เงียบ" — ไม่ใช่ throw (เคย crash: user tap ⌃C หลัง auth fail) */
    fun write(s: String) = write(s.toByteArray())

    fun write(bytes: ByteArray) {
        val b = activeBackend ?: return
        if (state != State.CONNECTED) return
        runCatching { b.write(bytes) }.onFailure {
            scope.launch(Dispatchers.Main) {
                if (activeBackend === b) activeBackend = null
                setState(State.DISCONNECTED, "write failed: ${it.message}")
            }
        }
    }

    // ---- R9 resize coalescer ----
    // fold/drag window = onSizeChanged ~60 ครั้ง/วินาที → เดิมยิง backend.resize ทุกครั้ง = SIGWINCH storm
    // (sshd ส่ง SIGWINCH + server redraw เต็มจอต่อครั้ง) — policy: first-immediate + trailing throttle ≤120ms
    //  - call แรกของ burst ส่งทันที (responsive), ตัวหลังจากนั้นรวมเป็น submit เดียวที่ pair ล่าสุด ณ deadline
    //  - burst ยาวต่อเนื่อง = ส่งซ้ำมากสุด 1 ครั้ง/120ms (pair ล่าสุดเสมอ → server ไม่มีวันค้าง size เก่า)
    // engine resize ยัง view-driven synchronous (TerminalView.onSizeChanged) — coalescer คุมเฉพาะ backend/pty
    var pendingResize: Pair<Int, Int>? = null
        private set

    @Volatile private var latestResize: Pair<Int, Int>? = null
    // nullable = "ยังไม่เคยส่ง" (อย่าใช้ 0L sentinel — clock test เริ่มที่ 0 จริงชน flag ได้)
    private var lastResizeSentNs: Long? = null
    private var resizeTrail: Job? = null

    fun resize(cols: Int, rows: Int) {
        if (state != State.CONNECTED) return
        val pair = cols to rows
        latestResize = pair
        scope.launch(Dispatchers.Main) { pendingResize = pair }
        val now = nanoClock()
        val waitNs = lastResizeSentNs?.let { RESIZE_MIN_GAP_NS - (now - it) } ?: 0L // ยังไม่เคยส่ง = first-immediate
        if (waitNs <= 0) {
            resizeTrail?.cancel()
            resizeTrail = null
            lastResizeSentNs = now
            runCatching { activeBackend?.resize(cols, rows) }
        } else if (resizeTrail?.isActive != true) {
            // deadline = lastSent+GAP (fixed ไม่ใช่ sliding — กัน burst ยาวเลื่อนไม่มีวันยิง)
            resizeTrail = scope.launch(Dispatchers.Main) {
                delay(waitNs / 1_000_000)
                val (c, r) = latestResize ?: return@launch
                lastResizeSentNs = nanoClock()
                runCatching { activeBackend?.resize(c, r) } // backend เป็น null ไปแล้ว (disconnect) = no-op ปลอดภัย
            }
        }
    }

    private fun setState(s: State, message: String?) {
        state = s
        scope.launch(Dispatchers.Main) {
            onUiStatus(s, message)
            // JSON เอง (ไม่ใช้ org.json — android.jar stub ทำ unit test พัง) — message ถูก escape
            val json = "{\"state\":\"${s.name.lowercase()}\",\"message\":${jsQuote(message ?: "")}}"
            evalJs("window.Aterm && Aterm.onStatus(${jsQuote(json)});")
        }
    }

    private fun startDrain() {
        if (!draining.compareAndSet(false, true)) return
        // Main: evaluateJavascript ห้ามเรียกจาก thread อื่น; delay(16) = suspend ไม่บล็อก UI
        drainJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                // R4: pollUpTo แทนวน poll — batch เดิมเพดาน 64KB/call (กัน JS string ใหญ่เกิน)
                val bytes = pending.pollUpTo(64 * 1024)
                if (bytes.isEmpty()) break
                val sink = nativeSink
                // R3 (F-1): sink/engine throw = ห้ามพังทั้ง drain loop/app — map ≡ write-fail pattern
                // (null backend ก่อน setState แล้วปิด loop; draining=false เพื่อให้ connect ครั้งใหม่ drain ต่อได้)
                try {
                    beforeFirstOutput?.let { beforeFirstOutput = null; it() }
                    if (sink != null) sink(bytes)
                    else evalJs("window.Aterm && Aterm.onBytes('${java.util.Base64.getEncoder().encodeToString(bytes)}');")
                } catch (t: Throwable) {
                    val b = activeBackend
                    activeBackend = null
                    setState(State.DISCONNECTED, "render failed: ${t.message}")
                    closingByUs = true // กัน onClosed(Normal) มาทับข้อความ "render failed"
                    try { b?.close() } catch (_: Throwable) {}
                    draining.set(false)
                    return@launch
                }
                delay(16)
            }
            draining.set(false)
        }
    }

    fun close() {
        beforeFirstOutput = null
        drainJob?.cancel()
        resizeTrail?.cancel() // R9: trailing resize ที่รออยู่ห้ามยิงข้าม session (backend ถูก close แล้ว)
        draining.set(false)
        pending.clear()
        closingByUs = true
        activeBackend?.close()
        activeBackend = null
        if (state != State.IDLE) setState(State.IDLE, null)
    }

    companion object {
        /** R9: เพดานอัตราการส่ง resize ไป backend (120ms — เร็วกว่า frame แต่ช้ากว่า drag storm ~16ms) */
        const val RESIZE_MIN_GAP_NS = 120_000_000L

        /** JSON string literal แบบแมน (escape ครบสำหรับ control chars/quote/backslash) */
        internal fun jsQuote(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                // \f 0x0C ฯลฯ → \uXXXX (branch ทั่วไป) — ห้ามใส่ literal มองไม่เห็นใน source
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            return sb.append('"').toString()
        }
    }
}
