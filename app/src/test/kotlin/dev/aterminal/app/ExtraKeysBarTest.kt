package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ปุ่ม special keys ต้องส่ง byte ตรงตาม VT — test ระดับ companion constants (ไม่ต้องมี Context/Robolectric)
 * regression guard: raw 0x1B ใน source เคยหายเงียบได้ — test nี้ยืนยัน byte แม้คนแก้จะมองไม่เห็นมัน
 */
class ExtraKeysBarTest {

    private fun bytes(s: String) = s.toByteArray().joinToString(" ") { "%02X".format(it) }

    @Test fun esc_is_single_0x1B() {
        assertEquals("1B", bytes(ExtraKeysBar.ESC))
    }

    @Test fun tab_and_ctrlc() {
        assertEquals("09", bytes(ExtraKeysBar.TAB))
        assertEquals("03", bytes(ExtraKeysBar.CTRL_C))
    }

    @Test fun arrows_are_csi_A_B_C_D() {
        assertEquals("1B 5B 41", bytes(ExtraKeysBar.UP))
        assertEquals("1B 5B 42", bytes(ExtraKeysBar.DOWN))
        assertEquals("1B 5B 43", bytes(ExtraKeysBar.RIGHT))
        assertEquals("1B 5B 44", bytes(ExtraKeysBar.LEFT))
    }

    @Test fun ctrl_lock_letters_map_to_control_codes() {
        assertEquals("01", bytes(ExtraKeysBar.ctrlOf('A')))
        assertEquals("03", bytes(ExtraKeysBar.ctrlOf('C'))) // Ctrl+C = SIGINT
        assertEquals("1A", bytes(ExtraKeysBar.ctrlOf('Z')))
    }
}
