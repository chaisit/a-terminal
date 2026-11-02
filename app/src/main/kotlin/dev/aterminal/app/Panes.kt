package dev.aterminal.app

/**
 * โมเดล pane ของหนึ่ง tab (round 5) — pure Kotlin, ไม่มี Android type (JVM-test ได้)
 *  - ขอบเขตรอบนี้: สูงสุด 2 pane ต่อบน (ผู้ใช้เคาะแล้ว; mobile screen เล็กกว่าจะได้ประโยชน์จาก 3+)
 *  - ชื่อบังคับตาม WT schema: splitPane (split=right|down), closeOtherPanes, resizePane — trace กลับ
 *    `~/refs/terminal/doc/cascadia/profiles.schema.json` ($defs.SplitDirection, action `splitPane`)
 *  - HORIZONTAL = แบ่งซ้าย-ขวา (WT "split: right"), VERTICAL = แบ่งบน-ล่าง (WT "split: down")
 */
enum class SplitOrientation { HORIZONTAL, VERTICAL }

class TerminalTab<T>(val id: Long) {

    val panes = ArrayList<T>()
    var orientation = SplitOrientation.HORIZONTAL
        private set
    var focused = 0
        private set
    /** สัดส่วน pane 0 (resizePane ใน WT ใช้ 0–1; clamp 0.25–0.75 กัน pane แตะขนาดมองไม่ทัน) */
    var fraction = 0.5f
        private set

    fun setFraction(f: Float) { fraction = f.coerceIn(0.25f, 0.75f) }

    fun focusedPane(): T? = panes.getOrNull(focused)

    /** WT splitPane: pane ใหม่ได้ focus (splitMode "duplicate" ใน WT ก็ focus ตัวใหม่) */
    fun split(handle: T): Boolean {
        if (panes.size >= MAX_PANES) return false
        panes.add(handle)
        focused = panes.size - 1
        return true
    }

    fun setFocused(index: Int): Boolean {
        if (index !in panes.indices) return false
        focused = index
        return true
    }

    fun indexOf(handle: T): Int = panes.indexOfFirst { it == handle }

    /** ปิดหนึ่ง pane = คืน handle ให้ caller dispose; focus ขยับไปตัวที่เหลือ (สไตล์ WT) */
    fun closePane(index: Int): T? {
        if (index !in panes.indices) return null
        val removed = panes.removeAt(index)
        focused = when {
            panes.isEmpty() -> 0
            index < focused -> focused - 1
            else -> focused.coerceAtMost(panes.size - 1)
        }
        return removed
    }

    /** closeOtherPanes: เก็บเฉพาะที่ focus, คืนที่เหลือให้ dispose */
    fun closeOthers(): List<T> {
        val keep = focusedPane() ?: return emptyList()
        val others = panes.filter { it != keep }
        panes.clear(); panes.add(keep); focused = 0
        return others
    }

    fun toggleOrientation() {
        orientation = if (orientation == SplitOrientation.HORIZONTAL)
            SplitOrientation.VERTICAL else SplitOrientation.HORIZONTAL
    }

    /** เมนูระบุทิศทางตรง ๆ (WT `split: right|down`) — ไม่สุ่ม toggle */
    fun setOrientation(o: SplitOrientation) { orientation = o }

    companion object { const val MAX_PANES = 2 }
}

/**
 * memory valve ตัวเดียวของ app (round 5): LRU ระดับ "pane = หนึ่ง WebView"
 *  - round 4 cap = 4 tab; รอบนี้ WebView คือหน่วยที่กิน memory จริง (~60–70MB/ตัว วัด RSS round 4)
 *  - victim = ที่ inactive นานสุด และ **ไม่อยู่ใน protected** (caller ส่ง focused pane ของ tab active —
 *    pane ที่ user กำลังมอง/พิมพ์ห้ามหาย; pane เดียวของ tab = focused อยู่แล้ว = รอดโดยธรรมชาติ)
 */
class PaneLru<T>(val cap: Int) {

    private class Entry<T>(val handle: T, var lastUsed: Long)

    private val items = ArrayList<Entry<T>>()
    private var clock = 0L
    private fun touchNow(e: Entry<T>) { clock += 1; e.lastUsed = clock }

    fun add(handle: T) { items.add(Entry(handle, 0)); items.last().let { touchNow(it) } }
    fun touch(handle: T) { items.firstOrNull { it.handle == handle }?.let { touchNow(it) } }
    fun remove(handle: T) { items.removeAll { it.handle == handle } }
    fun size(): Int = items.size
    fun handles(): List<T> = items.map { it.handle }

    /** เกิน cap = ปลดที่ inactive นานสุด (ยกเว้น protected) จนกลับเข้า cap — คืน handle ให้ caller dispose */
    fun evict(protectedHandles: Set<T>): List<T> {
        val out = ArrayList<T>()
        while (items.size > cap) {
            val victim = items.filter { it.handle !in protectedHandles }.minByOrNull { it.lastUsed }
                ?: break // ทุกตัว protected หมด = ไม่ evict (caller ตัดสินใจเองว่าจะยอมให้เกิน cap ชั่วคราว)
            items.remove(victim)
            out.add(victim.handle)
        }
        return out
    }
}
