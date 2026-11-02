package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** T4 (round 13): codec + cap-trim — JVM (org.json ตัวจริงผ่าน testImplementation) */
class SessionRestoreTest {

    private val snap = SessionRestore.Snapshot(
        tabs = listOf(
            SessionRestore.RestoreTab(listOf("lab-host"), SplitOrientation.HORIZONTAL, 0.5f, 0),
            SessionRestore.RestoreTab(listOf("termux-local", "scaffold-demo"), SplitOrientation.VERTICAL, 0.4f, 1),
        ),
        focusedTab = 1,
    )

    @Test fun `round-trip preserves everything`() {
        val back = SessionRestore.decode(SessionRestore.encode(snap))
        assertEquals(snap, back)
    }

    @Test fun `corrupt json decodes to null not throw`() {
        assertNull(SessionRestore.decode("{"))
        assertNull(SessionRestore.decode(""))
        assertNull(SessionRestore.decode("{\"v\":99,\"tabs\":[{\"slots\":[\"a\"],\"orientation\":\"H\",\"fraction\":0.5,\"focused\":0}]}"))
        assertNull(SessionRestore.decode("{\"v\":1,\"tabs\":[]}"))
        assertNull(SessionRestore.decode("{\"v\":1,\"tabs\":[{\"slots\":[],\"orientation\":\"H\",\"fraction\":0.5,\"focused\":0}]}"))
        assertNull(SessionRestore.decode("{\"v\":1,\"tabs\":[{\"slots\":[\"a\"],\"orientation\":\"X\",\"fraction\":0.5,\"focused\":0}]}"))
        assertNull(SessionRestore.decode("{\"v\":1,\"tabs\":[{\"slots\":[\"a\"],\"orientation\":\"H\",\"fraction\":5.0,\"focused\":0}]}"))
    }

    @Test fun `focused indices out of range are clamped on decode`() {
        val raw = """{"v":1,"focusedTab":42,"tabs":[{"slots":["a","b"],"orientation":"H","fraction":0.5,"focused":7}]}"""
        val s = SessionRestore.decode(raw)
        assertNotNull(s)
        assertEquals(0, s!!.focusedTab)
        assertEquals(1, s.tabs[0].focused)
    }

    @Test fun `cap trim drops from the tail and clamps focus`() {
        val big = SessionRestore.Snapshot(
            tabs = listOf(
                SessionRestore.RestoreTab(listOf("a", "b"), SplitOrientation.HORIZONTAL, 0.5f, 1),
                SessionRestore.RestoreTab(listOf("c", "d"), SplitOrientation.VERTICAL, 0.5f, 1),
                SessionRestore.RestoreTab(listOf("e"), SplitOrientation.HORIZONTAL, 0.5f, 0),
            ),
            focusedTab = 2,
        )
        val t = SessionRestore.trimToPaneCap(big, cap = 4)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), t.tabs.map { it.slots })
        assertEquals(1, t.focusedTab)
        // tab ที่ pane เดียวตกเป็น жертก่อนทั้ง tab; focused ของ tab ที่โดนหด = clamp
        val t2 = SessionRestore.trimToPaneCap(big, cap = 3)
        assertEquals(listOf("a", "b", "c"), t2.tabs.flatMap { it.slots })
        assertEquals(0, t2.tabs[1].focused)
    }

    @Test fun `trim below zero tabs yields empty snapshot`() {
        val t = SessionRestore.trimToPaneCap(snap, cap = 0)
        assertEquals(0, t.tabs.size)
    }
}
