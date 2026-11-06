# Roadmap

What we intend to build next, in rough order within each area. This is a plan, not a contract:
real-device feedback regularly reorders it, and some items below are gated on a small technical
spike that could still change their shape (or kill them).

## tmux interop (the active frontier)

The 0.5.12 release covers session continuity, titles, and the prefix keys mode. Next:

- **Clipboard bridge (OSC 52)** plus honest answers to terminal queries (colors, device attributes),
  so copy from a remote vim/tmux reaches the Android clipboard instead of vanishing.
- **Session and window browser**: a touch-driven list of tmux sessions and windows to switch by tap,
  built on the exec channel the version probe already uses.
- **Bells and window activity as Android notifications** — the setup snippet already reserves a
  commented hook section for this. Needs a small spike to find where the signal survives when no
  client is attached.
- **Older scrollback on demand**: pull a pane's history beyond what the app kept, via `capture-pane`.
- **Status strip from tmux control mode** (and possibly live pane previews) — depends on a protocol
  subset spike; we will only promise this if control mode proves dependable.

## Input and UI

- **Profile editor pickers** for the color scheme and the keys-bar preset, which today can only be
  chosen by editing `settings.json` by hand.
- **Key chord edge cases**: Win-key and `Ctrl+Shift+<digit>` combinations some paths cannot deliver,
  and chords arriving while an IME is composing.
- **Predictive back** gesture support as the Android platform requires it.

## Renderers and engine

- **Recover the scrollback**, not just the screen, when the WebView renderer process dies (today the
  session survives but the visible history does not).
- **ZWJ emoji sequences** (family/flag sequences) drawn as single glyphs — currently split into
  separate characters, matching xterm.js.
- **Publish the native renderer** (`engine-render`) alongside the parser in the
  [aterminal-engine](https://github.com/chaisit/aterminal-engine) library.

Want something prioritized? Open an issue — device-specific reports with a model name move things
the most.
