package dev.aterminal.app

/**
 * นโยบายกู้ pane เมื่อ WebView renderer ตาย (pure, ทดสอบบน JVM ได้)
 *  - จำกัดจำนวนครั้งต่อช่วงเวลา กันวนสร้าง WebView ถ้า renderer ตายทุกครั้งที่โหลด
 *  - เก็บ output ที่มาระหว่างหน้าโหลดใหม่ จำกัดขนาด ทิ้งเก่าสุดเมื่อเต็ม: จอเดิมหายอยู่แล้ว ส่วน redraw หลังกู้จะซ่อมให้
 */
class RendererRecovery(
    private val maxPerWindow: Int = 3,
    private val windowMs: Long = 60_000L,
    private val maxHeldChars: Int = 4 * 1024 * 1024,
) {
    private val recent = ArrayDeque<Long>()
    private val held = ArrayDeque<String>()
    private var heldChars = 0

    fun allow(nowMs: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first() >= windowMs) recent.removeFirst()
        if (recent.size >= maxPerWindow) return false
        recent.addLast(nowMs)
        return true
    }

    fun hold(script: String) {
        held.addLast(script)
        heldChars += script.length
        while (heldChars > maxHeldChars && held.isNotEmpty()) heldChars -= held.removeFirst().length
    }

    fun drain(): List<String> {
        val out = held.toList()
        held.clear()
        heldChars = 0
        return out
    }
}
