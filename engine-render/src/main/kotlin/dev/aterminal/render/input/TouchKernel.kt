package dev.aterminal.render.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * TouchKernel — state machine เดียวของ touch ทั้งหมด (round 11 T3)
 * ≡ terminal.html touchSelect (L89–220) ทุกกติกา — pure JVM, view เป็น glue ล้วน:
 *  - HOLD=500ms/SLOP=12px; mouse gate อ่านครั้งเดียวตอน DOWN (≡ mouseOn ต่อ gesture)
 *  - long-press = escape hatch ทุก level (selecting บัง mouse/scroll — ≡ branch `if (selecting)` ก่อน)
 *  - mouse on + moved: dominant axis ตัดครั้งเดียวที่ SLOP — |dx|>=|dy| → drag (mousedown@**start**,
 *    mousemove เมื่อ cell เปลี่ยน) ไม่งั้น wheel (1 notch/cellH; นิ้วลากลง = wheel-up 64 ≡ JS wheelAt(-sgn*ch))
 *  - tap (mouse on, !moved) = press+release ที่ cell เริ่ม; UP ขณะ pressSent → release ที่ last pos
 *  - CANCEL: release pressSent, keep selection (≡ touchcancel)
 *  - mouse off: drag = scroll (finger ขึ้น = top ลด = history ≡ T1), fling = view (level 0 เท่านั้น)
 *
 * coordinate = px ใน view, row = viewport row (0-based, clamp แล้ว) — host แปล abs = scroll.top + row
 * (mouse report ใช้ viewport row; selection ใช้ abs — ≡ JS cellAt ใช้ vy ตอนเรียก)
 */

data class KernelGeom(val cellW: Float, val cellH: Float, val cols: Int, val rows: Int)

sealed class GestureAction {
    data class ScrollBy(val rows: Int) : GestureAction() // sign ตรงเข้า ScrollState.scrollBy (ลบ = history)
    data class MousePress(val col: Int, val row: Int) : GestureAction()
    data class MouseMotion(val col: Int, val row: Int) : GestureAction()
    data class MouseRelease(val col: Int, val row: Int) : GestureAction()
    data class Wheel(val col: Int, val row: Int, val up: Boolean) : GestureAction() // up = button code 64
    data class SelStart(val col: Int, val row: Int) : GestureAction() // hold ลั่น — view word-select + copy
    data class SelExtend(val col: Int, val row: Int) : GestureAction()
    data class SelFinish(val dragged: Boolean) : GestureAction() // UP ขณะ selecting (view: dragged&&!copyOnSelect → copy)
    object SelClear : GestureAction() // tap = clear (เมื่อ hadSel)
}

class TouchKernel(var geom: KernelGeom, val slop: Float = 12f) {

    var selecting = false; private set
    var mouseOn = false; private set

    /** long-press ยังไม่ถูกล้ม — view จัด timer เอง แล้วเรียก onHold (เช็คซ้ำที่นี่ กัน race) */
    var armed = false; private set

    private var startX = 0f; private var startY = 0f
    private var lastX = 0f; private var lastY = 0f // ≡ JS lastX/lastY (update เฉพาะ move ที่ mouseOn && moved)
    private var pointerY = 0f // ≡ JS prevY (update เฉพาะ branch ที่ processed — scroll/wheel)
    private var moved = false
    private var dragged = false
    private var hadSel = false
    private var dominantDecided = false
    private var dominantDrag = false
    private var pressSent = false
    private var wheelAcc = 0f
    private var scrollAcc = 0f
    private var lastCellCol = -1; private var lastCellRow = -1

    /** ≡ JS cellAt: px → col/row clamp ใน viewport */
    fun cellAt(x: Float, y: Float): Pair<Int, Int> =
        (x / geom.cellW).toInt().coerceIn(0, geom.cols - 1) to
            (y / geom.cellH).toInt().coerceIn(0, geom.rows - 1)

    fun onDown(x: Float, y: Float, mouseOn: Boolean, hadSel: Boolean): List<GestureAction> {
        startX = x; startY = y; lastX = x; lastY = y; pointerY = y
        this.mouseOn = mouseOn; this.hadSel = hadSel
        moved = false; dragged = false; selecting = false; armed = true
        dominantDecided = false; pressSent = false
        wheelAcc = 0f; scrollAcc = 0f
        lastCellCol = -1; lastCellRow = -1
        return emptyList()
    }

    /** view เรียกเมื่อ timer HOLD ลั่น — null ถ้าถูกล้มไปก่อน (moved/cancelled/up) */
    fun onHold(): GestureAction? {
        if (!armed || moved) return null
        armed = false; selecting = true
        val (c, r) = cellAt(startX, startY)
        return GestureAction.SelStart(c, r)
    }

    fun onMove(x: Float, y: Float): List<GestureAction> {
        if (selecting) { // ≡ JS branch แรก: บัง scroll/mouse ทั้งหมดยกเว้น extend
            val (c, r) = cellAt(x, y)
            dragged = true
            return listOf(GestureAction.SelExtend(c, r))
        }
        if (!moved && hypot((x - startX).toDouble(), (y - startY).toDouble()) > slop) {
            moved = true; armed = false // ≡ clearTimeout(timer)
        }
        if (!mouseOn) {
            if (!moved) return emptyList()
            // drag scroll ≡ T1: finger ขึ้น (dy<0) → top ลด (history); สะสม px กัน partial หาย
            val dy = y - pointerY; pointerY = y
            scrollAcc += dy
            val k = (scrollAcc / geom.cellH).roundToInt()
            if (k == 0) return emptyList()
            scrollAcc -= k * geom.cellH
            return listOf(GestureAction.ScrollBy(k))
        }
        if (!moved) return emptyList()
        lastX = x; lastY = y
        if (!dominantDecided) {
            dominantDecided = true
            dominantDrag = abs(x - startX) >= abs(y - startY) // ≡ |dx|>=|dy| = drag
        }
        val out = ArrayList<GestureAction>(2)
        if (!dominantDrag) {
            // wheel: นิ้วลากลง (acc>0) = wheel-up 64 ≡ JS -sgn*ch → deltaY<0 → action 0
            wheelAcc += y - pointerY; pointerY = y
            while (abs(wheelAcc) >= geom.cellH) {
                val up = wheelAcc > 0
                val (c, r) = cellAt(x, y)
                out.add(GestureAction.Wheel(c, r, up))
                wheelAcc -= (if (up) 1 else -1) * geom.cellH
            }
        } else {
            if (!pressSent) { // JS dispatch mousedown ที่ *start* (ไม่ใช่ current cell)
                val (c, r) = cellAt(startX, startY)
                out.add(GestureAction.MousePress(c, r))
                pressSent = true
                lastCellCol = c; lastCellRow = r
            }
            val (c, r) = cellAt(x, y)
            if (c != lastCellCol || r != lastCellRow) { // ≡ dispatch เมื่อ cell เปลี่ยน (xterm dedup ครอบอีกชั้น)
                out.add(GestureAction.MouseMotion(c, r))
                lastCellCol = c; lastCellRow = r
            }
        }
        return out
    }

    fun onUp(): List<GestureAction> {
        armed = false
        if (selecting) {
            selecting = false
            return listOf(GestureAction.SelFinish(dragged)) // ≡ JS touchend-selecting (swallow กัน tap-clear)
        }
        val out = ArrayList<GestureAction>(3)
        if (mouseOn) {
            if (pressSent) {
                val (c, r) = cellAt(lastX, lastY)
                out.add(GestureAction.MouseRelease(c, r))
                pressSent = false
            } else if (!moved) {
                val (c, r) = cellAt(startX, startY) // tap = press+release cell เดิม → SGR click (htop/vim)
                out.add(GestureAction.MousePress(c, r))
                out.add(GestureAction.MouseRelease(c, r))
            }
            if (hadSel) out.add(GestureAction.SelClear)
            return out
        }
        if (hadSel) out.add(GestureAction.SelClear)
        return out
    }

    fun onCancel(): List<GestureAction> {
        armed = false
        return if (pressSent) { // keep selection — แค่ปล่อย press ให้แอป (≡ touchcancel)
            pressSent = false
            val (c, r) = cellAt(lastX, lastY)
            listOf(GestureAction.MouseRelease(c, r))
        } else emptyList()
    }

    /** hard reset กลาง gesture (engine swap ฯลฯ) — ไม่มี action ออก ต่างจาก onCancel */
    fun abort() {
        armed = false; selecting = false; pressSent = false; mouseOn = false
    }
}
