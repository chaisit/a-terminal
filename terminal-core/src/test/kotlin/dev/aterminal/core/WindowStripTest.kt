package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ticket tmux/09 (Seam 3a): WindowStrip — reducer ที่แปลง ControlEvents ของ strip subscription
 * (name = `w`, value = marker-prefix สองตัว + window name ตาม STRIP_FORMAT) เป็น window list เพื่อ render
 *
 * wire facts ที่ lock (spike 11-08):
 *  - subscription dump ทุก entry ทันทีเมื่อสร้าง, re-expand ≤1/s เมื่อค่าเปลี่ยน
 *  - entry format: `%subscription-changed <name> <session> <window> <index> <pane|-> : <value>`
 *  - marker อ่านจาก **หัว value** เท่านั้น (`*`/` ` + `!`/`:`) → name ที่มี `*`/`!` ไม่ทำให้ parse เพี้ยน
 *  - window close = %window-close (ตัดทันที — subscription reaper ฝั่ง server + re-expand ≤1s ไม่ควรให้ UI ค้าง)
 */
class WindowStripTest {

    private fun sub(window: String, index: Int, value: String, pane: String? = null) =
        ControlEvent.SubscriptionChanged("w", "\$0", window, index, pane, value)

    @Test
    fun `subscription dump builds the ordered window list with marker flags`() {
        var list = emptyList<WindowStrip.Window>()
        listOf(
            sub("@1", 0, "*:edit"), // active+no-activity → "*:"
            sub("@2", 1, " :build"), // idle+no-activity → " :" (marker ยาวคงที่ 2 ตัวเสมอ)
        ).forEach { ev -> WindowStrip.reduce(list, ev)?.let { list = it } }
        assertEquals(
            listOf(
                WindowStrip.Window("@1", 0, "edit", active = true, activity = false),
                WindowStrip.Window("@2", 1, "build", active = false, activity = false),
            ),
            list,
        )
    }

    @Test
    fun `activity marker and a name containing markers parse from the prefix only`() {
        var list = WindowStrip.reduce(
            emptyList(),
            sub("@3", 2, " !*weird*name"), // inactive+activity = " !" แล้ว name ชน marker ได้เลย
        )!!
        assertEquals(WindowStrip.Window("@3", 2, "*weird*name", active = false, activity = true), list.single())
        // re-expand เปลี่ยนเฉพาะ flags — name คงเดิมได้
        list = WindowStrip.reduce(list, sub("@3", 2, "*!*weird*name"))!!
        assertEquals(WindowStrip.Window("@3", 2, "*weird*name", active = true, activity = true), list.single())
    }

    @Test
    fun `events other than the strip subscription do not touch the list`() {
        val start = WindowStrip.reduce(emptyList(), sub("@1", 0, "*:main"))!!
        // subscription ชุดอื่น (ชื่อไม่ใช่ "w") =ของคนละ feature — ห้ามปน
        assertNull(WindowStrip.reduce(start, ControlEvent.SubscriptionChanged("s", "\$0", "%0", 0, null, "*main")))
        // pane-level entry (paneId != null) ไม่ใช่ window row — ข้าม
        assertNull(WindowStrip.reduce(start, sub("@1", 0, "* main", pane = "%2")))
        assertNull(WindowStrip.reduce(start, ControlEvent.SessionsChanged))
        assertNull(WindowStrip.reduce(start, ControlEvent.Output("%2", byteArrayOf(104, 105))))
    }

    @Test
    fun `select, rename, and close keep the list live between re-expansions`() {
        var list = listOf(
            WindowStrip.Window("@1", 0, "edit", active = true, activity = false),
            WindowStrip.Window("@2", 1, "build", active = false, activity = true),
        )
        // tap pill → server ตอบ %session-window-changed ก่อน re-expansion ≤1s — active ต้องย้ายทันที
        WindowStrip.reduce(list, ControlEvent.SessionWindowChanged("\$0", "@2"))?.let { list = it }
        assertEquals(listOf(false, true), list.map { it.active })
        // no-change สองหน้า (contract "null = no-change" — ห้าม allocate list เท่าเดิม):
        // @id จาก session อื่น (unique ทั้ง server = ไม่ตรงแถวเรา) และ id ที่ active อยู่แล้ว
        assertNull(WindowStrip.reduce(list, ControlEvent.SessionWindowChanged("\$1", "@77")))
        assertNull(WindowStrip.reduce(list, ControlEvent.SessionWindowChanged("\$0", "@2")))
        // rename: %window-renamed มี name — marker คงค่าล่าสุด
        WindowStrip.reduce(list, ControlEvent.WindowRenamed("@1", "vim"))?.let { list = it }
        assertEquals("vim", list[0].name)
        assertEquals(listOf(true, true), listOf(!list[0].active, list[1].activity)) // flags ไม่เปลี่ยน
        // unlinked rename (id อย่างเดียว — man 3.6): ไม่มี name ให้ใช้ = ไม่แตะ
        assertNull(WindowStrip.reduce(list, ControlEvent.UnlinkedWindowRenamed("@1")))
        // close = ตัดแถวทันที (subscription ฝั่ง server ก็ retire entry แต่ re-expand ช้าถึง 1s)
        WindowStrip.reduce(list, ControlEvent.WindowClose("@2"))?.let { list = it }
        assertEquals(listOf("@1"), list.map { it.id })
        // close ตัวที่ไม่อยู่ใน list = no-change
        assertNull(WindowStrip.reduce(list, ControlEvent.WindowClose("@9")))
        // วัดจริงบน tmux 3.6 (emulator gate 11-09): kill ของ session ที่ attach อยู่ส่ง `%unlinked-window-close`
        // เสมอ (ตาม man = "not linked to current session" — control.c classify หลัง window หายไป lookup เลยไม่เจอ)
        // ถ้า trim แค่ %window-close = ghost pill ค้างถาวร (subscription reaper ไม่ event apa) → trim ทั้งสองรูป
        assertNull(WindowStrip.reduce(emptyList(), ControlEvent.UnlinkedWindowClose("@nope")))
        assertEquals(emptyList<WindowStrip.Window>(), WindowStrip.reduce(list, ControlEvent.UnlinkedWindowClose("@1")))
    }

    @Test
    fun `exit clears the whole strip but session change of another session does not`() {
        val list = listOf(WindowStrip.Window("@1", 0, "edit", active = true, activity = false))
        assertEquals(emptyList<WindowStrip.Window>(), WindowStrip.reduce(list, ControlEvent.Exit(null)))
        // %session-changed = attached session อื่นเปลี่ยน — strip ที่ render อยู่รอ re-dump ของชุดใหม่ (ไม่ clear เอง)
        assertNull(WindowStrip.reduce(list, ControlEvent.SessionChanged("\$1", "other")))
    }

    @Test
    fun `malformed value fails soft without touching the list`() {
        val start = WindowStrip.reduce(emptyList(), sub("@1", 0, "*:main"))!!
        // value สั้นกว่า marker สองตัว / marker ตัวแรกไม่ใช่ `*`/` ` / ตัวสองไม่ใช่ `!`/`:` = format เราพัง — ignore
        assertNull(WindowStrip.reduce(start, sub("@9", 9, "*")))
        assertNull(WindowStrip.reduce(start, sub("@9", 9, "x!name")))
        assertNull(WindowStrip.reduce(start, sub("@9", 9, "! name")))
    }
}
