package dev.aterminal.app

/** ผลตัดสินปุ่ม Back (ADR 0002) — Esc = ส่ง ESC เข้า shell, System = ให้ระบบจัดการตามเดิม */
enum class EscOrSystem { Esc, System }

/**
 * Back-as-Escape (ADR 0002, round 23): เมื่อ settings เปิด, pane เปิดอยู่ และ IME ปิดอยู่
 * ปุ่ม Back = ส่ง ESC เข้า shell (≡ Termux back-key=escape) แทนการปิดแอป
 *
 * ลำดับความสำคัญ: IME ที่เปิดอยู่ต้องถูกปิดก่อนเสมอ (System) — ผู้ใช้กด Back เพื่อเลิกพิมพ์
 * ไม่ใช่เพื่อ escape; pane ไม่เปิด = ไม่มีปลายทางสำหรับ ESC (System = ปิดแอปตามปกติ)
 * Dialog ไม่อยู่ในสมการ — consume Back ก่อน activity อยู่แล้ว
 *
 * pure JVM (no Android) — ตัดสินจาก state ที่ inject เข้ามาเท่านั้น จึง test ด้วย JVM ได้
 */
object BackPolicy {

    fun decide(backAsEscape: Boolean, imeUp: Boolean, hasActivePane: Boolean): EscOrSystem =
        if (backAsEscape && !imeUp && hasActivePane) EscOrSystem.Esc else EscOrSystem.System
}
