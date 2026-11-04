package dev.aterminal.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.compose.ui.graphics.toArgb
import dev.aterminal.core.KeyButton
import dev.aterminal.core.KeyKind
import dev.aterminal.core.KeysLayout

/**
 * แถวปุ่มพิเศษใต้ terminal (แนว Termux extra-keys row) — คีย์ที่ soft keyboard ทั่วไปไม่มี
 *  - CTRL/⇧ = modifier จริง (round 21, `KeyModifierState`): กดค้าง = มีผลระหว่างนิ้วแตะ, tap = latch
 *    มีผลกับ input *ทุกทาง* (bar + soft keyboard ผ่าน funnel ที่ host แปลง) — ไม่ใช่ lock ใน_bar อีกแล้ว
 *    label ขึ้น ✓ เมื่ latch อยู่; ระบบ pressed-state วาดตอนกดค้างเอง
 *  - ESC/TAB/ลูกศร = escape sequence ตรง (encode กับ modifier ไม่ได้ = fail-soft ส่งตรง)
 *  - ตัวอักษร A/E/L/R/U/Z ส่ง raw — host แปลงผ่าน mods เดียวกับที่ Gboard ใช้
 *  - **Pinned zone** (round 22, ADR 0001 Manual Reveal): ⌨ ⏎ อยู่นอก fit-row ขวาสุด — มองเห็น/กดได้เสมอ
 *    ⌨ = toggle คีย์บอร์ด (ทาง reveal เดียวของแอป), ⏎ = Enter ผ่าน funnel
 *
 * **round 23 fit-to-width (ADR 0002):** ไม่มี HorizontalScrollView อีกแล้ว — back-swipe ที่เคยถูก scroll
 * กลืนกลายเป็น gesture ของระบบได้; preset จาก Settings.keysPreset (`layout`) วาดพอดีเนื้อ bar เสมอ:
 *  - กว้างแต่ละปุ่มจาก `KeysBarFit.widths` (clamp ไม่ให้เกิน share จริงของ row = ห้าม clip ห้าม scroll)
 *  - space ไม่พอ → glyphOnly: label ยาวยุบเป็น glyph เดียว (SHIFT→⇧ ฯลฯ) ไม่ใช่ใส่ scroll
 *  - key รองย้ายไป **Popup Variant**: กดค้าง (`setOnLongClickListener` มาตรฐาน) → `KeyPopup` เหนือปุ่ม
 *  - **fold (task 3b, ADR 0002 Consequences):** ปุ่มเยอะเกินบนจอแคบ (17 ปุ่ม/~275dp = sliver) →
 *    หางแถวถูกยุบไป popup ของปุ่ม **⋯** (tap เดียวเปิด, ไม่ใช่ long-press — ปุ่มที่ยุบไม่มีตัวให้กดค้าง);
 *    entry แสดง label เต็มเสมอ (popup กว้างพอ) even เมื่อปุ่มที่เห็นเป็น glyphOnly; rotate กลับกว้าง =
 *    `relayoutBody` คำนวณใหม่แล้ว unfold อัตโนมัติ (แถวถูกประกอบจาก `bodyItems` เสมอ = ไม่มี ⋯ ค้าง);
 *    host ที่ถูก fold และมี popup variant → variant เป็น entry ของมันเองต่อท้าย host (`flattenFolded`,
 *    fix round Important-1 — ไม่งั้น popup-only keys เช่น p_pipe/p_tilde unreachable เพราะใน popup
 *    ไม่มี long-press ให้เรียก variant; ปุ่มที่ *เห็น* ยังใช้ long-press แบบเดิม)
 *  - **Gesture Gutter**: paddingStart 24dp ว่าง ๆ ซ้ายสุด ให้ back-swipe ผ่าน (task 6 จะเพิ่ม bottom
 *    padding ผ่าน setPadding(currentStart,0,0,bottom) — ห้าม hard-code start=0 ที่อื่น)
 *  - สูง 48dp visual (≡ hit-slop target)
 *
 * ทุก byte literal เขียนเป็น escape (`\u001B`) — ห้ามใส่ raw 0x1B ใน source (มองไม่เห็น, editor/format ลบเงียบได้)
 * constants อยู่ companion = ทดสอบ byte ตรงบน JVM ได้ไม่ต้องมี Context (`ExtraKeysBarTest`)
 */
class ExtraKeysBar(
    context: Context,
    private val mods: KeyModifierState,
    layout: KeysLayout,
    private val onKey: (String) -> Unit,
    private val onKeyboardToggle: () -> Unit,
) : LinearLayout(context) {

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val modifierButtons = ArrayList<Pair<Button, Pair<ModifierKey, String>>>()
    // ปุ่ม key ของ body + label ต้นฉบับ (pattern เดียวกับ modifierButtons) — glyphOnly flip ต้อง re-render
    // *ทุก* ปุ่ม ไม่ใช่แค่ modifiers (bug review round 23: ค้าง "ESC"/"TAB" เต็มในปุ่มแคบ)
    private val keyButtons = ArrayList<Pair<Button, String>>()
    // ทุกปุ่ม body ตามลำดับ preset (รวมที่ถูก fold) — แถวจริงถูก *ประกอบใหม่* จาก list นี้เสมอใน applyFold
    // จึงไม่มี ⋯ ค้างและไม่มีปุ่มหายเมื่อ fold state เปลี่ยน (rotate wide↔narrow)
    private val bodyItems = ArrayList<BodyItem>()
    private val overflowBtn: Button // ⋯ สังเคราะห์ — slot สุดท้ายของแถวเมื่อ fold (task 3b)
    private val pinnedButtons = ArrayList<Button>() // ⌨ ⏎ — ทา chrome แบบ imperative (ห้าม statelist — IME highlight rebase-order-sensitive)
    private val separator: View // เส้นคั่น pinned zone — task 9 fix: สี derive จาก chrome (เดิม SEPARATOR_COLOR)
    private var folded: List<BodyItem> = emptyList()
    private var foldedCount = 0
    private var kbHl: ImeButtonHighlight? = null
    private var barLayout = layout
    private var glyphOnly = false
    // task 9: สีจาก scheme ของ pane active (null = ยังไม่ถูกทา → styling default ของ platform)
    private var chrome: ChromeColors? = null
    private var imeUp = false // ⌨ highlight ต้องไม่โดน chrome ทาทับตอน IME เปิด

    init {
        orientation = HORIZONTAL
        // root (GestureRoot) เพิ่ม bar ด้วย params ว่าง → bar คุม layout เอง: เต็มความกว้าง, สูง 48dp (ADR 0002)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
        // Gesture Gutter — เนื้อซ้ายสุดว่างให้ back-swipe ของระบบ (ห้ามกลืนด้วยปุ่มแรก)
        setPadding(dp(GUTTER_DP), 0, 0, 0)

        // body = แถวเดียว weight 1 (ไม่มี scroll); ปุ่มใส่ px width จาก relayoutBody() หลัง measure
        addView(row, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        // ---- Pinned zone (อยู่นอก fit-row ห้ามเลื่อนกลืน): ⌨ ⏎ — ⏎ ชิดขวาสุดเพราะถี่สุด (Q1=ก) ----
        separator = View(context).apply { setBackgroundColor(SEPARATOR_COLOR) } // ค่าเดิม = fallback ก่อน chrome ทา (minor 5)
        addView(separator, LayoutParams(dp(1), LayoutParams.MATCH_PARENT))
        pinnedBtn("⌨") { onKeyboardToggle() }.also { kbHl = ImeButtonHighlight(it) }
        pinnedBtn("⏎") { onKey(ENTER) }

        // ⋯ = หน้าต่างของปุ่มที่ถูก fold — tap เดียวเปิด popup (ปุ่มที่ยุบไปแล้วไม่มีตัวให้กดค้าง
        // จึงไม่ใช่ long-press variant); entry = label เต็ม + action เทียบเท่า tap ปุ่มจริง
        // และ host ที่มี popup variant → variant เป็น entry ของมันเองต่อท้าย host (flattenFolded,
        // fix round Important-1: p_pipe/p_tilde popup-only จะ unreachable ถ้า popup ยิงแค่ base bytes)
        overflowBtn = Button(context).apply {
            text = OVERFLOW_GLYPH
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            minimumWidth = 0 // ขนาด ≡ ปุ่ม body ธรรมดา (share จาก KeysBarFit) ไม่ใช่ pinned 56dp
            setOnClickListener {
                KeyPopup.show(this, flattenFolded(folded).map { KeyPopup.Entry(it.label, it.fire) })
            }
        }

        buildRow()

        // fire() อาจถูกเรียกจาก JavaBridge thread (Droid.onInput → transform) — setText จากนอก main
        // = CalledFromWrongThreadException ซึ่ง WebView กลืนเงียบ → คีย์ที่ consumed แล้วหายไปเลย
        // (root cause "CTRL พังบ้างไม่พังบ้าง" — วัดจริง 2026-11-04) → post เข้า main เสมอ
        mods.onChange = { post { refreshModifiers() } }
        refreshModifiers()
    }

    /** ⌨ highlight ตามสถานะ IME จริง — caller = KeyboardWatcher (single source of truth, ห้าม latch ในปุ่ม) */
    fun setImeUp(up: Boolean) {
        imeUp = up
        kbHl?.set(up)
    }

    /**
     * task 9: ทา chrome จาก scheme ของ pane active — bar bg = scheme bg (ต่อเนื่องกับ pane),
     * ปุ่ม body/⋯ = state-list (default pillIdleBg, pressed ยกเป็น pillActiveBg) + textActive fg,
     * ปุ่ม pinned = solid pillIdleBg (imperative — ดู applyChromeToButtons), เส้นคั่น = blend ตาม chrome;
     * preset reload (setLayout → buildRow) ทา chrome ล่าสุดซ้ำเอง, null = ไม่เคยทา → default คงเดิม
     */
    fun setChrome(c: ChromeColors) {
        chrome = c
        setBackgroundColor(c.barBg.toArgb())
        applyChromeToButtons()
    }

    private fun applyChromeToButtons() {
        val c = chrome ?: return
        val fg = c.textActive.toArgb()
        // body (key+modifier, รวมที่ถูก fold — view ยังอยู่) + ⋯ = state-list: pressed ยกเป็น pillActiveBg
        // (feedback คีย์ชั่ววูบต้องเป็น solid ทันที — ห้าม Ripple/fade, review ruling round task 9 fix)
        (keyButtons.map { it.first } + modifierButtons.map { it.first } + overflowBtn).forEach {
            it.background = buttonBg(c)
            it.setTextColor(fg)
        }
        // pinned zone = imperative paint อย่างเดียว (⌨ IME highlight เป็น rebase-order-sensitive — ห้าม statelist)
        pinnedButtons.forEach {
            it.setBackgroundColor(c.pillIdleBg.toArgb())
            it.setTextColor(fg)
        }
        // minor 5: เส้นคั่นตาม chrome แทน constant — blend barBg→textActive 32.3% ≡ 0x4A4A4A เมื่อ Campbell
        separator.setBackgroundColor(
            ChromePalette.blend(c.barBg.toArgb(), c.textActive.toArgb(), SEPARATOR_T)
        )
        kbHl?.rebase() // default ตอน IME ปิด = สี chrome ปัจจุบัน ไม่ใช่ drawable ของ platform
        if (imeUp) kbHl?.set(true) // ⌨ กำลัง highlight อยู่ — ทา chrome แล้วต้องคง PINNED_ACTIVE_COLOR
    }

    /**
     * bg ของปุ่ม body/⋯ หนึ่งตัว — StateListDrawable (pressed = solid lift → pillActiveBg,
     * default = pillIdleBg); instance ต่อปุ่มเท่านั้น (Drawable เดียวห้ามแชร์ข้าม View — bounds ขวางกัน)
     */
    private fun buttonBg(c: ChromeColors) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(c.pillActiveBg.toArgb()))
        addState(intArrayOf(), ColorDrawable(c.pillIdleBg.toArgb()))
    }

    /** preset เปลี่ยน (SettingsWatcher reload ใน MainActivity) = สร้าง body ใหม่; pinned zone ไม่แตะ */
    fun setLayout(newLayout: KeysLayout) {
        barLayout = newLayout
        buildRow()
        refreshModifiers()
    }

    private fun buildRow() {
        // popup ที่ anchor อยู่กับ view ใกล้ถูกลบ (preset reload ตอน popup เปิด) = ต้องปิดก่อน
        // ไม่งั้น PopupWindow ลอยค้าง anchored กับ view ที่หลุดจาก tree แล้ว (review M2)
        KeyPopup.dismiss()
        modifierButtons.clear()
        keyButtons.clear()
        bodyItems.clear()
        for (b in barLayout.buttons) {
            when (b.kind) {
                KeyKind.modifier -> bodyItems += modifierBtn(b.label ?: glyphOf(b.id), modifierKeyOf(b.id))
                else -> bodyItems += keyBtn(b)
            }
        }
        // preset reload = reset fold state (setLayout); คำนวณใหม่หลัง measure ผ่าน relayoutBody
        applyFold(0)
        applyChromeToButtons() // ปุ่มใหม่จาก buildRow ต้องได้สี chrome ปัจจุบัน (task 9)
        row.post { relayoutBody() } // width จริงรู้หลัง measure —ครั้งแรกก็ทางนี้ (init ยังไม่มี layout)
    }

    /**
     * ประกอบแถวจริงจาก `bodyItems` = head (size-overflow) + ⋯ เมื่อ fold (task 3b)
     * rebuild เสมอ (ไม่ diff) → rotate wide↔narrow ไม่มี ⋯ ค้าง/ปุ่มหาย; popup ที่ anchor กับ
     * view ที่อาจถูกรื้อออก = ปิดก่อน (guard เดียวกับ buildRow, review M2)
     * และทุก rebuild ต้อง teardown modifier press ที่ค้าง (fix wave B2 — ดู cancelStrandedHolds)
     */
    private fun applyFold(overflow: Int) {
        KeyPopup.dismiss()
        releaseStrandedPresses()
        row.removeAllViews()
        // parked (l): การซอย head/tail ผ่าน foldSplit (pure) = ลำดับที่ test ตารางไว้คือลำดับที่แถวจริงใช้
        val (head, tail) = foldSplit(bodyItems, overflow)
        folded = tail
        for (item in head) {
            row.addView(item.view, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT))
        }
        if (overflow > 0) row.addView(overflowBtn, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT))
        foldedCount = overflow
    }

    /**
     * fix wave B2: นิ้ว DOWN ค้างบน SHIFT/CTRL แล้ว slot นั้นถูก fold หายไป = UP/CANCEL ไม่เคยมาถึง
     * (view หลุดจากแถวกลาง stream) → mods ค้าง held จน press ครั้งถัดไป — ทุก rebuild ต้อง teardown ก่อน
     * cancel เฉพาะ held (press กลางทาง) เท่านั้น; latch จากการ tap = สถานะที่ user ตั้งใจ = คงไว้
     */
    private fun releaseStrandedPresses() {
        cancelStrandedHolds(mods)
        for ((b, _) in modifierButtons) b.isPressed = false // view จะถูก attach ใหม่ — ห้ามติด pressed ค้าง
    }

    /**
     * วัดจริงของ row → ส่วนกว้างต่อปุ่ม (px) — clamp ด้วย share จริงของ row:
     * preset ที่ปุ่มเยอะเกินจอแคบ (glyphOnly) ต้องไม่ clip/ไม่ scroll — label ไม่ได้พึ่ง ellipsize อัตโนมัติ
     * (Button ไม่ auto-ellipsize ในแถวนี้); การยุบ label เกิดจริงจากการ re-render ทุกปุ่มใน body
     * ตอน glyphOnly flip ทั้งสองทิศ (refreshKeyLabels + refreshModifiers) = ใบสั่ง ADR 0002
     * (bug 0.4.x: scroll=gestures ถูกกลืน — จึงเป็นที่มาของการยุบ label แทน)
     * ปุ่มเยอะเกิน touch slots (count > k) → applyFold ยุบหางไป popup ⋯ (ADR 0002 Consequences)
     */
    private fun relayoutBody() {
        val total = bodyItems.size
        if (total == 0 || row.width == 0) return
        val bodyDp = (row.width / resources.displayMetrics.density).toInt()
        val fit = KeysBarFit.widths(total, bodyDp)
        if (fit.overflow != foldedCount) applyFold(fit.overflow) // fold/unfold ตามเนื้อจริง (rotate)
        val n = row.childCount // == fit.widths.size (head + ⋯ เมื่อ fold)
        val cap = row.width / n
        for (i in 0 until n) {
            val lp = row.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            lp.width = minOf(dp(fit.widths[i]), cap)
        }
        if (fit.glyphOnly != glyphOnly) {
            glyphOnly = fit.glyphOnly
            // flip ทั้งสองทิศ: narrow→wide ต้องได้ label เต็มกลับ, wide→narrow ต้องยุบเป็น glyph
            // — ทุกปุ่ม body ไม่ใช่แค่ modifiers (bug review round 23: keyBtn ตั้ง label ครั้งเดียวตอน build)
            // (ปุ่มที่ fold อยู่ = view ยังไม่ attach — setText ไร้ผลค้าง, unfold มาแล้ว label ถูกต้อง)
            refreshKeyLabels()
            refreshModifiers() // label ค้าง (SHIFT✓ ฯลฯ) ต้องยุบ/ขยายตาม glyphOnly ด้วย
        }
        row.requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { relayoutBody() } // rotate/fold = เนื้อ bar เปลี่ยน → คำนวณส่วนกว้างใหม่
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** ปุ่ม pinned = กว้างกว่า keys ธรรมดา และอยู่นอก fit-row — shape รวมอยู่ที่ PinnedKeys (สอง bar ใช้ร่วมกัน) */
    private fun pinnedBtn(label: String, action: () -> Unit): Button {
        val b = PinnedKeys.button(context, label, action)
        addView(b, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        pinnedButtons += b
        return b
    }

    /**
     * tap = ยิง bytes ของ id; มี popup = กดค้างเรียก `KeyPopup` (entry เดียวกัน + variant)
     * variant ถูกเก็บเป็น `EntrySpec` เดียวกันทั้ง path กดค้าง (ปุ่มที่เห็น) และ flatten เข้า ⋯
     * (ปุ่มที่ถูก fold) — label/bytes ไม่แยกทางเพี้ยนกัน (fix round Important-1)
     */
    private fun keyBtn(b: KeyButton): BodyItem {
        val label = b.label ?: glyphOf(b.id)
        val variant = b.popup?.let { pop -> EntrySpec(pop.label ?: glyphOf(pop.id)) { send(pop) } }
        val button = Button(context).apply {
            text = display(label)
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            minimumWidth = 0 // share จริง (px) ชนะ minWidth เดิม — ไม่งั้นปุ่ม overflow เนื้อ = clip
            setOnClickListener { send(b) }
            variant?.let { v ->
                setOnLongClickListener {
                    KeyPopup.show(this, listOf(
                        KeyPopup.Entry(label) { send(b) },
                        KeyPopup.Entry(v.label, v.fire),
                    ))
                    true
                }
            }
        }
        keyButtons += button to label
        return BodyItem(button, label, variant) { send(b) }
    }

    /** app-kind (paste) = ส่ง sentinel ผ่าน onKey ให้ host routeKey แปลง (Task 4); key-kind = bytes ตรง */
    private fun send(b: KeyButton) {
        if (b.kind == KeyKind.app) onKey(APP_ACTION_PREFIX + b.id)
        else bytesOf(b.id)?.let { onKey(it) }
    }

    /**
     * modifier = touch-driven (DOWN=press, UP=release/click, CANCEL=ยกเลิก) — ToggleButton ใช้ไม่ได้:
     * มัน fire ตอน release อย่างเดียว จึงไม่มี concept "ระหว่างค้าง" (= bug เดิม user รายงาน 2026-11-04)
     * consume ทุก event (return true) — CANCEL = state ไม่ค้าง (ไม่มี parent scroll ให้กันอีก: ไม่มี scroll แล้ว)
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun modifierBtn(label: String, key: ModifierKey): BodyItem {
        val b = Button(context).apply {
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            minimumWidth = 0 // ส่วนกว้าง comes from KeysBarFit เหมือนปุ่มอื่น
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { mods.press(key); v.isPressed = true; true }
                    MotionEvent.ACTION_UP -> {
                        mods.release(key); v.isPressed = false; v.performClick(); true
                    }
                    MotionEvent.ACTION_CANCEL -> { mods.cancel(key); v.isPressed = false; true }
                    else -> true
                }
            }
        }
        modifierButtons += b to (key to label)
        // fire = tap เทียบเท่า (press+release ทันที = latch/ปลด ตาม state machine เดียวกัน) —
        // ใช้เมื่อถูก fold เข้า popup ⋯; ห้ามทำ press-and-hold ใน popup (controller ruling: popup
        // ไม่ใช่ hold zone — entry must fire the same handler the visible *click* path uses)
        return BodyItem(b, label) { mods.press(key); mods.release(key) }
    }

    private fun refreshModifiers() {
        for ((b, kl) in modifierButtons) {
            val (key, label) = kl
            val base = display(label)
            b.text = if (mods.isLatched(key)) "$base✓" else base
        }
    }

    /** re-render label ทุกปุ่ม key ของ body ตาม glyphOnly ปัจจุบัน (เรียกเฉพาะตอน flip) */
    private fun refreshKeyLabels() {
        for ((b, label) in keyButtons) b.text = display(label)
    }

    /** glyphOnly (ADR 0002) = ยุบ label ยาวเป็น glyph เดียวเพื่อให้พอดีปุ่ม minDp */
    private fun display(label: String): String = labelFor(label, glyphOnly)

    companion object {
        const val ESC = "\u001B"
        const val TAB = "\u0009"
        const val ENTER = "\r" // terminal Enter = CR (≡ xterm/Termux ส่งจริง ไม่ใช่ \n แบบ form) — ปุ่ม pinned ⏎
        const val CTRL_C = "\u0003"
        const val UP = "\u001B[A"
        const val DOWN = "\u001B[B"
        const val RIGHT = "\u001B[C"
        const val LEFT = "\u001B[D"

        /** สี highlight ของ ⌨ ขณะ IME เปิด (constant — task 9 ruling: จนกว่าจะมี scheme-aware accent) */
        internal const val PINNED_ACTIVE_COLOR = 0xFF1E4C7E.toInt()

        /** เส้นคั่น pinned zone — ค่า default ก่อน chrome + ของ MiniKeysBar (ยังไม่ theme-aware); bar ที่ themed แล้ว derive ใหม่ผ่าน SEPARATOR_T */
        internal const val SEPARATOR_COLOR = 0xFF4A4A4A.toInt()

        /** minor 5 fix: อัตรา blend barBg→textActive สำหรับเส้นคั่น (0.323 เป๊ะ ๆ = 0x4A4A4A เมื่อ Campbell ≡ ค่าเดิม) */
        internal const val SEPARATOR_T = 0.323f

        /** Ctrl + ตัวอักษร = control code (A→0x01 .. Z→0x1A) — เดียวกันกับที่ terminal คีย์บอร์ดจริงส่ง */
        internal fun ctrlOf(c: Char): String = String(byteArrayOf((c - 'A' + 1).toByte()))

        /** Gesture Gutter ซ้าย (ADR 0002) — back-swipe zone ของระบบ ห้ามมีปุ่มทับ */
        internal const val GUTTER_DP = 24

        /** glyph ของปุ่ม fold (task 3b, ADR 0002) — tap เปิด popup ของปุ่มที่ถูกยุบไป */
        internal const val OVERFLOW_GLYPH = "⋯"

        /**
         * sentinel prefix ของ KeyKind.app — bar ไม่รู้ behavior (paste ฯลฯ) ส่ง "app:paste" raw ให้
         * host `routeKey` แปลง (Task 4 wires behavior; ใน Task 3 แค่ forward แล้วต้องไม่ crash)
         */
        internal const val APP_ACTION_PREFIX = "app:"

        /** id → bytes ผ่าน funnel (id เดียวกับ KeyIds — persistence contract ของ Task 1) */
        internal fun bytesOf(id: String): String? = when (id) {
            "esc" -> ESC
            "tab" -> TAB
            "ctrl_c" -> CTRL_C
            "up" -> UP
            "down" -> DOWN
            "right" -> RIGHT
            "left" -> LEFT
            "raw_a" -> "a"
            "raw_e" -> "e"
            "raw_l" -> "l"
            "raw_r" -> "r"
            "raw_u" -> "u"
            "raw_z" -> "z"
            "p_pipe" -> "|"
            "p_dash" -> "-"
            "p_slash" -> "/"
            "p_tilde" -> "~"
            else -> null
        }

        /** glyph map = label set เดิมของ bar (round 22) — fallback เมื่อ KeyButton.label = null */
        internal fun glyphOf(id: String): String = when (id) {
            "esc" -> "ESC"
            "tab" -> "TAB"
            "ctrl_c" -> "⌃C"
            "shift" -> "SHIFT"
            "ctrl" -> "CTRL"
            "up" -> "↑"
            "down" -> "↓"
            "right" -> "→"
            "left" -> "←"
            "raw_a" -> "A"
            "raw_e" -> "E"
            "raw_l" -> "L"
            "raw_r" -> "R"
            "raw_u" -> "U"
            "raw_z" -> "Z"
            "p_pipe" -> "|"
            "p_dash" -> "-"
            "p_slash" -> "/"
            "p_tilde" -> "~"
            "paste" -> "⚡"
            else -> id
        }

        /**
         * label บริสุทธิ์ของปุ่มหนึ่งตามสถานะ glyphOnly — ทั้งสองทิศของ flip (wide→narrow ยุบ,
         * narrow→wide คืน label เต็ม) — stateless จึง tests JVM ได้ (ExtraKeysBarTest)
         */
        internal fun labelFor(label: String, glyphOnly: Boolean): String =
            if (glyphOnly) shortOf(label) else label

        /** glyph-only form (ใช้เฉพาะตอน glyphOnly — ADR 0002 "shrink glyph labels") */
        internal fun shortOf(label: String): String = when (label) {
            "ESC" -> "⎋"
            "TAB" -> "⇥"
            "SHIFT" -> "⇧"
            "CTRL" -> "⌃"
            else -> label
        }

        internal fun modifierKeyOf(id: String): ModifierKey =
            if (id == "ctrl") ModifierKey.CTRL else ModifierKey.SHIFT

        /**
         * flatten หางแถวที่ fold สำหรับ popup ⋯ (fix round, Important-1): host หนึ่งตัว = 1 entry;
         * host ที่มี popup variant = entry ของ variant ต่อท้าย host ทันที (label เต็มของ variant,
         * fire = bytes ของ variant เองผ่าน send path เดียวกัน) — ถ้าไม่ flatten, ปุ่ม popup-only
         * ของ round 23 dedupe (p_pipe ใน p_dash, p_tilde ใน p_slash) จะ unreachable บนจอแคบ
         * เพราะ entry ใน popup ยิงแค่ base bytes และใน popup ไม่มี long-press ให้เรียก variant
         * single level ตาม schema — KeyButton.popup เป็น plain KeyButton (variant ไม่มี variant)
         * pure (View ไม่เกี่ยว) → ตารางลำดับทดสอบบน JVM ได้ (ExtraKeysBarTest)
         */
        internal fun flattenFolded(hosts: List<FoldHost>): List<EntrySpec> =
            hosts.flatMap { h -> listOf(EntrySpec(h.label, h.fire)) + listOfNotNull(h.variant) }

        /**
         * fix wave B2 (pure part — tests in `ExtraKeysBarTest`): ยก modifier ที่กำลัง held
         * (ACTION_DOWN ค้างตอน slot ถูก fold/rebuild = ไม่เคยได้ UP/CANCEL) — latch ไม่แตะ
         */
        internal fun cancelStrandedHolds(mods: KeyModifierState) {
            for (key in ModifierKey.values()) if (mods.isHeld(key)) mods.cancel(key)
        }

        /**
         * parked (l) pin: fold ซอยตามลำดับเสมอ — head = `count-overflow` ตัว *หัว* แถว (ยังแสดง),
         * tail = overflow ตัว *หาง* แถว (ยุบเข้า ⋯, ลำดับคงเดิม) — ห้ามพับหัว/เลือกตัวสลับลำดับ;
         * overflow ≥ size (degenerate k==1) = head ว่าง, พับทั้งแถว. pure → ตารางใน `ExtraKeysBarTest`
         */
        internal fun <T> foldSplit(items: List<T>, overflow: Int): Pair<List<T>, List<T>> {
            val keep = (items.size - overflow).coerceAtLeast(0)
            return items.take(keep) to items.drop(keep)
        }
    }
}

/**
 * entry หนึ่งของ popup ⋯ (fix round Important-1) — pure data (no View) = รัน/table test บน JVM ได้;
 * `fire` = send path เดียวกับ tap/long-press จริงของปุ่มต้นทาง (key bytes / app sentinel / modifier latch)
 */
internal class EntrySpec(val label: String, val fire: () -> Unit)

/**
 * host ที่ fold เข้า popup ⋯ ในมุมมอง pure — label เต็ม + fire ของ host เอง + `variant` =
 * popup variant ของ host เป็น entry ของตัวเอง (null = ไม่มี) — single level: `variant` เป็น
 * EntrySpec leaf (type ห้าม variant-of-variant, ตรงตาม schema KeyButton.popup)
 * BodyItem extends ตัวนี้ → `flattenFolded(folded)` ใช้ได้ตรง ๆ และ test สร้าง host ได้โดยไม่ต้องมี View
 */
internal open class FoldHost(val label: String, val fire: () -> Unit, val variant: EntrySpec? = null)

/**
 * ปุ่ม body หนึ่งตัวใน ExtraKeysBar (task 3b fold) — view + label บริสุทธิ์ (popup แสดง label เต็ม
 * เสมอแม้ glyphOnly) + `fire` = action เทียบเท่า tap บนปุ่มจริง (key → send(bytes/app sentinel),
 * modifier → press+release ≡ tap latch) + `variant` (keyBtn ที่มี popup เท่านั้น) = long-press variant
 * ของมัน → popup ⋯ flatten เป็น entry เพิ่มของตัวเอง — `applyFold` ประกอบแถวจริงจาก list นี้เสมอ
 */
internal class BodyItem(
    val view: View,
    label: String,
    variant: EntrySpec? = null,
    fire: () -> Unit,
) : FoldHost(label, fire, variant)

/**
 * shape เดียวของปุ่ม pinned zone (⌨ ⏎) — สอง bar (ExtraKeysBar/MiniKeysBar) ต้องหน้าตา/ขนาดเหมือนกันเป๊ะ
 * (review round 22: กัน Duplicated Code แยกเพี้ยน) — กว้าง 56dp เต็มสูง bar (bar สูง 48dp ตั้งแต่ ADR 0002)
 */
internal object PinnedKeys {
    fun button(context: Context, label: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTypeface(Typeface.MONOSPACE)
            isAllCaps = false
            minWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 56f, context.resources.displayMetrics
            ).toInt()
            setOnClickListener { action() }
        }
}

/**
 * ตัว highlight ปุ่ม ⌨ (pinned zone) — เก็บ background เดิมไว้คืนเมื่อ IME ปิด
 * ใช้โดยสอง bar (ExtraKeysBar/MiniKeysBar) — สถานะจริงมาจาก KeyboardWatcher เท่านั้น
 */
internal class ImeButtonHighlight(private val button: Button) {
    private var defaultBg: Drawable? = button.background
    fun set(up: Boolean) {
        if (up) button.setBackgroundColor(ExtraKeysBar.PINNED_ACTIVE_COLOR)
        else button.background = defaultBg
    }
    /**
     * task 9: host ทา chrome ใหม่ = default ตอน IME ปิดเปลี่ยน — capture อีกครั้ง (MiniKeysBar ไม่เรียก = behavior เดิม)
     * call only after host has repainted the button — capturing before the repaint would store
     * PINNED_ACTIVE_COLOR (or the stale platform drawable) as the IME-off default
     */
    fun rebase() { defaultBg = button.background }
}
