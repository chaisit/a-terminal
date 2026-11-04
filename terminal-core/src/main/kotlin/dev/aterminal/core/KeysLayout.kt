package dev.aterminal.core

import kotlinx.serialization.Serializable

/**
 * Keys Bar layout model (round 23 chrome, task 1) — non-WT extension by precedent:
 * Windows Terminal has no on-screen key bar concept at all (hardware keyboard only),
 * so nothing here traces to profiles.schema.json. Readers without this field keep
 * working via Settings.keysPreset default = "default".
 *
 * A layout is a flat list of [KeyButton]s in left-to-right order. Buttons reference
 * stable ids from [KeyIds]; the byte mapping (id → escape sequence / raw letter)
 * lives in the app funnel unchanged — this file is schema only.
 */
@Serializable
enum class KeyKind {
    /** sends a key/sequence through the funnel (esc, tab, ctrl_c, arrows, raw letters, punctuation) */
    key,
    /** sticky modifier (tap = latch, hold = press-while-tapping) — SHIFT/CTRL */
    modifier,
    /** app-side action, no terminal bytes (e.g. paste from clipboard) */
    app,
}

@Serializable
data class KeyButton(
    val id: String,
    val kind: KeyKind = KeyKind.key,
    /** display text; null = app picks a label for [id] (funnel keeps today's glyphs as fallback) */
    val label: String? = null,
    /** long-press popup button (its own id must also be in [KeyIds]); null = no popup */
    val popup: KeyButton? = null,
)

@Serializable
data class KeysLayout(
    val buttons: List<KeyButton>,
)

/**
 * Stable id set — ids are the persistence contract: renaming one silently breaks
 * user-authored layouts, so add ids in a new task, never repurpose an old one.
 * Arrow→Home/End popups are deferred: they need new ids (home/end) added later,
 * not invented here.
 */
object KeyIds {
    val ALL: Set<String> = setOf(
        "esc", "tab", "ctrl_c", "shift", "ctrl",
        "up", "down", "left", "right", "paste",
        "raw_a", "raw_e", "raw_l", "raw_r", "raw_u", "raw_z",
        "p_pipe", "p_dash", "p_slash", "p_tilde",
    )

    /**
     * app-kind ids (KeyKind.app — action ฝั่ง app, ไม่มี terminal bytes). Currently just paste.
     * KeysLayoutTest pins every id here to appear in [KeyPresets.default]: an app action wired
     * end-to-end but present in no shipped preset has NO entry point (round 23 final review A1 —
     * that exact drift shipped once for ⚡; this set + guard makes it impossible again).
     */
    val APP: Set<String> = setOf("paste")
}

/**
 * Built-in presets selectable via Settings.keysPreset.
 * [default] reproduces the shipped bar's key set (ExtraKeysBar, round 22 order), with the
 * round 23 fit-to-width dedupe applied: 18 body buttons, p_pipe/p_tilde reachable only as
 * long-press popups (ADR 0002 — no horizontal scroll, so every button must fit the screen).
 * The ⚡ paste button (KeyKind.app) sits right after ⌃C — fix wave 1 added it because the
 * sentinel path was fully wired but NO preset shipped the button (final review A1). On narrow
 * screens the fold may push it into the ⋯ popup (one tap; ruling accepted for this round).
 * Popup map v1 = exactly { p_dash → p_pipe, p_slash → p_tilde } — the punctuation
 * neighbours share a thumb reach; anything wider waits for real usage data.
 */
object KeyPresets {

    val default: KeysLayout = KeysLayout(
        listOf(
            KeyButton("esc", label = "ESC"),
            KeyButton("tab", label = "TAB"),
            KeyButton("ctrl_c", label = "⌃C"),
            KeyButton("paste", kind = KeyKind.app, label = "⚡"),
            KeyButton("shift", kind = KeyKind.modifier, label = "SHIFT"),
            KeyButton("ctrl", kind = KeyKind.modifier, label = "CTRL"),
            KeyButton("up", label = "↑"),
            KeyButton("down", label = "↓"),
            KeyButton("right", label = "→"),
            KeyButton("left", label = "←"),
            KeyButton("raw_a", label = "A"),
            KeyButton("raw_e", label = "E"),
            KeyButton("raw_l", label = "L"),
            KeyButton("raw_r", label = "R"),
            KeyButton("raw_u", label = "U"),
            KeyButton("raw_z", label = "Z"),
            // p_pipe/p_tilde are popup-only (round 23 dedupe, ADR 0002 fit-to-width): they live as
            // the long-press variants of p_dash/p_slash below — standalone copies wasted two slots.
            KeyButton("p_dash", label = "-", popup = KeyButton("p_pipe", label = "|")),
            KeyButton("p_slash", label = "/", popup = KeyButton("p_tilde", label = "~")),
        )
    )

    /** one-hand layout: emergency + paste + modifiers + vertical arrows only (≤8 body buttons by contract, fix wave 1) */
    val compact: KeysLayout = KeysLayout(
        listOf(
            KeyButton("esc", label = "ESC"),
            KeyButton("tab", label = "TAB"),
            KeyButton("ctrl_c", label = "⌃C"),
            KeyButton("paste", kind = KeyKind.app, label = "⚡"),
            KeyButton("shift", kind = KeyKind.modifier, label = "SHIFT"),
            KeyButton("ctrl", kind = KeyKind.modifier, label = "CTRL"),
            KeyButton("up", label = "↑"),
            KeyButton("down", label = "↓"),
        )
    )

    /** unknown name → default (forward-compat: a preset written by a future app must not blank the bar) */
    fun byName(name: String): KeysLayout = when (name) {
        "compact" -> compact
        "default" -> default
        else -> default
    }
}
