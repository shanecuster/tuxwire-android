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
sharing a page from the browser asks what to do with the link: **read later**,
**save**, or **add as feed** (the add-feed form, with it already checked).
Going back keeps your place in the feed lists, the article list and the saved
list (the saved list also keeps its search and switch); the logo / home button
starts again at the top.
After **refresh all**, every feed that got new articles shows a `*` after its name,
so with a long list you can see where the new articles are. Opening a feed clears
its `*` (whether or not you read anything). The marks are kept in memory only, so
leaving the app starts clean.
Saving a new feed downloads its articles straight away, so it never looks empty.
Importing an OPML file does the same once, for all the feeds it added.
Some sites (OverTake.gg, for one) refuse apps but show their feed in a browser:
save the feed from the browser, then on the add-feed form choose **choose a
file**. The feed is imported once, under a made-up address such as
`import:overtake`, and a refresh leaves it alone; to update it, import a newer
copy of the file (it only adds what is new). Imported feeds are not synced or
exported to OPML.
Every article has a **save** button, and its page lets you add, edit or delete a
note. A saved article's button becomes **un-save**, which always asks first;
a note and a save go together, so un-saving an article with a note also deletes
the note, and the question says so. Deleting a note asks first too.
**Saved articles & notes**, on the home screen, lists them all, notes
first, with a search and a notes-only filter. The same screen (and the import /
export screen) can save everything to one Markdown file through Android's file
picker, so it can go to Google Drive if you choose that. A switch leaves out
saved articles that have no note.
**Read later**, on the home screen, is a list of links to come back to, kept
apart from Saved. Paste a link in its box, or share a page to tuxwire; either
way you are asked every time whether to put it on the list, save it, or cancel.
tuxwire looks up the page's title so the link has a name (if the page cannot be
reached, the address is used). Tapping a link opens it in your browser and marks
it read; it stays on the list, drawn like a read article, until you **drop** it
(which asks first) or **save** it (which keeps it with your saved articles).
**Sync** (home screen, optional) copies saved articles, notes and the Read Later
list to a CouchDB server you run yourself, so your devices share them. It is
off until you turn it on and save; it only runs when you tap **sync now**; which
feed articles you have read is not synced unless you turn on the separate
**read marks** switch. The address must be `https://` (plain `http://` only for this device),
and the password is stored encrypted with the Android Keystore, never in plain
text. **forget everything** (asks first) wipes the saved settings.
A second, separate switch (**feed list**, off by default) also copies your list
of feeds, so a feed you add on this phone appears in the terminal app and the
other way round. Feeds from other devices are added here; a feed another device
*removed* is only removed here if you tap **yes, remove** (its articles are kept
either way). Renames, topic changes and Reddit sources are not synced.
A third switch (**read marks**, off by default, and only while sync is on) also
shares which articles you have opened, so one you read on your phone shows as
read in the terminal app and the other way round. The address of each article
opened in the last 90 days is sent to your server only if it is on; nothing is
deleted from the server, and an article cannot be marked unread again.

## How it fits together

- **UI:** Kotlin + Jetpack Compose.
- **Engine:** `tuxwire-core`, the same Rust crate the terminal app uses, called
  through [UniFFI](https://mozilla.github.io/uniffi-rs/).
- **Privacy:** the only permission is `INTERNET`. Everything is stored on the
  device. Syncing to your own server is optional and off by default. The full
  policy is in [PRIVACY.md](PRIVACY.md).

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

### Build tools

Every tool that shapes the finished APK, and where its version is set. Change
one and the APK may differ, so change the table and the source together.

| Tool | Version | Set in |
| --- | --- | --- |
| JDK | 21 (OpenJDK; not enforced, no download plugin) | installed on the machine |
| Gradle | 9.6.0 (checksum-verified) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.2.10 | `gradle/libs.versions.toml` |
| SDK Build-Tools | 36.0.0 | `buildToolsVersion` in `app/build.gradle.kts` |
| Android platform | API 37 | `compileSdk` in `app/build.gradle.kts` |
| NDK | 30.0.16248370 | `pinnedNdkVersion` in `app/build.gradle.kts` |
| Rust | 1.97.0 | `rust/rust-toolchain.toml` |
| cargo-ndk | 4.1.2 (checked by the build) | `CARGO_NDK_VERSION` in `scripts/build-rust.sh` |
| Crates | exact versions | `rust/Cargo.lock` (`--locked`) |
| Signing (`apksigner`) | Build-Tools 34, not used by the build itself | see Releasing |

Then open this folder in Android Studio (**Open**, not New Project) and press
Run. Gradle calls `scripts/build-rust.sh`, which builds the Rust library for
each CPU type and generates the Kotlin bindings. Everyday builds make
`arm64-v8a` and `x86_64`; any release build (a Gradle task with "release" in
its name) also makes `armeabi-v7a`. Override with
`-PtuxwireAbis="arm64-v8a x86_64"`.

## Releasing

### Every release

1. **Version:** raise `versionCode` (it must go up every release) and
   `versionName` in `app/build.gradle.kts`, and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. **Merge, then tag** the release `v<versionName>` (for example `v0.1.1`) and
   push the tag, because F-Droid builds from tags. Never move or reuse a tag; a
   fix is a new version and a new tag.
3. **Check the release build.** `./gradlew assembleRelease` must succeed and
   `app/build/outputs/apk/release/` must hold an APK with `arm64-v8a`,
   `armeabi-v7a` and `x86_64` libraries (`unzip -l <apk> | grep '\.so'`). Google
   Play requires 16 KB page alignment for updates from 1 February 2027:
   `zipalign -c -P 16 -v 4 <apk>` (from the SDK's `build-tools`) must say
   "Verification successful", and Android Studio's **Build → Analyze APK** shows
   `16 KB` in the Alignment column of every 64-bit `.so`.

### Google Play: the signed bundle

Play takes an Android App Bundle (`.aab`), signed with an **upload key**; Play
re-signs what users download with its own app signing key.

1. **Make the upload key once**, outside this folder, and back it up. If it is
   lost you cannot upload updates with it.

   ```bash
   mkdir -p ~/keys
   keytool -genkeypair -v -keystore ~/keys/tuxwire-upload.jks \
     -alias tuxwire-upload -keyalg RSA -keysize 4096 -validity 10000
   ```

2. **Point the build at it.** Copy `keystore.properties.example` to
   `keystore.properties` (git-ignored) and fill in `storeFile` (a full path) and
   `keyAlias`. To keep the passwords out of any file, leave them out and set
   them in the terminal you build from (bash: `read -rs TUXWIRE_STORE_PASSWORD;
   export TUXWIRE_STORE_PASSWORD`; fish: `read -s -x TUXWIRE_STORE_PASSWORD`),
   and the same for `TUXWIRE_KEY_PASSWORD`.
3. **Build the bundle:** `./gradlew bundleRelease`. It lands in
   `app/build/outputs/bundle/release/app-release.aab`. Without
   `keystore.properties` the same command still works but the result is
   unsigned, which Play will reject. If the file exists but a detail (such as a
   password) is missing, the build prints a `WARNING` naming it and the result
   is unsigned too. Debug builds, including Android Studio's Run button, never
   need the key and ignore all of this.
4. **In Play Console,** use the public link to `PRIVACY.md` as the privacy
   policy. The app collects no data itself (see that file) and asks only for
   `INTERNET`.

Code shrinking (R8) is off on purpose; the reason is next to `isMinifyEnabled`
in `app/build.gradle.kts`.

### F-Droid

- **Metadata** lives in `fastlane/metadata/android/en-US/`. Add phone
  screenshots as `phoneScreenshots/1.png`, `2.png`, ... in that folder.
- **No JDK download plugin:** the project builds with whichever JDK is
  installed, as F-Droid's build server requires. Do not commit
  `gradle/gradle-daemon-jvm.properties` (it is git-ignored); it holds JDK
  download links.
- **Signing keys:** F-Droid signs with its own key unless the build is verified
  reproducible and your signature is used. Android refuses to update an app with
  one signed by a different key, so a phone cannot move between the F-Droid and
  Play builds without reinstalling. Decide before the first upload whether that
  matters to you.

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
