package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeybindingsTest {

    // ---------- ChordParser ----------
    @Test fun `basic chord with modifiers`() {
        assertEquals(KeyChord(true, false, true, "f"), ChordParser.parse("ctrl+shift+f"))
    }

    @Test fun `modifiers may trail and case is free`() {
        assertEquals(KeyChord(true, false, false, "f"), ChordParser.parse("f+ctrl"))
        assertEquals(KeyChord(true, false, true, "f"), ChordParser.parse("Ctrl+Shift+F"))
    }

    @Test fun `aliases normalize to canonical names`() {
        assertEquals("pageup", ChordParser.parse("ctrl+pgup")?.key)
        assertEquals("pagedown", ChordParser.parse("ctrl+pgdn")?.key)
        assertEquals("escape", ChordParser.parse("ctrl+esc")?.key)
        assertEquals("enter", ChordParser.parse("shift+return")?.key)
    }

    @Test fun `plus minus digits`() {
        assertEquals("plus", ChordParser.parse("ctrl+plus")?.key)
        assertEquals("minus", ChordParser.parse("ctrl+-")?.key)
        assertEquals("0", ChordParser.parse("ctrl+0")?.key)
        assertEquals("down", ChordParser.parse("alt+shift+down")?.key)
    }

    @Test fun `reject bad segments`() {
        for (bad in listOf("ctrl+", "", "  ", "win+f", "numpad5", "vk(041)", "sc(65)",
            "ctrl+shift", "a+b")) {
            assertNull("ต้องปฏิเสธ '$bad'", ChordParser.parse(bad))
        }
    }

    // ---------- Resolver: defaults + overlay ----------
    private fun resolveBindings(json: String) =
        KeybindingResolver.resolve(SettingsLoader.parse(json))

    private fun ResolvedTable.entry(key: String, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        entries.find { it.chord.key == key && it.chord.ctrl == ctrl && it.chord.alt == alt && it.chord.shift == shift }

    @Test fun `baked defaults mirror WT chords`() {
        val t = KeybindingResolver.resolve(Settings()).table
        assertEquals("find", Command.nameOf(t.entry("f", ctrl = true, shift = true)!!.command))
        assertEquals(ActionTarget.TERMINAL, t.entry("f", ctrl = true, shift = true)!!.target)
        assertEquals("markMode", Command.nameOf(t.entry("m", ctrl = true, shift = true)!!.command))
        assertEquals("newTab", Command.nameOf(t.entry("t", ctrl = true, shift = true)!!.command))
        assertEquals("closePane", Command.nameOf(t.entry("w", ctrl = true, shift = true)!!.command))
        assertEquals("nextTab", Command.nameOf(t.entry("tab", ctrl = true)!!.command))
        assertEquals("left", t.entry("left", alt = true)!!.command.strArg("direction"))
        assertEquals("previous", t.entry("left", ctrl = true, alt = true)!!.command.strArg("direction"))
        assertEquals("scrollUpPage", Command.nameOf(t.entry("pageup", ctrl = true, shift = true)!!.command))
        assertEquals("resetFontSize", Command.nameOf(t.entry("0", ctrl = true)!!.command))
        assertEquals("duplicateTab", Command.nameOf(t.entry("d", ctrl = true, shift = true)!!.command))
    }

    @Test fun `legacy shipped file overrides baked closePane with closeTab`() {
        // เคสจริงของ settings.defaults.json round ≤6: ctrl+shift+w = closeTab (legacy command)
        val r = resolveBindings("""{"keybindings":[{"keys":"ctrl+shift+w","command":"closeTab"}]}""")
        val e = r.table.entry("w", ctrl = true, shift = true)!!
        assertEquals("closeTab", Command.nameOf(e.command))
        assertEquals(ActionTarget.APP, e.target)
    }

    @Test fun `unbound disables default - both spellings and keys-only`() {
        val r = resolveBindings(
            """{"keybindings":[
               {"keys":"ctrl+shift+f","id":"unbound"},
               {"keys":"ctrl+shift+m","command":"unbound"},
               {"keys":"ctrl+shift+a"}]}"""
        )
        assertTrue(r.table.entries.none { it.chord.key == "f" && it.chord.shift && it.chord.ctrl })
        assertTrue(r.table.entries.none { it.chord.key == "m" })
        assertTrue(r.table.entries.none { it.chord.key == "a" })
    }

    @Test fun `modern two-layer resolves id through actions`() {
        val r = resolveBindings(
            """{"actions":[{"id":"Terminal.FindText","command":"find","name":"Find…"}],
               "keybindings":[{"keys":["ctrl+f"],"id":"Terminal.FindText"}]}"""
        )
        val e = r.table.entry("f", ctrl = true)!!
        assertEquals("find", Command.nameOf(e.command))
    }

    @Test fun `arg-bearing action resolves through actions layer`() {
        val r = resolveBindings(
            """{"actions":[{"id":"x","command":{"action":"findMatch","direction":"next"}}],
               "keybindings":[{"keys":"ctrl+shift+g","id":"x"}]}"""
        )
        val e = r.table.entry("g", ctrl = true, shift = true)!!
        assertEquals(ActionTarget.TERMINAL, e.target)
        assertEquals("next", e.command.strArg("direction"))
    }

    @Test fun `unknown id dropped with warning, app survives`() {
        val r = resolveBindings("""{"keybindings":[{"keys":"ctrl+shift+j","id":"Nope.Nope"}]}""")
        assertTrue(r.table.entries.none { it.chord.key == "j" })
        assertTrue(r.warnings.any { "Nope.Nope" in it })
    }

    @Test fun `multi-chord array warns and skips, single-element array equals string`() {
        val r = resolveBindings(
            """{"keybindings":[
               {"keys":["ctrl+k","ctrl+f"],"id":"whatever"},
               {"keys":["ctrl+shift+b"],"command":"duplicateTab"}]}"""
        )
        assertTrue(r.warnings.any { "multi-chord" in it })
        // ระวัง: ctrl+shift+k (clearBuffer default) มีอยู่ — เช็คให้ตรง chord ของ multi-chord (ctrl+k)
        assertTrue(r.table.entries.none { it.chord.key == "k" && !it.chord.shift && it.chord.ctrl })
        assertEquals("duplicateTab", Command.nameOf(r.table.entry("b", ctrl = true, shift = true)!!.command))
    }

    @Test fun `chord without modifier is dropped, defaults intact`() {
        val r = resolveBindings("""{"keybindings":[{"keys":"a","command":"find"}]}""")
        assertTrue(r.warnings.any { "modifier" in it })
        assertEquals("find", Command.nameOf(r.table.entry("f", ctrl = true, shift = true)!!.command))
    }

    @Test fun `out-of-subset args become UNSUPPORTED not crash`() {
        val r = resolveBindings(
            """{"actions":[{"id":"a","command":{"action":"moveFocus","direction":"parent"}}],
               "keybindings":[{"keys":"ctrl+shift+y","id":"a"}]}"""
        )
        assertEquals(ActionTarget.UNSUPPORTED, r.table.entry("y", ctrl = true, shift = true)!!.target)
    }

    @Test fun `unknown action name is UNSUPPORTED but table survives`() {
        val r = resolveBindings("""{"keybindings":[{"keys":"ctrl+shift+q","command":"notAnAction"}]}""")
        assertEquals(ActionTarget.UNSUPPORTED, r.table.entry("q", ctrl = true, shift = true)!!.target)
        assertEquals("find", Command.nameOf(r.table.entry("f", ctrl = true, shift = true)!!.command))
    }

    // ---------- toJsJson: app↔JS contract (golden) ----------
    @Test fun `golden js table format`() {
        val t = ResolvedTable(listOf(
            ResolvedEntry(KeyChord(true, false, true, "f"), Command.Simple("find"), ActionTarget.TERMINAL),
            ResolvedEntry(KeyChord(true, false, true, "t"), Command.Simple("newTab"), ActionTarget.APP),
            ResolvedEntry(KeyChord(true, false, false, "1"),
                argCommand("adjustFontSize", "delta" to "1"), ActionTarget.APP),
        ))
        assertEquals(
            """[{"ctrl":true,"alt":false,"shift":true,"key":"f","target":"terminal","cmd":{"action":"find"}},""" +
                """{"ctrl":true,"alt":false,"shift":true,"key":"t","target":"app","cmd":{"action":"newTab"}},""" +
                """{"ctrl":true,"alt":false,"shift":false,"key":"1","target":"app","cmd":{"action":"adjustFontSize","delta":"1"}}]""",
            t.toJsJson()
        )
    }

    @Test fun `defaults resolve without warnings`() {
        val r = KeybindingResolver.resolve(Settings())
        assertTrue(r.warnings.isEmpty())
        assertEquals(KeybindingResolver.DEFAULTS.size, r.table.entries.size)
    }
}
