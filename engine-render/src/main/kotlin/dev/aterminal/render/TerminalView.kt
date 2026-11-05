package dev.aterminal.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextShaper
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.aterminal.core.ActionTarget
import dev.aterminal.core.Command
import dev.aterminal.core.ImeAutocomplete
import dev.aterminal.core.NativeKeymap
import dev.aterminal.core.ResolvedEntry
import dev.aterminal.core.ResolvedTable
import dev.aterminal.core.strArg
import dev.aterminal.engine.TerminalEngine
import dev.aterminal.engine.term.Cell
import dev.aterminal.render.input.BufferText
import dev.aterminal.render.input.Finder
import dev.aterminal.render.input.GestureAction
import dev.aterminal.render.input.KernelGeom
import dev.aterminal.render.input.MarkKey
import dev.aterminal.render.input.MarkStateMachine
import dev.aterminal.render.input.MouseActions
import dev.aterminal.render.input.MouseEmitter
import dev.aterminal.render.input.MouseGate
import dev.aterminal.render.input.PreEdit
import dev.aterminal.render.input.ScrollState
import dev.aterminal.render.input.SelectionModel
import dev.aterminal.render.input.TouchKernel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * NativeTerminalView — วาด grid ของ :engine ลง Canvas (แทน WebView+xterm.js)
 *  - shaping: API 31+ ใช้ android.text.TextShaper + Canvas.drawGlyphs (แบบ array — javap ยืนยันแล้ว
 *    ว่า android-34 ไม่มี overload PositionedGlyphs บน Canvas; consumer ได้ PositionedGlyphs มา group ตาม Font แล้ว flush)
 *    นี่คือบทเรียน "TextShaper = text not graphics": ให้ shaper จัดThai cluster/combining ให้ตรง posisi
 *  - fallback API 29-30 (T5): drawText ทีละ grapheme cluster ณ cell origin (Clusters) — marks อยู่ในพื้นที่ของ cell ตัวเอง
 *  - cell lock (round 23 + HW 11-05): run ที่มี combining mark **หรือ** advance รวมไม่ ≡ grid
 *    = path drawText ต่อ cluster **ทุก API level** (shaper ต่อ cluster = ~1 native call ต่อ cell
 *    ต่อเฟรม — ดู comment ใน drawRun; เกณฑ์ความกว้าง = CellLock.placementExact + memo ใน view)
 *  - run ASCII (all code<127, w1) = drawText path เดียวกันทุก API (mono advance = cellW)
 *  - input: soft keyboard ผ่าน BaseInputConnection (commit → UTF-8 → onFeed), hardware key ผ่าน onKeyDown
 *    (arrow/DECCKM อ่าน engine.modes.appCursorKeys — vim ต้องได้ \x1bOA)
 */
class TerminalView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    // ---- model wiring (host ตั้ง) ----
    var engine: TerminalEngine? = null
        private set
    var palette: Palette = Palette(DEFAULT16, DEFAULT_FG, DEFAULT_BG, DEFAULT_FG)
    var onFeed: ((ByteArray) -> Unit)? = null       // keystroke → backend
    var onGridResize: ((cols: Int, rows: Int) -> Unit)? = null // view เปลี่ยน size → backend pty resize

    // ---- metrics ----
    private val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var cellW = 8f
    private var cellH = 16f
    private var baselineOffset = 13f
    var fontSizePx = 0f
        private set
    private var typefaceStack: List<String> = emptyList() // T6: fontFace แบบ CSS stack — resolve เป็น mono จริง

    private val boldCache = HashMap<Int, Typeface>()
    private val scratch = Rect()

    // ---- scrollback (T1) — view state ล้วน, engine ไม่รู้ ----
    val scroll = ScrollState()

    // ---- text selection (T2, ≡ terminal.html touchSelect) + touch kernel (T3) ----
    val selection = SelectionModel()
    var onCopy: ((String) -> Unit)? = null // host = clipboard + toast (SessionBridge)
    var copyOnSelect = false // WT global ≡ JS: copyOnSelect=true = watcher copies on change, gesture ไม่ copy ซ้ำ

    // ---- keybindings (T4, ≡ attachCustomKeyEventHandler / aterm-keys.js) ----
    var keymap: ResolvedTable = ResolvedTable(emptyList()) // host push จาก rebuildKeybindings
    var onAppAction: ((Command) -> Unit)? = null // APP-target/UNSUPPORTED → host runAction (≡ Droid.onAction)

    // ---- preedit (T5, ≡ xterm.js composition hold — G8) ----
    var preeditEnabled = true // non-WT extension (settings.preeditEnabled); false = path เดิม byte-for-byte (emergency rollback)
    /**
     * host ตั้งตาม settings.imeSuggestions (ค่าจริงคำนวณนอก view: `ImeSuggestions.strictNow`)
     * true = ขอ inputType แบบ "ช่องรหัสผ่านผ่านตา" ซึ่ง IME ส่วนใหญ่เลิกเดาคำให้ **แต่เลิก
     * compose ด้วย** (วัดจริงแล้ว: tap 'a' ได้ 61 ทันที ไม่มี preedit) = user ต้องเลือกเอง
     * ระหว่าง " strip เติมคำให้ " กับ "พิมพ์ภาษาที่ต้องรวม cluster ได้" (ดู ImeSuggestions)
     */
    var imeStrict = false
        set(v) {
            if (field == v) return
            field = v
            refreshIme() // IME อ่าน EditorInfo ใหม่ = ผลทันทีโดยไม่ต้องปิด-เปิดคีย์บอร์ด
        }
    /**
     * host นับ "IME ไม่เชื่อ flag" (HW 11-05) — view فقطรายงานสิ่งที่เห็น การตีความ/เตือน
     * อยู่ที่ host เพราะ webview ส่งสัญญาณเดียวกันคนละ path (Chromium เป็นเจ้าของ InputConnection)
     * COMPOSITION = IME เปิด composing region ตอนเราขอ password mode; BURST = ข้อความทั้งก้อน
     * ถึง PTY โดยไม่มี composition (autocorrect/แตะคำจาก strip)
     */
    var onImeAutocomplete: ((ImeAutocomplete.Kind, String) -> Unit)? = null
    val preEdit = PreEdit()

    // ---- find (T6, ≡ aterm-find.js — state อยู่ที่ view เหมือน JS อยู่ที่ bar; HUD = onFindStatus ≡ Droid.findStatus) ----
    val finder = Finder()
    private val mark = MarkStateMachine() // T7 ≡ let mark ใน aterm-keys.js (state = pure class, paint/reveal = view)
    var onFindStatus: ((query: String?, i: Int, n: Int) -> Unit)? = null // query null = ปิด (≡ findStatus(''))
    private val selHandler = Handler(Looper.getMainLooper())
    // T3: arbitration ทั้งหมดอยู่ใน TouchKernel (pure, ≡ JS) — view เป็น glue: px เข้า GestureAction ออก
    private val kernel = TouchKernel(KernelGeom(8f, 16f, 80, 24))
    private val mouseEm = MouseEmitter()
    private val longPressRunnable = Runnable { kernel.onHold()?.let { dispatchGesture(it) } }

    // ---- cursor blink ----
    private var blinkOn = true
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkTick = object : Runnable {
        override fun run() {
            if (engine?.modes?.cursorVisible == true) {
                blinkOn = !blinkOn
                // redraw ทั้ง grid = ค่ายังชีพของ native renderer — view ไม่ได้ focus = cursor ไม่ถูกวาด
                // (guard ใน onDraw) จึงไม่ต้องปลุก onDraw ทุก 530ms ตอน find bar/dialog/IME ถือ focus อยู่
                if (hasFocus()) invalidate()
            }
            blinkHandler.postDelayed(this, 530)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundColor(DEFAULT_BG)
    }

    fun configure(fontSizePx: Float, fontFace: String?) {
        this.fontSizePx = fontSizePx
        this.typefaceStack = FontStack.families(fontFace)
        boldCache.clear() // stack ใหม่ = cache เดิมใช้ไม่ได้ (key hash ของ family เก่า)
        fitCache.evictAll() // cellW/font เปลี่ยน = ข้อสรุปความกว้างเก่าใช้ไม่ได้ (LruCache ไม่มี clear)
        basePaint.typeface = resolveTypeface(Typeface.NORMAL)
        basePaint.textSize = fontSizePx
        val fm = basePaint.fontMetrics
        cellW = maxOf(1f, ceil(basePaint.measureText("M")))
        val lineH = fm.descent - fm.ascent
        // round 23: Mark Headroom — cellH เดิม (round 15-HW) แค่ ~= webview 1.2, ไทยสามระดับยังชน
        // neighboring rules → + clearance ต่อ font size (MarkHeadroom = single tunable, ≡ webview 1.33)
        // baselineOffset ด้านล่าง center ด้วย (cellH - lineH)/2 อยู่แล้ว = ห้าม center ซ้ำ
        cellH = maxOf(1f, ceil(maxOf(lineH * 1.05f, fontSizePx * 1.15f))) + MarkHeadroom.clearancePx(fontSizePx)
        baselineOffset = ((cellH - lineH) / 2f - fm.ascent)
        requestLayout(); invalidate()
        if (width > 0 && height > 0) onSizeChanged(width, height, 0, 0) // cell metrics เปลี่ยน = grid count เปลี่ยน
    }

    /**
     * ≡ CSS font-family fallback แต่บังคับ MONO จริง: `Typeface.create(name)` ไม่ throw เมื่อ font
     * ไม่ถูก bundle — มันให้ Roboto แบบเงียบ (T6: cellW=31/advance=20 ทั้ง grid เพี้ยน) — จึงตรวจ
     * advance i==M==W แล้วไล่ family ตาม stack; ไม่เจอเลย = MONOSPACE ของระบบ (AOSP = mono จริง)
     */
    private fun resolveTypeface(style: Int): Typeface {
        val key = style * 31 + typefaceStack.hashCode()
        boldCache[key]?.let { return it }
        val probe = Paint(basePaint) // measure tool — ไม่สน color/style อื่น
        probe.textSize = fontSizePx
        var chosen: Typeface? = null
        for (fam in typefaceStack) {
            // round 15-HW: family ที่ bundle = load จาก asset ก่อน (≡ @font-face ฝั่ง webview, parity)
            // — Typeface.create(fam) บน font ที่ไม่ได้ติดตั้งเครื่อง = Roboto เงียบ (กับดัก round-11)
            val bundled = BundledFont.lookup(context.assets, fam)
            val tf = (bundled?.let { runCatching { Typeface.create(it, style) }.getOrNull() }
                ?: runCatching { Typeface.create(fam, style) }.getOrNull()) ?: continue
            if (isMonospaced(probe, tf)) { chosen = tf; break }
        }
        if (chosen == null) {
            val mono = Typeface.create(Typeface.MONOSPACE, style)
            if (!isMonospaced(probe, mono)) android.util.Log.w("ATerminal", "no monospace family in $typefaceStack — grid drift likely")
            chosen = mono
        }
        boldCache[key] = chosen
        return chosen
    }

    /** mono จริง = advance ของ glyph แคบ/กว้าง/เต็มแถว ตรงกันทุกตัว */
    private fun isMonospaced(probe: Paint, tf: Typeface): Boolean {
        probe.typeface = tf
        val i = probe.measureText("i"); val m = probe.measureText("M"); val w = probe.measureText("W")
        return Math.abs(i - m) < 0.01f && Math.abs(m - w) < 0.01f
    }

    private fun paintFor(style: RenderStyle): TextPaint {
        val p = basePaint
        p.typeface = resolveTypeface(
            (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0),
        )
        p.isUnderlineText = style.underline
        p.isStrikeThruText = style.strike
        return p
    }

    private fun dim(c: Int) = (c and 0x00FFFFFF) or (0x80 shl 24)

    // ================= draw =================

    override fun onDraw(canvas: Canvas) {
        val e = engine ?: return
        canvas.drawColor(palette.defaultBg)
        val b = e.buf
        val cols = e.cols; val rows = e.rows
        scroll.clamp(b.baseY) // reflow/resize/swap — clamp เดียว ไม่ follow (T1)
        val vpTop = scroll.top
        // selection tint (T2) — under text ≡ xterm canvas renderer (selection layer ใต้ glyphs);
        // cell ที่มี bg สีของตัวเองจะทับ tint = approximation จด FINDINGS (xterm ก็ skip bg cell สีตาม selection model)
        if (selection.active) {
            paintSelection.color = palette.selectionBg
            for (r in 0 until rows) {
                val rng = selection.rowRange(vpTop + r) ?: continue
                val from = rng.first
                val to = minOf(rng.last, cols - 1)
                if (from <= to) {
                    canvas.drawRect(from * cellW, r * cellH, (to + 1) * cellW, (r + 1) * cellH, paintSelection)
                }
            }
        }
        for (r in 0 until rows) {
            val line = b.lines[vpTop + r] // absolute (scrollback inclusive); vpTop<=baseY ⇒ ในขอบเสมอ
            val top = r * cellH
            for (run in RunMerger.mergeLine(line, palette)) {
                val x0 = run.startCol * cellW
                val w = (run.endCol - run.startCol) * cellW
                if (run.style.bg != palette.defaultBg) {
                    scratch.set(x0.toInt(), top.toInt(), Math.ceil((x0 + w).toDouble()).toInt(), (top + cellH).toInt())
                    paintBg.color = run.style.bg
                    canvas.drawRect(scratch.left.toFloat(), scratch.top.toFloat(), scratch.right.toFloat(), scratch.bottom.toFloat(), paintBg)
                }
                drawRun(canvas, run, x0, top + baselineOffset)
                if (run.style.overline) {
                    // T04: เส้น ≡ underline จริง = stroke+AA ผ่าน metric ของ font itself
                    // (bundled FiraCode 50/1950em ≈ 0.94px ที่ fs14 — drawRect ขอบแข็ง 1px
                    // มองบางกว่า underline ทั้งที่ "เท่ากัน" เพราะ underline ของ glyph renderer
                    // มี AA spread; stroke+AA ให้ ink profile เดียวกัน — device-proven round 20)
                    // paintFor (ไม่พึ่ง side-effect ของ drawRun) = metric ตรง typeface ของ run เอง
                    val t = overlineThicknessPx(paintFor(run.style).underlineThickness, cellH)
                    // dim ≡ drawRun: faint text ต้องได้ band จางเท่า glyph (underline ก็จางตาม paint)
                    paintLine.color = if (run.style.dimAlpha) dim(run.style.fg) else run.style.fg
                    paintLine.strokeWidth = t
                    canvas.drawLine(x0, top + 1f + t / 2f, x0 + w, top + 1f + t / 2f, paintLine)
                }
            }
        }
        // cursor — xterm canvas renderer (D5, device-proven 2026-11-03): filled block + glyph ที่วาด
        // ทับด้วยสี bg ของ cell เอง (bg default → defaultBg ≡ สีพื้นหลังเดิมของ glyph ใต้ block)
        // T1: cursor อยู่ใน live area (abs = baseY+y) — scroll ขึ้นดู history = ไม่เห็น cursor (≡ xterm)
        val curRow = b.baseY + b.y - vpTop
        if (blinkOn && b.x < cols && e.modes.cursorVisible && hasFocus() && curRow in 0 until rows) {
            val cy = curRow * cellH
            val cell = b.lines[vpTop + curRow].get(b.x)
            // block กว้างตาม cell.width ของ glyph ใต้ cursor (wide/CJK = 2 cells ≡ xterm; dummy ครึ่งขวา = 1 cell, ไม่ redraw)
            val cx = b.x * cellW
            val cw = if (cell.width > 0) cell.width else 1
            paintCursor.color = palette.cursor
            canvas.drawRect(cx, cy, cx + cw * cellW, cy + cellH, paintCursor)
            // INVISIBLE = ไม่วาด glyph ใต้ cursor เช่นกัน (≡ xterm; RunMerger ซ่อนที่ body แล้ว — guard นี้กัน path ตรงของ cursor)
            if (cell.width > 0 && cell.attrs and Cell.B_INVISIBLE == 0 && cell.ch.isNotEmpty() && cell.ch != " ") {
                val st = StyleResolver.resolve(cell, palette)
                // dimAlpha off: bg ของ cell ไม่ dim ตาม fg (≡ xterm — alpha ใช้กับ glyph colour เท่านั้น)
                drawRun(canvas, Run(cell.ch, st.copy(fg = st.bg, bg = palette.cursor, dimAlpha = false), b.x, b.x + cell.width), cx, cy + baselineOffset)
            }
        }
        // preedit (T5): composition ที่ IME ถือไว้ — underline ที่ cell cursor, ไม่แตะ PTY (≡ G8 "xterm hold")
        if (preeditEnabled && preEdit.active) {
            val pr = paintFor(
                RenderStyle(palette.defaultFg, palette.defaultBg, false, false, true, false, false, false),
            )
            pr.color = palette.defaultFg
            val prRow = (b.baseY + b.y - vpTop).coerceIn(0, rows - 1)
            canvas.drawText(preEdit.text, b.x * cellW, prRow * cellH + baselineOffset, pr)
        }
        // scrollbar บาง (T1) — มี history เมื่อ total > rows; thumb ∝ viewport, ตำแหน่ง ∝ top/baseY
        val total = b.lines.size
        if (total > rows) {
            val gridH = rows * cellH
            val thumbH = maxOf(cellH * 1.5f, rows.toFloat() / total * gridH)
            val travel = gridH - thumbH
            val thumbY = if (b.baseY > 0) vpTop.toFloat() / b.baseY * travel else 0f
            paintScroll.color = 0x59FFFFFF
            // T5 fix: draw in the dedicated slot right of the painted columns (ScrollBarSlot reserves it)
            val tx = ScrollBarSlot.thumbX(width.toFloat(), cols * cellW)
            canvas.drawRect(tx, thumbY, tx + ScrollBarSlot.THUMB_W, thumbY + thumbH, paintScroll)
        }
    }

    private val paintBg = Paint()
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG) // T04: AA ให้ ink profile ≡ underline (glyph renderer ก็ AA)
    private val paintSelection = Paint()
    private val paintCursor = Paint()
    private val paintScroll = Paint()

    /** memo ของ "run นี้วางด้วย advance ของมันเองแล้วตรง grid หรือยัง" (ดู [CellLock.placementExact]) */
    private class RunFit(val text: String, val cells: Int, val face: Typeface, val exact: Boolean)

    /** LruCache ไม่ใช่ HashMap: ข้อความ terminal เปลี่ยนไม่หยุด = cache ไม่จำกัดจะโตไปเรื่อย ๆ */
    private val fitCache = LruCache<Int, RunFit>(512)

    /**
     * measureText **ครั้งเดียวต่อ run string ที่เปลี่ยน** (memo แล้ว) แล้วเทียบกับ grid ของมัน
     * — เร็วกว่า draw ต่อ cluster มาก และ path ที่ถูกเลือกจะถูกต้องทุกเครื่อง
     * เพราะ measureText ใช้ layout engine เดียวกับที่ shaper ใช้วาง glyph จริง
     */
    private fun placementExact(text: String, cells: Int, p: TextPaint): Boolean {
        val face = p.typeface
        val key = (text.hashCode() * 31 + cells) * 31 + face.hashCode()
        fitCache.get(key)?.let { if (it.text == text && it.cells == cells && it.face == face) return it.exact }
        val ok = CellLock.placementExact(p.measureText(text), cells, cellW)
        fitCache.put(key, RunFit(text, cells, face, ok))
        return ok
    }

    private fun drawRun(canvas: Canvas, run: Run, x: Float, baseline: Float) {
        val p = paintFor(run.style)
        p.color = if (run.style.dimAlpha) dim(run.style.fg) else run.style.fg
        if (isAscii(run.text)) { // ASCII fast path — เดิมทุกตัวอักษรทุก API (mono advance = cellW)
            canvas.drawText(run.text, x, baseline, p)
            return
        }
        // Cell lock (round 23) = run ที่มี zero-width cp (combining mark) ต้องถูก anchor ทีละ cluster
        // ณ cell ของตัวเอง: glyph ของ mark เหล่านี้มาจาก fallback font (NotoSansThai) ซึ่ง advance
        // ≠ cellW → advance สะสมทั้ง run = เสา '│' ในตารางไทยงอ (บั๊กที่ round 23 ปิด)
        // Grid contract: every cluster occupies its own cell (RunMerger invariant: dummy skipped,
        // wide isolated) → anchor cluster i at run.startCol + i.
        //
        // perf 11-04: path นี้เคยเป็น TextShaper **ต่อ cluster** บน API31+ ซึ่งจ่ายต่อ cluster =
        // JNI callback + GlyphVector + Font wrapper + IntArray/FloatArray + ลูป per-glyph
        // ≈ 1 calls/cell/เฟรม (ไทยเต็มจอ 80 คอลัมน์ = ~2 พัน shape ต่อ redraw หนึ่งครั้ง)
        // drawText ต่อ cluster = 1 call เล็ก (minikin + cache ของมันเอง) เทียบ shaper ที่จ่าย
        // JNI + GlyphVector + Font + IntArray/FloatArray ต่อ cluster → ถูกกว่ามาก = path ที่
        // API<31 ใช้อยู่แล้วรอบ 11 → รวมเป็น path เดียวทุก API level
        //
        // HW 11-05 (Honor เพี้ยน, Tab S10+ ไม่เป็น): zero-width อย่างเดียว **กันไม่หมด** — run ที่มีแต่
        // ตัวเต็ม cell แต่ font หลักไม่มี glyph ให้ (ไทย `นานๆ` ใน FiraCode) ก็ advance ≠ cellW เช่นกัน
        // → `│` ปิดท้ายเส้นนั้นขยับซ้าย ~8px (วัดจาก screenshot: border อยู่ที่ x=2078 แทน 2086 ขณะที่
        //   ทุก column อื่นของ row เดียวกันตรงเป๊ะ) ส่วน `─`/`│` ไม่เพี้ยนเพราะ bundled font มี glyph
        // → จึงวัดความกว้างจริงของ run เทียบ grid (memo แล้ว) แทนการเดา coverage: measureText ใช้ layout
        //   engine เดียวกับ shaper = เมื่อไหร่ตรง grid, วาดครั้งเดียวได้เสมอ → box-heavy (htop/vim)
        //   ยังเร็วเท่าเดิม, Thai/ฟอนต์ fallback = anchor ทีละ cell (จ่ายเท่า round 23 ที่ใช้อยู่แล้ว)
        if (Build.VERSION.SDK_INT < 31 || CellLock.needsPerCluster(run.text) ||
            !placementExact(run.text, run.endCol - run.startCol, p)
        ) {
            var cx = x
            // HW crash 11-04 (Honor PNM-N49, API 37) = **ห้ามใช้ partial-range overload ที่นี่**:
            // drawText(String, start, X, x, y, paint) กำกวม — docs เขียน X = count แต่ AOSP
            // BaseRecordingCanvas ตรวจ `(start | end | end - start | text.length() - end) < 0`
            // = X เป็น exclusive **end** และ throw IndexOutOfBoundsException() แบบไม่มี message
            // (ตรงกับ log พอดี) — สองความหมายต่างกันเฉพาะ start > 0 ซึ่งเป็น case ของเราทุก cluster
            // หลังตัวแรก จึงวาด substring ผ่าน drawText(text, x, y, paint) = form เดียวที่
            // ไม่มีความหมายที่สองให้ตีผิด (path ที่ API<31 ใช้อยู่แล้วตั้งแต่ round 11)
            // ค่า real: alloc String เล็กต่อ cluster — ถูกกว่า TextShaper ต่อ cluster (ของเดิมรอบ 23) มาก
            Clusters.forEach(run.text) { s, e ->
                canvas.drawText(run.text.substring(s, e), cx, baseline, p)
                cx += cellW
            }
            return
        }
        // API31+ run ที่ layout ของมัน ≡ grid แล้ว (box-drawing/CJK/…) = วาดครั้งเดียว
        // ทั้ง run โดย advance ของ shaper เอง — พิสูจน์แล้วว่าไม่ drift จึง anchor เพิ่มไม่ต้อง
        shapeAndDraw(canvas, run.text, x, baseline, p)
    }

    /**
     * Shape one text at origin x (shaper advances are relative to x only — drawRun admits a run
     * here only after [placementExact] proved its advance sum equals its grid span, so nothing
     * downstream of x can drift)
     */
    private fun shapeAndDraw(canvas: Canvas, text: String, x: Float, baseline: Float, p: TextPaint) {
        TextShaper.shapeText(text, 0, text.length, TextDirectionHeuristics.LTR, p) { _, _, glyphs, gp ->
            val n = glyphs.glyphCount() // javap: method ไม่ใช่ property
            var i = 0
            while (i < n) {
                val font = glyphs.getFont(i)
                var j = i + 1
                while (j < n && glyphs.getFont(j) === font) j++
                val m = j - i
                val ids = IntArray(m) { glyphs.getGlyphId(i + it) }
                val pos = FloatArray(m * 2)
                for (k in 0 until m) {
                    pos[k * 2] = x + glyphs.getGlyphX(i + k)
                    pos[k * 2 + 1] = baseline + glyphs.getGlyphY(i + k)
                }
                canvas.drawGlyphs(ids, 0, pos, 0, m, font, gp)
                i = j
            }
        }
    }

    private fun isAscii(s: String): Boolean {
        for (i in s.indices) if (s[i].code >= 128) return false
        return true
    }

    // ================= geometry / resize =================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    /** grid ล่าสุดที่ view คำนวณจากขนาด (อาจนำหน้า engine — engine ถูกสร้างทีหลังตอน startConnect) */
    var gridCols = 80; private set
    var gridRows = 24; private set

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0 || cellW <= 0f) return
        // round 23 T5: floor over (w - thumb slot) — the scrollbar never paints over the last column
        val cols = ScrollBarSlot.gridCols(w.toFloat(), cellW)
        val rows = maxOf(1, (h / cellH).toInt())
        if (cols == gridCols && rows == gridRows && engine != null) return
        gridCols = cols; gridRows = rows
        val e = engine
        if (e != null && (cols != e.cols || rows != e.rows)) { e.resize(cols, rows); invalidate() }
        // host always gets size (engine อาจยังไม่ create — host เก็บไว้ create ที่ grid ถูก)
        onGridResize?.invoke(cols, rows)
    }

    // ================= input =================

    /**
     * host สร้าง engine ใหม่ทุก startConnect — ทุก view state ที่อ้าง engine เดิม ต้อง reset ตรงนี้
     * (T0 = hook/contract: scroll/selection/preedit/mark/find ที่เพิ่มตั้งแต่ T1 ต้องเคลียร์ในนี้ตาม);
     * host ห้าม assign `engine` ตรง ๆ อีก — compiler กันไว้แล้ว (private set)
     */
    fun attachEngine(e: TerminalEngine?) {
        engine = e
        blinkOn = true
        scroll.attach(e?.buf?.baseY ?: 0)
        selection.clear() // T2: row อ้างอิง abs ของ engine เดิม — เก็บไว้ = เพี้ยน
        selHandler.removeCallbacks(longPressRunnable)
        kernel.abort() // T3: ทิ้ง state gesture กลางคร่อม (release ทิ้งได้ — engine/session ใหม่แล้ว)
        mouseEm.reset() // T3: motion dedup ของ engine เดิมใช้ไม่ได้
        preEdit.clear() // T5: composition ค้างผูกกับ session เดิม — feed ลง session ใหม่ไม่ได้
        rawSentText = ""
        finder.reset() // T6: matches/idx อ้าง abs row ของ engine เดิม
        markExit(true) // T7: mark ค้าง = HUD/markOn ต้องถูกปลด (onMarkMode(false)) + anchor/cur ผูก engine เดิม
        invalidate()
    }

    /** host แปลง text ก่อนเป็น bytes (round 21: app ใช้ทำ keys-bar modifier) — default identity, funnel เดียวกับ feedBytes */
    var preFeedTransform: (String) -> String = { it }

    /** host บอกว่า keys-bar modifier กำลัง active → composition ต้องถูก treat-as-final ทันที (round 21) */
    var preemptComposition: () -> Boolean = { false }

    /**
     * Gboard *ไม่ reset* composing word เมื่อเรา feed บางส่วนลง PTY แล้ว — update ถัดไปส่งทั้งคำ
     * ("j" → flush → "jd") = ตัวอักษรซ้ำ (วัดจริง 2026-11-04: pane "jjjiddj") — ≡ ฝั่ง webview
     * ที่ xterm dedup ด้วย `_dataAlreadySent`; ที่นี่ = prefix ที่ถูกเขียนลง PTY แล้วของคำปัจจุบัน
     */
    private var rawSentText = ""

    /**
     * preedit ที่ค้างอยู่ *ตอน modifier ถูกกด* = text ที่ user พิมพ์ก่อน modifier → ส่ง raw
     * (ข้าม preFeedTransform — ไม่งั้น transform จะ rewrite text อดีตด้วย modifier ใหม่ + กิน latch)
     * ฝั่ง webview ใช้ทางคู่กัน: Droid.onFlush (round 21, วัดจริงบน emulator)
     */
    fun flushPreeditRaw() {
        val pending = preEdit.text
        if (pending.isEmpty()) return
        preEdit.clear()
        rawSentText += pending // invariant: คำเต็มของ IME = rawSentText + preEdit.text
        feedBytes(pending.toByteArray(Charsets.UTF_8))
        invalidate()
    }

    /** ตัด prefix ที่เขียนลง PTY แล้วออกจาก update ของ IME — match ไม่ได้ = คำเปลี่ยน (autocorrect) = ส่งเต็ม + reset */
    private fun stripWrittenPrefix(text: String): String {
        if (rawSentText.isEmpty() || !text.startsWith(rawSentText)) { rawSentText = ""; return text }
        return text.substring(rawSentText.length)
    }

    fun feedText(s: String) { feedBytes(preFeedTransform(s).toByteArray(Charsets.UTF_8)) }
    fun feedBytes(b: ByteArray) {
        onFeed?.invoke(b)
        // ≡ xterm.js scrollOnUserInput (default true): พิมพ์ = กลับ tail ปล่อย content-lock
        // (T6 soak supplement: native คง pin กลาง flood แม้ command ทำงานจริง — มองไม่เห็น output)
        // funnel นี้เฉพาะ keyboard/paste; mouse bytes เข้า onFeed ตรง (≡ xterm: scrollOnUserInput = key เท่านั้น)
        val baseY = engine?.buf?.baseY
        if (baseY != null && scroll.revealForInput(baseY)) invalidate()
    }

    /**
     * T3: drag scroll ย้ายเข้า TouchKernel (level 0) — detector เหลือแค่ fling (momentum)
     * fling scroll เฉพาะ level 0 ≡ แผน (mouse on = gesture ทั้งหมดเป็นของแอปแล้ว)
     */
    private var flingVel = 0f // px/วิ (signed, ฺbias ตามนิ้ว: swipe ขึ้น = ติดลบ = top ลด)
    private var flingAcc = 0f
    private var flingTickRunning = false
    private val flingTick = object : Runnable {
        override fun run() {
            val e = engine ?: return
            flingAcc += flingVel * 0.016f / cellH
            val k = flingAcc.roundToInt()
            if (k != 0) {
                flingAcc -= k
                val before = scroll.top
                scroll.scrollBy(k, e.buf.baseY)
                invalidate()
                if (scroll.top == before) { // ชนขอบ = หยุด (กัน v ค้างไร้ความหมาย)
                    flingTickRunning = false
                    return
                }
            }
            flingVel *= 0.85f
            if (abs(flingVel) < cellH) { flingTickRunning = false; return }
            selHandler.postDelayed(this, 16)
        }
    }

    private val flinger = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (kernel.mouseOn || kernel.selecting || engine?.buf?.baseY == 0) return false
            flingVel = velocityY
            flingAcc = 0f
            if (!flingTickRunning) {
                flingTickRunning = true
                selHandler.postDelayed(flingTick, 16)
            }
            return true
        }
    })

    private fun stopFling() {
        flingVel = 0f
        if (flingTickRunning) {
            flingTickRunning = false
            selHandler.removeCallbacks(flingTick)
        }
    }

    /** drain loop เรียกหลัง engine.write ทุก batch — sticky/pinned ≡ xterm (T1) + eviction compensation (OPEN #3) */
    fun engineOutput(prevBaseY: Int, evicted: Int = 0) {
        engine?.let { scroll.onOutput(prevBaseY, it.buf.baseY, evicted) }
    }

    /** runAction TERMINAL-target (scrollUp…scrollToBottom, T1) — คืน true เมื่อ handle ได้ */
    fun scrollOp(name: String): Boolean {
        val e = engine ?: return false
        val baseY = e.buf.baseY
        when (name) {
            "scrollUp" -> scroll.scrollBy(-1, baseY)
            "scrollDown" -> scroll.scrollBy(1, baseY)
            "scrollUpPage" -> scroll.pageUp(e.rows, baseY)
            "scrollDownPage" -> scroll.pageDown(e.rows, baseY)
            "scrollToTop" -> scroll.toTop(baseY)
            "scrollToBottom" -> scroll.toBottom(baseY)
            else -> return false
        }
        invalidate()
        return true
    }

    // ================= gesture dispatch (T3 ≡ terminal.html touchSelect L89–220) =================

    /** mouse byte หนึ่ง event → MouseEmitter (guards/restrict/dedup ≡ triggerMouseEvent) → onFeed */
    private fun feedMouse(button: Int, action: Int, col: Int, row: Int) {
        val e = engine ?: return
        val bytes = mouseEm.report(
            MouseGate.protocol(e.modes), MouseGate.encoding(e.modes), e.cols, e.rows,
            button, action, col, row,
        )
        bytes ?: return
        onFeed?.invoke(bytes)
    }

    private fun dispatchGesture(a: GestureAction) {
        val e = engine ?: return
        when (a) {
            is GestureAction.ScrollBy -> {
                if (e.buf.baseY != 0) { scroll.scrollBy(a.rows, e.buf.baseY); invalidate() }
            }
            is GestureAction.MousePress -> feedMouse(0, MouseActions.PRESS, a.col, a.row)
            is GestureAction.MouseMotion -> feedMouse(0, MouseActions.MOTION, a.col, a.row)
            is GestureAction.MouseRelease -> feedMouse(0, MouseActions.RELEASE, a.col, a.row)
            is GestureAction.Wheel -> feedMouse(MouseActions.BUTTON_WHEEL, if (a.up) 0 else 1, a.col, a.row)
            is GestureAction.SelStart -> beginSelect(a.col, a.row)
            is GestureAction.SelExtend -> extendSelection(a.col, scroll.top + a.row)
            is GestureAction.SelFinish -> if (a.dragged && !copyOnSelect) doCopy() // ลากจบ = single toast
            GestureAction.SelClear -> { selection.clear(); invalidate() }
        }
    }

    /** ≡ JS long-press timer body: word fallback = single cell, copy ทันที (copyOnSelect=false path) */
    private fun beginSelect(col: Int, viewportRow: Int) {
        val e = engine ?: return
        val absRow = (scroll.top + viewportRow).coerceIn(0, e.buf.lines.size - 1)
        val w = BufferText.wordAt(BufferText.lineText(e.buf.lines[absRow], 0, e.cols), col) ?: (col to col)
        selection.selectWord(w.first, w.second, absRow)
        vibrate(15) // ≡ navigator.vibrate(15)
        doCopy() // selection change ตอน hold → JS ได้ copy เสมอ (manual เอง หรือ watcher เมื่อ copyOnSelect=true)
        invalidate()
    }

    /** ≡ JS touchmove-while-selecting: snap คำเฉพาะทิศ after (kernel บัง scroll ให้แล้ว — level 0/mouse on alike) */
    private fun extendSelection(col: Int, absRowIn: Int) {
        val e = engine ?: return
        val absRow = absRowIn.coerceIn(0, e.buf.lines.size - 1)
        val w = BufferText.wordAt(BufferText.lineText(e.buf.lines[absRow], 0, e.cols), col)
        val after = absRow > selection.anchorRow || (absRow == selection.anchorRow && col >= selection.anchorCol)
        selection.extend(if (w != null && after) w.second else col, absRow)
        if (copyOnSelect) doCopy() // ≡ onSelectionChange watcher (fire ทุก change — storm ≡ JS เดิม)
        invalidate()
    }

    private fun doCopy(singleLine: Boolean = false) {
        val e = engine ?: return
        var text = BufferText.extract(e.buf.lines, selection)
        // ≡ JS doCopy singleLine: s.replace(/\r?\n\s*/g, ' ') — WT CopyAction.singleLine
        if (singleLine) text = Regex("\r?\n\\s*").replace(text, " ")
        if (text.isNotEmpty()) onCopy?.invoke(text) // ≡ JS `if (s && Droid) copyToClipboard(s)`
    }

    // ---- find API (T6) ≡ aterm-find.js: bar เรียก open/next/prev/close — JS recompute ต่อ keystroke เดียวกัน ----

    private fun findLineAt(absRow: Int): String? {
        val e = engine ?: return null
        val lines = e.buf.lines
        if (absRow < 0 || absRow >= lines.size) return null // ≡ getLine(r) null (eviction ระหว่าง scan)
        return BufferText.lineText(lines[absRow], 0, e.cols)
    }

    private fun findLineCount(): Int = engine?.buf?.lines?.size ?: 0

    /** ≡ show(): selection ที่ match (cell mapping) + reveal-center เมื่อนอก viewport + report */
    private fun findShow(m: Finder.Match?) {
        if (m == null) {
            selection.clear()
            postFind()
            invalidate()
            return
        }
        val e = engine ?: return
        val lines = e.buf.lines
        val line = if (m.row in lines.indices) lines[m.row] else null
        val span = line?.let { BufferText.cellSpan(it, m.col, m.len) }
        if (span == null) {
            selection.clear() // match หายไปพร้อม eviction — JS clearSelection เช่นกัน
        } else {
            selection.setRange(span.first, m.row, span.last, m.row) // ≡ term.select(col,row,len) — tint path เดียว T2
        }
        scroll.revealCenter(m.row, e.rows, e.buf.baseY) // ≡ scrollLines(m.row-(top+rows/2)) เฉพาะนอก viewport
        postFind()
        invalidate()
    }

    /** ≡ report() → Droid.findStatus({q,i,n}) / '' — HUD status strip เดียวกับ webview path */
    private fun postFind() {
        val q = finder.query
        if (q.isEmpty()) onFindStatus?.invoke(null, 0, 0)
        else {
            val c = finder.count()
            if (c == "0/0") onFindStatus?.invoke(q, 0, 0)
            else onFindStatus?.invoke(q, finder.idx + 1, finder.matches.size)
        }
    }

    /** bar input → recompute(true) ≡ JS input listener (query = input.value) */
    fun findQuery(q: String) {
        finder.setQuery(q)
        val m = finder.recompute(::findLineAt, findLineCount(), resetIdx = true, viewportTop = scroll.top)
        if (finder.matches.isEmpty()) selection.clear()
        findShow(m)
    }

    fun findNext() = findShow(finder.next(::findLineAt, findLineCount(), scroll.top))
    fun findPrev() = findShow(finder.prev(::findLineAt, findLineCount(), scroll.top))

    /** ≡ close(): matches ไม่ต้องล้าง (JS ไม่ล้าง) แต่ report '' + clear selection — reset() ปลอดภัยกว่ากับ re-open */
    fun closeFind() {
        finder.reset()
        selection.clear()
        invalidate()
        onFindStatus?.invoke(null, 0, 0)
    }

    // ---- markMode (T7) ≡ aterm-keys.js L80–165: state = MarkStateMachine (pure), view = paint/reveal/report ----

    /** ≡ Droid.onMarkMode — HUD "⌖ MARK MODE" ฝั่ง host + markOn set สำหรับ routeKey divert (D6) */
    var onMarkMode: ((Boolean) -> Unit)? = null

    /** ≡ toggleMark (chord markMode / palette) */
    fun toggleMark() {
        if (mark.active) { markExit(true); return }
        val e = engine ?: return // ≡ JS `if (mark || !term) return`
        mark.enter(e.buf.baseY + e.buf.y, e.buf.x, e.cols) // ≡ posAbs (col clamp ใน enter)
        onMarkMode?.invoke(true)
        markPaint()
    }

    /** ≡ markKey (ทางที่ 2 — ExtraKeysBar bytes bypass chord, D6); true = consume (ห้ามส่ง PTY) */
    fun markKey(data: String): Boolean = when (val k = mark.consume(data)) {
        MarkKey.Off -> false // mode ปิด = caller ส่ง byte ตามปกติ (JS: Droid.onInput)
        MarkKey.Passthrough -> { markExit(true); false } // unmatched = exit(clear) แล้วส่งจริง ≡ JS
        MarkKey.Exit -> { markExit(true); true }
        MarkKey.CopyExit -> { doCopy(); markExit(true); true } // ≡ markEnterCopy: copy ก่อน clear
        is MarkKey.Move -> { markMove(k.name); true }
    }

    private fun markExit(clearSel: Boolean) {
        if (!mark.active) return // ≡ JS guard
        mark.exit()
        if (clearSel) selection.clear()
        onMarkMode?.invoke(false)
        invalidate()
    }

    private fun markMove(name: String) {
        val e = engine ?: return
        if (mark.move(name, e.cols, e.buf.lines.size - 1, e.rows)) markPaint() // last ≡ buffer.active.length-1
    }

    /** ≡ paintMark: silent select (กัน copy storm) + reveal-center เมื่อ cur นอก viewport */
    private fun markPaint() {
        val e = engine ?: return
        val s = mark.selectionSpan(e.cols)
        selection.setRange(s[0], s[1], s[2], s[3])
        selection.silent = true // ≡ window.__atermSilentSelect
        scroll.revealCenter(mark.curRow, e.rows, e.buf.baseY) // ≡ scrollLines(cur-(top+rows/2)) เฉพาะนอก viewport
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        runCatching { // vibrator อาจไม่มี/ถูก restrict — ไม่ใช่ failure ของ gesture
            val v = context.getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 31) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        }
    }

    /**
     * T3: ทุก gesture ผ่าน TouchKernel (pure ≡ JS) — view glue: IME/focus, timer HOLD, fling (level 0),
     * dispatch GestureAction → scroll / mouse bytes (MouseGate+MouseEmitter) / selection
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                // round 15-HW (Honor V6): เลิก showSoftInput ตรงนี้ — DOWN ของทุก gesture (รวม scroll
                // 1/2 นิ้ว) เคยเด้ง IME ก่อนรู้ว่า gesture คืออะไร; host (MainActivity.GestureRoot + TapGate)
                // เป็นผู้ตัดสิน tap→IME จุดเดียว — view เป็น pure gesture/IME-target (onCheckIsTextEditor คงไว้)
                performClick()
                stopFling()
                flinger.onTouchEvent(event) // fling ต้องเห็น DOWN เพื่อ track sequence
                val e = engine
                kernel.geom = KernelGeom(cellW, cellH, e?.cols ?: gridCols, e?.rows ?: gridRows)
                // ≡ JS `mouseOn = mouseMode() !== "none"` — อ่านครั้งเดียวต่อ gesture
                kernel.onDown(event.x, event.y, e != null && MouseGate.active(e.modes), selection.active)
                selHandler.removeCallbacks(longPressRunnable)
                selHandler.postDelayed(longPressRunnable, HOLD_MS) // kernel.onHold() เช็กการล้มเอง
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // round 15-HW: นิ้วที่สอง — kernel กิน pointer 0 อย่างเดียว (≡ JS ที่อ่าน touches[0])
                // แค่กันไม่ตก super (super จะสร้าง event ต่อเนื่องจาก gesture ผสม) และห้าม re-arm HOLD
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                flinger.onTouchEvent(event)
                kernel.onMove(event.x, event.y).forEach { dispatchGesture(it) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                selHandler.removeCallbacks(longPressRunnable)
                flinger.onTouchEvent(event)
                kernel.onUp().forEach { dispatchGesture(it) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { // ≡ JS touchcancel: ทิ้ง timer, keep selection, ปล่อย press
                selHandler.removeCallbacks(longPressRunnable)
                stopFling()
                kernel.onCancel().forEach { dispatchGesture(it) }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** IME อ่าน EditorInfo ใหม่อีกครั้ง (focus ไม่หลุด คีย์บอร์ดไม่กะพริบ) — ดู [imeStrict] */
    private fun refreshIme() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        runCatching { imm.restartInput(this) }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // T5: เดิมใส่ VISIBLE_PASSWORD (กัน autocorrect) = password mode — Gboard *ไม่ compose เลย*
        // (วัดจริง: tap 'a' → commit 61 ทันที, ไม่มี preedit) → ถอดเหลือ TEXT|NO_SUGGESTIONS
        // ≡ xterm webview (textarea autocorrect=off + ยังมีการรวม cluster); บทเรียน จด FINDINGS
        outAttrs.inputType = if (imeStrict) {
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (!preeditEnabled) { // path เดิม byte-for-byte (emergency rollback) = ไม่มี preedit ให้วัด
                    if (imeStrict) onImeAutocomplete?.invoke(ImeAutocomplete.Kind.BURST, text.toString())
                    feedText(text.toString()); return true
                }
                // T5: t *แทน* composing region — preedit ถูก discard, feed t ครั้งเดียว (≡ G8: onData +1)
                // t = ทั้งคำ — ตัดส่วนที่ raw-flush/feeding ไปก่อนหน้าแล้วออกก่อน (rawSentText)
                val composingWasOpen = preEdit.active
                val rest = stripWrittenPrefix(text.toString())
                if (imeStrict && !composingWasOpen) {
                    // rest = ตัวอักษรที่ *เราไม่เคยส่ง* และไม่มี composition กำกับ = IME เติมให้
                    onImeAutocomplete?.invoke(ImeAutocomplete.Kind.BURST, rest)
                }
                rawSentText = ""
                preEdit.commit(rest)?.let { feedText(it) }
                invalidate(); return true
            }
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                // HW 11-05: password mode ที่ IME เคารพจะไม่มี composing region เลย — มี = ไม่เชื่อ flag
                // นับครั้งเดียวต่อ region (ไม่ใช่ต่อ update ที่คำยาวขึ้น) = preEdit ยังไม่ active;
                // path rollback (preeditEnabled=false) ไม่มี preedit ให้อ้าง → วัดด้วย BURST อย่างเดียว
                if (imeStrict && preeditEnabled && !preEdit.active) {
                    onImeAutocomplete?.invoke(ImeAutocomplete.Kind.COMPOSITION, text.toString())
                }
                // round 21: keys-bar modifier กำลัง active = shortcut, ไม่ใช่ composition — Gboard send
                // letters ผ่าน setComposingText (วัดจริง ATermMods) ถ้าเก็บเข้า preedit = key แรก "หาย"
                // (flush ทีหลัง = latch หมดอายุ) → treat-as-final: feed ทันทีผ่าน funnel (+transform)
                if (preeditEnabled && preemptComposition()) {
                    preEdit.clear()
                    val t = text.toString()
                    if (t.isEmpty()) rawSentText = ""
                    else {
                        // Gboard ยังส่งทั้งคำ — ส่งเฉพาะส่วนใหม่ใต้ transform, ส่วนเดิมถูกเขียนไปแล้ว (rawSentText)
                        // ส่งทีละ code point ≡ คีย์จริงกดทีละใต้ modifier (latch = ตัวแรก, hold = ทุกตัว);
                        // ถ้าส่งทั้งก้อน = multi-char → fail-soft ถอด modifier ทั้งกลุ่ม (วัดจริง: Gboard batch ค้าง)
                        val rest = stripWrittenPrefix(t)
                        rawSentText = t
                        var i = 0
                        while (i < rest.length) {
                            val cp = rest.codePointAt(i)
                            feedText(String(Character.toChars(cp)))
                            i += Character.charCount(cp)
                        }
                    }
                    invalidate(); return true
                }
                if (!preeditEnabled) { if (text.isNotEmpty()) feedText(text.toString()); return true }
                // T5: เก็บไว้ underline ที่ cursor — ไม่ feed (≡ xterm hold; path เดิม = intermediate bytes รั่ว)
                // เก็บเฉพาะหางที่ *ยังไม่* ถูก raw-write (finish/commit จะ feed จาก preedit — ห้ามซ้ำส่วนที่ส่งแล้ว)
                if (rawSentText.isNotEmpty() && text.startsWith(rawSentText)) {
                    preEdit.setComposing(text.toString().substring(rawSentText.length))
                } else {
                    rawSentText = ""
                    preEdit.setComposing(text.toString())
                }
                invalidate(); return true
            }
            override fun finishComposingText(): Boolean {
                if (preeditEnabled) { // composition จบแบบไม่มี commitText มาแทน — feed สิ่งที่รวมไว้ 1 ครั้ง (เฉพาะหาง, ส่วน raw-write ไปแล้วไม่อยู่ใน preedit)
                    preEdit.finish()?.let { feedText(it) }
                    rawSentText = ""
                    invalidate()
                }
                return super.finishComposingText()
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // T5: backspace ขณะ composing = ยุบ preedit ก่อน; ว่างแล้วค่อย DEL (path เดิม)
                if (preeditEnabled && beforeLength > 0 && preEdit.shrinkBefore(beforeLength)) {
                    invalidate(); return true
                }
                feedBytes(byteArrayOf(0x7f)); return true // ผ่าน funnel = ได้ reveal-on-input ด้วย (≡ key)
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                // วัดจริง (T5 trace): Gboard ส่ง keyevent ทั้ง DOWN และ UP — dispatchKey ห้ามป้อนซ้ำตอน UP
                // (bug เดิม: soft backspace = 0x7f สองครั้ง; UP = consume อย่างเดียว)
                if (event.action == KeyEvent.ACTION_UP) return true
                return dispatchKey(event) || super.sendKeyEvent(event)
            }
        }
    }

    /**
     * round 15-HW: host สลับตอน gesture เริ่ม/จบ (MainActivity.observeGesture) — false ชั่วคราวขณะ
     * scroll = ViewRootImpl ส่ง viewClicked โดยไม่มี input target → IME auto-reopen ไม่เด้ง
     * ≡ term.blur() ฝั่ง webview; default true = พฤติกรรมเดิมทุก path
     */
    @Volatile
    var textEditorEnabled = true

    override fun onCheckIsTextEditor(): Boolean = textEditorEnabled

    /**
     * ≡ aterm-keys onKey ลำดับ **mark (T7) > chord > feed** — T5 guard ≡ JS isComposing:
     * preedit active = ไม่ dispatch chord (aterm-keys รอ compositionend ก่อน — กัน ctrl+shift ค้างกลาง composing);
     * chord match แล้ว execFail = fall-through feed ปกติ ≡ JS `execTerminal default → return true`
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // T7 ≡ aterm-keys onKey: guard isComposing (preedit) ก่อน — mark ไม่เห็น key กลาง composition;
        // ใน mark mode ทุก key belongs to it ก่อน chord (unmatched = exit แล้ว feed ปกติ, ข้าม chord ≡ JS return true)
        if (mark.active && !preEdit.active) {
            val mk = NativeKeymap.canonicalName(keyCode, event.isShiftPressed)
            when (mk) {
                "enter" -> { doCopy(); markExit(true); return true } // ≡ markEnterCopy
                "escape" -> { markExit(true); return true }
                "up", "down", "left", "right", "pageup", "pagedown", "home", "end" -> { markMove(mk); return true }
                else -> markExit(true) // unmatched: exit(clear) แล้วปล่อย key = dispatchKey (ไม่เข้า chord)
            }
            return dispatchKey(event)
        }
        if (!preEdit.active) {
            val name = NativeKeymap.canonicalName(keyCode, event.isShiftPressed)
            val entry = NativeKeymap.match(keymap, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, name)
            if (entry != null && execTerminalChord(entry)) return true
        }
        return dispatchKey(event)
    }

    /** true = consumed. view ทำ TERMINAL ที่ state อยู่กับ view ≡ execTerminal ฝั่ง term
     *  (scroll…, selectAll, copy, sendInput) — APP/UNSUPPORTED → host ≡ Droid.onAction;
     *  TERMINAL ที่ view ยังไม่ implement (find T6 / markMode T7 / clearBuffer — host มี branch แล้ว) → host เช่นกัน */
    fun execTerminalChord(entry: ResolvedEntry): Boolean {
        val name = Command.nameOf(entry.command) ?: return false
        if (entry.target != ActionTarget.TERMINAL ||
            name in setOf("find", "findMatch", "markMode", "clearBuffer")
        ) {
            onAppAction?.invoke(entry.command)
            return true
        }
        when (name) {
            "scrollUp", "scrollDown", "scrollUpPage", "scrollDownPage", "scrollToTop", "scrollToBottom" ->
                return scrollOp(name)
            "selectAll" -> {
                val e = engine ?: return true
                selection.setRange(0, 0, e.cols - 1, e.buf.lines.size - 1) // ≡ term.selectAll() ทั้ง buffer
                if (copyOnSelect) doCopy(false) // ≡ selectionchange watcher
                invalidate(); return true
            }
            "copy" -> { doCopy(entry.command.strArg("singleLine") == "true"); return true } // JS doCopy คืน true เสมอ
            "sendInput" -> { // JS: replace /\u001b/gi → ESC แล้ว input เข้า term
                val input = entry.command.strArg("input")
                if (input != null) feedText(Regex("\\\\u001b", RegexOption.IGNORE_CASE).replace(input, "\u001b"))
                return true
            }
            else -> return false // TERMINAL novel ใน catalog (อนาคต) = fall-through ≡ JS default:false
        }
    }

    /** map key → VT bytes; arrow/DECCKM ตาม engine.modes.appCursorKeys (vim/alt-screen) */
    private fun dispatchKey(event: KeyEvent): Boolean {
        // T5: physical key ขณะ preedit active — DEL ยุบ preedit (ไม่ feed), ESC = discard แล้วปล่อยให้
        // table ข้างล่าง feed ESC 1 ครั้ง (≡ xterm: ทิ้ง composition, ESC ยังถึง app)
        if (preeditEnabled && preEdit.active) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> { preEdit.shrinkBefore(1); invalidate(); return true }
                KeyEvent.KEYCODE_ESCAPE -> { preEdit.clear(); rawSentText = ""; invalidate() }
                else -> Unit
            }
        }
        val e = engine
        val appKeys = e?.modes?.appCursorKeys == true
        val bytes: ByteArray? = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> if (appKeys) "\u001bOA" else "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> if (appKeys) "\u001bOB" else "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (appKeys) "\u001bOC" else "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> if (appKeys) "\u001bOD" else "\u001b[D"
            else -> {
                val ch = event.unicodeChar
                val ctrl = event.isCtrlPressed // javap: KeyEvent.isCtrlPressed() (API19+)
                val nm = NativeKeymap.canonicalName(event.keyCode, event.isShiftPressed)
                when {
                    ctrl && ch in 'a'.code..'z'.code -> (ch - 'a'.code + 1).toChar().toString()
                    ctrl && ch in 'A'.code..'Z'.code -> (ch - 'A'.code + 1).toChar().toString()
                    // T5: physical layout ที่ unicodeChar==0 ยังได้ control byte ผ่าน keyCode table
                    ctrl && !event.isAltPressed && nm.length == 1 && nm[0] in 'a'..'z' ->
                        (nm[0] - 'a' + 1).toChar().toString()
                    else -> null
                }
            }
        }?.toByteArray(Charsets.UTF_8)
        if (bytes != null) { feedBytes(bytes); return true } // funnel เดียวกับ feedText (telemetry เห็นครบ)
        if (event.unicodeChar != 0 && event.isPrintingKey) {
            feedText(event.unicodeChar.toChar().toString()); return true
        }
        // T5 named-key table: บทเรียน space ตายที่ unicodeChar==0 — physical layout บางตัวไม่ give
        // unicode ให้ fall back keyCode (≡ NativeKeymap grammar) — round 12 T5: gate = "path 783 ยังไม่ได้ feed"
        // ไม่ใช่ `uc==0`! (SPACE วัดจริงจาก keyevent: uc=32 แต่ isPrintingKey=**false** → ตกร่องทั้งสองทาง
        // ถ้าลืม — guard `uc==0` ที่เขียนรอบนี้ฆ่า space ด้วยตัวเอง, จับได้ด้วย byte gate ไม่ใช่ JVM test)
        // layout จริงต้องชนะ table: key ที่ uc≠0 AND isPrinting (shift+2 ไทย → 'ฯ') ถูก 783 feed ไปแล้วถึงตรงนี้
        // — EQUALS→minus ยังเป็นชื่อ chord ไม่ใช่ text
        if (!event.isCtrlPressed && !event.isAltPressed &&
            !(event.unicodeChar != 0 && event.isPrintingKey)
        ) {
            val nm = NativeKeymap.canonicalName(event.keyCode, event.isShiftPressed)
            val plain = when {
                nm == "space" -> " "
                nm.length == 1 && nm[0].isLetterOrDigit() -> if (event.isShiftPressed) nm.uppercase() else nm
                else -> NativeKeymap.plainPunctuation(event.keyCode, event.isShiftPressed)
            }
            if (plain != null) { feedText(plain); return true }
        }
        return false
    }

    // ---- focus/blink lifecycle ----
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        blinkHandler.removeCallbacks(blinkTick)
        if (hasWindowFocus) { blinkOn = true; blinkHandler.postDelayed(blinkTick, 530); invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); blinkHandler.postDelayed(blinkTick, 530) }
    override fun onDetachedFromWindow() {
        blinkHandler.removeCallbacks(blinkTick)
        selHandler.removeCallbacks(longPressRunnable)
        stopFling()
        super.onDetachedFromWindow()
    }

    companion object {
        // fallback palette (host ต้อง configure ด้วย Scheme จริง — ค่าพวกนี้ = campbell basics)
        val DEFAULT16 = intArrayOf(
            0xFF0C0C0C.toInt(), 0xFFC50F1F.toInt(), 0xFF13A10E.toInt(), 0xFFC19C00.toInt(),
            0xFF0037DA.toInt(), 0xFF881798.toInt(), 0xFF3A96DD.toInt(), 0xFFCCCCCC.toInt(),
            0xFF767676.toInt(), 0xFFE74856.toInt(), 0xFF16C60C.toInt(), 0xFFF9F1A5.toInt(),
            0xFF3B78FF.toInt(), 0xFFB4009E.toInt(), 0xFF61D6D6.toInt(), 0xFFF2F2F2.toInt(),
        )
        const val DEFAULT_FG = 0xFFCCCCCC.toInt()
        const val DEFAULT_BG = 0xFF0C0C0C.toInt()
        private const val HOLD_MS = 500L // ≡ JS HOLD (terminal.html touchSelect)
    }
}

/**
 * Round 20 T04: overline band thickness = the font's own underline metric
 * (what `isUnderlineText` draws with) when that metric is in a visible range;
 * outside the range the clamp wins over strict parity (see floor/cap below).
 * Bounds are specified in OverlineBandTest.
 */
internal fun overlineThicknessPx(underlineThickness: Float, cellH: Float): Float {
    // 2px floor (user-approved 2026-11-03): the bundled font's own underline
    // metric is 0.94px at our sizes — hairline-thin metrics (broken or real)
    // must not render a band that vanishes, which was the original complaint
    val floor = minOf(2f, cellH / 4f)
    if (!underlineThickness.isFinite() || underlineThickness <= 0f) return floor
    // cap: even a lying finite metric must not bury the glyphs (quarter cell)
    return underlineThickness.coerceIn(floor, cellH / 4f)
}
