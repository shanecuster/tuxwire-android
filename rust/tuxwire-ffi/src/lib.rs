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
//! ## What is exported
//!
//! * **Theme palettes** (`list_themes`, `default_theme_name`) -- pure data.
//! * **The database** (`Database`) -- one object the app opens once. Through it
//!   the app reads topics, feeds and articles, adds and removes feeds, marks
//!   articles read, saves settings, and refreshes feeds from the network.
//! * **Read Later** (`list_read_later`, `add_read_later`, `save_link`, ...,
//!   plus `is_web_address` and `fetch_link_title` for pasted links) -- the
//!   list of links to read, which sync shares through the main sync switch.
//! * **A feed preview** (`preview_feed`) -- looks up the feed for an address
//!   (a feed address, or a web page that has a feed) so the "add feed" screen
//!   can check it before saving.
//!
//! All the real work (SQLite, fetching, parsing) lives in `tuxwire-core`. The
//! functions here are thin: lock the database, call core, convert the answer
//! into the plain records below.
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
use std::sync::{Arc, Mutex, MutexGuard};

use tuxwire_core::export::{ExportOptions, render_saved_markdown};
use tuxwire_core::fetch_report::FetchReport;
use tuxwire_core::fetchers::{SourceType, configured_sources, fetch_all};
use tuxwire_core::models::Article;
use tuxwire_core::opml::{parse_opml, write_opml};
use tuxwire_core::storage::{Feed, FeedEntry, ReadLaterItem, Scope, Storage};
use tuxwire_core::sync::SyncConfig;
use tuxwire_core::sync::feeds::{feed_key, is_syncable_url, sync_feeds};
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
// Records the app reads
// ---------------------------------------------------------------------------

/// A topic, as shown on the first screen: a name plus how many feeds it holds
/// and how many unread articles those feeds have.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TopicInfo {
    /// The topic's name, e.g. "linux".
    pub name: String,
    /// How many feeds are filed under this topic.
    pub feed_count: u32,
    /// Unread articles across those feeds.
    pub unread: u32,
}

/// A feed, as shown on the second screen.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FeedInfo {
    /// Database ID, used to remove the feed.
    pub id: i64,
    /// Display name.
    pub name: String,
    /// The feed's address; also what identifies its articles.
    pub url: String,
    /// The topic it is filed under.
    pub topic: String,
    /// How many of its articles are unread.
    pub unread: u32,
}

/// An article, as shown in the article list.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ArticleInfo {
    /// Database ID, used to mark it read.
    pub id: i64,
    /// Headline.
    pub title: String,
    /// The article's own web address (what "open in browser" opens).
    pub url: String,
    /// Name of the feed it came from.
    pub source: String,
    /// When it was published, as seconds since 1 January 1970 UTC. A plain
    /// number crosses the bridge easily; Kotlin turns it into a date.
    pub published: i64,
    /// Whether it has been read.
    pub read: bool,
    /// Whether it has been saved.
    pub saved: bool,
    /// Whether the article has a summary to show. The app uses this to decide
    /// what a tap does: open the summary screen if there is one, otherwise go
    /// straight to the browser. (The summary text itself is fetched only when
    /// the summary screen opens, so long lists stay light.)
    pub has_summary: bool,
}

/// What an OPML import did, for the message shown to the person.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OpmlReport {
    /// Feeds that were new and have been added.
    pub added: u32,
    /// Feeds skipped because they were already in the list (including repeats
    /// inside the file itself).
    pub already_there: u32,
    /// Entries skipped because they could not be used (for example an address
    /// that is not `http` or `https`).
    pub ignored: u32,
}

/// One article in full, for the summary screen.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ArticleDetail {
    /// Database ID.
    pub id: i64,
    /// Headline.
    pub title: String,
    /// The article's own web address (what "open in browser" opens).
    pub url: String,
    /// Name of the feed it came from.
    pub source: String,
    /// When it was published, as seconds since 1 January 1970 UTC.
    pub published: i64,
    /// The plain-text summary, or `None` if the feed sent none.
    pub summary: Option<String>,
    /// Whether the article has been saved.
    pub saved: bool,
    /// The person's note on the article, or `None` if there is none.
    pub note: Option<String>,
}

/// One saved article, for the Saved screen: the article's basics plus the
/// dates and the note.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SavedArticle {
    /// Database ID.
    pub id: i64,
    /// Headline.
    pub title: String,
    /// The article's own web address (what "open in browser" opens).
    pub url: String,
    /// Name of the feed it came from.
    pub source: String,
    /// When it was published, as seconds since 1 January 1970 UTC.
    pub published: i64,
    /// When it was saved, as seconds since 1970. Articles saved before the
    /// app recorded this fall back to their publish time, so it is never
    /// missing.
    pub saved_at: i64,
    /// The note, or `None` if the article has none.
    pub note: Option<String>,
    /// When the note was last written, as seconds since 1970, or `None`.
    pub noted_at: Option<i64>,
}

/// Builds the app's feed records, each with its unread count.
///
/// `only_topic` of `Some(name)` keeps just the feeds filed under that topic;
/// `None` keeps every feed. Shared by [`Database::list_all_feeds`] and
/// [`Database::list_feeds_in_topic`] so the two cannot drift apart.
fn feed_infos(storage: &Storage, only_topic: Option<&str>) -> Result<Vec<FeedInfo>, FfiError> {
    // A loop with `?` is used (rather than `.map(..)`) because counting can
    // fail, and `?` is the simple way to stop at the first failure.
    let mut feeds = Vec::new();
    for feed in storage.list_feeds()? {
        // `if let Some(..) && ..` runs only when a topic filter was given
        // and this feed is filed somewhere else; such feeds are skipped.
        if let Some(topic) = only_topic
            && feed.topic != topic
        {
            continue;
        }
        let unread = storage.unread_count(&Scope::Feeds(vec![feed.url.clone()]))? as u32;
        feeds.push(FeedInfo {
            id: feed.id,
            name: feed.name,
            url: feed.url,
            topic: feed.topic,
            unread,
        });
    }
    Ok(feeds)
}

/// Converts a stored article into the record the app shows.
impl From<&Article> for ArticleInfo {
    /// Copies the fields the list needs. Takes a reference (`&Article`) because
    /// the caller still owns the article and only we need to read it.
    fn from(article: &Article) -> ArticleInfo {
        ArticleInfo {
            id: article.id,
            title: article.title.clone(),
            url: article.url.clone(),
            source: article.source.clone(),
            // `timestamp()` gives seconds since 1970 for a chrono date-time.
            published: article.timestamp.timestamp(),
            read: article.read,
            saved: article.saved,
            has_summary: article.summary.is_some(),
        }
    }
}

/// Converts a stored article into the full record for the summary screen.
impl From<&Article> for ArticleDetail {
    /// Copies the fields the summary screen shows.
    fn from(article: &Article) -> ArticleDetail {
        ArticleDetail {
            id: article.id,
            title: article.title.clone(),
            url: article.url.clone(),
            source: article.source.clone(),
            published: article.timestamp.timestamp(),
            summary: article.summary.clone(),
            saved: article.saved,
            note: article.note.clone(),
        }
    }
}

/// Converts a stored article into the record the Saved screen shows.
impl From<&Article> for SavedArticle {
    /// Copies the fields the Saved screen needs.
    fn from(article: &Article) -> SavedArticle {
        SavedArticle {
            id: article.id,
            title: article.title.clone(),
            url: article.url.clone(),
            source: article.source.clone(),
            published: article.timestamp.timestamp(),
            // `unwrap_or` supplies the publish time when `saved_at` is empty.
            saved_at: article.saved_at.unwrap_or(article.timestamp).timestamp(),
            // A note that is only spaces counts as no note, matching the export.
            note: article.note.clone().filter(|text| !text.trim().is_empty()),
            noted_at: article.noted_at.map(|moment| moment.timestamp()),
        }
    }
}

/// What one refresh did, so the app can show a one-line result.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RefreshSummary {
    /// How many feeds were tried.
    pub attempted: u32,
    /// How many of those failed.
    pub failed: u32,
    /// How many articles were new (not seen before).
    pub new_articles: u32,
    /// True when every feed failed because the network was unreachable, the
    /// best sign the device is offline.
    pub offline: bool,
    /// One line per failed feed ("Name: why"), for showing or logging.
    pub messages: Vec<String>,
    /// The addresses of the feeds that got at least one new article, each once,
    /// so the feed list can mark them. Empty when nothing was new.
    pub feeds_with_new: Vec<String>,
}

/// Turns core's fetch tally into the record the app shows.
impl From<&FetchReport> for RefreshSummary {
    /// Copies the counts across. `as u32` converts from `usize`; the numbers
    /// are far too small for that to ever overflow.
    fn from(report: &FetchReport) -> RefreshSummary {
        RefreshSummary {
            attempted: report.attempted as u32,
            failed: report.failed as u32,
            new_articles: report.new_article_ids.len() as u32,
            offline: report.is_offline(),
            messages: report.failure_messages.clone(),
            // The tally has no database to ask which feeds the new articles
            // belong to; `Database::refresh` fills this in afterwards.
            feeds_with_new: Vec::new(),
        }
    }
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

/// Helpers used only inside this crate (not exported to Kotlin).
impl Database {
    /// Takes the lock and returns a guard giving access to the `Storage`.
    ///
    /// The guard unlocks automatically when it goes out of scope, so callers
    /// simply let it drop at the end of a block. `lock()` returns a `Result`
    /// because a lock is "poisoned" if a thread panicked while holding it; we
    /// report that as an error instead of crashing the whole app.
    fn lock(&self) -> Result<MutexGuard<'_, Storage>, FfiError> {
        self.storage.lock().map_err(|_| FfiError::Failed {
            detail: "database lock was poisoned by an earlier panic".to_string(),
        })
    }

    /// Looks an article up by ID, turning "not found" into a readable error
    /// (an associated function: it takes the already-locked `Storage`).
    fn existing_article(storage: &Storage, id: i64) -> Result<Article, FfiError> {
        storage.article_by_id(id)?.ok_or_else(|| FfiError::Failed {
            detail: "that article no longer exists".to_string(),
        })
    }

    /// Fetches the given feeds and stores what comes back.
    ///
    /// This is the shared body of `refresh_all` and `refresh_feed`. It is split
    /// into three steps so the database lock is **not** held during the slow
    /// network step: holding it would freeze every screen that reads the
    /// database (and a held lock cannot safely be kept across an `.await`).
    ///
    /// 1. (caller) choose which feeds to refresh.
    /// 2. Download them all, with no lock held.
    /// 3. Take the lock again and store the results.
    async fn refresh(&self, feeds: Vec<Feed>) -> Result<RefreshSummary, FfiError> {
        // Step 2: turn the stored feeds into fetchers, then download them. No
        // lock is held here, so the app stays responsive and can still read
        // the database while this waits on the network.
        let sources = configured_sources(feeds.iter().map(Feed::to_source_config).collect());
        let outcomes = fetch_all(&sources).await;

        // Step 3: lock once, store every outcome, and tally the results. The
        // inner block makes the guard drop as soon as storing is done.
        let mut report = FetchReport::default();
        let origins;
        {
            let storage = self.lock()?;
            for outcome in outcomes {
                report.record_outcome(&storage, outcome)?;
            }
            // Still under the lock: look up which feeds the new articles came
            // from, so the app can mark them.
            origins = storage.article_origins(&report.new_article_ids)?;
        }

        // Turn the origins into a sorted list of feed addresses, each once. A
        // `BTreeSet` drops duplicates and keeps the order stable; an article
        // stored before addresses were recorded has none and is skipped.
        let addresses: std::collections::BTreeSet<String> = origins
            .into_iter()
            .filter_map(|origin| origin.feed_url)
            .collect();
        let mut summary = RefreshSummary::from(&report);
        summary.feeds_with_new = addresses.into_iter().collect();
        Ok(summary)
    }
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
        let storage = self.lock()?;
        Ok(storage.article_count()?)
    }

    /// Every topic that has at least one feed, in alphabetical order, with its
    /// feed count and unread count. An empty list means no feeds yet.
    pub fn list_topics(&self) -> Result<Vec<TopicInfo>, FfiError> {
        let storage = self.lock()?;

        // Step 1: group the feeds by topic. `list_feeds` already returns them
        // sorted by topic, so feeds of one topic sit next to each other and we
        // only need to compare each feed with the previous group.
        // Each group is (topic name, the feed URLs in it).
        let mut groups: Vec<(String, Vec<String>)> = Vec::new();
        for feed in storage.list_feeds()? {
            // `last_mut()` is the most recent group, if any; `Some(group)` is
            // matched only when it is the same topic as this feed.
            match groups.last_mut() {
                Some((name, urls)) if *name == feed.topic => urls.push(feed.url),
                _ => groups.push((feed.topic, vec![feed.url])),
            }
        }

        // Step 2: for each group, ask core how many of its articles are unread.
        // `Scope::Feeds(urls)` limits the count to articles from those feeds.
        let mut topics = Vec::with_capacity(groups.len());
        for (name, urls) in groups {
            let feed_count = urls.len() as u32;
            let unread = storage.unread_count(&Scope::Feeds(urls))? as u32;
            topics.push(TopicInfo {
                name,
                feed_count,
                unread,
            });
        }
        Ok(topics)
    }

    /// Every feed, alphabetical by name (ignoring upper/lower case), each with
    /// its topic and unread count. This is what the app's first screen shows.
    pub fn list_all_feeds(&self) -> Result<Vec<FeedInfo>, FfiError> {
        let storage = self.lock()?;
        let mut feeds = feed_infos(&storage, None)?;
        // `sort_by_key` orders the list by the value the closure returns.
        // Lower-casing makes "arch" and "Arch" sort together.
        feeds.sort_by_key(|feed| feed.name.to_lowercase());
        Ok(feeds)
    }

    /// The feeds filed under `topic`, by name, each with its unread count.
    pub fn list_feeds_in_topic(&self, topic: String) -> Result<Vec<FeedInfo>, FfiError> {
        let storage = self.lock()?;
        feed_infos(&storage, Some(&topic))
    }

    /// Adds an RSS/Atom feed and returns its ID. Fails with a readable message
    /// if that address is already added.
    ///
    /// Names and topics are trimmed of surrounding spaces, and empty ones are
    /// rejected here so a blank row can never reach the database.
    pub fn add_feed(&self, name: String, url: String, topic: String) -> Result<i64, FfiError> {
        // `trim()` returns a view with spaces removed from both ends.
        let (name, url, topic) = (name.trim(), url.trim(), topic.trim());
        if name.is_empty() || url.is_empty() || topic.is_empty() {
            return Err(FfiError::Failed {
                detail: "a feed needs a name, an address and a topic".to_string(),
            });
        }
        let storage = self.lock()?;
        Ok(storage.add_feed(name, url, topic, SourceType::Rss)?)
    }

    /// Removes a feed by ID; returns whether it existed. Its articles are kept.
    pub fn remove_feed(&self, id: i64) -> Result<bool, FfiError> {
        let storage = self.lock()?;
        Ok(storage.remove_feed(id)?)
    }

    /// Adds every usable feed from the text of an OPML file and says what
    /// happened. Feeds already in the list are left alone. A file that is not
    /// OPML, or is too big or damaged, fails with a readable message and adds
    /// nothing.
    pub fn import_opml(&self, text: String) -> Result<OpmlReport, FfiError> {
        // Step 1: read the file *before* touching the database, so a bad file
        // changes nothing.
        let import = parse_opml(&text)?;

        // Step 2: remember which addresses are already stored (lower-cased, to
        // match how the reader compares them).
        let storage = self.lock()?;
        let mut known: std::collections::HashSet<String> = storage
            .list_feeds()?
            .into_iter()
            .map(|feed| feed.url.to_ascii_lowercase())
            .collect();

        // Step 3: add the new ones, counting as we go.
        let mut added = 0u32;
        let mut already_there = import.repeated as u32;
        for feed in &import.feeds {
            // `insert` returns `false` if the address was already in the set.
            if !known.insert(feed.url.to_ascii_lowercase()) {
                already_there += 1;
                continue;
            }
            storage.add_feed(&feed.name, &feed.url, &feed.topic, feed.source_type)?;
            added += 1;
        }
        Ok(OpmlReport {
            added,
            already_there,
            ignored: import.ignored as u32,
        })
    }

    /// Every feed written out as the text of an OPML file, ready to be saved
    /// to a file the person chose.
    pub fn export_opml(&self) -> Result<String, FfiError> {
        let storage = self.lock()?;
        // Convert each stored feed to the shared `SourceConfig` shape the
        // writer takes.
        let sources: Vec<_> = storage
            .list_feeds()?
            .iter()
            .map(|feed| feed.to_source_config())
            .collect();
        Ok(write_opml(&sources))
    }

    /// The articles from one feed, newest first.
    pub fn list_articles(&self, feed_url: String) -> Result<Vec<ArticleInfo>, FfiError> {
        let storage = self.lock()?;
        // `.iter().map(ArticleInfo::from).collect()`: walk the articles by
        // reference, convert each with the `From<&Article>` impl above, and
        // gather the results into a `Vec`.
        let articles = storage.articles_by_feed(&feed_url)?;
        Ok(articles.iter().map(ArticleInfo::from).collect())
    }

    /// One article in full (including its summary), for the summary screen.
    /// Fails with a readable message if no article has this ID.
    pub fn article_detail(&self, id: i64) -> Result<ArticleDetail, FfiError> {
        let storage = self.lock()?;
        // `article_by_id` returns `Option<Article>`: `Some` if found. Turning a
        // missing article into an error here keeps the Kotlin side simple.
        match storage.article_by_id(id)? {
            Some(article) => Ok(ArticleDetail::from(&article)),
            None => Err(FfiError::Failed {
                detail: "that article no longer exists".to_string(),
            }),
        }
    }

    /// Marks an article read.
    pub fn mark_read(&self, id: i64) -> Result<(), FfiError> {
        let storage = self.lock()?;
        Ok(storage.mark_read(id)?)
    }

    /// Every saved article, newest first, each with its note (if any).
    ///
    /// The Saved screen filters and sorts this list itself (for the "notes
    /// only" view and search), so it is returned whole.
    pub fn list_saved_articles(&self) -> Result<Vec<SavedArticle>, FfiError> {
        let storage = self.lock()?;
        let articles = storage.saved_articles()?;
        Ok(articles.iter().map(SavedArticle::from).collect())
    }

    /// Saves an article (with no note). Saving also marks it read.
    ///
    /// Saving an article that is already saved does nothing, so the date it
    /// was first saved is kept and an existing note is not wiped. To take a
    /// save away again, see [`Database::unsave_article`]. Fails with a
    /// readable message if no article has this ID.
    pub fn save_article(&self, id: i64) -> Result<(), FfiError> {
        let storage = self.lock()?;
        let article = Self::existing_article(&storage, id)?;
        // Only save when it is not saved yet: `Storage::save_article` also
        // sets the note, so calling it on a saved article would clear a note.
        if !article.saved {
            storage.save_article(id, None)?;
        }
        Ok(())
    }

    /// Takes an article out of Saved: the opposite of [`Database::save_article`].
    ///
    /// A note and a save are locked together, so this also deletes the
    /// article's note. The screen must therefore ask the person to confirm
    /// before calling it (and say so when there is a note); this function
    /// does not ask. The article itself stays, and stays read.
    ///
    /// Returns `true` when a save was removed and `false` when the article
    /// was not saved. Fails with a readable message if no article has this ID.
    pub fn unsave_article(&self, id: i64) -> Result<bool, FfiError> {
        let storage = self.lock()?;
        // Step 1: an unknown ID is an error, not a quiet `false`.
        Self::existing_article(&storage, id)?;
        // Step 2: core clears the save, the note and both dates.
        Ok(storage.unsave_article(id)?)
    }

    /// Sets, replaces or clears the note on an article.
    ///
    /// A note of `None`, or only spaces, clears the note and leaves the
    /// article's saved state alone. A real note also saves the article (core
    /// keeps a note and a save locked together), keeping an existing save
    /// date. Fails with a readable message if no article has this ID.
    pub fn set_note(&self, id: i64, note: Option<String>) -> Result<(), FfiError> {
        let storage = self.lock()?;
        // An unknown ID is an error before anything is written.
        Self::existing_article(&storage, id)?;

        // Tidy the note. `map` trims the ends, and `filter` turns an empty
        // result into `None`, so "nothing typed" clears the note. Core's
        // `update_note` then stores it, saves the article when there is a
        // real note, and keeps the "noted" date in step with the note.
        let note = note
            .map(|text| text.trim().to_string())
            .filter(|text| !text.is_empty());
        Ok(storage.update_note(id, note)?)
    }

    /// The text of the "saved articles and notes" export, ready to be written
    /// to a file the person chooses.
    ///
    /// `include_unnoted` says whether saved articles that have no note are
    /// listed too (the first section of the file). Returns an empty string
    /// when there is nothing to export, so the app can say so instead of
    /// saving an empty file. Reading only: exporting never changes or
    /// deletes anything.
    pub fn export_saved(&self, include_unnoted: bool) -> Result<String, FfiError> {
        let storage = self.lock()?;
        let articles = storage.saved_articles()?;
        Ok(render_saved_markdown(
            &articles,
            &ExportOptions { include_unnoted },
        ))
    }

    /// Reads a saved setting (for example "theme"), or `None` if never saved.
    pub fn get_setting(&self, key: String) -> Result<Option<String>, FfiError> {
        let storage = self.lock()?;
        Ok(storage.get_setting(&key)?)
    }

    /// Saves a setting, replacing any earlier value.
    pub fn set_setting(&self, key: String, value: String) -> Result<(), FfiError> {
        let storage = self.lock()?;
        Ok(storage.set_setting(&key, &value)?)
    }

    /// Forgets which server changes were already seen and which articles were
    /// already sent, so the next sync starts from scratch. Used when the person
    /// points sync at a different server or database. Saved articles and notes
    /// on this device are not touched.
    pub fn reset_sync_state(&self) -> Result<(), FfiError> {
        let storage = self.lock()?;
        Ok(storage.reset_sync_state()?)
    }

    /// The feeds another device removed that are still in this device's list
    /// and have not been answered. Reads only the local database (no network),
    /// so the screen can show a waiting question again after a restart.
    pub fn pending_feed_removals(&self) -> Result<Vec<FeedRemovalRequest>, FfiError> {
        let storage = self.lock()?;
        Ok(removal_requests(storage.pending_feed_removals()?))
    }

    /// The person said yes: take the feed at `url` out of this device's list
    /// (its articles are kept, like any removal) and tell sync it is gone.
    /// Returns whether the feed was in the list. Call this only after the
    /// person confirmed.
    pub fn confirm_feed_removal(&self, url: String) -> Result<bool, FfiError> {
        let storage = self.lock()?;
        // Find the stored feed whose address matches, ignoring capitals.
        let wanted = feed_key(&url);
        let found = storage
            .list_feeds()?
            .into_iter()
            .find(|feed| feed_key(&feed.url) == wanted);
        // Remove first, then tell sync: if removing fails (`?`) the question
        // stays pending and is asked again.
        let existed = match found {
            Some(feed) => storage.remove_feed(feed.id)?,
            None => false,
        };
        storage.feed_removed_locally(&url)?;
        Ok(existed)
    }

    /// The person said no: keep the feed. Nothing is sent to the server, and
    /// the question is asked again only if the other device changes the feed
    /// again.
    pub fn keep_feed(&self, url: String) -> Result<(), FfiError> {
        let storage = self.lock()?;
        Ok(storage.dismiss_feed_removal(&url)?)
    }

    /// The Read Later list, newest first. Opened items stay on it (with
    /// `read` set) until the person discards or saves them.
    pub fn list_read_later(&self) -> Result<Vec<ReadLaterEntry>, FfiError> {
        let storage = self.lock()?;
        // `into_iter` takes each item by value, `map` converts it, `collect`
        // gathers the results into the `Vec` Kotlin receives.
        Ok(storage
            .list_read_later()?
            .into_iter()
            .map(ReadLaterEntry::from)
            .collect())
    }

    /// Puts a link on the Read Later list. `title` may be empty (the address
    /// is then used as the title).
    ///
    /// Returns `true` when the link is newly on the list and `false` when it
    /// was already there (nothing changes). Fails with a readable message when
    /// `url` is not an ordinary `http://` or `https://` address.
    pub fn add_read_later(&self, title: String, url: String) -> Result<bool, FfiError> {
        let storage = self.lock()?;
        Ok(storage.add_read_later(&title, &url)?)
    }

    /// Marks a Read Later link as opened. It stays on the list. Returns `true`
    /// when something changed and `false` when it was not on the list or was
    /// already marked.
    pub fn mark_read_later_read(&self, url: String) -> Result<bool, FfiError> {
        let storage = self.lock()?;
        Ok(storage.mark_read_later_read(&url)?)
    }

    /// Takes a link off the Read Later list (the other devices drop it too).
    /// Returns `true` when it was on the list.
    ///
    /// This does not ask for confirmation; the screen must, as with un-saving.
    pub fn discard_read_later(&self, url: String) -> Result<bool, FfiError> {
        let storage = self.lock()?;
        Ok(storage.discard_read_later(&url)?)
    }

    /// Saves a Read Later link permanently and takes it off the list. Returns
    /// the saved article's ID. An article already saved keeps its note.
    /// Fails with a readable message when the link is not on the list.
    pub fn promote_read_later(&self, url: String) -> Result<i64, FfiError> {
        let storage = self.lock()?;
        Ok(storage.promote_read_later(&url)?)
    }

    /// Saves a link permanently without putting it on the Read Later list: the
    /// "Save" answer for a pasted or shared link. Returns the saved article's
    /// ID. If the link was on the list it leaves it. Fails with a readable
    /// message when `url` is not an ordinary web address.
    pub fn save_link(&self, title: String, url: String) -> Result<i64, FfiError> {
        let storage = self.lock()?;
        Ok(storage.save_link(&title, &url)?)
    }
}

/// One link on the Read Later list, as the app shows it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ReadLaterEntry {
    /// The link's web address. Pass it back to the other Read Later methods.
    pub url: String,
    /// The page's title, or the address when no title was found.
    pub title: String,
    /// When it was added, in seconds since 1970.
    pub added_at: i64,
    /// Whether the person has opened it. Opened items stay on the list.
    pub read: bool,
}

/// Converts core's list item into the record Kotlin sees.
impl From<ReadLaterItem> for ReadLaterEntry {
    /// Moves the fields across; the date becomes plain seconds.
    fn from(item: ReadLaterItem) -> ReadLaterEntry {
        ReadLaterEntry {
            url: item.url,
            title: item.title,
            added_at: item.added_at.timestamp(),
            read: item.read,
        }
    }
}

/// Whether `text` is an ordinary web address (`http://` or `https://`, with a
/// host). The app uses this to decide whether pasted or shared text can go on
/// the Read Later list, so Kotlin and core always agree on what counts.
#[uniffi::export]
pub fn is_web_address(text: String) -> bool {
    is_syncable_url(&text)
}

/// Looks up the title of the web page at `url`, for a pasted or shared link.
///
/// This is a courtesy: it returns `None` whenever there is no usable title
/// (the page is unreachable, is not HTML, or has no `<title>`), and the caller
/// then uses the address instead. It reaches out to the link's own site only,
/// and gives up after ten seconds.
#[uniffi::export(async_runtime = "tokio")]
pub async fn fetch_link_title(url: String) -> Option<String> {
    // `.ok()` turns an error into `None`; `.flatten()` merges the two layers
    // of `Option` (a successful lookup that found no title is `Ok(None)`).
    tuxwire_core::fetchers::link_title::fetch_page_title(&url)
        .await
        .ok()
        .flatten()
}

/// One feed that another device removed and this device still has. The app
/// shows these to the person and asks before taking anything out.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FeedRemovalRequest {
    /// The feed's name, as this device knows it from the server.
    pub name: String,
    /// The feed's address. Pass it back to `confirm_feed_removal` or `keep_feed`.
    pub url: String,
    /// The topic it is filed under.
    pub topic: String,
}

/// What one feed-list sync did.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FeedSyncSummary {
    /// Core's one-line report (changes sent, anything skipped).
    pub report: String,
    /// How many feeds other devices added that are now in this device's list.
    pub added: u32,
    /// Feeds another device removed that are still here. Nothing has been
    /// removed: the person must be asked.
    pub removals: Vec<FeedRemovalRequest>,
}

/// Turns core's feed entries into the records Kotlin sees.
fn removal_requests(entries: Vec<FeedEntry>) -> Vec<FeedRemovalRequest> {
    // `into_iter` takes the entries by value; `map` builds one record from
    // each; `collect` gathers them into a `Vec`.
    entries
        .into_iter()
        .map(|entry| FeedRemovalRequest {
            name: entry.name,
            url: entry.url,
            topic: entry.topic,
        })
        .collect()
}

/// The two network-using methods. They are `async`, so UniFFI turns them into
/// Kotlin `suspend fun`s (see `preview_feed` below for what that means), and
/// they live in their own block because `async_runtime = "tokio"` must be
/// declared for async exports.
#[uniffi::export(async_runtime = "tokio")]
impl Database {
    /// Downloads every feed and stores the new articles.
    pub async fn refresh_all(&self) -> Result<RefreshSummary, FfiError> {
        // Read the feed list under the lock, then let go of the lock *before*
        // refreshing (the inner block drops the guard at its closing brace).
        let feeds = {
            let storage = self.lock()?;
            storage.list_feeds()?
        };
        self.refresh(feeds).await
    }

    /// Downloads just the feed at `feed_url` and stores its new articles.
    pub async fn refresh_feed(&self, feed_url: String) -> Result<RefreshSummary, FfiError> {
        // Same as `refresh_all`, keeping only the matching feed.
        let feeds = {
            let storage = self.lock()?;
            storage
                .list_feeds()?
                .into_iter()
                .filter(|feed| feed.url == feed_url)
                .collect()
        };
        self.refresh(feeds).await
    }

    /// Runs one optional CouchDB sync of saved articles, notes and the Read
    /// Later list, and returns a one-line summary such as "2 sent, 1 received".
    ///
    /// Kotlin passes the settings in on every call and keeps the password
    /// encrypted on its side; this crate never stores any of it. `enabled` is
    /// the on/off switch: while it is `false`, core refuses to touch the
    /// network, so a bug in the screen cannot make sync run by accident.
    /// `read_marks` is the separate, off-by-default switch for sharing which
    /// articles were opened; it does nothing unless `enabled` is also `true`.
    /// Error text comes from core's fixed phrases and never contains the
    /// address or the password.
    pub async fn sync_now(
        &self,
        enabled: bool,
        read_marks: bool,
        url: String,
        database: String,
        username: String,
        password: String,
    ) -> Result<String, FfiError> {
        // Build the settings object. The password is moved into it and is
        // hidden from `Debug` output.
        // `with_read_marks` turns on the separate opened-articles switch.
        let config =
            SyncConfig::new(enabled, url, database, username, password).with_read_marks(read_marks);
        // `&self.storage` is the lock itself: core takes it only briefly, and
        // never holds it while waiting on the network.
        let report = tuxwire_core::sync::sync_now(&self.storage, &config).await?;
        Ok(report.summary())
    }

    /// Runs one optional sync of the **feed list** and returns what it did.
    ///
    /// This is separate from `sync_now` and has its own switch: `feeds` must be
    /// `true` as well as `enabled`, or core refuses before touching the
    /// network. Feeds other devices added are added to this device's list
    /// here; feeds another device removed are only *returned* in `removals`
    /// (nothing is removed). The app asks the person, then calls
    /// `confirm_feed_removal` or `keep_feed`.
    pub async fn sync_feeds_now(
        &self,
        enabled: bool,
        feeds: bool,
        url: String,
        database: String,
        username: String,
        password: String,
    ) -> Result<FeedSyncSummary, FfiError> {
        // Step 1: settings, with the separate feed switch.
        let config = SyncConfig::new(enabled, url, database, username, password).with_feeds(feeds);

        // Step 2: this device's feed list as plain entries. Only RSS feeds are
        // synced. The lock is held just for the read (the block's closing
        // brace drops it), never across the network wait below.
        let local: Vec<FeedEntry> = {
            let storage = self.lock()?;
            storage
                .list_feeds()?
                .into_iter()
                .filter(|feed| feed.source_type == SourceType::Rss)
                .map(|feed| FeedEntry {
                    name: feed.name,
                    url: feed.url,
                    topic: feed.topic,
                })
                .collect()
        };

        // Step 3: the exchange with the server.
        let report = sync_feeds(&self.storage, &config, local).await?;

        // Step 4: add the feeds other devices added, then list the removals.
        let storage = self.lock()?;
        let pending = storage.pending_feed_additions()?;
        // Addresses already here (lower-case), so a feed is never added twice.
        let mut known: std::collections::HashSet<String> = storage
            .list_feeds()?
            .iter()
            .map(|feed| feed_key(&feed.url))
            .collect();
        let mut added: u32 = 0;
        for entry in &pending {
            // `insert` is `true` only the first time an address is seen.
            if known.insert(feed_key(&entry.url)) {
                storage.add_feed(&entry.name, &entry.url, &entry.topic, SourceType::Rss)?;
                added += 1;
            }
            // Tell sync this device has it now (also when it already did).
            storage.feed_added_locally(&entry.url)?;
        }
        Ok(FeedSyncSummary {
            report: report.summary(),
            added,
            removals: removal_requests(storage.pending_feed_removals()?),
        })
    }
}

/// Checks a server, database and login without reading or writing any
/// articles. Free-standing (not tied to a `Database`) because it works before
/// sync is switched on, and needs nothing from the database.
#[uniffi::export(async_runtime = "tokio")]
pub async fn sync_test_connection(
    url: String,
    database: String,
    username: String,
    password: String,
) -> Result<(), FfiError> {
    // `enabled` is true here only so the settings object is complete; core's
    // test ignores it by design.
    let config = SyncConfig::new(true, url, database, username, password);
    Ok(tuxwire_core::sync::test_connection(&config).await?)
}

// ---------------------------------------------------------------------------
// Feed preview
// ---------------------------------------------------------------------------

/// How `preview_feed` arrived at the feed, so the add-feed screen can tell
/// the person what happened. Mirrors `tuxwire_core::fetchers::discover::FoundBy`
/// (see the module comment on why types are mirrored). Kotlin sees it as an
/// `enum class FoundBy { DIRECT, ADVERTISED, GUESSED }`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FoundBy {
    /// The address given was itself a feed.
    Direct,
    /// The web page named its feed in a `<link>` line.
    Advertised,
    /// The page named nothing; a common address (`/feed`, `/index.xml`, ...)
    /// turned out to be a feed.
    Guessed,
}

/// Converts core's answer into the mirror type. `match` must list every
/// variant, so a new one added to core is a compile error here until handled.
impl From<tuxwire_core::fetchers::discover::FoundBy> for FoundBy {
    fn from(found_by: tuxwire_core::fetchers::discover::FoundBy) -> Self {
        use tuxwire_core::fetchers::discover::FoundBy as Core;
        match found_by {
            Core::Direct => FoundBy::Direct,
            Core::Advertised => FoundBy::Advertised,
            Core::Guessed => FoundBy::Guessed,
        }
    }
}

/// A quick look at a feed, used to check an address before subscribing to it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FeedPreview {
    /// The address of the feed itself. When the person typed a web page, this
    /// is the feed that page led to, and it is the address to save.
    pub url: String,
    /// How the feed was found (typed directly, advertised by the page, or
    /// found at a common address).
    pub found_by: FoundBy,
    /// The feed's own title, or its address when the feed has none.
    pub title: String,
    /// How many entries (articles) the feed currently lists.
    pub entry_count: u32,
    /// Titles of up to the first five entries, newest first as the feed gives
    /// them.
    pub latest_titles: Vec<String>,
}

/// Finds the feed for `url` (a feed address, or the address of a page that has
/// a feed), downloads and parses it, and returns a short summary.
///
/// All the looking is done by core's `discover_feed`: the address itself first,
/// then the feed link the page advertises (comment feeds skipped), then a short
/// list of usual addresses.
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
    let found = tuxwire_core::fetchers::discover::discover_feed(&url).await?;

    // Take the pieces out of the answer. `found.url` is the feed's own
    // address, which may differ from what was typed.
    let feed = found.feed;
    let feed_url = found.url;

    // The feed's title is optional in the feed format. `.map(..)` pulls out the
    // text if present; `.unwrap_or_else(..)` falls back to the address if not.
    let title = feed
        .title
        .map(|t| t.content)
        .unwrap_or_else(|| feed_url.clone());

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
        url: feed_url,
        found_by: found.found_by.into(),
        title,
        entry_count,
        latest_titles,
    })
}

/// A quick look at the feed inside a file the person picked, before importing
/// it. `bytes` is the file's contents and `label` its display name (used only
/// in error messages).
///
/// The `url` in the answer is the key the feed would be stored under
/// (`import:overtake`), built from the feed's title. A file that is empty, too
/// big or not RSS/Atom (a saved "Checking your browser" page, for example)
/// fails with a message naming the file.
///
/// Not `async`: parsing bytes already in memory does no waiting.
#[uniffi::export]
pub fn preview_feed_file(bytes: Vec<u8>, label: String) -> Result<FeedPreview, FfiError> {
    // `&bytes` lends the bytes to core without copying them. Core does the
    // checking, parsing and summarising.
    let preview = tuxwire_core::feed_import::preview_feed_bytes(&bytes, &label)?;

    // Copy core's summary into the record Kotlin receives. A file has no
    // address to be "found" at, so it counts as given directly.
    Ok(FeedPreview {
        url: preview.key,
        found_by: FoundBy::Direct,
        title: preview.title,
        entry_count: preview.entry_count,
        latest_titles: preview.latest_titles,
    })
}

/// What an import from a file did.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FileImportReport {
    /// The key the feed is stored under, such as `import:overtake`.
    pub key: String,
    /// How many articles in the file were new to the database.
    pub added: u32,
    /// How many articles the file held in all.
    pub total: u32,
    /// `true` if this import created the feed, `false` if it only added newer
    /// articles to a feed that was already there.
    pub created: bool,
}

#[uniffi::export]
impl Database {
    /// Imports the feed in a picked file as a feed called `name` under `topic`.
    ///
    /// A second import with the same name (ignoring case and punctuation)
    /// updates the same feed and adds only what is new; the feed's stored name
    /// and topic are kept. A bad file stores nothing. All the work is in core's
    /// `import_feed_bytes`; this only passes the bytes through under the lock.
    pub fn import_feed_file(
        &self,
        bytes: Vec<u8>,
        label: String,
        name: String,
        topic: String,
    ) -> Result<FileImportReport, FfiError> {
        let storage = self.lock()?;
        let report =
            tuxwire_core::feed_import::import_feed_bytes(&storage, &bytes, &label, &name, &topic)?;
        Ok(FileImportReport {
            key: report.key,
            added: report.added,
            total: report.total,
            created: report.created,
        })
    }
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

    // -- helpers for the database tests ------------------------------------

    /// Opens a brand-new database in a unique temporary directory. Returns the
    /// database and the directory so the test can delete it afterwards.
    fn temp_database() -> (Arc<Database>, std::path::PathBuf) {
        // Same naming trick as `fresh_database_opens_and_is_empty`: process id
        // plus a nanosecond timestamp is unique enough, with no extra crate.
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir =
            std::env::temp_dir().join(format!("tuxwire-ffi-test-{}-{unique}", std::process::id()));
        let db = Database::open(dir.join("tuxwire.db").to_string_lossy().into_owned()).unwrap();
        (db, dir)
    }

    /// Starts a tiny web server on 127.0.0.1 (this computer only; nothing
    /// leaves the machine) that answers every request with a small RSS feed
    /// of two articles. Returns the feed's address.
    ///
    /// It runs on a plain background thread using only the standard library,
    /// so the tests need no extra dependency. The thread serves a few
    /// requests and is cleaned up when the test program exits.
    fn serve_sample_feed() -> String {
        use std::io::{Read, Write};

        // Port 0 means "pick any free port"; `local_addr` says which one.
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();

        let body = r#"<?xml version="1.0"?>
<rss version="2.0"><channel><title>Sample feed</title>
<item><title>First article</title><link>https://example.com/1</link><description><![CDATA[<p>First &amp; best. A second sentence.</p>]]></description></item>
<item><title>Second article</title><link>https://example.com/2</link></item>
</channel></rss>"#;

        std::thread::spawn(move || {
            // `.incoming()` yields each connection as it arrives; `take(4)`
            // stops after four so the thread ends by itself.
            for stream in listener.incoming().take(4) {
                let mut stream = stream.unwrap();
                // Read (and ignore) the request so the client is not cut off.
                let mut buffer = [0u8; 2048];
                let _ = stream.read(&mut buffer);
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/rss+xml\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes());
            }
        });

        format!("http://{address}/feed.xml")
    }

    /// Starts a tiny web server on 127.0.0.1 that pretends to be a blog: its
    /// home page names a comments feed first and the real posts feed second,
    /// and both feeds exist. Returns the server's address (no trailing `/`).
    fn serve_blog() -> String {
        use std::io::{Read, Write};

        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();

        std::thread::spawn(move || {
            for stream in listener.incoming().take(8) {
                let mut stream = stream.unwrap();
                // Read the request and take the path from its first line
                // (`GET /posts.xml HTTP/1.1`).
                let mut buffer = [0u8; 2048];
                let read = stream.read(&mut buffer).unwrap_or(0);
                let request = String::from_utf8_lossy(&buffer[..read]).into_owned();
                let path = request.split_whitespace().nth(1).unwrap_or("/").to_string();

                let feed = |title: &str| {
                    format!(
                        r#"<?xml version="1.0"?><rss version="2.0"><channel><title>{title}</title><item><title>A post</title><link>https://example.com/p</link></item></channel></rss>"#
                    )
                };
                let body = match path.as_str() {
                    "/" => Some(
                        r#"<html><head>
<link rel="alternate" type="application/rss+xml" title="Blog » Comments Feed" href="/comments.xml">
<link rel="alternate" type="application/rss+xml" title="Blog" href="/posts.xml">
</head><body>hi</body></html>"#
                            .to_string(),
                    ),
                    "/posts.xml" => Some(feed("Blog posts")),
                    "/comments.xml" => Some(feed("Comments for Blog")),
                    _ => None,
                };
                let response = match body {
                    Some(body) => format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    ),
                    None => {
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .to_string()
                    }
                };
                let _ = stream.write_all(response.as_bytes());
            }
        });

        format!("http://{address}")
    }

    /// Previewing a web page finds its real feed (skipping the comments feed
    /// listed first) and says how; previewing the feed address itself is used
    /// as it is.
    #[tokio::test]
    async fn preview_finds_the_feed_for_a_page() {
        let site = serve_blog();

        // A page address: the posts feed is found through the page's link.
        let preview = preview_feed(format!("{site}/")).await.unwrap();
        assert_eq!(preview.url, format!("{site}/posts.xml"));
        assert_eq!(preview.found_by, FoundBy::Advertised);
        assert_eq!(preview.title, "Blog posts");
        assert_eq!(preview.entry_count, 1);

        // The feed's own address is kept as typed.
        let direct = preview_feed(format!("{site}/posts.xml")).await.unwrap();
        assert_eq!(direct.url, format!("{site}/posts.xml"));
        assert_eq!(direct.found_by, FoundBy::Direct);
    }

    // -- database tests ------------------------------------------------------

    /// A fresh database has no topics. Feeds group under their topic with the
    /// right counts, can be listed per topic, and can be removed. Duplicate and
    /// blank feeds are rejected with readable messages.
    #[test]
    fn feeds_group_into_topics_and_can_be_removed() {
        let (db, dir) = temp_database();
        assert!(db.list_topics().unwrap().is_empty());

        db.add_feed("LWN".into(), "https://lwn.net/feed".into(), "linux".into())
            .unwrap();
        db.add_feed("Arch".into(), "https://arch/feed".into(), "linux".into())
            .unwrap();
        let ars = db
            .add_feed("Ars".into(), "https://ars/feed".into(), "tech".into())
            .unwrap();

        // Two topics, alphabetical, with 2 and 1 feeds.
        let topics = db.list_topics().unwrap();
        let summary: Vec<(String, u32)> = topics
            .iter()
            .map(|t| (t.name.clone(), t.feed_count))
            .collect();
        assert_eq!(summary, [("linux".to_string(), 2), ("tech".to_string(), 1)]);

        // Every feed, alphabetical by name regardless of topic or case.
        let all: Vec<String> = db
            .list_all_feeds()
            .unwrap()
            .into_iter()
            .map(|f| f.name)
            .collect();
        assert_eq!(all, ["Arch", "Ars", "LWN"]);

        // Feeds in a topic come back by name.
        let linux = db.list_feeds_in_topic("linux".into()).unwrap();
        let names: Vec<&str> = linux.iter().map(|f| f.name.as_str()).collect();
        assert_eq!(names, ["Arch", "LWN"]);

        // A duplicate address and a blank name are both refused.
        let duplicate = db.add_feed("Again".into(), "https://ars/feed".into(), "tech".into());
        assert!(duplicate.unwrap_err().to_string().contains("already added"));
        assert!(
            db.add_feed("  ".into(), "https://x/feed".into(), "tech".into())
                .is_err()
        );

        // Removing the only "tech" feed removes the topic too.
        assert!(db.remove_feed(ars).unwrap());
        assert_eq!(db.list_topics().unwrap().len(), 1);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A setting is absent until saved, then reads back and can be replaced.
    #[test]
    fn settings_can_be_saved_and_replaced() {
        let (db, dir) = temp_database();
        assert_eq!(db.get_setting("theme".into()).unwrap(), None);
        db.set_setting("theme".into(), "Nord".into()).unwrap();
        db.set_setting("theme".into(), "Dracula".into()).unwrap();
        assert_eq!(
            db.get_setting("theme".into()).unwrap().as_deref(),
            Some("Dracula")
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The whole loop against a local feed: refresh stores the articles, they
    /// show as unread, marking one read lowers the count, and a second refresh
    /// finds nothing new.
    ///
    /// Note: the HTTP client honours the `HTTP_PROXY` family of environment
    /// variables. If one is set on your machine, also set
    /// `NO_PROXY=127.0.0.1` when running this test.
    #[tokio::test]
    async fn refresh_stores_articles_and_tracks_unread() {
        let (db, dir) = temp_database();
        let url = serve_sample_feed();
        db.add_feed("Sample".into(), url.clone(), "tech".into())
            .unwrap();

        // First refresh: two new articles, nothing failed.
        let first = db.refresh_all().await.unwrap();
        assert_eq!(first.attempted, 1);
        assert_eq!(first.failed, 0);
        assert_eq!(first.new_articles, 2);

        // Both are listed and unread; the topic and feed counts agree.
        let articles = db.list_articles(url.clone()).unwrap();
        assert_eq!(articles.len(), 2);
        assert!(articles.iter().all(|a| !a.read));
        assert_eq!(db.list_topics().unwrap()[0].unread, 2);

        // Reading one lowers the unread count by one.
        db.mark_read(articles[0].id).unwrap();
        assert_eq!(db.list_feeds_in_topic("tech".into()).unwrap()[0].unread, 1);

        // Refreshing the same feed again adds nothing new.
        let second = db.refresh_feed(url).await.unwrap();
        assert_eq!(second.new_articles, 0);
        assert_eq!(db.article_count().unwrap(), 2);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Summaries survive the whole trip: the feed's HTML description is stored
    /// as plain text, the list only says whether one exists, and the detail
    /// call returns the text. An article whose feed sent none has no summary.
    #[tokio::test]
    async fn summaries_are_stored_and_returned_as_plain_text() {
        let (db, dir) = temp_database();
        let url = serve_sample_feed();
        db.add_feed("Sample".into(), url.clone(), "tech".into())
            .unwrap();
        db.refresh_all().await.unwrap();

        // The list says which articles have a summary (first yes, second no).
        let articles = db.list_articles(url).unwrap();
        let first = articles
            .iter()
            .find(|a| a.title == "First article")
            .unwrap();
        let second = articles
            .iter()
            .find(|a| a.title == "Second article")
            .unwrap();
        assert!(first.has_summary);
        assert!(!second.has_summary);

        // The detail has the HTML removed and the entity decoded.
        let detail = db.article_detail(first.id).unwrap();
        assert_eq!(detail.title, "First article");
        assert_eq!(
            detail.summary.as_deref(),
            Some("First & best. A second sentence.")
        );
        assert_eq!(db.article_detail(second.id).unwrap().summary, None);

        // An unknown ID is a readable error, not a crash.
        assert!(db.article_detail(99999).is_err());

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Refreshes the sample feed (two articles) into a fresh database and
    /// returns the database, the two article IDs and the folder to clean up.
    async fn database_with_two_articles() -> (Arc<Database>, i64, i64, std::path::PathBuf) {
        let (db, dir) = temp_database();
        let url = serve_sample_feed();
        db.add_feed("Sample".into(), url.clone(), "tech".into())
            .unwrap();
        db.refresh_all().await.unwrap();
        let articles = db.list_articles(url).unwrap();
        let first = articles
            .iter()
            .find(|a| a.title == "First article")
            .unwrap();
        let second = articles
            .iter()
            .find(|a| a.title == "Second article")
            .unwrap();
        (db.clone(), first.id, second.id, dir)
    }

    /// Saving, writing a note, changing it and clearing it all show up in the
    /// article detail and the saved list, and the note's date follows the note.
    #[tokio::test]
    async fn saving_and_noting_an_article() {
        let (db, first, _second, dir) = database_with_two_articles().await;

        // Nothing is saved to begin with.
        assert!(db.list_saved_articles().unwrap().is_empty());
        assert!(!db.article_detail(first).unwrap().saved);

        // Saving marks it saved and read, with no note.
        db.save_article(first).unwrap();
        let detail = db.article_detail(first).unwrap();
        assert!(detail.saved);
        assert_eq!(detail.note, None);
        let saved = db.list_saved_articles().unwrap();
        assert_eq!(saved.len(), 1);
        assert_eq!(saved[0].note, None);
        assert_eq!(saved[0].noted_at, None);

        // A note is trimmed and stored, with a note date.
        db.set_note(first, Some("  worth trying  ".into())).unwrap();
        let saved = db.list_saved_articles().unwrap();
        assert_eq!(saved[0].note.as_deref(), Some("worth trying"));
        assert!(saved[0].noted_at.is_some());
        assert_eq!(
            db.article_detail(first).unwrap().note.as_deref(),
            Some("worth trying")
        );

        // Clearing it (nothing, or only spaces) removes the note and its date
        // but leaves the article saved.
        db.set_note(first, Some("   ".into())).unwrap();
        let saved = db.list_saved_articles().unwrap();
        assert_eq!(saved.len(), 1);
        assert_eq!(saved[0].note, None);
        assert_eq!(saved[0].noted_at, None);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Writing a note on an article that is not saved saves it first, and an
    /// unknown article is a readable error rather than a crash.
    #[tokio::test]
    async fn a_note_saves_the_article_and_unknown_ids_fail() {
        let (db, first, second, dir) = database_with_two_articles().await;

        db.set_note(second, Some("remember this".into())).unwrap();
        assert!(db.article_detail(second).unwrap().saved);
        // Only the noted one is saved.
        let saved = db.list_saved_articles().unwrap();
        assert_eq!(saved.len(), 1);
        assert_eq!(saved[0].id, second);
        assert!(!db.article_detail(first).unwrap().saved);

        assert!(db.save_article(99999).is_err());
        assert!(db.set_note(99999, Some("x".into())).is_err());

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Saving again, or noting an already-saved article, keeps the date it
    /// was first saved.
    #[tokio::test]
    async fn saving_again_keeps_the_original_save_date() {
        let (db, first, _second, dir) = database_with_two_articles().await;

        db.save_article(first).unwrap();
        // The full-precision date, straight from the database.
        let saved_at = |db: &Database| {
            db.lock()
                .unwrap()
                .article_by_id(first)
                .unwrap()
                .unwrap()
                .saved_at
        };
        let before = saved_at(&db);
        assert!(before.is_some());

        db.save_article(first).unwrap();
        db.set_note(first, Some("a note".into())).unwrap();
        assert_eq!(saved_at(&db), before);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Un-saving removes the save and the note together, keeps the article,
    /// can be undone by saving again, and reports when there was nothing to
    /// remove. Clearing a note on an unsaved article does not save it.
    #[tokio::test]
    async fn unsaving_removes_the_save_and_the_note() {
        let (db, first, second, dir) = database_with_two_articles().await;

        // A saved article with a note: un-saving takes both away.
        db.set_note(first, Some("keep this".into())).unwrap();
        assert!(db.unsave_article(first).unwrap());
        assert!(db.list_saved_articles().unwrap().is_empty());
        let detail = db.article_detail(first).unwrap();
        assert!(!detail.saved);
        assert_eq!(detail.note, None);

        // The article is still there, and saving it again starts clean.
        db.save_article(first).unwrap();
        assert_eq!(db.list_saved_articles().unwrap()[0].note, None);

        // Nothing to remove: `false`. An unknown article is an error.
        assert!(!db.unsave_article(second).unwrap());
        assert!(db.unsave_article(99999).is_err());

        // Clearing a note on an article that is not saved saves nothing.
        db.set_note(second, None).unwrap();
        assert!(!db.article_detail(second).unwrap().saved);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Sync is safe by default: with the switch off, `sync_now` refuses before
    /// any network use; a plain-http address to another machine and an address
    /// with a login inside it are both refused with plain sentences that do not
    /// repeat the address or the password; and resetting the sync state leaves
    /// saved articles and notes alone.
    #[tokio::test]
    async fn sync_refuses_unsafe_or_switched_off_settings() {
        let (db, first, _second, dir) = database_with_two_articles().await;
        db.save_article(first).unwrap();

        // Switched off: refused, even with otherwise valid settings.
        let off = db
            .sync_now(
                false,
                false,
                "https://couch.invalid".into(),
                "tuxwire".into(),
                "user".into(),
                "secret-pass".into(),
            )
            .await;
        assert!(off.is_err());

        // Plain http to another machine, and a login inside the address.
        for bad in ["http://couch.invalid", "https://user:pw@couch.invalid"] {
            let err = sync_test_connection(
                bad.into(),
                "tuxwire".into(),
                "user".into(),
                "secret-pass".into(),
            )
            .await
            .unwrap_err()
            .to_string();
            assert!(!err.contains("secret-pass"), "password leaked: {err}");
            assert!(!err.contains("couch.invalid"), "address leaked: {err}");
        }

        // Resetting the bookkeeping keeps the save.
        db.reset_sync_state().unwrap();
        assert_eq!(db.list_saved_articles().unwrap().len(), 1);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Feed sync is safe by default: with either switch off it refuses before
    /// any network use, and the refusal does not repeat the address or the
    /// password.
    #[tokio::test]
    async fn feed_sync_needs_both_switches() {
        let (db, dir) = temp_database();
        db.add_feed("A".into(), "https://a.example/feed".into(), "t".into())
            .unwrap();
        // (enabled, feeds): every combination except both on is refused.
        for (enabled, feeds) in [(false, false), (true, false), (false, true)] {
            let err = db
                .sync_feeds_now(
                    enabled,
                    feeds,
                    "https://couch.invalid".into(),
                    "tuxwire".into(),
                    "user".into(),
                    "secret-pass".into(),
                )
                .await
                .unwrap_err()
                .to_string();
            assert!(!err.contains("secret-pass"), "password leaked: {err}");
            assert!(!err.contains("couch.invalid"), "address leaked: {err}");
        }
        // The feed is untouched.
        assert_eq!(db.list_all_feeds().unwrap().len(), 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A feed another device removed is listed, not removed. Keeping it stops
    /// the question; a newer removal asks again; confirming removes the feed
    /// (and only then).
    #[test]
    fn a_removal_waits_for_the_persons_answer() {
        use tuxwire_core::sync::feeds::{FeedDoc, feed_doc_id};
        let (db, dir) = temp_database();
        db.add_feed("A".into(), "https://a.example/Feed".into(), "t".into())
            .unwrap();

        // Pretend the server delivered a removal with the given revision/time.
        let deliver = |rev: &str, at: i64| {
            let storage = db.lock().unwrap();
            let doc = FeedDoc {
                id: feed_doc_id("https://a.example/feed"),
                rev: Some(rev.to_string()),
                v: 1,
                url: "https://a.example/feed".to_string(),
                name: "A".to_string(),
                topic: "t".to_string(),
                source_type: "rss".to_string(),
                present: false,
                updated_at: at,
            };
            storage.apply_remote_feed_batch(vec![doc], "1").unwrap();
        };
        // The device's own list has to be recorded first, as a sync would.
        {
            let storage = db.lock().unwrap();
            let entries = vec![FeedEntry {
                name: "A".into(),
                url: "https://a.example/Feed".into(),
                topic: "t".into(),
            }];
            storage.record_local_feeds(&entries).unwrap();
        }

        // Asked about, but still there.
        deliver("1-a", 9_000_000_000_000);
        assert_eq!(db.pending_feed_removals().unwrap().len(), 1);
        assert_eq!(db.list_all_feeds().unwrap().len(), 1);

        // No: kept, and not asked again.
        db.keep_feed("https://a.example/feed".into()).unwrap();
        assert!(db.pending_feed_removals().unwrap().is_empty());
        assert_eq!(db.list_all_feeds().unwrap().len(), 1);

        // A newer removal asks again; yes removes it (address in other capitals).
        deliver("2-b", 9_000_000_000_001);
        assert_eq!(db.pending_feed_removals().unwrap().len(), 1);
        assert!(
            db.confirm_feed_removal("https://A.example/FEED".into())
                .unwrap()
        );
        assert!(db.list_all_feeds().unwrap().is_empty());
        assert!(db.pending_feed_removals().unwrap().is_empty());

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The export lists saved articles without a note first and the notes
    /// after, the switch leaves the first section out, there is an empty
    /// result when nothing is saved, and exporting changes nothing.
    #[tokio::test]
    async fn exporting_saved_articles_and_notes() {
        let (db, first, second, dir) = database_with_two_articles().await;

        // Nothing saved: nothing to export.
        assert_eq!(db.export_saved(true).unwrap(), "");

        db.save_article(first).unwrap();
        db.set_note(second, Some("try this on the NAS".into()))
            .unwrap();

        let everything = db.export_saved(true).unwrap();
        let saved_at = everything.find("## Saved articles (1)").unwrap();
        let notes_at = everything.find("## Notes (1)").unwrap();
        assert!(saved_at < notes_at, "articles without notes come first");
        assert!(everything.contains("First article"));
        assert!(everything.contains("> try this on the NAS"));

        let only_notes = db.export_saved(false).unwrap();
        assert!(only_notes.contains("## Notes (1)"));
        assert!(!only_notes.contains("First article"));

        // Exporting twice gives the same text and the note is still there.
        assert_eq!(db.export_saved(true).unwrap(), everything);
        assert_eq!(
            db.article_detail(second).unwrap().note.as_deref(),
            Some("try this on the NAS")
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A small OPML file used by the import tests: two feeds in a folder, one
    /// outside any folder, and one unusable address.
    const SAMPLE_OPML: &str = r#"<?xml version="1.0"?>
<opml version="2.0"><body>
  <outline text="Linux">
    <outline text="LWN" xmlUrl="https://lwn.example/rss"/>
    <outline text="Arch &amp; Co" xmlUrl="https://arch.example/rss"/>
  </outline>
  <outline text="Loose" xmlUrl="https://loose.example/rss"/>
  <outline text="Bad" xmlUrl="javascript:alert(1)"/>
</body></opml>"#;

    /// Importing adds new feeds under their folders, files loose ones as
    /// "Uncategorized", and reports the counts; importing again adds nothing.
    #[test]
    fn opml_import_adds_feeds_and_reports_counts() {
        let (db, dir) = temp_database();

        let first = db.import_opml(SAMPLE_OPML.to_string()).unwrap();
        assert_eq!(
            first,
            OpmlReport {
                added: 3,
                already_there: 0,
                ignored: 1
            }
        );
        let topics: Vec<String> = db
            .list_topics()
            .unwrap()
            .into_iter()
            .map(|t| t.name)
            .collect();
        assert_eq!(topics, ["Linux", "Uncategorized"]);

        // The same file again: everything usable is already there.
        let second = db.import_opml(SAMPLE_OPML.to_string()).unwrap();
        assert_eq!(
            second,
            OpmlReport {
                added: 0,
                already_there: 3,
                ignored: 1
            }
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A file that is not OPML is a readable error and adds nothing.
    #[test]
    fn opml_import_of_a_bad_file_changes_nothing() {
        let (db, dir) = temp_database();
        assert!(db.import_opml("<html></html>".to_string()).is_err());
        assert!(db.list_topics().unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Exporting writes every feed, and importing that text into a fresh
    /// database gives back the same feeds and topics.
    #[test]
    fn opml_export_then_import_round_trips() {
        let (db, dir) = temp_database();
        db.import_opml(SAMPLE_OPML.to_string()).unwrap();
        let text = db.export_opml().unwrap();
        assert!(text.contains("Arch &amp; Co"));

        let (other, other_dir) = temp_database();
        let report = other.import_opml(text).unwrap();
        assert_eq!(report.added, 3);
        let names: Vec<String> = other
            .list_all_feeds()
            .unwrap()
            .into_iter()
            .map(|f| f.name)
            .collect();
        // Alphabetical ignoring case: "Loose" sorts before "LWN" (o before w).
        assert_eq!(names, ["Arch & Co", "Loose", "LWN"]);

        let _ = std::fs::remove_dir_all(&dir);
        let _ = std::fs::remove_dir_all(&other_dir);
    }

    /// A feed that cannot be reached is reported, not fatal: the summary says
    /// it failed (and that we look offline), with one message naming the feed.
    #[tokio::test]
    async fn refresh_reports_an_unreachable_feed() {
        let (db, dir) = temp_database();
        db.add_feed("Dead".into(), "http://127.0.0.1:1/x".into(), "tech".into())
            .unwrap();

        let summary = db.refresh_all().await.unwrap();
        assert_eq!(summary.attempted, 1);
        assert_eq!(summary.failed, 1);
        assert_eq!(summary.new_articles, 0);
        assert_eq!(summary.messages.len(), 1);
        assert!(summary.messages[0].starts_with("Dead: "));

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The Read Later list round-trips through the bridge: adding, a duplicate,
    /// a refused address, opening (stays listed), and discarding.
    #[test]
    fn read_later_list_round_trip() {
        let (db, dir) = temp_database();
        assert!(db.list_read_later().unwrap().is_empty());

        // A new link is added; the same link again is not.
        assert!(
            db.add_read_later("A post".into(), "https://a.example/p".into())
                .unwrap()
        );
        assert!(
            !db.add_read_later("A post".into(), "https://a.example/p".into())
                .unwrap()
        );

        // Addresses that are not web addresses are refused with a message.
        for bad in ["file:///etc/passwd", "javascript:alert(1)", "nonsense", ""] {
            assert!(db.add_read_later("x".into(), bad.into()).is_err(), "{bad}");
        }

        // An empty title becomes the address.
        db.add_read_later("  ".into(), "https://b.example/q".into())
            .unwrap();
        let list = db.list_read_later().unwrap();
        assert_eq!(list.len(), 2);
        let untitled = list
            .iter()
            .find(|e| e.url == "https://b.example/q")
            .unwrap();
        assert_eq!(untitled.title, "https://b.example/q");

        // Opening marks it read and keeps it listed; doing it twice is a no-op.
        assert!(
            db.mark_read_later_read("https://a.example/p".into())
                .unwrap()
        );
        assert!(
            !db.mark_read_later_read("https://a.example/p".into())
                .unwrap()
        );
        let list = db.list_read_later().unwrap();
        assert!(
            list.iter()
                .find(|e| e.url == "https://a.example/p")
                .unwrap()
                .read
        );
        assert!(!untitled_read(&list));

        // Discarding removes it; discarding again reports nothing was there.
        assert!(db.discard_read_later("https://a.example/p".into()).unwrap());
        assert!(!db.discard_read_later("https://a.example/p".into()).unwrap());
        assert_eq!(db.list_read_later().unwrap().len(), 1);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Whether the "b.example" item in `list` is marked read (a test helper).
    fn untitled_read(list: &[ReadLaterEntry]) -> bool {
        list.iter()
            .find(|e| e.url == "https://b.example/q")
            .map(|e| e.read)
            .unwrap()
    }

    /// Saving a Read Later link makes it a saved article and takes it off the
    /// list; saving a pasted link directly does the same without the list, and
    /// saving a link that is already saved keeps its note.
    #[test]
    fn read_later_links_can_be_saved() {
        let (db, dir) = temp_database();

        // From the list: it leaves the list and appears in Saved.
        db.add_read_later("Cool tool".into(), "https://a.example/tool".into())
            .unwrap();
        let id = db
            .promote_read_later("https://a.example/tool".into())
            .unwrap();
        assert!(db.list_read_later().unwrap().is_empty());
        let saved = db.list_saved_articles().unwrap();
        assert_eq!(saved.len(), 1);
        assert_eq!(saved[0].id, id);
        assert_eq!(saved[0].title, "Cool tool");

        // Promoting something that is not listed is a readable error.
        assert!(
            db.promote_read_later("https://a.example/tool".into())
                .is_err()
        );

        // Directly: saved without ever being listed.
        let direct = db
            .save_link("Direct".into(), "https://c.example/d".into())
            .unwrap();
        assert!(db.list_read_later().unwrap().is_empty());
        assert_eq!(db.list_saved_articles().unwrap().len(), 2);

        // A note on a saved link survives saving the same link again.
        db.set_note(direct, Some("remember this".into())).unwrap();
        let again = db
            .save_link("Direct".into(), "https://c.example/d".into())
            .unwrap();
        assert_eq!(again, direct);
        let note = db
            .list_saved_articles()
            .unwrap()
            .into_iter()
            .find(|a| a.id == direct)
            .unwrap()
            .note;
        assert_eq!(note.as_deref(), Some("remember this"));

        // A refused address saves nothing.
        assert!(
            db.save_link("x".into(), "file:///etc/passwd".into())
                .is_err()
        );
        assert_eq!(db.list_saved_articles().unwrap().len(), 2);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// `is_web_address` accepts ordinary web addresses only, and the title
    /// lookup gives `None` (never an error or a hang) when a page cannot be
    /// reached or is not a web address.
    #[tokio::test]
    async fn web_addresses_and_title_lookup() {
        assert!(is_web_address("https://example.com/a?b=c".into()));
        assert!(is_web_address("  http://example.com ".into()));
        for bad in [
            "file:///x",
            "javascript:alert(1)",
            "example.com",
            "",
            "https://",
        ] {
            assert!(!is_web_address(bad.into()), "{bad}");
        }
        assert_eq!(fetch_link_title("nonsense".into()).await, None);
        assert_eq!(fetch_link_title("http://127.0.0.1:9/".into()).await, None);
    }

    /// Read Later sync is off unless the main switch is on: with it off the
    /// sync refuses before any network use, and the list is untouched.
    #[tokio::test]
    async fn read_later_sync_follows_the_main_switch() {
        let (db, dir) = temp_database();
        db.add_read_later("A".into(), "https://a.example/p".into())
            .unwrap();
        let err = db
            .sync_now(
                false,
                false,
                "https://couch.invalid".into(),
                "tuxwire".into(),
                "user".into(),
                "secret-pass".into(),
            )
            .await
            .unwrap_err()
            .to_string();
        assert!(!err.contains("secret-pass"), "password leaked: {err}");
        assert!(!err.contains("couch.invalid"), "address leaked: {err}");
        assert_eq!(db.list_read_later().unwrap().len(), 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Read marks ride on the main switch: asking for them while sync itself is
    /// off is refused before any network use, without leaking the address or
    /// the password, and what has been read stays read.
    #[tokio::test]
    async fn read_marks_need_the_main_switch() {
        let (db, first, _second, dir) = database_with_two_articles().await;
        db.mark_read(first).unwrap();
        let err = db
            .sync_now(
                false,
                true,
                "https://couch.invalid".into(),
                "tuxwire".into(),
                "user".into(),
                "secret-pass".into(),
            )
            .await
            .unwrap_err()
            .to_string();
        assert!(!err.contains("secret-pass"), "password leaked: {err}");
        assert!(!err.contains("couch.invalid"), "address leaked: {err}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    // -- importing a feed from a picked file ---------------------------------

    /// The bytes of a small feed, standing in for a file the picker returned.
    const PICKED_FEED: &str = r#"<?xml version="1.0"?><rss version="2.0"><channel><title>Overtake</title>
        <item><title>One</title><link>https://example.com/one</link></item>
        <item><title>Two</title><link>https://example.com/two</link></item>
        </channel></rss>"#;

    /// The preview names the feed and the key it would be stored under, and a
    /// file that is not a feed is an error that names the file.
    #[test]
    fn a_picked_file_can_be_previewed() {
        let preview =
            preview_feed_file(PICKED_FEED.as_bytes().to_vec(), "index.rss".to_string()).unwrap();
        assert_eq!(preview.title, "Overtake");
        assert_eq!(preview.url, "import:overtake");
        assert_eq!(preview.entry_count, 2);
        assert_eq!(preview.latest_titles, vec!["One", "Two"]);

        let err = preview_feed_file(b"<html></html>".to_vec(), "page.html".to_string())
            .unwrap_err()
            .to_string();
        assert!(err.contains("page.html"), "{err}");
    }

    /// Importing adds the feed with its articles; a second import of the same
    /// file adds nothing and makes no duplicate feed; a bad file changes
    /// nothing.
    #[test]
    fn importing_a_file_adds_the_feed_once() {
        let (db, dir) = temp_database();
        let bytes = PICKED_FEED.as_bytes().to_vec();

        let first = db
            .import_feed_file(
                bytes.clone(),
                "index.rss".into(),
                "Overtake".into(),
                "sim-racing".into(),
            )
            .unwrap();
        assert!(first.created);
        assert_eq!((first.added, first.total), (2, 2));
        assert_eq!(first.key, "import:overtake");

        let feeds = db.list_all_feeds().unwrap();
        assert_eq!(feeds.len(), 1);
        assert_eq!(feeds[0].url, "import:overtake");
        assert_eq!(feeds[0].unread, 2);
        assert_eq!(db.list_articles("import:overtake".into()).unwrap().len(), 2);

        let again = db
            .import_feed_file(
                bytes,
                "index.rss".into(),
                "Overtake".into(),
                "sim-racing".into(),
            )
            .unwrap();
        assert!(!again.created);
        assert_eq!(again.added, 0);
        assert_eq!(db.list_all_feeds().unwrap().len(), 1);

        let bad = db.import_feed_file(
            b"nope".to_vec(),
            "bad.txt".into(),
            "Other".into(),
            "misc".into(),
        );
        assert!(bad.is_err());
        assert_eq!(db.list_all_feeds().unwrap().len(), 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A refresh leaves an imported feed alone: nothing to download, so no
    /// failure is counted for it.
    #[tokio::test]
    async fn refresh_ignores_an_imported_feed() {
        let (db, dir) = temp_database();
        db.import_feed_file(
            PICKED_FEED.as_bytes().to_vec(),
            "index.rss".into(),
            "Overtake".into(),
            "sim-racing".into(),
        )
        .unwrap();
        let summary = db.refresh_all().await.unwrap();
        assert_eq!(summary.failed, 0);
        assert_eq!(summary.new_articles, 0);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A refresh names the feed that got new articles, once, and a second
    /// refresh with nothing new names none.
    #[tokio::test]
    async fn refresh_names_the_feeds_with_new_articles() {
        let (db, dir) = temp_database();
        let address = serve_sample_feed();
        db.add_feed("Sample".into(), address.clone(), "news".into())
            .unwrap();

        let first = db.refresh_all().await.unwrap();
        assert_eq!(first.new_articles, 2);
        assert_eq!(first.feeds_with_new, vec![address]);

        let second = db.refresh_all().await.unwrap();
        assert_eq!(second.new_articles, 0);
        assert!(second.feeds_with_new.is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
