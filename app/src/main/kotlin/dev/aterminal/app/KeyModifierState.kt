package dev.aterminal.app

/** Modifier ปุ่มเดียวใน keys bar — แยก state ของ CTRL กับ ⇧ (round 21: modifier จริง แทน latch ใน_bar เดิม) */
enum class ModifierKey { CTRL, SHIFT }

/**
 * State machine ของปุ่ม modifier (CTRL/⇧) ใน keys bar — pure Kotlin, JVM-tested (`KeyModifierStateTest`)
 *
 * กฎ (user flow 2026-11-04: "กด CTRL ค้างแล้วกดตัวอักษรอื่น แล้วปล่อยทั้งสอง"):
 *  - press-hold  = modifier ชั่วคราว มีผลกับ input ทุกทาง *ระหว่างค้าง* (≡ physical ctrl) — ใช้แล้วไม่ consume (ปล่อยนิ้ว = จบ)
 *  - tap (press+release ไม่มี input คั่น) = latch — มีผลกับ input ถัดไปทางไหนก็ได้ (bar หรือ soft keyboard) แล้วปลดเอง
 *  - tap ซ้ำตอน latch = ปลด (ทางออกของคนที่กดผิด)
 *  - fail-soft: input ที่ encode ไม่ได้ (CSI, multi-char, paste, ไทย) ส่งตามเดิม — latch ปลดทันที (ห้ามค้างไปแก้ input ภายหลัง),
 *    held คงอยู่ (นิ้วจริงยังกด) — ห้าม drop input เด็ดขาด
 *
 * owner = MainActivity (ตัวเดียวทั้ง activity, ใช้กับ active pane) — funnel input ทุกทางเรียก [transform] ก่อนเขียน PTY:
 * routeKey (keys bar), Droid.onInput (webview IME), TerminalView.preFeedTransform (native IME)
 */
class KeyModifierState {

    /** UI redraw — ยิงทุกครั้งที่ state เปลี่ยน (bar เปลี่ยน label/สี) */
    var onChange: (() -> Unit)? = null

    /**
     * ขอบ (edge) ของ `anyActive()` — ยิงเฉพาะตอน inactive↔active (true ตอน modifier เริ่ม active, false ตอนหลุดทั้งหมด)
     * host ใช้ push ให้ webview flush composing region ของ xterm (Gboard เก็บตัวอักษรใน composition
     * เหมือน native preedit — ฟังด้านเดียวไม่พอ) — ยิงจากทุก thread (Droid.onInput = JAVASCRIPT thread)
     */
    var onActiveChange: ((Boolean) -> Unit)? = null

    private var held = booleanArrayOf(false, false)
    private var latched = booleanArrayOf(false, false)
    private var usedWhileHeld = booleanArrayOf(false, false)
    private var cancelingLatch = booleanArrayOf(false, false)
    private var lastActive = false

    private fun idx(m: ModifierKey) = m.ordinal

    @Synchronized
    fun isHeld(m: ModifierKey) = held[idx(m)]
    @Synchronized
    fun isLatched(m: ModifierKey) = latched[idx(m)]
    @Synchronized
    fun isActive(m: ModifierKey) = held[idx(m)] || latched[idx(m)]

    /** มี modifier ใด active — host ใช้ preempt composition ของ IME (shortcut ≠ text input) */
    @Synchronized
    fun anyActive(): Boolean = isActive(ModifierKey.CTRL) || isActive(ModifierKey.SHIFT)

    @Synchronized
    fun press(m: ModifierKey) {
        val i = idx(m)
        held[i] = true
        usedWhileHeld[i] = false
        cancelingLatch[i] = latched[i]
        if (latched[i]) latched[i] = false // press ขณะ latch = เตรียมปลด (tap ซ้ำ) หรือใช้ต่อ — release ตัดสิน
        fire() // sempre: UI ต้อง repaint ตอนกด (highlight held) แม้ latch ไม่เปลี่ยน
    }

    @Synchronized
    fun release(m: ModifierKey) {
        val i = idx(m)
        held[i] = false
        when {
            cancelingLatch[i] -> { cancelingLatch[i] = false } // tap ซ้ำตอน latch = ปลดแล้ว อยู่ OFF
            usedWhileHeld[i] -> Unit                           // hold-then-use = modifier จบพร้อมนิ้ว
            else -> latched[i] = true                          // ปล่อยเปล่า = tap → latch
        }
        fire()
    }

    /** ACTION_CANCEL (parent scroll intercept ฯลฯ) = ยกเลิกทุกอย่างของ modifier นี้ — กัน ✓ ค้าง */
    @Synchronized
    fun cancel(m: ModifierKey) {
        val i = idx(m)
        held[i] = false; latched[i] = false; usedWhileHeld[i] = false; cancelingLatch[i] = false
        fire()
    }

    /** สลับ pane/tab = latch ของ pane เก่าหมดความหมาย (นิ้ว hold ย้ายตามไม่ได้) */
    @Synchronized
    fun clearLatches() {
        if (latched[0] || latched[1]) { latched[0] = false; latched[1] = false; fire() }
    }

    /**
     * แปลง input ที่จะเขียน PTY — คืน byte/text ที่ต้องส่ง (ห้ามคืน null/ห้าม drop)
     * consume = เฉพาะ single ASCII char ที่ encode ได้เท่านั้น
     */
    @Synchronized
    fun transform(input: String): String {
        val ctrl = isActive(ModifierKey.CTRL)
        val shift = isActive(ModifierKey.SHIFT)
        if (!ctrl && !shift) return input
        val c = input.singleOrNull() // multi-char (paste/composition/CSI bytes) = ไม่ใช่ shortcut → fail-soft ด้านล่าง
        val code = when {
            c == null -> null
            ctrl -> ctrlCode(c)
            shift && c in 'a'..'z' -> c.uppercase()
            else -> null
        }
        if (code == null) { // encode ไม่ได้ → fail-soft: ส่งตรง, ปลด latch (held คง)
            val wasLatched = latched[0] || latched[1]
            latched[0] = false; latched[1] = false
            if (wasLatched) fire()
            return input
        }
        if (ctrl) consume(ModifierKey.CTRL)
        if (shift) consume(ModifierKey.SHIFT)
        fire()
        return code
    }

    private fun consume(m: ModifierKey) {
        val i = idx(m)
        if (latched[i]) latched[i] = false else usedWhileHeld[i] = true // held = คงไว้จนปล่อยนิ้ว
    }

    /** xterm control bytes — letters 0x01..0x1A + bracket trio (Ctrl+[ = ESC, \\ = FS, ] = GS) */
    private fun ctrlCode(c: Char): String? = when {
        c in 'a'..'z' || c in 'A'..'Z' -> String(byteArrayOf((c.lowercaseChar() - 'a' + 1).toByte()))
        c == '[' -> "\u001B"
        c == '\\' -> "\u001C"
        c == ']' -> "\u001D"
        else -> null
    }


    private fun fire() {
        onChange?.invoke()
        val active = anyActive()
        if (active != lastActive) { lastActive = active; onActiveChange?.invoke(active) }
    }
}
