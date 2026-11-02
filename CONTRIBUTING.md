# Contributing

Issues and pull requests are welcome.

- Run `./gradlew test :app:assembleDebug` before opening a pull request.
- Include a test with a bug fix where the code allows it.
- Update `app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt` when adding a dependency, font or ported code.
- Change `xterm.js` only through `tools/patch_xterm_ime.py`.
- New settings should trace back to the Windows Terminal settings schema.
- Never commit keystores, private keys or `keystore.properties`.

The `engine/` module is also published as [aterminal-engine](https://github.com/chaisit/aterminal-engine); this repository is its source of truth.

By contributing you agree that your contribution is licensed under the MIT License.
