package dev.aterminal.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * ตรรกะ "IME เปิด/ปิด" แบบ pure (JVM-test) — fallback heuristic เมื่อ insets listener ไม่ช่วย
 *  - ใต้ legacy adjustResize (app นี้, targetSdk ต่ำกว่า 35) root จะถูกย่อแทน IME inset
 *    → ค่า inset may report 0 เสมอ; heuristic จึงเป็นตัวขับจริงจนกว่า insets จะพิสูจน์ตัวเอง (เห็น ime=true สักครั้ง)
 *  - baseline = สูงสุดที่เห็นที่ความกว้างปัจจุบัน; root สั้นกว่า baseline เกิน shrinkRatio = IME เปิด
 *  - ความกว้างเปลี่ยน (rotate/multi-window) = baseline ใหม่ทันที กัน landscape ถูกรวมเป็น "keyboard เปิด"
 */
class ImeHeuristic(val shrinkRatio: Float = 0.25f) {

    private var width = 0
    private var baseline = 0
    private var up = false

    /** คืน true/false เมื่อสถานะเพิ่งเปลี่ยน, null = ไม่เปลี่ยน (caller ยิง callback เฉพาะค่าที่ไม่ null) */
    fun onLayout(w: Int, h: Int): Boolean? {
        if (w <= 0 || h <= 0) return null
        if (w != width) {                                   // rotate / resize อื่น = landscape สูงเตี้ยกว่าเสมอ
            width = w; baseline = h
            val was = up; up = false
            return if (was) false else null
        }
        if (h >= baseline) {                                // โตขึ้น = ปิด keyboard (หรือใหญ่กว่าเดิม = baseline ใหม่)
            baseline = h
            val was = up; up = false
            return if (was) false else null
        }
        if (!up && h < baseline * (1f - shrinkRatio)) { up = true; return true }
        if (up && h >= baseline * (1f - shrinkRatio)) { up = false; return false }
        return null
    }

    val imeUp: Boolean get() = up
}

/**
 * คอยดู IME ของ root (round 5 / C12) — สองกลไกในตัวเดียว:
 *  primary  = WindowInsets Type.ime() — **return insets เดิม ห้าม consume** (adjustResize ต้องหด root ต่อ
 *             ไม่งั้น fit()/JS resize chain ตาย = regression ทั้ง round 3–4)
 *  fallback = ImeHeuristic บน layout size ของ root — ใช้จนกว่า insets จะ show ime=true สักครั้ง
 *             (insets ที่ report แต่ false เสมอใต้ adjustResize = ไม่มีประโยชน์, อย่าเชื่อ)
 * device gate: วัดบน emulator ว่าจะรันตัวไหนจริง แล้ว finalize ใน test-report-5
 */
class KeyboardWatcher(private val root: View) {

    var onImeChanged: ((Boolean) -> Unit)? = null
    private val heuristic = ImeHeuristic()
    private var insetsTrusted = false

    fun start() {
        // T3 (round 13): นี่คือ insets listener ตัวเดียวของ root — เพิ่ม edge-to-edge padding เข้าที่นี่
        // ห้ามมี listener สองตัวทับ root (ViewCompat set ทับกัน = ตัวเก่าตายเงียบ)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // edge-to-edge (targetSdk 35 บน API35: fullscreen flag ถูก ignore → bars โผล่จริง):
            // root padding = systemBars∪cutout (top) และ max(systemBars.bottom, ime.bottom) (bottom)
            // ใต้ API34+fullscreen: inset ทั้งคู่ = 0 → padding 0 = พฤติกรรมเดิมทุกตัวอักษร
            // ใต้ API34+adjustResize: IME = window หด, ime() report 0 (-heuristic ขับ) → ไม่ double-shrink
            // ใต้ API35: window ไม่หด → ime() report จริง → padding = resize ที่เราคุมเอง (≡ ผลลัพธ์เดิม)
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottom = maxOf(bars.bottom, imeBottom)
            if (root.paddingTop != bars.top || root.paddingBottom != bottom ||
                root.paddingLeft != bars.left || root.paddingRight != bars.right
            ) {
                root.setPadding(bars.left, bars.top, bars.right, bottom)
            }
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (visible && !insetsTrusted) {
                insetsTrusted = true
                android.util.Log.i("ATermIme", "insets driver: saw ime()=true -> insets now trusted")   // device-gate C12
            }
            if (insetsTrusted) onImeChanged?.invoke(visible)
            insets
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (insetsTrusted) return@addOnLayoutChangeListener
            heuristic.onLayout(r - l, b - t)?.let {
                android.util.Log.i("ATermIme", "heuristic driver: ime=$it h=${b - t}")                  // device-gate C12
                onImeChanged?.invoke(it)
            }
        }
    }
}
