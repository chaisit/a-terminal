package dev.aterminal.app

import dev.aterminal.core.KeyPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun enter_is_single_0x0D() {
        // terminal Enter = CR — ≡ สิ่งที่ xterm/Termux ส่งจริง (ไม่ใช่ \n จาก form semantics)
        assertEquals("0D", bytes(ExtraKeysBar.ENTER))
    }

    @Test fun ctrl_lock_letters_map_to_control_codes() {
        assertEquals("01", bytes(ExtraKeysBar.ctrlOf('A')))
        assertEquals("03", bytes(ExtraKeysBar.ctrlOf('C'))) // Ctrl+C = SIGINT
        assertEquals("1A", bytes(ExtraKeysBar.ctrlOf('Z')))
    }

    // glyphOnly flip (ADR 0002 shrink rule) — label เดียวกันต้องยุบ/ขยาย ครบทั้งสองทิศ
    // (regression guard: review round 23 = flip ถูก wire แค่ modifier ปุ่ม, ปุ่ม key ค้าง "ESC" เต็ม)
    @Test fun labelFor_collapses_every_mapped_label_when_glyph_only() {
        assertEquals("⎋", ExtraKeysBar.labelFor("ESC", true))
        assertEquals("⇥", ExtraKeysBar.labelFor("TAB", true))
        assertEquals("⇧", ExtraKeysBar.labelFor("SHIFT", true))
        assertEquals("⌃", ExtraKeysBar.labelFor("CTRL", true))
    }

    @Test fun labelFor_restores_full_labels_when_not_glyph_only() {
        // narrow→wide = ต้องได้ label เต็มกลับ (stateless ต่อ flag → ไม่มี state ค้างจากทิศ narrow)
        assertEquals("ESC", ExtraKeysBar.labelFor("ESC", false))
        assertEquals("TAB", ExtraKeysBar.labelFor("TAB", false))
        assertEquals("SHIFT", ExtraKeysBar.labelFor("SHIFT", false))
        assertEquals("CTRL", ExtraKeysBar.labelFor("CTRL", false))
    }

    @Test fun labelFor_passes_through_labels_without_glyph_mapping() {
        // label ที่ shortOf ไม่รู้จัก = คงเดิมทั้งสองทิศ (⌃C, อักษรเดียว, popup glyphs)
        for (label in listOf("⌃C", "↑", "A", "|", "~", "⚡")) {
            assertEquals(label, ExtraKeysBar.labelFor(label, true))
            assertEquals(label, ExtraKeysBar.labelFor(label, false))
        }
    }

    // ---- fold-popup flatten (fix round, Important-1) ----
    // p_pipe/p_tilde เป็น popup-only (round 23 dedupe) = อยู่ใน p_dash/p_slash เท่านั้น;
    // ถ้า host ถูก fold เข้า ⋯ แล้ว popup ยิงแค่ base bytes → "|" และ "~" หาไม่ได้ใน portrait
    // flatten = host ทุกตัวได้ 1 entry, host ที่มี variant ได้ variant เป็น entry ของมันเองทันทีถัดไป
    // (label เต็ม + bytes ของ variant เอง) — single level ตาม schema (KeyButton.popup = plain KeyButton)

    @Test fun flatten_host_without_popup_yields_one_entry_firing_host_bytes() {
        val fired = mutableListOf<String>()
        val out = ExtraKeysBar.flattenFolded(listOf(FoldHost("ESC", { fired += "esc" })))
        assertEquals(listOf("ESC"), out.map { it.label })
        out[0].fire()
        assertEquals(listOf("esc"), fired)
    }

    @Test fun flatten_host_with_popup_yields_host_then_variant_in_order() {
        val fired = mutableListOf<String>()
        val host = FoldHost("-", { fired += "-" }, EntrySpec("|") { fired += "|" })
        val out = ExtraKeysBar.flattenFolded(listOf(host))
        assertEquals("variant entry of ตัวมันเอง ต่อท้าย host ทันที", listOf("-", "|"), out.map { it.label })
        out[1].fire()
        assertEquals("variant ยิง bytes ของตัวเอง ไม่ใช่ของ host", listOf("|"), fired)
    }

    @Test fun flatten_keeps_hosts_in_order_with_each_variant_right_after_its_host() {
        val items = listOf(
            FoldHost("Z", {}),
            FoldHost("-", {}, EntrySpec("|") {}),
            FoldHost("SHIFT", {}), // modifier = ไม่มี variant เสมอ
            FoldHost("/", {}, EntrySpec("~") {}),
        )
        assertEquals(
            listOf("Z", "-", "|", "SHIFT", "/", "~"),
            ExtraKeysBar.flattenFolded(items).map { it.label },
        )
    }

    @Test fun flatten_variants_are_single_level_only() {
        // type-level guarantee: variant = EntrySpec (leaf ไม่มี field variant) → variant-of-variant
        // เขียนไม่ออก; flatten จึงไม่สร้าง entry เพิ่มจากรอยต่อ host→variant เด็ดขาด
        val out = ExtraKeysBar.flattenFolded(
            listOf(FoldHost("h", {}, EntrySpec("v") {}), FoldHost("x", {})),
        )
        assertEquals(3, out.size)
    }

    // ---- fix wave B2: fold rebuild must not strand a modifier press ----
    // นิ้ว DOWN ค้างบน SHIFT (mods.press แล้ว) ตอน slot ถูก fold = UP/CANCEL ไม่เคยมาถึง
    // → held ค้าง; cancelStrandedHolds คือ teardown ที่ applyFold เรียกก่อน rebuild ทุกครั้ง

    @Test fun cancelStrandedHolds_releases_held_modifier_completely() {
        val mods = KeyModifierState()
        mods.press(ModifierKey.SHIFT)
        assertTrue("probe: press must register as held before teardown", mods.isHeld(ModifierKey.SHIFT))
        ExtraKeysBar.cancelStrandedHolds(mods)
        assertFalse("stranded hold must not survive a fold rebuild", mods.isHeld(ModifierKey.SHIFT))
        // cancel semantics (≡ ACTION_CANCEL): จบทั้งก้อน — ห้าม latch แทน (ไม่ใช่ tap)
        assertFalse("cancel must not convert a stranded hold into a latch", mods.isLatched(ModifierKey.SHIFT))
        assertFalse(mods.anyActive())
    }

    @Test fun cancelStrandedHolds_leaves_tapped_latches_untouched() {
        val mods = KeyModifierState()
        mods.press(ModifierKey.CTRL)
        mods.release(ModifierKey.CTRL) // tap เปล่า = latch (สถานะที่ user ตั้งใจ — fold ห้ามลบ)
        ExtraKeysBar.cancelStrandedHolds(mods)
        assertTrue("latch without a live hold is not a stranded press", mods.isLatched(ModifierKey.CTRL))
        // และ key ที่ไม่เกี่ยวกับ held เลยต้องไม่โดน (cancel per-key ไม่ใช่ clear-all)
        mods.press(ModifierKey.SHIFT) // held only SHIFT
        ExtraKeysBar.cancelStrandedHolds(mods)
        assertTrue("CTRL latch survives while CTRL is not held", mods.isLatched(ModifierKey.CTRL))
        assertFalse("SHIFT hold was live at teardown → cancelled", mods.isHeld(ModifierKey.SHIFT))
    }

    // ---- parked (l): fold order pin — head ต้องเป็นตัวหัวแถว, tail = หางแถว (applyFold ใช้ foldSplit ตรง ๆ) ----

    @Test fun foldSplit_keeps_head_and_folds_tail_in_order() {
        val items = listOf("esc", "tab", "ctrl_c", "shift", "ctrl", "up", "down")
        val (head, tail) = ExtraKeysBar.foldSplit(items, 2)
        assertEquals("head = ตัวหัวแถวตามลำดับ preset", listOf("esc", "tab", "ctrl_c", "shift", "ctrl"), head)
        assertEquals("tail = หางแถว, ลำดับคงเดิม (popup ⋯ แสดงตามนี้)", listOf("up", "down"), tail)
    }

    @Test fun foldSplit_no_overflow_keeps_everything_visible() {
        val items = listOf("a", "b", "c")
        val (head, tail) = ExtraKeysBar.foldSplit(items, 0)
        assertEquals(items, head)
        assertEquals(emptyList<String>(), tail)
    }

    @Test fun foldSplit_degenerate_fold_all_leaves_empty_head() {
        // k==1 (body < minDp): ⋯ ปุ่มเดียว, ยุบทั้งแถว — head ว่างได้แต่ห้าม index เกิน/สลับทิศ
        val items = listOf("a", "b", "c")
        val (head, tail) = ExtraKeysBar.foldSplit(items, 5)
        assertEquals(emptyList<String>(), head)
        assertEquals(items, tail)
        // partition invariant: ทุกตัวอยู่พอดีที่หนึ่งที่ (head ∪ tail = of, ไม่หาย/ไม่ซ้ำ)
        assertEquals(items, head + tail)
    }

    @Test fun default_preset_folded_tail_keeps_pipe_and_tilde_reachable() {
        // เคสจริงจาก bug (portrait 275dp: k=6 → head = esc,tab,ctrl_c,paste,shift; หาง = items 6–17
        // รวม p_dash/p_slash) — projection เดียวกับ keyBtn: popup variant → EntrySpec ของตัวเอง
        val foldedTail = KeyPresets.default.buttons.drop(5).map { b ->
            FoldHost(
                b.label ?: b.id,
                { /* base bytes */ },
                b.popup?.let { p -> EntrySpec(p.label ?: p.id) { /* variant bytes */ } },
            )
        }
        val labels = ExtraKeysBar.flattenFolded(foldedTail).map { it.label }
        assertTrue("| ต้อง reachable: $labels", labels.contains("|"))
        assertTrue("~ ต้อง reachable: $labels", labels.contains("~"))
        assertTrue("host ของ variant ยังอยู่: $labels", labels.containsAll(listOf("-", "/")))
    }
}
