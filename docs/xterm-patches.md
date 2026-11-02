# Patches to xterm.js

`app/src/main/assets/xterm/xterm.js` is `@xterm/xterm` 5.5.0 (MIT) with these changes, applied by
`tools/patch_xterm_ime.py`. Android WebView reports most soft keyboard input as key code 229, which the
unpatched bundle mishandles.

| Patch | Where | Change |
|---|---|---|
| P1 | `_handleAnyTextareaChanges` | Retry the textarea check up to 5 times, 30 ms apart |
| P2 | `_inputEvent` | Accept finished commits after a key code 229 keydown, skipping a repeat of the same key press |
| P3 | `handlePasteEvent` | Add `preventDefault()` |
| P4 | send sites | Clear the helper textarea after each send, so keyboards do not treat it as an editable text box |
| P5 | `_keyDown` | Reset the repeat guard on each keydown, so `ll`, `//` and `&&` are not dropped |
