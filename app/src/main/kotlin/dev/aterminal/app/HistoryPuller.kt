package dev.aterminal.app

import dev.aterminal.core.TerminalBackend
import dev.aterminal.core.TmuxHistory
import dev.aterminal.engine.TerminalEngine
import dev.aterminal.engine.term.TermLine

/**
 * ticket tmux/08: one pull = one page — gesture ที่ขอบบนเป็นผู้เรียก, ไม่มี auto-chain (Q4)
 *
 * ลำดับต่อบน pull (wire facts ทั้งหมดใน TmuxHistory KDoc):
 *   1. `display -p '#{history_size}'` → gate `hasDeeperHistory` (บน top แล้ว = จบเงียบ, ไม่มี capture)
 *   2. `capture-pane -p -J -S -(displayDepth+page) -E -1` — window anchored ที่ tail of history
 *   3. generation เปลี่ยนระหว่างทาง (pane ถูกรีไซเซ) = ทิ้งทั้งหน้า ไม่ inject — gesture ถัดไป re-anchor เอง (Q3)
 *   4. `exitCode == null` = output ชน exec cap (1MB) หรือ channel ปิดก่อน status = ทิ้งทั้งก้อน (ห้าม inject partial)
 *      + halve page ครั้งถัดไป (floor MIN) — Q2
 *
 * fail-soft ทุกทาง: fail = warn line เดียวใน ConnectionLog, gesture ถัดไปลองใหม่ (pull แพง ~2ms server + 1 RTT)
 *
 * threading: pull() เร็ว ๆ กันต้องมาจาก thread เดียว (host = main) — busy flag ไม่มี synchronization
 * depths provider คืน (logical, display) ของ scrollback ฝั่ง app — logical ตัด overlap, display กำหนด window (คนละมิติโดยเจตนา)
 *  - native = อ่าน buffer ตรง ๆ; webview = evaluateJavascript round-trip (suspend จริง) — null = renderer ไม่พร้อม = abort
 */
internal class HistoryPuller(
    private val backend: TerminalBackend,
    private val target: () -> String?,
    private val appDepths: suspend () -> Pair<Int, Int>?,
    private val generation: () -> Int,
    private val onInject: (List<String>) -> Unit,
    private val onStatus: (Boolean) -> Unit = {},
    private val onWarn: (String) -> Unit = {},
) {

    private var busy = false
    private var pageSize = TmuxHistory.DEFAULT_PAGE_LINES

    /**
     * true-top marker: historySize ที่พิสูจน์แล้วว่า "capture ทั้งก้อน = ไม่มีของเก่ากว่านี้" (window==historySize
     * และ fetched ≤ overlap — เกิดได้จริงกับ wrap-heavy history ที่ logical_total < history_size display:
     * gate `hasDeeperHistory(logical,…)` ไม่มีทางปิดเอง = gesture ที่ top churn นิรันดร์ toast+capture+warn)
     * history โตเกิน marker (live output ใหม่) = gate เปิดเอง — reflow (gen เปลี่ยน) = ลืม marker (re-anchor)
     */
    private var topObservedSize = -1

    /**
     * sticky display:logical ratio — measured from the last capture (window display lines ÷ logical lines returned, clamp ≥1)
     * Window sizing must be in DISPLAY units the pane actually spends: `-J` output shrinks wrapped-heavy content (measured 7.5:1
     * on 600-char lines at 80 cols) — sizing by app displayDepth alone then fetches less than the overlap ⇒ planInjection
     * permanently empty (stall). app displayDepth/app logicalDepth is exact when pane cols == app cols; other content = learned
     * from the first real capture. 1 when the app has no scrollback yet (no ratio observable, page-sized window is the floor).
     */
    private var ratio = 1.0

    suspend fun pull() {
        if (busy) return // gesture ซ้ำระหว่าง pull = ทิ้ง (ผลรอบปัจจุบันจะครอบคลุมเอง)
        busy = true
        try {
            pullOnce() // onStatus(true) ยกไปไว้ใน pullOnce — no-op (บน top / fail-soft ก่อน capture) ต้องเงียบจริง (Q4)
        } finally {
            onStatus(false) // idempotent end — host ปิด toast เสมอแม้ never started
            busy = false
        }
    }

    private suspend fun pullOnce() {
        val target = target()
        val gen = generation()
        val depths = appDepths()
        if (depths == null) {
            onWarn("depths unavailable (renderer not ready)")
            return
        }
        val (logical0, display) = depths
        val sizeRes = runCatching { backend.exec(TmuxHistory.historySizeCommand(target)) }
            .getOrElse { e ->
                onWarn("history size exec failed: ${e.javaClass.simpleName}")
                return
            }
        val historySize = TmuxHistory.parseHistorySize(sizeRes.stdout)
        if (historySize == null) {
            onWarn("history size unparseable (exit ${sizeRes.exitCode})") // "no server running" ฯลฯ — ไม่ใช่ fatal
            return
        }
        if (!TmuxHistory.hasDeeperHistory(logical0, historySize)) return // on top of history = ไม่มีอะไรเก่ากว่านี้ (จบเงียบ — ไม่มี toast)
        if (historySize <= topObservedSize) return // proven ceiling — unit-blind gate ผ่านได้แต่ไม่มีของจริง (จบเงียบเช่นกัน)
        onStatus(true) // จากจุดนี้มีของให้ดึงจริง = "Loading history…" (Q4) — fail-soft ทีหลังมี onStatus(false) ใน pull() ปิดให้เสมอ
        // window ต้องเป็น DISPLAY lines ที่ *pane* ใช้: overlap (logical0 บรรทัด) กิน display ≈ logical0×r
        // r เริ่มจาก avg ฝั่ง app (display/logical — exact เมื่อ pane cols ≡ app cols) แล้วเรียนรู้จาก capture จริง
        val ratioNow = if (logical0 > 0) maxOf(ratio, display.toDouble() / logical0) else ratio
        val overlapDisplay = kotlin.math.ceil(logical0 * ratioNow).toInt().coerceAtLeast(display)
        val window = TmuxHistory.windowAbove(overlapDisplay, pageSize, historySize)
        val cap = runCatching { backend.exec(TmuxHistory.captureCommand(target, window)) }
            .getOrElse { e ->
                onWarn("history capture exec failed: ${e.javaClass.simpleName}")
                return
            }
        if (cap.exitCode == null) { // cap overflow (หรือ channel ปิดกลางทาง) — ผลบางส่วนใช้ไม่ได้ = halve แล้วค่อยใหม่
            pageSize = TmuxHistory.nextPageSize(pageSize, overflow = true)
            onWarn("capture overflow at window=$window — page -> $pageSize")
            return
        }
        if (cap.exitCode != 0) {
            onWarn("capture exit ${cap.exitCode}: ${cap.stderr.take(120)}")
            return
        }
        if (generation() != gen) { // reflow ระหว่าง pull — anchor เก่า invalid, gesture ถัดไป re-measure (Q3)
            ratio = 1.0 // consumption ratio เก่า invalid ไปพร้อม reflow — เรียนใหม่จาก capture ถัดไป
            topObservedSize = -1 // เช่นกัน: unit-blind proof เก่าใช้ไม่ได้หลัง re-wrap — re-probe ได้ (fail-safe = churn รอบเดียว)
            onWarn("history page dropped: resize mid-pull")
            return
        }
        // depth อ่านซ้ำ: output ใหม่ระหว่าง exec สองตัว = overlap ฝั่ง app โตขึ้น → inject น้อยลง = safe ทิศ duplication
        val logicalNow = appDepths()?.first ?: return // renderer ตายกลาง pull = ทิ้งหน้า (fail-soft)
        val fetched = TmuxHistory.captureLines(cap.stdout)
        if (fetched.isNotEmpty()) ratio = maxOf(1.0, window.toDouble() / fetched.size) // sticky: วัดจริงจาก pane (fail-soft ทิศ over-capture)
        val fresh = TmuxHistory.planInjection(fetched, logicalNow)
        if (fresh.isEmpty() && fetched.isNotEmpty()) {
            if (window >= historySize) {
                topObservedSize = historySize // window = history ทั้งก้อน แล้วถูก overlap กลืนหมด = on top จริง (unit-independent proof) — จบเงียบ
                return
            }
            onWarn("window=$window gave ${fetched.size} logical lines < overlap=$logicalNow — ratio=$ratio, next pull grows")
            return
        }
        if (fresh.isNotEmpty()) onInject(fresh)
    }
}

/**
 * tmux/08: capture text → TermLine พร้อม inject — throwaway engine ที่ cols ของ app
 * (re-wrap ≡ สิ่งที่ app จะวาด — บรรทัดยาวเกิน cols = wrap ต่อเนื่อง, ผล = input + continuations, ห้ามตัดที่ input.size)
 *
 * isWrapped trap (test `parseHistoryText keeps...` จับได้): ทั้ง bundle และ engine ติด isWrapped=true
 * เฉพาะ wrap ที่ y++ ปกติ — wrap ตรงแถว scrollBottom = scrollUp แล้ว *ไม่* ติด flag (bundle literal ตรวจแล้ว 11-07)
 * → scratch rows=1 (ทุก wrap = bottom = scroll) = history ทั้งก้อนถูก inject โดยไม่กลุ่มบรรทัด → resize/reflow ผิดทั้งหน้า
 * วิธีแก้: chunk ที่ rows0 เพียงพอต่อการไม่มี scroll เลย (ทุก wrap = mid-screen = flag ติดโดย parser ≡ xterm)
 * bound: frag(line) ≤ ceil(2·len/cols) (UTF-16 unit กว้างสุด 2), min 1; rows0 = (ผลรวม bound ทุกบรรทัด) + 2 → cursor ไม่ชน scrollBottom
 * twin: terminal.html atermPrependHistory (webview scratch) ใช้ rule เดียวกัน — budget คนละค่า (JS = rows 512−2, parser คนละตัว) ห้ามคิดว่า sync กันอัตโนมัติ
 * memory: chunk ≈ 400 แถว (rows0×cols cells) — หนึ่ง throwaway engine ต่อ chunk, ทิ้งหลังย้าย line ออก
 */
private const val HISTORY_CHUNK_ROWS = 400

internal fun parseHistoryText(lines: List<String>, cols: Int): List<TermLine> {
    val out = mutableListOf<TermLine>()
    var i = 0
    while (i < lines.size) {
        var budget = 0
        var j = i
        while (j < lines.size) {
            val f = maxOf(1, (2 * lines[j].length + cols - 1) / cols)
            if (j > i && budget + f > HISTORY_CHUNK_ROWS) break
            budget += f
            j++
        }
        out += parseHistoryChunk(lines.subList(i, j), cols, budget + 2)
        i = j
    }
    return out
}

/** chunk เดียว = no-scroll โดย construction (rows0 ≥ used+1): cursor หลัง CRLF สุดท้าย = y = จำนวนแถว content พอดี */
private fun parseHistoryChunk(lines: List<String>, cols: Int, rows0: Int): List<TermLine> {
    val t = TerminalEngine(cols0 = cols, rows0 = rows0, historySize = 0)
    val sb = StringBuilder()
    for (l in lines) sb.append(l).append("\r\n")
    t.write(sb.toString().toByteArray(Charsets.UTF_8))
    return (0 until t.buf.y).map { t.buf.lines[it] }
}
