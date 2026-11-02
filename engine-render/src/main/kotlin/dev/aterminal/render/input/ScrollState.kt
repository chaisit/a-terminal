package dev.aterminal.render.input

/**
 * ScrollState — view state ของ scrollback ฝั่ง native (≡ xterm viewportY/ydisp — round 11 T1)
 *
 *  - `top` = absolute index ของบรรทัดบนสุดที่มองเห็น (`lines[top]`), ขอบ [0..baseY]
 *      top == baseY → อยู่ล่างสุด (tail/sticky); top < baseY → user scroll ขึ้น (pinned)
 *  - sticky rule ≡ xterm: output ใหม่ datang ถ้า "ก่อนเขียนอยู่ล่างสุด" → follow baseY ใหม่;
 *    ถ้า pinned อยู่ = clamp เท่านั้น (ห้ามดึง user กลับ — บทเรียน webview round 4 เช่นกัน)
 *  - resize/reflow/swap: clamp อย่างเดียว (ตำแหน่ง absolute ของ content อาจเลื่อนภายใต้ rewrap —
 *    semantics เดียวกับ xterm, bug-compatible เช่นกัน)
 *
 * pure JVM: ไม่อ้าง engine/Android — baseY/rows ป้อนเข้าเป็น parameter ทุก call (ทดสอบตรงได้)
 */
class ScrollState {
    var top = 0
        private set

    /**
     * เรียกหลัง engine.write ทุก batch — prevBaseY = ค่าก่อนเขียน, baseY = ปัจจุบัน,
     * evicted = จำนวนบรรทัดที่ถูก evict จากหัวระหว่าง batch (delta ของ ScreenBuffer.evictedTotal — OPEN #3)
     *
     * OPEN #3 fix (≡ xterm gold — probe `xterm-sticky-probe.txt` stable:true, ydisp 20→14):
     * ที่ cap, baseY คงที่ แต่ content เลื่อน index ลง = evicted → pinned view ต้องเลื่อนตาม (content-lock)
     * ไม่งั้น output ใหม่ลาก viewport เข้า tail ทีละ 1 (flood = manual scroll ตาย)
     */
    fun onOutput(prevBaseY: Int, baseY: Int, evicted: Int = 0) {
        top = if (top >= prevBaseY) baseY
        else if (evicted > 0) (top - evicted).coerceAtLeast(0)
        else top.coerceIn(0, baseY)
    }

    /** baseY เปลี่ยนจาก resize/reflow (ทั้งโตและหด) — clamp อย่างเดียว ไม่ follow */
    fun clamp(baseY: Int) { top = top.coerceIn(0, baseY) }

    /** delta > 0 = ดูใหม่ขึ้น (ลง toward bottom), < 0 = ดูเก่าขึ้น (scrollback) */
    fun scrollBy(delta: Int, baseY: Int) { top = (top + delta).coerceIn(0, baseY) }

    fun pageUp(rows: Int, baseY: Int) = scrollBy(-rows, baseY)
    fun pageDown(rows: Int, baseY: Int) = scrollBy(rows, baseY)
    fun toTop(baseY: Int) { top = 0 }
    fun toBottom(baseY: Int) { top = baseY }

    /**
     * กติกา ≡ aterm-find.js:86–88 / aterm-keys.js reveal — center เฉพาะเมื่อเป้าหมายนอก viewport
     * คืน true = viewport เลื่อน (caller invalidate เมื่อเลื่อน)
     */
    fun revealCenter(absRow: Int, rows: Int, baseY: Int): Boolean {
        if (absRow in top until top + rows) return false
        top = (absRow - rows / 2).coerceIn(0, baseY)
        return true
    }

    fun isAtBottom(baseY: Int) = top >= baseY

    /**
     * ≡ xterm.js `scrollOnUserInput` (default true): keystroke ใด ๆ = ปล่อย content-lock กลับ tail
     * (T6 soak supplement จับได้: native คง pin ตอน user พิมพ์ → มองไม่เห็นสิ่งที่ตัวเองพิมพ์)
     * คืน true = view เลื่อน (caller invalidate)
     */
    fun revealForInput(baseY: Int): Boolean {
        if (isAtBottom(baseY)) return false
        top = baseY
        return true
    }

    /** engine ใหม่/attach = กลับ bottom (empty buffer → top 0) */
    fun attach(baseY: Int) { top = baseY }
}
