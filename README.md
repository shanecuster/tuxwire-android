# tuxwire-android

A native Android RSS reader that looks and feels like a terminal app: a
monospace font, bordered panels, and the same colour themes
as [tuxwire](https://github.com/shanecuster/tuxwire). No Termux needed.

Status: early development. Milestone 2 is in: a home screen listing every feed
(tap a feed's topic to see just that topic), then articles, with a
slide-across drill-down, add and remove feeds, refresh, themes, and
open-in-browser. Tapping an article opens a plain-text summary first (articles
with no summary open straight in the browser). Screens have a pinned top bar
and bottom bar so the app can be used one-handed, and the logo changes
colour each time you return to the home screen. Feed lists can be imported
from and exported to OPML files (the format other feed readers use). Adding a
feed accepts a blog's address as well as a feed address: tuxwire finds the feed
(skipping comment feeds). tuxwire also appears in Android's Share menu, so
sharing a page from the browser opens the add-feed form with it already checked.
Saving a new feed downloads its articles straight away, so it never looks empty.
Importing an OPML file does the same once, for all the feeds it added.
Every article has a **save** button, and its page lets you add, edit or delete a
note. A saved article's button becomes **un-save**, which always asks first;
a note and a save go together, so un-saving an article with a note also deletes
the note, and the question says so. Deleting a note asks first too.
**Saved articles & notes**, on the home screen, lists them all, notes
first, with a search and a notes-only filter. The same screen (and the import /
export screen) can save everything to one Markdown file through Android's file
picker, so it can go to Google Drive if you choose that. A switch leaves out
saved articles that have no note.
**Sync** (home screen, optional) copies saved articles and notes to a CouchDB
server you run yourself, so your devices share them. It is off until you turn
it on and save; it only runs when you tap **sync now**; read/unread is not
synced. The address must be `https://` (plain `http://` only for this device),
and the password is stored encrypted with the Android Keystore, never in plain
text. **forget everything** (asks first) wipes the saved settings.

## How it fits together

- **UI:** Kotlin + Jetpack Compose.
- **Engine:** `tuxwire-core`, the same Rust crate the terminal app uses, called
  through [UniFFI](https://mozilla.github.io/uniffi-rs/).
- **Privacy:** the only permission is `INTERNET`. Everything is stored on the
  device. Syncing to your own server is optional and off by default.

## Building

You need Android Studio (with the SDK and the NDK from SDK Manager → SDK
Tools), Rust via [rustup](https://rustup.rs), and cargo-ndk:

```bash
cargo install cargo-ndk --version 4.1.2 --locked
```

The build is pinned so it comes out the same every time:

- **NDK:** exactly the version in `pinnedNdkVersion` in `app/build.gradle.kts`
  (currently `30.0.16248370`). Install that version in SDK Manager (tick
  *Show Package Details* under *NDK (Side by side)*). The build stops with a
  message if it is missing, rather than using a different one.
- **cargo-ndk:** version `4.1.2`, the version this app is built and tested
  with. Check yours with `cargo ndk --version`.
- **Rust:** `rust/rust-toolchain.toml` names the exact compiler version and the
  four Android targets; rustup downloads them the first time you build, so you
  do not need `rustup target add`.
- **Dependencies:** `Cargo.lock` is committed and the build uses `--locked`;
  the shared engine is pinned to one commit in `rust/Cargo.toml`.

Then open this folder in Android Studio (**Open**, not New Project) and press
Run. Gradle calls `scripts/build-rust.sh`, which builds the Rust library for
each CPU type and generates the Kotlin bindings. Everyday builds make
`arm64-v8a` and `x86_64`; any release build (a Gradle task with "release" in
its name) also makes `armeabi-v7a`. Override with
`-PtuxwireAbis="arm64-v8a x86_64"`.

## Releasing

- **F-Droid metadata** lives in `fastlane/metadata/android/en-US/`. Add phone
  screenshots as `phoneScreenshots/1.png`, `2.png`, ... in that folder. Add a
  `changelogs/<versionCode>.txt` for every release.
- **Version:** raise `versionCode` (it must go up every release) and
  `versionName` in `app/build.gradle.kts`, then tag the release `v<versionName>`
  (for example `v0.1.0`), because F-Droid builds from tags.
- **No JDK download plugin:** the project builds with whichever JDK is
  installed, as F-Droid's build server requires.

Rust-only checks:

```bash
cd rust
cargo test
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

## Licence

MIT. The bundled font is JetBrains Mono (unmodified) under the SIL OFL 1.1; see
`third_party/fonts/`.
