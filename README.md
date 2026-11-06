# A-Terminal

[![ci](https://github.com/chaisit/a-terminal/actions/workflows/ci.yml/badge.svg)](https://github.com/chaisit/a-terminal/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A Windows Terminal style SSH client for Android, with tabs, split panes and a
`settings.json` that follows the Windows Terminal schema.

[ภาษาไทย](#ภาษาไทย)

## Features

- SSH through Apache MINA SSHD: password and private key login, host key pinning on first use
- Tabs and split panes, with sessions restored after the app restarts
- Automatic reconnect with backoff; a changed host key is always refused
- Two terminal engines: xterm.js in a WebView (default) and a native Kotlin engine ([aterminal-engine](https://github.com/chaisit/aterminal-engine))
- Unicode 11 widths in both engines, so emoji and Thai line up under tmux
- tmux interop: a profile `commandline` like `tmux new -A -s main` so every redial rejoins the same
  session; tab titles follow `set-titles`; a copy-only `.tmux.conf` snippet; and a **tmux keys** mode —
  prefix button plus a quick-keys row (`% c d z n p w x [ s ,` and arrows)
- Hardware keyboards, Windows Terminal style keybindings, find, and selection
- Local shell through Termux, by connecting to its `sshd`

Android 10 (API 29) or later. Planned work is in [ROADMAP.md](ROADMAP.md).

## Install

Download `app-release.apk` from [Releases](https://github.com/chaisit/a-terminal/releases) and check its
SHA-256 and signing certificate against the release notes. Google Play does not carry this app.

Your device must allow installs from your browser or file manager.

## Security notes

- **Private keys are encrypted at rest** (AES-256-GCM under an Android Keystore key) before being written to the app's private storage. Use a dedicated key for this device, and a passphrase where you can.
- Saved passwords and host key pins use `EncryptedSharedPreferences`, backed by the Android Keystore.
- App data is excluded from Android backup and device transfer. Use **Export settings.json** to move your profiles, then import your keys again.
- Auto-paste is off by default. Turning on `autoPasteClipboard` sends every clipboard change to the active shell.

Report vulnerabilities as described in [SECURITY.md](SECURITY.md).

## Known limitations

- If the WebView renderer dies, the pane recovers and the session stays connected, but the screen and scrollback are lost.
- Emoji joined with ZWJ, such as family sequences, are drawn as separate characters, as in xterm.js.
- Code comments are still largely in Thai, except in `engine/`.
- Under heavy `monkey` stress on an Android 15 emulator, the window can stay blank after a home gesture collides with an app start. System Settings does the same. Pressing Home and reopening recovers.

## Build

JDK 17 or later and the Android SDK (platform 35) are required.

```sh
./gradlew test :app:assembleDebug
```

To sign a release build, copy `keystore.properties.example` to `keystore.properties`, fill it in, and run
`./gradlew :app:assembleRelease`. Without that file the release APK is unsigned. Never commit a keystore.

`app/src/main/assets/xterm/xterm.js` is xterm.js 5.5.0 with IME fixes for Android WebView. Do not edit it by
hand: change `tools/patch_xterm_ime.py`, which applies the patches to the upstream bundle. See
[docs/xterm-patches.md](docs/xterm-patches.md).

## License

[MIT](LICENSE). Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and the
app shows them under **New session → Open source licenses**.

Much of this code was written with an AI coding agent under human direction and testing.

A-Terminal is not affiliated with Microsoft. Windows Terminal is a trademark of Microsoft.

## ภาษาไทย

A-Terminal คือแอป SSH บน Android สไตล์ Windows Terminal มี tab, split pane และ `settings.json` ตาม schema ของ
Windows Terminal รองรับ Android 10 ขึ้นไป ดาวน์โหลด APK ได้จากหน้า Releases และควรตรวจ SHA-256 กับ certificate
ตาม release notes ก่อนติดตั้ง

ข้อควรรู้: private key ถูกเข้ารหัส (AES-256-GCM ด้วยกุญแจใน Android Keystore) ก่อนเก็บในพื้นที่ส่วนตัวของแอป จึงควรใช้ key แยกสำหรับเครื่องนี้ และแอปไม่ส่งข้อมูลไป
backup ผู้ใช้จึงต้อง Export settings.json เองเมื่อย้ายเครื่อง
