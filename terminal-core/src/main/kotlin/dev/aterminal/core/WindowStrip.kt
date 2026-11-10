package dev.aterminal.core

/**
 * ticket tmux/09 (Seam 3a): strip state machine — ControlEvents → window list พร้อม render
 *
 * input ที่ใช้ (subset ที่โผล่จริงใน spike logs 11-08):
 *  - `%subscription-changed w $sess @win <index> - : <marker><name>` — ตัวขับหลัก (dump ทันที + re-expand ≤1/s)
 *  - `%session-window-changed` — active ย้ายทันที (ก่อน re-expansion ≤1s ที่ user จะรู้สึก)
 *  - `%window-renamed` — ชื่อใหม่ (marker คงล่าสุด); `%window-close`/`%unlinked-window-close` — ตัดแถวทันที
 *    (ไม่รอ reaper ของ server; 3.6 วัดจริง: ทุก kill = รูป unlinked — ดู trimClosed)
 *  - `%exit` — clear ทั้ง strip (channel จบ = ข้อมูลเก่าห้ามค้างบน UI)
 *
 * value = marker prefix ยาวคงที่ 2 ตัว (TmuxControl.STRIP_FORMAT: `*`/space = active, `!`/`:` = activity)
 * → name ที่มี `*`/`!`/`:` นำหน้า parse ไม่เพี้ยน เพราะ consume จากหัวเท่านั้น
 *
 * pure function: `reduce` คืน list ใหม่เมื่อมีผล, null = no-change (alloc-free ใน path เงียบ ≤1 event/s)
 */
object WindowStrip {

    data class Window(
        val id: String,   // @id (identity convention เดียวกับ select-window)
        val index: Int,   // ตำแหน่งใน session — sort key
        val name: String,
        val active: Boolean,
        val activity: Boolean,
    )

    /** subscription name ที่ strip ของเราใช้ (TmuxControl.STRIP_SUBSCRIPTION_NAME) — ชุดอื่นห้ามปน */
    private const val STRIP = TmuxControl.STRIP_SUBSCRIPTION_NAME

    fun reduce(windows: List<Window>, event: ControlEvent): List<Window>? = when (event) {
        is ControlEvent.SubscriptionChanged -> subscriptionRow(windows, event)
        // @id unique ทั้ง server → id จาก session อื่นไม่มีวันตรงแถวเรา (cross-session event = null เงียบ)
        is ControlEvent.SessionWindowChanged -> when {
            windows.none { it.id == event.windowId } -> null
            windows.first { it.id == event.windowId }.active -> null // active อยู่แล้ว = no-change จริง (รักษา null contract, ลด recompose)
            else -> windows.map { it.copy(active = it.id == event.windowId) }
        }
        is ControlEvent.WindowRenamed -> {
            val i = windows.indexOfFirst { it.id == event.windowId }
            if (i < 0 || windows[i].name == event.name) null
            else windows.toMutableList().also { it[i] = it[i].copy(name = event.name) }
        }
        // วัดจริง 11-09: tmux 3.6 ส่ง `%unlinked-window-close` ทุกเคสที่ kill (แม้ window ของ session ที่ attach —
        // control.c classify หลัง destroy) ถ้า trim แค่ linked = ghost ค้างถาวร — @id unique ทั้ง server จึง trim safe
        is ControlEvent.WindowClose -> trimClosed(windows, event.windowId)
        is ControlEvent.UnlinkedWindowClose -> trimClosed(windows, event.windowId)
        is ControlEvent.Exit -> if (windows.isEmpty()) null else emptyList()
        else -> null
    }

    private fun trimClosed(windows: List<Window>, windowId: String): List<Window>? {
        val kept = windows.filterNot { it.id == windowId }
        return if (kept.size == windows.size) null else kept
    }

    private fun subscriptionRow(windows: List<Window>, event: ControlEvent.SubscriptionChanged): List<Window>? {
        if (event.name != STRIP || event.paneId != null) return null // window-level entries เท่านั้น
        val row = parseValue(event.windowId, event.index, event.value) ?: return null // format เราพัง = ignore, UI คงสถานะเดิม
        val i = windows.indexOfFirst { it.id == row.id }
        return when {
            i < 0 -> (windows + row).sortedBy { it.index }
            windows[i] == row -> null // re-expand ค่าเดิม = เงียบ (no alloc, no recompose)
            else -> windows.toMutableList().also { it[i] = row }.sortedBy { it.index }
        }
    }

    /** marker prefix 2 ตัว + name → แถว Window ตรงตัว (no Triple — กันสลับตำแหน่ง active/activity แบบเงียบ) */
    private fun parseValue(windowId: String, index: Int, value: String): Window? {
        if (value.length < 2) return null
        val active = when (value[0]) { '*' -> true; ' ' -> false; else -> return null }
        val activity = when (value[1]) { '!' -> true; ':' -> false; else -> return null }
        return Window(windowId, index, value.substring(2), active, activity)
    }
}
