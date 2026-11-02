package dev.aterminal.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// R5 (F-4): pure half ของ guard — ครบทุกกรณีของ (finishing, destroyed)
// ครึ่ง Activity/AlertDialog ไม่ test ที่นี่ (Robolectric ไม่อยู่ใน setup — ดู grep-gate ใน test-report-10)
class DialogGuardsTest {
    @Test fun only_alive_activity_shows() {
        assertTrue(canShowDialog(finishing = false, destroyed = false))
        assertFalse(canShowDialog(finishing = true, destroyed = false))
        assertFalse(canShowDialog(finishing = false, destroyed = true))
        assertFalse(canShowDialog(finishing = true, destroyed = true))
    }
}
