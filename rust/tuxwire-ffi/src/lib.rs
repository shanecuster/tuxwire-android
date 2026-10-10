//! `tuxwire-ffi`: the bridge between the Android app (Kotlin) and `tuxwire-core`
//! (Rust).
//!
//! ## How the bridge works
//!
//! Everything marked `#[uniffi::export]`, `#[derive(uniffi::Record)]` and so on
//! below becomes callable from Kotlin. At build time a tool called
//! `uniffi-bindgen` reads the compiled library and writes a Kotlin file
//! (`tuxwire_ffi.kt`) containing matching classes and functions, so from the
//! Kotlin side it looks like ordinary Kotlin code:
//!
//! ```kotlin
//! val themes = listThemes()            // calls the Rust function below
//! val db = Database.open(path)         // calls Database::open
//! ```
//!
//! ## What is exported in this first milestone
//!
//! Three small things, each chosen because it proves a different part of the
//! toolchain works on a real phone before we build the real screens:
//!
//! 1. **Theme palettes** (`list_themes`) -- pure data, no I/O. Proves the
//!    Kotlin <-> Rust bridge itself works.
//! 2. **A database handle** (`Database`) -- opens tuxwire's SQLite database and
//!    runs its migrations. Proves SQLite compiled for Android.
//! 3. **A feed preview** (`preview_feed`) -- fetches a feed over HTTPS. Proves
//!    networking and TLS work on Android.
//!
//! ## Why mirror types instead of exporting core's types directly?
//!
//! `tuxwire-core` knows nothing about UniFFI (it must stay usable by the
//! terminal app too), so it cannot carry UniFFI's `derive` attributes. Instead
//! this crate defines small "mirror" types (`RgbColor`, `ThemePalette`, ...)
//! and converts core's types into them with `From` implementations. The glue
//! stays in this crate and core stays clean.

// This one line generates the low-level plumbing UniFFI needs (the C-compatible
// entry points the Kotlin side calls through). It must appear exactly once, at
// the crate root, before the other exports are used.
uniffi::setup_scaffolding!();

// `Arc` is Rust's thread-safe shared pointer, and `Mutex` allows only one
// thread at a time to use the value inside it. Both are explained where they
// are used, in `Database` below.
use std::path::Path;
use std::sync::{Arc, Mutex};

use tuxwire_core::storage::Storage;
use tuxwire_core::theme::{BUILTIN_THEMES, DEFAULT_THEME, Palette, Rgb, picker_order};

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/// The one error type the Kotlin side sees.
///
/// Rust functions that can fail return `Result<T, E>`. Across the bridge, UniFFI
/// turns the `Err` case into a Kotlin exception (`FfiException`) that the app
/// catches with an ordinary `try`/`catch`.
///
/// There is a single variant for now: tuxwire-core reports failures as
/// `anyhow::Error` (a catch-all error carrying a chain of explanatory
/// messages), and the app only needs those messages to show the user.
///
/// The field is called `detail`, not `message`, on purpose: Kotlin's built-in
/// exception class already has a property named `message`, and a generated
/// subclass declaring its own would clash with it.
#[derive(Debug, uniffi::Error)]
pub enum FfiError {
    /// Something failed in tuxwire-core. `detail` is the full human-readable
    /// explanation, including each underlying cause.
    Failed {
        /// The explanation to show or log.
        detail: String,
    },
}

/// How an `FfiError` prints. Required for any error type: it is the text shown
/// when the error is displayed or logged.
impl std::fmt::Display for FfiError {
    /// Writes the error's detail text.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // `match` takes the value apart by variant; there is only one for now.
        match self {
            FfiError::Failed { detail } => write!(f, "{detail}"),
        }
    }
}

/// Marks `FfiError` as a proper Rust error type. All the required behaviour
/// already comes from `Debug` + `Display` above, so the body is empty.
impl std::error::Error for FfiError {}

/// Lets `?` convert core's `anyhow::Error` into an `FfiError` automatically.
///
/// With this in place, a function returning `Result<_, FfiError>` can call a
/// core function that returns `anyhow::Result<_>` and simply write `?` after
/// it: on failure, `?` calls this `from` to do the conversion.
impl From<anyhow::Error> for FfiError {
    /// Wraps an `anyhow::Error`, keeping its whole chain of causes.
    fn from(err: anyhow::Error) -> FfiError {
        // `{err:#}` is the "alternate" format for anyhow errors: it prints the
        // top-level message followed by every underlying cause, separated by
        // colons, e.g. "failed to open database at /x: unable to open file".
        FfiError::Failed {
            detail: format!("{err:#}"),
        }
    }
}

// ---------------------------------------------------------------------------
// Theme palettes
// ---------------------------------------------------------------------------

/// One colour as three bytes (0-255). Mirror of core's `Rgb`.
///
/// `#[derive(uniffi::Record)]` makes this a plain data class on the Kotlin side
/// (a `data class RgbColor(val r: UByte, ...)`), copied across the bridge by
/// value.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RgbColor {
    /// Red channel, 0-255.
    pub r: u8,
    /// Green channel, 0-255.
    pub g: u8,
    /// Blue channel, 0-255.
    pub b: u8,
}

/// Converts core's colour type into the mirror type.
impl From<Rgb> for RgbColor {
    /// Copies the three channels across.
    fn from(c: Rgb) -> RgbColor {
        RgbColor {
            r: c.r,
            g: c.g,
            b: c.b,
        }
    }
}

/// The eleven colour slots every tuxwire theme fills in. Mirror of core's
/// `Palette`; see that type for what each slot is used for.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemePalette {
    /// The whole app's background.
    pub background: RgbColor,
    /// Box borders around panels.
    pub panel_border: RgbColor,
    /// Normal text.
    pub text_primary: RgbColor,
    /// De-emphasized text: meta lines, hints, the footer.
    pub text_muted: RgbColor,
    /// Even fainter text (skipped articles).
    pub text_dim: RgbColor,
    /// Unread (and "new") article titles; the banner.
    pub accent_unread: RgbColor,
    /// Read article titles.
    pub accent_read: RgbColor,
    /// Skipped article titles.
    pub accent_skipped: RgbColor,
    /// Saved article titles and note previews.
    pub accent_saved: RgbColor,
    /// Errors.
    pub accent_breaking: RgbColor,
    /// The selected row's highlight, key labels, focused fields.
    pub accent_selected: RgbColor,
}

/// Converts core's `Palette` into the mirror type, slot by slot.
impl From<Palette> for ThemePalette {
    /// Converts every slot with `RgbColor::from` (written `.into()` here: the
    /// compiler picks the `From<Rgb> for RgbColor` impl above from context).
    fn from(p: Palette) -> ThemePalette {
        ThemePalette {
            background: p.background.into(),
            panel_border: p.panel_border.into(),
            text_primary: p.text_primary.into(),
            text_muted: p.text_muted.into(),
            text_dim: p.text_dim.into(),
            accent_unread: p.accent_unread.into(),
            accent_read: p.accent_read.into(),
            accent_skipped: p.accent_skipped.into(),
            accent_saved: p.accent_saved.into(),
            accent_breaking: p.accent_breaking.into(),
            accent_selected: p.accent_selected.into(),
        }
    }
}

/// A named theme and its colours, as shown in the theme picker.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemeInfo {
    /// Display name, e.g. "Everforest". Also the key the app saves to remember
    /// the user's choice.
    pub name: String,
    /// The theme's colours.
    pub colors: ThemePalette,
}

/// Every built-in theme, in the order the picker shows them (alphabetical,
/// ignoring case) -- the same eight palettes and the same order as the
/// terminal app, because both read the same table in `tuxwire-core`.
#[uniffi::export]
pub fn list_themes() -> Vec<ThemeInfo> {
    // `picker_order()` returns indices into the built-in table, already sorted
    // by name. `.into_iter().map(..).collect()` walks those indices, turns each
    // into a `ThemeInfo`, and gathers the results into a `Vec`.
    picker_order()
        .into_iter()
        .map(|index| {
            // Borrow the table entry (`&`): we only read it, never change it.
            let builtin = &BUILTIN_THEMES[index];
            ThemeInfo {
                name: builtin.name.to_string(),
                colors: builtin.colors.into(),
            }
        })
        .collect()
}

/// The name of the theme used when the user has not chosen one yet.
#[uniffi::export]
pub fn default_theme_name() -> String {
    DEFAULT_THEME.to_string()
}

/// The version of this glue library, shown on the app's "about" panel so it is
/// obvious which Rust build is running.
#[uniffi::export]
pub fn core_version() -> String {
    // `env!` is read while compiling: it bakes in the version number from this
    // crate's Cargo.toml.
    env!("CARGO_PKG_VERSION").to_string()
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

/// A handle to tuxwire's SQLite database, usable from Kotlin.
///
/// `#[derive(uniffi::Object)]` marks this as an *object*: Kotlin holds a
/// reference to it (not a copy) and calls methods on it. It lives as long as
/// Kotlin keeps a reference.
///
/// ## Why the `Mutex`
///
/// Kotlin may call methods from several threads at once, so UniFFI requires
/// every object to be safe to share between threads. `Storage` wraps a SQLite
/// connection, which can be *moved* to another thread but must not be *used*
/// by two threads at the same instant. A `Mutex` ("mutual exclusion") fixes
/// that: a thread must `lock()` it before touching what is inside, and other
/// threads wait their turn.
#[derive(uniffi::Object)]
pub struct Database {
    /// The real database handle from tuxwire-core, behind the lock.
    storage: Mutex<Storage>,
}

#[uniffi::export]
impl Database {
    /// Opens the database file at `path`, creating it (and running any pending
    /// migrations) if needed.
    ///
    /// On Android the app passes a file inside its private storage directory
    /// (`Context.filesDir`); this crate deliberately never decides where files
    /// live. `Storage::open_at` is the function tuxwire-core gained when it was
    /// split away from the terminal app for exactly this purpose.
    ///
    /// Returns an `Arc<Database>`: UniFFI objects are handed to Kotlin through
    /// `Arc`, Rust's thread-safe reference-counted pointer. The object is freed
    /// when the last reference (Kotlin's or Rust's) goes away.
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Database>, FfiError> {
        // `Path::new(&path)` views the text as a file path without copying it.
        // The `?` on the next line means: if opening fails, convert the error
        // with `From<anyhow::Error> for FfiError` and return it immediately.
        let storage = Storage::open_at(Path::new(&path))?;

        // Wrap in the lock, then in the shared pointer, and hand it back.
        Ok(Arc::new(Database {
            storage: Mutex::new(storage),
        }))
    }

    /// How many articles are stored. A fresh database says 0.
    pub fn article_count(&self) -> Result<i64, FfiError> {
        // `lock()` waits for the lock and returns a guard that unlocks
        // automatically when it goes out of scope at the end of this function.
        // It returns a `Result` because a lock is "poisoned" if a thread
        // panicked while holding it; we report that as an error instead of
        // crashing the whole app.
        let storage = self.storage.lock().map_err(|_| FfiError::Failed {
            detail: "database lock was poisoned by an earlier panic".to_string(),
        })?;

        // Ask core for the count; `?` converts any failure as before.
        Ok(storage.article_count()?)
    }
}

// ---------------------------------------------------------------------------
// Feed preview
// ---------------------------------------------------------------------------

/// A quick look at a feed, used to check a URL before subscribing to it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FeedPreview {
    /// The feed's own title, or the URL when the feed has none.
    pub title: String,
    /// How many entries (articles) the feed currently lists.
    pub entry_count: u32,
    /// Titles of up to the first five entries, newest first as the feed gives
    /// them.
    pub latest_titles: Vec<String>,
}

/// Downloads and parses the feed at `url` and returns a short summary.
///
/// ## What `async` means here
///
/// Fetching a page waits on the network, and a phone app must never freeze its
/// screen while waiting. An `async fn` returns immediately with a "future" (a
/// promise of a result) that does the waiting without blocking a thread. UniFFI
/// turns this into a Kotlin `suspend fun`, which the app calls from a coroutine
/// and which simply pauses that coroutine until the answer arrives.
///
/// `async_runtime = "tokio"` tells UniFFI to run the future on the tokio async
/// runtime, which reqwest (the HTTP library inside tuxwire-core) requires.
#[uniffi::export(async_runtime = "tokio")]
pub async fn preview_feed(url: String) -> Result<FeedPreview, FfiError> {
    // `.await` pauses this function until the download and parse finish; the
    // thread is free to do other work meanwhile. The `?` converts a failure
    // (bad URL, no network, malformed XML) into an `FfiError`.
    let feed = tuxwire_core::fetchers::rss::fetch_feed(&url).await?;

    // The feed's title is optional in the feed format. `.map(..)` pulls out the
    // text if present; `.unwrap_or_else(..)` falls back to the URL if not.
    let title = feed.title.map(|t| t.content).unwrap_or_else(|| url.clone());

    // Remember the total before we look at individual entries.
    let entry_count = feed.entries.len() as u32;

    // An iterator chain, read top to bottom:
    //   .iter()       walk the entries by reference
    //   .take(5)      stop after five
    //   .map(..)      turn each entry into its title text ("(untitled)" if none)
    //   .collect()    gather the results into a Vec<String>
    let latest_titles = feed
        .entries
        .iter()
        .take(5)
        .map(|entry| {
            entry
                .title
                .as_ref()
                .map(|t| t.content.clone())
                .unwrap_or_else(|| "(untitled)".to_string())
        })
        .collect();

    Ok(FeedPreview {
        title,
        entry_count,
        latest_titles,
    })
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

// `#[cfg(test)]`: compiled only by `cargo test`, never into the phone library.
#[cfg(test)]
mod tests {
    use super::*;

    /// The theme list has all eight built-ins, in alphabetical order, and the
    /// default theme is one of them. This is the property the app's picker
    /// relies on.
    #[test]
    fn themes_are_listed_alphabetically_and_include_the_default() {
        let themes = list_themes();

        // Eight palettes ship with tuxwire.
        assert_eq!(themes.len(), 8);

        // Sorting a lowercase copy of the names must not change their order.
        let names: Vec<String> = themes.iter().map(|t| t.name.to_lowercase()).collect();
        let mut sorted = names.clone();
        sorted.sort();
        assert_eq!(names, sorted);

        // The default theme's name appears in the list.
        assert!(themes.iter().any(|t| t.name == default_theme_name()));
    }

    /// Colours survive the trip through the mirror types unchanged.
    #[test]
    fn colours_convert_without_changing() {
        let rgb = Rgb {
            r: 0x2d,
            g: 0x35,
            b: 0x3b,
        };
        assert_eq!(
            RgbColor::from(rgb),
            RgbColor {
                r: 0x2d,
                g: 0x35,
                b: 0x3b
            }
        );
    }

    /// A brand-new database file opens, migrates, and reports zero articles.
    /// This is the same call the Android app makes on first launch.
    #[test]
    fn fresh_database_opens_and_is_empty() {
        // A unique path in the system temp directory (no extra dependency
        // needed): process id + a nanosecond timestamp.
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir =
            std::env::temp_dir().join(format!("tuxwire-ffi-test-{}-{unique}", std::process::id()));
        let path = dir.join("tuxwire.db");

        // `open` creates the missing directory itself (that is part of
        // `Storage::open_at`'s job), so no setup is needed.
        let db = Database::open(path.to_string_lossy().into_owned()).unwrap();
        assert_eq!(db.article_count().unwrap(), 0);

        // Clean up after ourselves; ignore errors, the OS clears temp anyway.
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A failure inside core becomes an `FfiError` whose text keeps the cause.
    #[test]
    fn opening_an_impossible_path_gives_a_readable_error() {
        // A file cannot be created inside a path that passes through an
        // existing *file*; the parent here is /dev/null.
        let result = Database::open("/dev/null/cannot/create/tuxwire.db".to_string());
        let message = match result {
            Ok(_) => panic!("expected an error"),
            Err(err) => err.to_string(),
        };
        assert!(!message.is_empty());
    }

    /// Feed preview rejects a nonsense URL cleanly instead of panicking.
    /// `#[tokio::test]` runs this async test on a tokio runtime. A refused
    /// connection to 127.0.0.1 needs no real network (the project's rule for
    /// offline-friendly tests).
    #[tokio::test]
    async fn preview_of_an_unreachable_feed_is_an_error() {
        let result = preview_feed("http://127.0.0.1:9/feed.xml".to_string()).await;
        assert!(result.is_err());
    }
}
