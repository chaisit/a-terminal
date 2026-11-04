# Changelog

## 0.5.2

- **Update notices now download the APK with one tap.** The "Update available" dialog has a new
  primary button, **Download v.X.Y.Z**, which opens the APK file directly in your browser — no more
  hunting for the asset on the release page. **View release** is still there as a secondary button
  (and remains the only button for releases without a downloadable APK). Installing still happens
  through your browser as before; the app never installs anything itself.

## 0.5.1

- **Pinch-to-zoom has been removed.** The two-finger font-size gesture added in 0.5.0 could not be
  made smooth on real devices — the terminal has to re-layout every text line on each step of the
  gesture, and even after two rounds of optimization it stayed visibly behind your fingers in both
  renderers. Rather than ship something that feels laggy, it is cut for now. Font size still lives
  in **⋮** (**A−** / **A+**), and regular two-finger scrolling and text selection are untouched.

## 0.5.0

- **The top bar is one row now.** The status strip moved into the tab row: session pills, the status
  line, the **∨** new-session menu and the new **⋮** overflow menu all share a single row, and the
  terminal gained the freed rows back. Everything the old strip could do lives in **⋮** — font size,
  keep-CPU-awake, log copy, settings export/import, renderer switch — and your installed version is
  printed at the bottom of that menu.
- **The keys bar always fits the screen.** It now stretches edge to edge, and keys that no longer fit
  fold into a **⋯** popup instead of scrolling out of reach — every key is at most two taps away.
  Rotating the device re-fits the bar. A **⚡ paste** button is back in the bar.
- **Pinch with two fingers to change the font size**, like a map app.
- **Back now sends Escape** while a session is open and the keyboard is down (like Termux's back-key),
  so a stray Back press no longer leaves the app; it still closes the keyboard and dialogs first, and
  you can turn the behaviour off via `backAsEscape` in settings.json.
- **Menus never hide behind the keyboard** — opening any menu closes the on-screen keyboard first.
- **Update check.** A-Terminal now checks GitHub Releases at most once a day and tells you when a new
  version is out, showing the release notes of every version you skipped in one dialog; **View
  release** opens the download page in your browser (the app never installs anything itself). You can
  also check any time from **⋮ → Check for updates**, and switch the automatic check off with
  **🌐 Update checks**.
- With a physical keyboard connected, the keys bar can hide itself to give the grid more room
  (opt in via `hideKeysBarOnHardwareKeyboard` in settings.json).
- Bar menus, popups and dialogs now follow your terminal colour scheme, and the native renderer draws
  Thai upper/lower marks without clipping, keeps table columns straight, and places its scrollbar in
  its own reserved slot.

## 0.4.8

- **The on-screen keyboard only appears when you ask for it.** Tapping the terminal — or starting a
  session, switching tabs, closing a pane — no longer pops it up by itself. The new **⌨ button** at the
  right end of the keys bar is now the only way it opens, and tapping it again closes the keyboard. If
  the keyboard is open when you switch panes, it stays open and follows to the new pane. Forms and
  dialogs keep the system's normal behaviour.
- **⌨ and ⏎ (Enter) now sit in a pinned zone** at the right edge of the keys bar, outside the horizontal
  scroll: always visible, always one tap away — in both the full bar and the compact landscape bar. ⏎
  sends a real terminal Enter and respects a latched CTRL/⇧ exactly like a physical key combination.
- Long-pressing the **A−** button (the hidden diagnostics gesture) now copies a keyboard event log to
  the clipboard in every build, including this one — useful when reporting keyboard bugs. The log
  contains keyboard events only, never terminal content or credentials.

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
