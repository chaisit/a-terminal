package dev.aterminal.render.input

/**
 * MouseReport / MouseEmitter — mirror xterm.js 5.5.0 CoreMouseService (round 11 T3)
 *
 * Source ที่อ่านจริงจาก bundle `app/src/main/assets/xterm/xterm.js` (offset ~265.8k–268.2k +
 * producer ที่ ~36.1k + DECSET ที่ ~181.3k — ห้ามจำ, opening bundle ใหม่ทุกครั้งที่แตะไฟล์นี้):
 *  - action: 0=release, 1=press, 32=motion; wheel = button 4 + action 0(up)/1(down)
 *  - button code `c(e,sgr)`: (ctrl16|shift4|alt8) then button==4 → |64|action;
 *    else |button&3, button&4 → |64, button&8 → |128; motion → |32; release&&!sgr → |3
 *  - DEFAULT encoding (X10 byte): ESC [ M + (code+32)(col1+32)(row1+32); byte ใด >255 = report หายทั้งอัน
 *    (JS: col++/row++ *ก่อน* encoder → coord = 1-based แล้ว +32; 0-based ของเรา = +33)
 *  - SGR encoding: ESC[<code;col1;row1 + ('m' ถ้า action==release && button!=4 ไม่งั้น 'M')
 *  - 1005/1015: xterm.js 5.5.0 ไม่รองรับเอง ("DECSET 1015 not supported #2507") → MouseGate ไม่ map = ≡
 *  - protocol restrict: X10 = press(button<4) เท่านั้น + strip modifiers; VT200 = reject motion;
 *    DRAG = reject motion ของ button3; ANY = ผ่านหมด; NONE = closed (mode 9 ไม่มี flag ใน :engine → unreachable, gap จด FINDINGS)
 *  - triggerMouseEvent guards: col/row นอก viewport → null; wheel+motion → null; button3 press/release → null;
 *    motion ที่ (col,row,button,action) เท่า last event พอดี → dedup null; encoder คืน "" → ไม่ update last event
 */

enum class MouseProtocol { NONE, X10, VT200, DRAG, ANY }
enum class MouseEncoding { DEFAULT, SGR }

object MouseActions {
    const val RELEASE = 0
    const val PRESS = 1
    const val MOTION = 32
    const val BUTTON_NONE = 3
    const val BUTTON_WHEEL = 4
}

object MouseReport {

    /** ≡ c(e, sgr) ใน bundle */
    fun buttonCode(button: Int, action: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, sgr: Boolean): Int {
        var i = (if (ctrl) 16 else 0) or (if (shift) 4 else 0) or (if (alt) 8 else 0)
        if (button == MouseActions.BUTTON_WHEEL) {
            i = i or 64 or action // action 0=up 64, 1=down 65
        } else {
            i = i or (button and 3)
            if (button and 4 != 0) i = i or 64
            if (button and 8 != 0) i = i or 128
            i = when {
                action == MouseActions.MOTION -> i or 32
                action != MouseActions.RELEASE || sgr -> i
                else -> i or 3 // X10 release = code|3
            }
        }
        return i
    }

    /** ≡ DEFAULT encoder — col/row 0-based จาก view (JS col++ ก่อน encoder = +33 จาก 0-based); null เมื่อ byte >255 */
    fun encodeDefault(button: Int, action: Int, col: Int, row: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): ByteArray? {
        val b = buttonCode(button, action, ctrl, shift, alt, false) + 32
        val x = col + 33
        val y = row + 33
        if (b > 255 || x > 255 || y > 255) return null
        return byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), // ESC [ M — เรียง 0x1b,[,M ตาม JS template
            b.toByte(), x.toByte(), y.toByte())
    }

    /** ≡ SGR encoder — text bytes (ASCII ล้วน) */
    fun encodeSgr(button: Int, action: Int, col: Int, row: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): ByteArray {
        val code = buttonCode(button, action, ctrl, shift, alt, true)
        val t = if (action == MouseActions.RELEASE && button != MouseActions.BUTTON_WHEEL) 'm' else 'M'
        return "\u001b[<$code;${col + 1};${row + 1}$t".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * MouseEmitter — stateful ≡ triggerMouseEvent (guards + restrict + motion dedup)
 * view สร้างตัวเดียว; เรียก report ทุก gesture action; คืน bytes ให้ onFeed หรือ null (= xterm ไม่ส่ง)
 */
class MouseEmitter {
    // last event (button, action, col, row) — JS เก็บทุก event, dedup เช็คเฉพาะ motion
    private var lastButton = -1
    private var lastAction = -1
    private var lastCol = -1
    private var lastRow = -1

    fun reset() { lastButton = -1 }

    fun report(
        proto: MouseProtocol, enc: MouseEncoding, cols: Int, rows: Int,
        button: Int, action: Int, col: Int, row: Int,
        ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false,
    ): ByteArray? {
        if (proto == MouseProtocol.NONE) return null
        if (col < 0 || col >= cols || row < 0 || row >= rows) return null // JS: boundary check ก่อน col++/row++
        if (button == MouseActions.BUTTON_WHEEL && action == MouseActions.MOTION) return null
        if (button == MouseActions.BUTTON_NONE && action != MouseActions.MOTION) return null
        if (button != MouseActions.BUTTON_WHEEL && (action == 2 || action == 3)) return null // legacy actions xterm ไม่รับ
        if (action == MouseActions.MOTION && button == lastButton && action == lastAction &&
            col == lastCol && row == lastRow
        ) return null // ≡ _equalEvents (motion เท่านิ่ง — JS เทียบทั้ง event)
        var c = ctrl; var s = shift; var a = alt
        when (proto) {
            MouseProtocol.X10 -> {
                if (!(button != MouseActions.BUTTON_WHEEL && action == MouseActions.PRESS)) return null
                c = false; s = false; a = false // X10 strip modifiers (restrict)
            }
            MouseProtocol.VT200 -> if (action == MouseActions.MOTION) return null
            MouseProtocol.DRAG -> if (action == MouseActions.MOTION && button == MouseActions.BUTTON_NONE) return null
            MouseProtocol.ANY -> Unit
            MouseProtocol.NONE -> return null
        }
        val bytes = when (enc) {
            MouseEncoding.SGR -> MouseReport.encodeSgr(button, action, col, row, c, s, a)
            MouseEncoding.DEFAULT -> MouseReport.encodeDefault(button, action, col, row, c, s, a)
                ?: return null // "" in JS → ไม่ส่งและไม่ update lastEvent
        }
        lastButton = button; lastAction = action; lastCol = col; lastRow = row
        return bytes
    }
}
