package dev.aterminal.app

import dev.aterminal.core.TerminalBackend

/** backend+identity ที่ overlay ใช้ pull (capture ตอน probe-armed — ห้าม arm blind ตาม contract 08) */
internal class HistorySource(val backend: TerminalBackend, val target: () -> String?)

/**
 * ticket tmux/08b: state machine ของ history overlay (read-only, b1) — pure JVM, UI แค่ bind
 *
 * composition บน HistoryPuller (wireings ที่ต่างจาก live path, โดยเจตนา):
 *  - appDepths = (delivered, delivered): overlap = สิ่ง ที่ overlay จ่ายออกไปแล้ว *ทั้งหมด* (live buffer ไม่เกี่ยว —
 *    overlay เป็น view ของ server history ตรง ๆ; unit = logical lines จาก `-J`, display estimate = logical —
 *    ratio ของ puller เรียน cost จริงจาก pane เอง = ผิดพลาดได้ทิศ over-capture เท่านั้น = ปลอดภัย)
 *  - generation = constant: overlay วาด strings ของตัวเอง, reflow ฝั่ง renderer ไม่ invalidates (decision b1)
 *  - หน้าแรกของ overlay ≡ เนื้อหาเหนือ viewport ของ pane (หน้าเดิมที่ 08 inject) — การเปิดไม่ใช่ dup, เป็นการขยาย
 *
 * cap = MAX_LINES: trim ที่ tail (newest) — user เห็นช่วงนั้นใน pane อยู่แล้ว และการ trim ฝั่ง oldest จะเลื่อน
 * anchor ระหว่าง pull; overlap (delivered) *ไม่* หักเส้นที่ trim = กัน trimmed line ถูก deliver ซ้ำที่ขอบล่าง
 *
 * threading: pull() ต้องมาจาก main เช่นเดียวกับ HistoryPuller (busy flag ไม่ synchronize)
 */
internal class OverlayPager(
    backend: TerminalBackend,
    target: () -> String?,
    private val maxLines: Int = MAX_LINES,
    private val onPrepend: (List<String>) -> Unit = {},
    private val onTrimBottom: (Int) -> Unit = {},
    onStatus: (Boolean) -> Unit = {},
    onWarn: (String) -> Unit = {},
) {
    /** oldest-first = ทิศการวาดของ overlay (บรรทัดเก่าอยู่บน) */
    val lines = mutableListOf<String>()
    private var delivered = 0

    private val puller = HistoryPuller(
        backend = backend,
        target = target,
        appDepths = { delivered to delivered },
        generation = { 0 },
        onInject = { page ->
            lines.addAll(0, page) // older page = prepend, oldest-first ภายในหน้าคงเดิม
            delivered += page.size
            onPrepend(page)
            val over = lines.size - maxLines
            if (over > 0) {
                repeat(over) { lines.removeAt(lines.size - 1) }
                onTrimBottom(over)
            }
        },
        onStatus = onStatus,
        onWarn = onWarn,
    )

    suspend fun pull() = puller.pull()

    companion object {
        const val MAX_LINES = 5_000 // hard cap ฝั่ง overlay (OOM lesson เดียวกับ history-limit) — 5000 บรรทัด text ≈ เล็กน้อย
    }
}
