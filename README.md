# tuxwire-android

A native Android RSS reader that looks and feels like a terminal app: a
monospace Nerd Font, bordered panels, and the same colour themes and icon sets
as [tuxwire](https://github.com/shanecuster/tuxwire). No Termux needed.

Status: early development. Milestone 2 is in: a home screen listing every feed
(tap a feed's topic to see just that topic), then articles, with a
slide-across drill-down, add and remove feeds, refresh, themes, and
open-in-browser. Tapping an article opens a plain-text summary first (articles
with no summary open straight in the browser).

## How it fits together

- **UI:** Kotlin + Jetpack Compose.
- **Engine:** `tuxwire-core`, the same Rust crate the terminal app uses, called
  through [UniFFI](https://mozilla.github.io/uniffi-rs/).
- **Privacy:** the only permission is `INTERNET`. Everything is stored on the
  device. Syncing to your own server is optional and off by default.

## Building

You need Android Studio (with the SDK, NDK, and CMake from SDK Manager →
SDK Tools), Rust via [rustup](https://rustup.rs), and cargo-ndk:

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
```

Then open this folder in Android Studio (**Open**, not New Project) and press
Run. Gradle calls `scripts/build-rust.sh`, which builds the Rust library for
each CPU type and generates the Kotlin bindings.

Rust-only checks:

```bash
cd rust
cargo test
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

## Licence

MIT. The bundled font is JetBrains Mono Nerd Font under the SIL OFL; see
`third_party/fonts/`.
