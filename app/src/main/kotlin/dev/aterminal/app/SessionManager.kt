package dev.aterminal.app

/**
 * state machine ของ tabs — one Compose tab = one live session
 *  - ไม่มี Android type ใน public surface = ทดสอบ JVM ล้วนได้ (สไตล์ SessionControllerTest)
 *  - generic over handle (จริง = TerminalSession; test = String)
 *  - maxLive: LRU cap กัน memory บวม (WebView per tab ~tens MB) — ผู้เรียกได้ handle ที่ถูก
 *    evict กลับไปเพื่อ dispose (close backend + destroy WebView) เอง
 */
class SessionManager<T>(val maxLive: Int = 4) {

    private class Entry<T>(val id: Long, val handle: T, var lastUsed: Long)

    private val tabs = mutableListOf<Entry<T>>()
    private var nextId = 1L
    private var clock = 0L

    /** 0 = ไม่มี tab ใด (idle) */
    var activeId: Long = 0L
        private set

    fun ids(): List<Long> = tabs.map { it.id }
    fun size(): Int = tabs.size
    fun get(id: Long): T? = tabs.firstOrNull { it.id == id }?.handle

    /** handle ของ tab ที่ active (นับเป็น "used" สำหรับ LRU) */
    fun active(): T? = tabs.firstOrNull { it.id == activeId }?.also { touch(it) }?.handle

    /** เปิด tab ใหม่ = active; คืน handle ที่ถูก LRU evict ( caller ต้อง dispose ) */
    fun open(handle: T): List<T> {
        val e = Entry(nextId++, handle, 0)
        touch(e)
        tabs.add(e)
        activeId = e.id
        val evicted = ArrayList<T>()
        while (tabs.size > maxLive) {
            // victim = non-active ที่ inactive นานสุด; กรณี maxLive=1 อย่างเดียวถึงยอม evict ตัว active
            val victim = tabs.filter { it.id != activeId }.minByOrNull { it.lastUsed }
                ?: tabs.minByOrNull { it.lastUsed } ?: break
            tabs.remove(victim)
            evicted.add(victim.handle)
            if (victim.id == activeId) activeId = 0L
        }
        return evicted
    }

    fun switchTo(id: Long): Boolean {
        val e = tabs.firstOrNull { it.id == id } ?: return false
        activeId = id
        touch(e)
        return true
    }

    /** ปิด tab = คืน handle ให้ caller dispose; active ขยับไป tab ข้างเคียง; ปิดตัวสุดท้าย → activeId=0 */
    fun close(id: Long): T? {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val e = tabs.removeAt(idx)
        if (activeId == id) {
            // neighbor เดียวกับ WT: ตัวขวาที่เหลือ ถ้าหมดค่อยถอยซ้าย
            activeId = (tabs.getOrNull(idx) ?: tabs.getOrNull(idx - 1))?.id ?: 0L
        }
        return e.handle
    }

    private fun touch(e: Entry<T>) {
        clock += 1
        e.lastUsed = clock
    }
}
