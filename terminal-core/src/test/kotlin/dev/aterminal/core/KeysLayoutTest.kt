package dev.aterminal.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KeysLayout schema gate (round 23 chrome, task 1):
 * presets must be serializable, typo-free against KeyIds.ALL, and the new
 * Settings.keysPreset field must default to "default" for old settings.json files.
 */
class KeysLayoutTest {

    // same policy as SettingsLoader — forward-compat via unknown-key tolerance
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test fun `default preset round-trips through JSON with ignoreUnknownKeys`() {
        val text = json.encodeToString(KeysLayout.serializer(), KeyPresets.default)
        // unknown field injected = forward-compat probe (a future reader must still parse today's blob)
        val withUnknown = text.replaceFirst("{\"buttons\"", "{\"futureField\":42,\"buttons\"")
        // parked (g): replaceFirst silently no-ops if serializer ever reorders fields —
        // without this the probe would decode the untouched text and pass vacuously
        assertNotEquals("injection site vanished - probe is vacuous if the pattern no longer matches", text, withUnknown)
        assertEquals(KeyPresets.default, json.decodeFromString(KeysLayout.serializer(), withUnknown))
    }

    @Test fun `byName resolves known presets and falls back to default`() {
        assertEquals(KeyPresets.default, KeyPresets.byName("default"))
        assertEquals(KeyPresets.compact, KeyPresets.byName("compact"))
        assertEquals(KeyPresets.default, KeyPresets.byName("nope"))
    }

    @Test fun `every shipped button id is in the KeyIds stable set`() {
        val presets = listOf(KeyPresets.default, KeyPresets.compact)
        for (preset in presets) {
            for (b in preset.buttons) {
                assertTrue("unknown id '${b.id}'", KeyIds.ALL.contains(b.id))
                b.popup?.let { assertTrue("unknown popup id '${it.id}'", KeyIds.ALL.contains(it.id)) }
            }
        }
    }

    @Test fun `compact fits in 8 body buttons max`() {
        // fix wave 1 (A1): ⚡ paste joins the preset — contract widened 7 → 8
        assertTrue("compact has ${KeyPresets.compact.buttons.size} buttons",
            KeyPresets.compact.buttons.size <= 8)
    }

    @Test fun `default preset is 18 buttons after pipe-tilde dedupe and paste add`() {
        // ADR 0002 fit-to-width: p_pipe/p_tilde dropped from standalone (they are the popup
        // variants of p_dash/p_slash) — 19 → 17; fix wave 1 (A1) adds ⚡ paste back → 18.
        // KeyIds.ALL still keeps all 20 ids.
        assertEquals(18, KeyPresets.default.buttons.size)
        val standalone = KeyPresets.default.buttons.map { it.id }
        assertTrue("p_pipe must be popup-only", !standalone.contains("p_pipe"))
        assertTrue("p_tilde must be popup-only", !standalone.contains("p_tilde"))
        assertEquals(20, KeyIds.ALL.size)
    }

    @Test fun `every app-kind id ships in the default preset`() {
        // final review A1: paste was wired end-to-end (bar sentinel → routeKey → pasteNow) but
        // present in NO preset = zero entry points in every shipped configuration. Pin the
        // id↔preset link so an app action can never be orphaned again.
        val shipped = KeyPresets.default.buttons.map { it.id }
        assertTrue("KeyIds.APP must be non-empty (empty = vacuous guard, probe lost)", KeyIds.APP.isNotEmpty())
        for (id in KeyIds.APP) {
            assertTrue("app-kind id '$id' must appear in KeyPresets.default (no entry point otherwise)",
                shipped.contains(id))
        }
        // non-vacuity probe: mentally delete the paste button → this must FAIL (guard actually sees the drift)
        val withoutPaste = KeyPresets.default.buttons.filter { it.id != "paste" }.map { it.id }
        assertTrue("probe: preset minus paste must violate the guard above",
            KeyIds.APP.any { !withoutPaste.contains(it) })
        // paste must be app-kind in the preset itself (a key-kind paste would send bytes, not the sentinel)
        assertEquals(KeyKind.app, KeyPresets.default.buttons.first { it.id == "paste" }.kind)
    }

    @Test fun `default popup map is exactly p_dash to p_pipe and p_slash to p_tilde`() {
        val popups = KeyPresets.default.buttons.filter { it.popup != null }
            .associate { it.id to it.popup!!.id }
        assertEquals(mapOf("p_dash" to "p_pipe", "p_slash" to "p_tilde"), popups)
    }

    @Test fun `keysPreset round-trips through SettingsLoader and old files default to default`() {
        assertEquals("compact", SettingsLoader.parse("""{"keysPreset":"compact"}""").keysPreset)
        val rendered = SettingsLoader.render(SettingsLoader.parse("""{"keysPreset":"compact"}"""))
        assertEquals("compact", SettingsLoader.parse(rendered).keysPreset)
        // old settings.json (no keysPreset field at all) must keep working → "default"
        assertEquals("default", SettingsLoader.parse("""{"profiles":{"list":[]}}""").keysPreset)
    }
}
