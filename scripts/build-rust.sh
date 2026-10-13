#!/usr/bin/env bash
# build-rust.sh -- compile the Rust half of the app and generate the Kotlin
# bridge code. Gradle runs this automatically before every Android build (see
# the `buildRust` task in app/build.gradle.kts); you can also run it by hand:
#
#     scripts/build-rust.sh
#
# It does two jobs:
#
#   1. Compile `tuxwire-ffi` (and through it `tuxwire-core`) into a native
#      library, `libtuxwire_ffi.so`, once for each CPU type the app supports,
#      and put the results where Android packages them:
#          app/src/main/jniLibs/<cpu type>/libtuxwire_ffi.so
#
#   2. Run UniFFI's bindgen over the library to write the Kotlin file that lets
#      the app call into it:
#          app/src/main/kotlin/uniffi/tuxwire_ffi/tuxwire_ffi.kt
#
# Neither output is committed to git; both are rebuilt from the Rust source.
#
# Settings (all optional, passed as environment variables):
#   TUXWIRE_ABIS      CPU types to build, space separated.
#                     Default: "arm64-v8a x86_64"
#                       arm64-v8a    real phones (your Pixel)
#                       x86_64       the Android Studio emulator on a PC
#                     Also supported: armeabi-v7a (old 32-bit phones), x86.
#                     A release build for the stores should build all of them:
#                       TUXWIRE_ABIS="arm64-v8a armeabi-v7a x86_64" scripts/build-rust.sh
#   TUXWIRE_MIN_API   Lowest Android version supported (API level). Default: 26
#   TUXWIRE_SKIP_NDK  If set to 1, skip step 1 and only write the Kotlin
#                     bindings. Handy for checking the bridge without the NDK.
#   ANDROID_HOME      Where the Android SDK is. If unset, it is read from
#                     `sdk.dir` in local.properties, which Android Studio writes.
#   TUXWIRE_NDK_VERSION  The exact NDK version to use, e.g. 30.0.16248370
#                     (Gradle sets this from `pinnedNdkVersion` in
#                     app/build.gradle.kts). If that version is not installed
#                     the build stops with a message instead of quietly using
#                     another one, so builds stay reproducible.
#   ANDROID_NDK_HOME  Which NDK folder to use. If set, it wins over everything.
#                     If neither this nor TUXWIRE_NDK_VERSION is set (running
#                     the script by hand), the newest installed NDK is used.

# `set -e`  stop at the first command that fails.
# `set -u`  treat use of an undefined variable as an error (catches typos).
# `set -o pipefail`  a failure anywhere in a `a | b` pipeline counts as failure.
set -euo pipefail

# --- Find the project root ---------------------------------------------------
# `${BASH_SOURCE[0]}` is the path of this script file; `dirname` strips the file
# name and `cd ... && pwd` turns it into an absolute path. The project root is
# the folder above `scripts/`. This lets the script work no matter which folder
# it is started from (Gradle starts it from the project root, you might not).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"

# --- Make sure cargo can be found --------------------------------------------
# Android Studio is usually started from the desktop launcher, which does NOT
# read your shell's startup files, so ~/.cargo/bin (where rustup puts `cargo`)
# may be missing from PATH. Add it explicitly.
export PATH="$HOME/.cargo/bin:$PATH"

# --- Settings ----------------------------------------------------------------
# `${NAME:-default}` means "the value of NAME, or the default if NAME is unset
# or empty".
ABIS="${TUXWIRE_ABIS:-arm64-v8a x86_64}"
MIN_API="${TUXWIRE_MIN_API:-26}"

# --- Small helper: print an error and quit -----------------------------------
# `>&2` sends the message to the "error" output stream; `exit 1` stops the
# script with a failure code, which makes Gradle report the build as failed.
fail() {
    echo "build-rust.sh: error: $*" >&2
    exit 1
}

# --- Check the tools we need are installed -----------------------------------
# `command -v NAME` prints where NAME is, and fails if it is not installed.
# `&>/dev/null` hides the output; we only care about success or failure.
command -v cargo &>/dev/null \
    || fail "cargo not found. Install Rust from https://rustup.rs and try again."

# --- Step 1: compile for Android ---------------------------------------------
if [ "${TUXWIRE_SKIP_NDK:-0}" != "1" ]; then
    command -v cargo-ndk &>/dev/null \
        || fail "cargo-ndk not found. Install it with:  cargo install cargo-ndk"

    # Locate the Android SDK: environment variable first, then local.properties.
    if [ -z "${ANDROID_HOME:-}" ] && [ -f "$ROOT/local.properties" ]; then
        # `sed -n 's/^sdk\.dir=//p'` prints only the line starting with
        # "sdk.dir=", with that prefix removed -- leaving just the path.
        ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -n 1)"
    fi
    [ -n "${ANDROID_HOME:-}" ] \
        || fail "Android SDK not found. Open the project in Android Studio once (it writes local.properties), or set ANDROID_HOME."

    # Locate the NDK. Three ways, in order:
    #   1. ANDROID_NDK_HOME is already set: use it as it is.
    #   2. TUXWIRE_NDK_VERSION is set (the normal case, set by Gradle): use
    #      exactly that version, and stop if it is not installed.
    #   3. Neither: take the newest installed. `ls -1` lists one folder per
    #      line, `sort -V` sorts version numbers properly (so 30.0 comes after
    #      9.0), `tail -n 1` keeps the last, which is the newest.
    if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${TUXWIRE_NDK_VERSION:-}" ]; then
        # `-d` tests that the path is a folder.
        [ -d "$ANDROID_HOME/ndk/$TUXWIRE_NDK_VERSION" ] \
            || fail "NDK $TUXWIRE_NDK_VERSION is not installed in $ANDROID_HOME/ndk. In Android Studio: Settings > Languages & Frameworks > Android SDK > SDK Tools > NDK (Side by side) > tick Show Package Details, then install exactly that version."
        ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$TUXWIRE_NDK_VERSION"
    elif [ -z "${ANDROID_NDK_HOME:-}" ]; then
        NDK_VERSION="$(ls -1 "$ANDROID_HOME/ndk" 2>/dev/null | sort -V | tail -n 1)"
        [ -n "$NDK_VERSION" ] \
            || fail "No NDK found in $ANDROID_HOME/ndk. In Android Studio: Settings > Languages & Frameworks > Android SDK > SDK Tools > NDK (Side by side)."
        ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
    fi
    # `export` makes the variables visible to the programs we start below
    # (cargo-ndk reads them).
    export ANDROID_HOME ANDROID_NDK_HOME
    echo "build-rust.sh: using NDK at $ANDROID_NDK_HOME"

    # Build the list of `-t <cpu type>` arguments for cargo-ndk, one per ABI.
    # An array (`( )`) keeps each argument separate, even if one had spaces.
    NDK_ARGS=()
    for abi in $ABIS; do
        NDK_ARGS+=(-t "$abi")
    done

    # Run cargo-ndk from inside rust/ (where Cargo.toml lives):
    #   --platform   lowest Android API level the library must run on
    #   -o           where to copy the finished .so files
    #   build ...    the ordinary `cargo build`, in release (optimized) mode
    #   --locked     refuse to change Cargo.lock; the build must use exactly the
    #                dependency versions that were committed (reproducible)
    # The parentheses run this in a subshell so the `cd` does not affect the
    # rest of the script.
    (
        cd "$ROOT/rust"
        cargo ndk "${NDK_ARGS[@]}" \
            --platform "$MIN_API" \
            -o "$ROOT/app/src/main/jniLibs" \
            build --release --locked --package tuxwire-ffi
    )
fi

# --- Step 2: generate the Kotlin bridge --------------------------------------
# UniFFI reads the *description* of the exported functions that is embedded in
# the compiled library. That description is identical for every CPU type, so we
# read it from a normal build for THIS computer rather than from an Android
# build. This is quick, and it works even when the NDK step was skipped.
(
    cd "$ROOT/rust"
    cargo build --locked --package tuxwire-ffi

    # `rm -rf` then `mkdir -p`: start from a clean folder so a function we
    # removed from Rust cannot linger in an old generated file.
    OUT="$ROOT/app/src/main/kotlin/uniffi"
    rm -rf "$OUT"
    mkdir -p "$ROOT/app/src/main/kotlin"

    # `cargo run -p uniffi-bindgen -- generate ...` starts our little helper
    # program (rust/uniffi-bindgen) with these arguments:
    #   --library    the compiled glue library to read
    #   --language   write Kotlin (UniFFI also supports Swift, Python, ...)
    #   --out-dir    where to put the generated file
    #   --no-format  skip the optional ktlint pretty-printer (not installed)
    cargo run --locked --quiet --package uniffi-bindgen -- generate \
        --library target/debug/libtuxwire_ffi.so \
        --language kotlin \
        --out-dir "$ROOT/app/src/main/kotlin" \
        --no-format
)

echo "build-rust.sh: done."
