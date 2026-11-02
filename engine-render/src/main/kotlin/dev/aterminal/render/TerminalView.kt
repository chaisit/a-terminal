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
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.aterminal.core.ActionTarget
import dev.aterminal.core.Command
import dev.aterminal.core.NativeKeymap
import dev.aterminal.core.ResolvedEntry
import dev.aterminal.core.ResolvedTable
import dev.aterminal.core.strArg
import dev.aterminal.engine.TerminalEngine
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
            if (engine?.modes?.cursorVisible == true) { blinkOn = !blinkOn; invalidate() }
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
        basePaint.typeface = resolveTypeface(Typeface.NORMAL)
        basePaint.textSize = fontSizePx
        val fm = basePaint.fontMetrics
        cellW = maxOf(1f, ceil(basePaint.measureText("M")))
        val lineH = fm.descent - fm.ascent
        // round 15-HW: สูตร cellH คงเดิม by design — canvas ไม่ clip glyph ต่อ cell และ bundled FiraCode
        // (hhea 1.23em) × 1.05 ≈ webview `lineHeight:1.2` พอดี; ถ้า Honor retest ยังเห็น native clip ค่อยคุยกัน
        cellH = maxOf(1f, ceil(maxOf(lineH * 1.05f, fontSizePx * 1.15f)))
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
                    paintLine.color = run.style.fg
                    canvas.drawRect(x0, top + 1f, x0 + w, top + 2f, paintLine)
                }
            }
        }
        // cursor (block outline) — ง่ายและเห็นชัดบน screenshot; refine visual ทีหลัง
        // T1: cursor อยู่ใน live area (abs = baseY+y) — scroll ขึ้นดู history = ไม่เห็น cursor (≡ xterm)
        val curRow = b.baseY + b.y - vpTop
        if (blinkOn && b.x < cols && e.modes.cursorVisible && hasFocus() && curRow in 0 until rows) {
            paintCursor.color = palette.cursor
            val cx = b.x * cellW; val cy = curRow * cellH
            canvas.drawRect(cx, cy, cx + cellW, cy + cellH, paintCursorOutline)
            canvas.drawRect(cx, cy + cellH - 2f, cx + cellW, cy + cellH, paintCursor)
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
            val w = width.toFloat()
            canvas.drawRect(w - 4f, thumbY, w, thumbY + thumbH, paintScroll)
        }
    }

    private val paintBg = Paint()
    private val paintLine = Paint()
    private val paintSelection = Paint()
    private val paintCursor = Paint()
    private val paintScroll = Paint()
    private val paintCursorOutline = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }

    private fun drawRun(canvas: Canvas, run: Run, x: Float, baseline: Float) {
        val p = paintFor(run.style)
        p.color = if (run.style.dimAlpha) dim(run.style.fg) else run.style.fg
        if (isAscii(run.text)) { // ASCII fast path — เดิมทุกตัวอักษรทุก API (mono advance = cellW)
            canvas.drawText(run.text, x, baseline, p)
            return
        }
        if (Build.VERSION.SDK_INT < 31) {
            // T5: TextShaper ไม่มี API<31 — วาดทีละ grapheme cluster ณ cell origin ของมันเอง
            // (invariant RunMerger: 1 cell = 1 cluster ใน run — dummy.skip, wide = run เดี่ยว)
            var cx = x
            for (c in Clusters.split(run.text)) {
                canvas.drawText(c, cx, baseline, p)
                cx += cellW
            }
            return
        }
        // API31+: shape ทั้ง run (Thai cluster ข้าม codepoint ได้ตรง font position) แล้ววาด glyph ชุด
        TextShaper.shapeText(run.text, 0, run.text.length, TextDirectionHeuristics.LTR, p) { _, _, glyphs, gp ->
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
        val cols = maxOf(1, (w / cellW).toInt())
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
        finder.reset() // T6: matches/idx อ้าง abs row ของ engine เดิม
        markExit(true) // T7: mark ค้าง = HUD/markOn ต้องถูกปลด (onMarkMode(false)) + anchor/cur ผูก engine เดิม
        invalidate()
    }

    fun feedText(s: String) { feedBytes(s.toByteArray(Charsets.UTF_8)) }
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

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // T5: เดิมใส่ VISIBLE_PASSWORD (กัน autocorrect) = password mode — Gboard *ไม่ compose เลย*
        // (วัดจริง: tap 'a' → commit 61 ทันที, ไม่มี preedit) → ถอดเหลือ TEXT|NO_SUGGESTIONS
        // ≡ xterm webview (textarea autocorrect=off + ยังมีการรวม cluster); บทเรียน จด FINDINGS
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (!preeditEnabled) { feedText(text.toString()); return true } // path เดิม byte-for-byte
                // T5: t *แทน* composing region — preedit ถูก discard, feed t ครั้งเดียว (≡ G8: onData +1)
                preEdit.commit(text.toString())?.let { feedText(it) }
                invalidate(); return true
            }
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (!preeditEnabled) { if (text.isNotEmpty()) feedText(text.toString()); return true }
                // T5: เก็บไว้ underline ที่ cursor — ไม่ feed (≡ xterm hold; path เดิม = intermediate bytes รั่ว)
                preEdit.setComposing(text.toString()); invalidate(); return true
            }
            override fun finishComposingText(): Boolean {
                if (preeditEnabled) { // composition จบแบบไม่มี commitText มาแทน — feed สิ่งที่รวมไว้ 1 ครั้ง
                    preEdit.finish()?.let { feedText(it) }
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
                KeyEvent.KEYCODE_ESCAPE -> { preEdit.clear(); invalidate() }
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
