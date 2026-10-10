# CLAUDE.md — tuxwire-android

Native Android app (Kotlin + Jetpack Compose) for tuxwire, a standalone RSS
reader. The UI imitates the terminal app. All logic lives in the shared Rust
crate `tuxwire-core` (from the `tuxwire` repo), reached through UniFFI.

## Layout

- `rust/` — Cargo workspace: `tuxwire-ffi` (the UniFFI glue over core) and
  `uniffi-bindgen` (helper that writes the Kotlin bindings).
- `app/` — the Android app (Gradle module).
- `scripts/build-rust.sh` — cross-compiles Rust with cargo-ndk and generates
  the Kotlin bindings. Gradle runs it before every build.
- Generated and git-ignored: `app/src/main/jniLibs/`, `app/src/main/kotlin/uniffi/`.

## Rules

- Never merge, push, or delete branches or tags. Shane merges and pushes after
  testing. Feature branches off master; commit in small single-purpose steps.
- Inspect and report first, then wait for answers before building.
- No new dependencies (Rust or Gradle) without asking first.
- `tuxwire-core` is pinned by git `rev` in `rust/Cargo.toml`. Bump it
  deliberately, in its own commit.
- No hostnames, credentials, or private URLs in the repo.
- UI code never hard-codes a colour: use `LocalTuxwireColors` slots.
- Saved articles and notes stay on the device. Sync to the user's own CouchDB
  is optional, off by default, and tucked away in settings.
- Never run `tmux kill-server`; use named sessions and kill only your own.
- Back up a real database before running anything that migrates it.

## Documentation standard

Shane is learning through this code. Every file, class, function, and property
gets a doc comment saying what it is for. Non-obvious blocks get `//` comments
explaining what and why, written for someone new to Kotlin/Rust/Compose.

## Checks

In `rust/`: `cargo test`, `cargo clippy --all-targets -- -D warnings`,
`cargo fmt --check` must all pass before each commit.

At the end of each piece of work: short report (what changed, how to test,
anything surprising), and flag anything worth saving to notes as
title / command / when to use it.
