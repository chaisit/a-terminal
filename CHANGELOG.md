# Changelog

## 0.4.7

- **CTRL and SHIFT in the keys bar are real modifiers now.** Hold CTRL and type — from the keys bar or
  the on-screen keyboard — to send Ctrl+letter while held, exactly like a physical keyboard; a quick tap
  arms the modifier for the next key only, and tapping again disarms it. ⇧ works the same way (the next
  letter is typed uppercase). This covers every letter plus `[ \ ]` (Ctrl+\[ = Esc), so combos like
  Ctrl+D work without adding a button per combo. Input that cannot carry a modifier (Thai text, pastes,
  arrow keys) is passed through untouched and the modifier is never applied to a later key by mistake.
- WebView panes gained 2–3 rows of height: the size calculation no longer reserves space for a bar that
  has not existed for several releases.
- The native (experimental) renderer now matches WebView for bold ANSI colours, the filled block cursor,
  hidden text (SGR 8) and overlines.
- Fixed characters typed just before pressing a modifier occasionally being duplicated or lost — both
  renderers now resolve the on-screen keyboard's in-progress composition instead of letting a later
  commit re-send it.

## 0.4.6

- The **New session** menu has a **Renderer** item: tap it to switch new panes between the WebView engine
  (default) and the native Kotlin engine, without hand-editing `settings.json`. Existing panes keep the
  renderer they were opened with. The native renderer is experimental — WebView remains the default and
  is the better-rendered of the two today.

## 0.4.5

Everything added since 0.3.0:

- SSH sessions survive backgrounding: while a session is open a foreground service keeps the process and
  network alive, and an app-level keepalive stops real networks from silently dropping idle connections.
- Device rotation, window resize and split layout changes no longer close running sessions.
- Private keys are encrypted at rest (AES-256-GCM under an Android Keystore key) instead of stored plainly.
- Re-pin flow: when a pinned host's key changes you are shown the old and the new fingerprint and can accept
  the new key instead of being locked out of the profile. Auto-reconnect never prompts — a mid-session key
  change stays a hard error, so a real interception is not papered over.
- Pinned hosts are no longer probed before login, which removes the failed-login noise servers used to see.
- New **Copy connection log** menu item to collect a connection timeline for debugging.

## 0.3.0

First public release.
