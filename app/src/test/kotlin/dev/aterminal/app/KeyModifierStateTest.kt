package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modifier จริง (round 21) — CTRL/⇧ ต้องมีผลกับ input ทุกทาง (bar + soft keyboard) ไม่ใช่แค่ปุ่มใน bar
 * flow จริงของ user: กด CTRL ค้าง → กดตัวอักษรบน Gboard → ปล่อยทั้งสอง = Ctrl+ตัวอักษร
 * หรือ tap CTRL (latch) → พิมพ์ตัวอักษรถัดไปจากทางไหนก็ได้ = Ctrl+ตัวอักษร แล้วปลดเอง
 * fail-soft: input ที่ encode ไม่ได้ต้องส่งตามเดิม ห้ามหาย และ latch ต้องปลด (ห้ามค้างแล้วแก้ input ภายหลัง)
 */
class KeyModifierStateTest {

    private val mods = KeyModifierState()

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02X".format(it) }
    private fun tap(m: ModifierKey) { mods.press(m); mods.release(m) }

    // --- tap = latch ---

    @Test fun tap_ctrl_latches_and_next_letter_becomes_control_code() {
        tap(ModifierKey.CTRL)
        assertTrue(mods.isActive(ModifierKey.CTRL))
        assertEquals("04", bytes(mods.transform("d"))) // Ctrl+D — เคสที่ user รายงานว่าไม่มี
        assertFalse(mods.isActive(ModifierKey.CTRL))   // ปลดเองหลังใช้
    }

    @Test fun second_tap_cancels_latch() {
        tap(ModifierKey.CTRL)
        tap(ModifierKey.CTRL) // tap ซ้ำ = ปลด (user กด CTRL หนี ตอนกดผิด)
        assertFalse(mods.isActive(ModifierKey.CTRL))
        assertEquals("d", mods.transform("d"))
    }

    @Test fun latched_ctrl_typed_letter_from_ime_is_transformed_then_released() {
        tap(ModifierKey.CTRL)
        assertEquals("03", bytes(mods.transform("c"))) // Ctrl+C ผ่าน Gboard ก็ต้องได้ SIGINT
        assertEquals("c", mods.transform("c"))         // ครั้งถัดไป plain
    }

    // --- hold = transient modifier ---

    @Test fun hold_ctrl_letter_while_held_then_release_off() {
        mods.press(ModifierKey.CTRL)
        assertEquals("04", bytes(mods.transform("d"))) // พิมพ์ตอนนิ้ว CTRL ยังค้าง
        assertTrue(mods.isHeld(ModifierKey.CTRL))      // hold ไม่ถูก consume (≡ ค้าง physical ctrl)
        mods.release(ModifierKey.CTRL)
        assertFalse(mods.isActive(ModifierKey.CTRL))
        assertEquals("d", mods.transform("d"))         // ปล่อยแล้ว = จบ
    }

    @Test fun hold_without_use_then_release_becomes_latch() {
        mods.press(ModifierKey.CTRL)
        mods.release(ModifierKey.CTRL) // ปล่อยเปล่า = tap → latch
        assertTrue(mods.isActive(ModifierKey.CTRL))
    }

    // --- fail-soft: ห้ามหาย + latch ต้องไม่ค้าง ---

    @Test fun latched_ctrl_on_unencodable_passes_through_and_clears_latch() {
        tap(ModifierKey.CTRL)
        assertEquals("\u001B[A", mods.transform("\u001B[A")) // ลูกศร = encode ไม่ได้ → ส่งตรง
        assertFalse(mods.isActive(ModifierKey.CTRL))
    }

    @Test fun latched_ctrl_on_multichar_text_passes_through_and_clears_latch() {
        tap(ModifierKey.CTRL)
        assertEquals("hello", mods.transform("hello")) // paste/composition = ไม่ใช่ shortcut
        assertFalse(mods.isActive(ModifierKey.CTRL))
        assertEquals("4E 6F", bytes(mods.transform("No"))) // latch สนิทแล้ว — ห้ามกลายร่างทีหลัง
    }

    @Test fun held_ctrl_survives_unencodable_input_until_release() {
        mods.press(ModifierKey.CTRL)
        assertEquals("\u001B[A", mods.transform("\u001B[A")) // นิ้วยังค้าง = modifier ยังพร้อม
        assertEquals("01", bytes(mods.transform("a")))
        mods.release(ModifierKey.CTRL)
    }

    // --- SHIFT ---

    @Test fun tap_shift_uppercases_next_letter_and_releases() {
        tap(ModifierKey.SHIFT)
        assertEquals("D", mods.transform("d"))
        assertFalse(mods.isActive(ModifierKey.SHIFT))
    }

    @Test fun ctrl_beats_shift_and_consumes_both() {
        tap(ModifierKey.CTRL); tap(ModifierKey.SHIFT)
        assertEquals("04", bytes(mods.transform("d"))) // Ctrl+ตัว = control code เดียวกัน uppercase/lowercase
        assertFalse(mods.isActive(ModifierKey.CTRL))
        assertFalse(mods.isActive(ModifierKey.SHIFT))
    }

    // --- bracket trio (xterm: Ctrl+[ = ESC) ---

    @Test fun latched_ctrl_bracket_maps_to_xterm_control_bytes() {
        tap(ModifierKey.CTRL)
        assertEquals("1B", bytes(mods.transform("[")))  // Ctrl+[ = ESC (exit vim)
        tap(ModifierKey.CTRL)
        assertEquals("1C", bytes(mods.transform("\\"))) // Ctrl+\ = FS (ถ้าย้ายชั้นจะรวม ^ _ ด้วย — รอบนี้ triad เดิม)
    }

    // --- lifecycle ---

    @Test fun touch_cancel_clears_everything() {
        tap(ModifierKey.CTRL)
        mods.press(ModifierKey.SHIFT)
        mods.cancel(ModifierKey.CTRL); mods.cancel(ModifierKey.SHIFT) // scroll-intercept = ยกเลิกทั้งหมด
        assertEquals("d", mods.transform("d"))
        assertFalse(mods.isActive(ModifierKey.CTRL))
        assertFalse(mods.isActive(ModifierKey.SHIFT))
    }

    @Test fun clear_latches_keeps_held() {
        tap(ModifierKey.CTRL)                // latched
        mods.press(ModifierKey.SHIFT)        // finger ยังค้างบน SHIFT
        mods.clearLatches()                  // สลับ pane
        assertFalse(mods.isLatched(ModifierKey.CTRL))
        assertTrue(mods.isHeld(ModifierKey.SHIFT)) // นิ้วจริงยังกดอยู่ — state ต้องตรงกับสัมผัส
    }

    @Test fun observer_notified_on_state_changes() {
        var changes = 0
        mods.onChange = { changes++ }
        tap(ModifierKey.CTRL)                // down + up = 2
        assertEquals("04", bytes(mods.transform("d"))) // +1 (consume → latch off)
        assertEquals(3, changes)
    }

    // --- webview composition flush hook (round 21) ---

    @Test fun active_change_pushes_only_on_edges_for_js_flush() {
        val seen = mutableListOf<Boolean>()
        mods.onActiveChange = { seen.add(it) }
        tap(ModifierKey.CTRL)                        // down = active; up = latch (ยัง active = ไม่ push ซ้ำ)
        assertEquals("04", bytes(mods.transform("d"))) // consume → ปลด = inactive
        assertEquals(listOf(true, false), seen)
        mods.press(ModifierKey.SHIFT)                // hold = active
        assertEquals("4A", bytes(mods.transform("j"))) // ใช้ตอน held — ยัง active (นิ้วค้าง) = ห้าม push false
        assertEquals(listOf(true, false, true), seen)
        mods.release(ModifierKey.SHIFT)              // ยกนิ้ว = จบ
        assertEquals(listOf(true, false, true, false), seen)
    }
}
