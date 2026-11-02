package dev.aterminal.core

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsTest {

    private fun content(e: kotlinx.serialization.json.JsonElement?): String =
        (e as? JsonPrimitive)?.content ?: "‹not a primitive›"

    @Test fun `command string parses to Simple and round-trips as string`() {
        val s = SettingsLoader.parse("""{"actions":[{"id":"a","command":"find"}]}""")
        assertEquals(Command.Simple("find"), s.actions[0].command)
        assertTrue(SettingsLoader.render(s).contains("\"command\":\"find\""))
    }

    @Test fun `command object parses to Arg, unknown args absorbed into args`() {
        val s = SettingsLoader.parse(
            """{"actions":[{"id":"c","command":{"action":"copy","singleLine":true,"wtTomorrowFlag":1}}]}"""
        )
        val c = s.actions[0].command as Command.Arg
        assertEquals("copy", c.name)
        assertEquals("true", content(c.args["singleLine"]))
        assertEquals("1", content(c.args["wtTomorrowFlag"])) // flag อนาคต = เก็บไว้ใน args ไม่หาย
        // render คืน object + action flatten (ไม่มี nested "args" map — ตาม schema)
        val rendered = SettingsLoader.render(s)
        assertTrue(rendered.contains("\"action\":\"copy\"") && !rendered.contains("\"args\""))
    }

    @Test fun `bad command type is Unknown and renders as unbound`() {
        val s = SettingsLoader.parse("""{"actions":[{"id":"x","command":123}]}""")
        assertEquals(Command.Unknown, s.actions[0].command)
        assertTrue(SettingsLoader.render(s).contains("\"command\":\"unbound\""))
    }

    @Test fun `dispatcher json round-trip via CommandCodec`() {
        val c = CommandCodec.parseCommandJson("""{"action":"splitPane","split":"right","size":"0.6"}""")
        assertEquals("splitPane", Command.nameOf(c))
        assertEquals("right", c.strArg("split"))
        assertEquals(0.6, c.doubleArg("size")!!, 1e-9)
        assertEquals("""{"action":"splitPane","split":"right","size":"0.6"}""", CommandCodec.renderCommand(c))
    }

    @Test fun `CommandCodec garbage in = Unknown out (no throw)`() {
        assertEquals(Command.Unknown, CommandCodec.parseCommandJson("not json {{"))
        assertEquals(Command.Unknown, CommandCodec.parseCommandJson("[1,2]"))
    }

    @Test fun `catalog covers both targets and rejects outside subset`() {
        assertEquals(ActionTarget.TERMINAL, ActionCatalog.targetOf("markMode"))
        assertEquals(ActionTarget.TERMINAL, ActionCatalog.targetOf("clearBuffer"))
        assertEquals(ActionTarget.APP, ActionCatalog.targetOf("splitPane"))
        assertEquals(ActionTarget.APP, ActionCatalog.targetOf("adjustFontSize"))
        // ชื่อจริงใน WT enum แต่ยังไม่ support รอบเรา = UNSUPPORTED
        assertEquals(ActionTarget.UNSUPPORTED, ActionCatalog.targetOf("addMark"))
        assertEquals(ActionTarget.UNSUPPORTED, ActionCatalog.targetOf("commandPalette"))
    }

    @Test fun `validate checks enum args per action`() {
        assertEquals(ActionTarget.APP, ActionCodec.validate(argCommand("moveFocus", "direction" to "down")))
        assertEquals(ActionTarget.UNSUPPORTED,
            ActionCodec.validate(argCommand("moveFocus", "direction" to "nextInOrder"))) // นอก subset 5
        assertEquals(ActionTarget.APP, ActionCodec.validate(argCommand("resizePane", "direction" to "up")))
        assertEquals(ActionTarget.UNSUPPORTED,
            ActionCodec.validate(argCommand("splitPane", "split" to "up"))) // mobile 2-pane cap
        assertEquals(ActionTarget.TERMINAL, ActionCodec.validate(argCommand("findMatch", "direction" to "prev")))
        assertEquals(ActionTarget.UNSUPPORTED,
            ActionCodec.validate(argCommand("findMatch", "direction" to "sideways")))
        assertEquals(ActionTarget.APP, ActionCodec.validate(Command.Simple("moveFocus"))) // no args = defaults
    }
}
