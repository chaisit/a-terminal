package dev.aterminal.render.input

import dev.aterminal.engine.term.ModeState

/**
 * MouseGate — แปลง ModeState flags → protocol/encoding ที่ xterm.js ใช้จริง (round 11 T3)
 *
 * DECSET handler ที่อ่านจาก bundle (~181.3k): set 9→X10, 1000→VT200, 1002→DRAG, 1003→ANY,
 * reset 9/1000/1002/1003 → NONE ทั้งหมด; 1006→SGR, 1016→SGR_PIXELS, 1005/1015 = "not supported" (log อย่างเดียว)
 *  - engine เก็บ flag (purity-lock) ไม่ใช่ "last setter" → ใช้ precedence ANY>DRAG>VT200 ≡ ผลรวม mode ที่แอปตั้ง
 *  - **gap (จด FINDINGS):** mode 9 (X10 protocol) และ 1016 (SGR_PIXELS) ไม่มี flag ใน ModeState —
 *    native จะรายงานเป็น protocol/encoding ต่ำกว่าแทน; 1005/1015 ไม่ต้องทำ = xterm เองก็ไม่รองรับ
 */
object MouseGate {

    fun protocol(m: ModeState): MouseProtocol = when {
        m.mouseAny -> MouseProtocol.ANY
        m.mouseDrag -> MouseProtocol.DRAG
        m.mouseClick -> MouseProtocol.VT200
        else -> MouseProtocol.NONE
    }

    fun encoding(m: ModeState): MouseEncoding =
        if (m.mouseSgr) MouseEncoding.SGR else MouseEncoding.DEFAULT

    /** ≡ JS `mouseMode() !== "none"` — gesture gate (อ่านครั้งเดียวตอน touchstart) */
    fun active(m: ModeState): Boolean = protocol(m) != MouseProtocol.NONE
}
