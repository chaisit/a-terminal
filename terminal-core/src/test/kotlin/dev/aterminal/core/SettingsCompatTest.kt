package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * compat gate (round 7): ไฟล์ settings.json ของ round ≤6 + blob ของ WT จริง ต้อง parse ได้
 * และ render ต้องไม่ churn รูป legacy (keys อยู่เป็น string, command อยู่เป็น string)
 * SHIPPED_JSON mirror app/src/main/assets/settings.defaults.json — ถ้า asset เปลี่ยน ให้ sync ที่นี่ด้วย
 */
class SettingsCompatTest {

    private val shippedJson = """
    {
      "${'$'}schema-note": "subset ของ WT settings.json — trace: refs/terminal/doc/cascadia/profiles.schema.json; unknown keys ignored (forward-compat แบบ WT)",
      "defaultProfile": "scaffold-demo",
      "profiles": {
        "defaults": {
          "fontFace": "Cascadia Mono, JetBrains Mono, monospace",
          "fontSize": 14,
          "historySize": 9001,
          "snapOnInput": true,
          "closeOnExit": "automatic",
          "colorScheme": "Campbell"
        },
        "list": [
          { "guid": "scaffold-demo", "name": "Echo (scaffold)", "type": "echo", "hidden": false },
          { "guid": "termux-local", "name": "Termux (local)", "type": "termux", "hidden": false,
            "note": "ต้องมี Termux + sshd บน localhost:8022 — ดู docs/setup.md" },
          { "guid": "lab-host", "name": "Lab (host sshd)", "type": "ssh", "host": "10.0.2.2", "port": 22,
            "user": "kai", "authMethod": "key", "keyAlias": null }
        ]
      },
      "keybindings": [
        { "keys": "ctrl+shift+t", "command": "newTab" },
        { "keys": "ctrl+shift+w", "command": "closeTab" },
        { "keys": "ctrl+shift+f", "command": "find" }
      ],
      "copyOnSelect": false,
      "trimPaste": true
    }
    """.trimIndent()

    @Test fun `shipped round-6-era file parses with same values`() {
        val s = SettingsLoader.parse(shippedJson)
        assertEquals(3, s.profiles.list.size)
        assertEquals("termux", s.profiles.list[1].type)
        assertEquals(3, s.keybindings.size)
        assertEquals(listOf("ctrl+shift+t", "ctrl+shift+w", "ctrl+shift+f"),
            s.keybindings.map { it.keys.single() })
        assertEquals(Command.Simple("newTab"), s.keybindings[0].command)
        assertEquals(emptyList<ActionEntry>(), s.actions)
    }

    @Test fun `render keeps legacy shapes - no array churn`() {
        val s = SettingsLoader.parse(shippedJson)
        val out = SettingsLoader.render(s)
        assertTrue(out.contains("\"keys\":\"ctrl+shift+t\""))    // string เดิม ไม่ใช่ ["..."]
        assertTrue(out.contains("\"command\":\"newTab\""))       // string เดิม ไม่ใช่ object
        assertFalse(out.contains("[\"ctrl+shift+t\"]"))
        // round-trip: value เท่าเดิมทุก field (data class equality)
        assertEquals(s, SettingsLoader.parse(out))
    }

    @Test fun `two-chord array renders back as array`() {
        val s = SettingsLoader.parse("""{"keybindings":[{"keys":["ctrl+k","ctrl+f"],"command":"find"}]}""")
        val out = SettingsLoader.render(s)
        assertTrue(out.contains("\"keys\":[\"ctrl+k\",\"ctrl+f\"]"))
    }

    @Test fun `real WT blob parses without exception (ignoreUnknownKeys)`() {
        // fragment จาก WT settings.json จริง (legacy form: command object + fields ที่เราไม่มี)
        val wt = """
        {
          "defaultProfile": "{2c4de342-...}",
          "actions": [
            { "command": { "action": "findMatch", "direction": "next" }, "id": "Terminal.FindNextMatch" },
            { "command": { "action": "copy", "singleLine": false }, "id": "Terminal.CopyToClipboard",
              "name": { "key": "CommandNames/FindMatch", "value": "Copy" } }
          ],
          "keybindings": [
            { "command": { "action": "copy", "singleLine": false }, "keys": "ctrl+shift+c" },
            { "keys": "ctrl+shift+h", "command": "history" },
            { "command": "duplicateTab", "keys": "ctrl+shift+d" }
          ],
          "profiles": { "defaults": { "bellStyle": ["window"], "fontAttributes": [] } },
          "newTabMenu": [ { "type": "remainingProfiles" } ],
          "showTabsInTitlebar": true
        }
        """.trimIndent()
        val s = SettingsLoader.parse(wt) // ต้องไม่ throw
        assertEquals(2, s.actions.size)
        assertEquals("Terminal.FindNextMatch", s.actions[0].id)
        assertEquals("next", (s.actions[0].command as Command.Arg).args["direction"]?.toString()?.trim('"'))
        // legacy object-command ใน keybinding เก็บ args ครบ (WT ของจริงเขียนแบบนี้)
        val copy = s.keybindings[0]
        assertEquals("copy", Command.nameOf(copy.command!!))
        assertEquals("false", (copy.command as Command.Arg).args["singleLine"].toString())
        // "history" = action WT จริงที่อยู่นอก subset ของเรา → ยัง parse ได้ (Simple), resolver จัดการเป็น UNSUPPORTED
        assertEquals(Command.Simple("history"), s.keybindings[1].command)
        val r = KeybindingResolver.resolve(s)
        assertEquals(ActionTarget.UNSUPPORTED, r.table.entries.find { it.chord.key == "h" }!!.target)
    }

    // 0.2.0: default = false (ไฟล์ที่ไม่มี field นี้ก็ปิด) — เปิดได้ด้วยการตั้งค่าเอง
    @Test fun `keepCpuAwake defaults false and parses true`() {
        assertFalse(SettingsLoader.parse("""{"profiles":{"list":[]}}""").keepCpuAwake)
        assertTrue(SettingsLoader.parse("""{"keepCpuAwake":true,"profiles":{"list":[]}}""").keepCpuAwake)
    }

    @Test fun `autoPasteClipboard defaults false and overrides to true`() {
        assertFalse(SettingsLoader.parse("""{}""").autoPasteClipboard)
        assertTrue(SettingsLoader.parse("""{"autoPasteClipboard": true}""").autoPasteClipboard)
        // round-trip ผ่าน render (field ใหม่อยู่ใน canonical subset ของเรา)
        val off = SettingsLoader.parse("""{"autoPasteClipboard": false}""")
        assertEquals(off, SettingsLoader.parse(SettingsLoader.render(off)))
    }

    @Test fun `unknown top-level fields survive round trip as unknown (dropped on render is fine)`() {
        // render ของเรา = canonical subset ของเรา (unknown หายไป = "normalize" ตามที่ design ไว้ round 6 — E2)
        val s = SettingsLoader.parse("""{"showTabsInTitlebar": true, "keybindings": []}""")
        assertEquals(s, SettingsLoader.parse(SettingsLoader.render(s)))
    }
}
