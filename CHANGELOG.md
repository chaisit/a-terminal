# Changelog

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
