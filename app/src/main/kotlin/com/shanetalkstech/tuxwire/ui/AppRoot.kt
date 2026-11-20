package com.shanetalkstech.tuxwire.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.defaultThemeName
import uniffi.tuxwire_ffi.listThemes

/** The settings key under which the chosen theme's name is saved. */
private const val THEME_SETTING = "theme"

/**
 * How wide the window must be (in dp) for the two-pane home. 600dp is Android's
 * own line between a phone and a tablet-sized window. It is the width of the
 * window the app is in, not of the device, so a foldable that is open but in a
 * narrow split-screen window gets the one-pane layout, as it should.
 */
private const val WIDE_SCREEN_DP = 600

/** The scroll-memory key of the right pane while it shows "all feeds". */
private const val ALL_PANE_KEY = "ALL"

/**
 * Saves the feed shown in the right pane across screen recreations (folding,
 * unfolding, rotating). It is stored as the same text a back-stack entry uses;
 * "nothing selected" (all feeds) is `null` and Compose saves that itself.
 */
private val SelectionSaver: Saver<Screen.Articles?, String> = Saver(
    save = { it?.encode() ?: "" },
    restore = { text -> Screen.decode(text) as? Screen.Articles },
)

/**
 * The root of the whole app: opens the database, applies the saved theme, and
 * shows whichever screen the person is on, sliding between them.
 *
 * Navigation is a plain "back stack": a list of screens, the last one being
 * visible. Going deeper pushes a screen on the end; going back removes it. It
 * is kept in `rememberSaveable` so it survives rotation.
 *
 * @param sharedText text another app shared to tuxwire through Android's Share
 *   menu (normally a web address), or `null` when nothing was shared. When it
 *   arrives the question about the link opens (read later, save, or add as a
 *   feed).
 * @param onSharedTextHandled called once the shared text has been acted on, so
 *   the activity can forget it (otherwise it would be acted on again).
 */
@Composable
fun AppRoot(sharedText: String? = null, onSharedTextHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- Themes ---------------------------------------------------------
    // The built-in themes come from Rust. A failure (such as the native library
    // not loading) is captured so it can be shown rather than crashing.
    val themesResult = remember { runCatching { listThemes() } }
    val themes = themesResult.getOrDefault(emptyList())

    // The chosen theme's name. `null` until the saved choice has been read.
    var themeName by rememberSaveable { mutableStateOf<String?>(null) }

    // --- The database ----------------------------------------------------
    // Open it once, in the background. The file lives in the app's private
    // storage folder; Rust is told the path because core never decides where
    // files live. `null` means "still opening".
    val dbState by produceState<Result<Database>?>(null) {
        value = runCatching {
            onIo { Database.open(File(context.filesDir, "tuxwire.db").path) }
        }
    }
    val db = dbState?.getOrNull()

    // Once the database is open, read the saved theme (or use the default).
    // `LaunchedEffect(db)` runs when `db` first becomes available.
    LaunchedEffect(db) {
        if (db != null && themeName == null) {
            themeName = runCatching { onIo { db.getSetting(THEME_SETTING) } }.getOrNull()
                ?: runCatching { defaultThemeName() }.getOrDefault("")
        }
    }

    // The colours for the current theme (the plain default set until known).
    val activeTheme = themes.firstOrNull { it.name == themeName } ?: themes.firstOrNull()
    val colors = activeTheme?.colors?.toTuxwireColors() ?: LocalTuxwireColors.current

    // Switches to the next theme in the list and saves the choice.
    val cycleTheme: () -> Unit = {
        if (themes.isNotEmpty() && db != null) {
            // `indexOfFirst` gives -1 when not found, so +1 starts at the first.
            val next = themes[(themes.indexOfFirst { it.name == themeName } + 1) % themes.size]
            themeName = next.name
            scope.launch { runCatching { onIo { db.setSetting(THEME_SETTING, next.name) } } }
        }
    }

    // --- Navigation -------------------------------------------------------
    var stack by rememberSaveable(stateSaver = Screen.StackSaver) {
        mutableStateOf(listOf<Screen>(Screen.Home))
    }
    // Counts how many times the person has come back to the home screen. The
    // logo colour is picked from this number, so it changes on every return.
    var homeVisits by rememberSaveable { mutableStateOf(0) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = {
        if (stack.size > 1) {
            stack = stack.dropLast(1)
            // Arriving back on the home screen counts as a visit.
            if (stack.last() == Screen.Home) homeVisits++
        }
    }
    // Where each list was scrolled to, so "back" returns to the same place in a
    // long list. It lives here because this function never leaves the screen,
    // while the list screens do (each is removed when a deeper one opens).
    val scrollMemory = rememberSaveable(saver = ScrollMemory.Saver) { ScrollMemory() }

    // The feeds that got new articles in a refresh and have not been opened yet,
    // marked with a `*` in the feed list. It lives here, for the same reason as
    // `scrollMemory`: the feed list screen is removed when a feed opens, but the
    // marks must outlast it. Opening a feed clears its mark (see `onOpenFeed`).
    val freshFeeds = rememberSaveable(saver = FreshFeeds.Saver) { FreshFeeds() }

    // The articles a refresh brought in since the app opened, drawn in the "new"
    // blue until read. Held here for the same reason as `freshFeeds`: the lists
    // that show them come and go, but the set must outlast them.
    val newArticles = rememberSaveable(saver = NewArticles.Saver) { NewArticles() }

    // --- Wide screens (an unfolded foldable, a tablet) ---------------------
    // True when the window is wide enough for the two-pane home. The value is
    // read again whenever the window changes size, so folding or unfolding the
    // phone switches layouts by itself.
    val expanded = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP
    // The feed whose articles the right pane shows; `null` means "all feeds".
    // Saved, so folding and unfolding (which recreate the screen) keep it.
    var selectedFeed by rememberSaveable(stateSaver = SelectionSaver) {
        mutableStateOf<Screen.Articles?>(null)
    }
    // A topic the left list is narrowed to (a topic label was tapped), or null.
    var filterTopic by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped whenever one pane changes something the other shows (a refresh,
    // an article marked read), so both lists reload.
    var paneVersion by remember { mutableIntStateOf(0) }
    // Whether the refresh on opening has been started. Saved so folding the
    // phone later does not refresh again; a fresh launch starts it again.
    var autoRefreshDone by rememberSaveable { mutableStateOf(false) }
    // Where the right pane's list is scrolled to is remembered under this key.
    val paneKey = selectedFeed?.encode() ?: ALL_PANE_KEY

    // Throws away the whole back stack and shows the home screen.
    val goHome: () -> Unit = {
        if (stack.size > 1) {
            // Jumping home is a "start over": every list begins at the top again.
            scrollMemory.clear()
            stack = listOf(Screen.Home)
            homeVisits++
        }
    }

    // After every change of screen, keep the remembered positions of the screens
    // still in the back stack and forget the rest. This is what makes "back" keep
    // your place while opening the same list afresh starts at the top. (A
    // `SideEffect` runs after each successful redraw of this function.)
    SideEffect {
        scrollMemory.keepOnly(stack.map { it.encode() }.toSet() + paneKey)
    }

    // Something was shared to tuxwire: show the question about the link on top
    // of home (read later, save, or add as a feed). A title that came with the
    // link is passed along. `LaunchedEffect(sharedText)` runs whenever
    // `sharedText` changes to a new value, including the first time.
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            stack = listOf(
                Screen.Home,
                Screen.Link(
                    address = firstWebAddress(sharedText),
                    titleHint = sharedTitleHint(sharedText),
                    fromShare = true,
                ),
            )
            onSharedTextHandled()
        }
    }

    // The logo's colour: one of the current theme's accent colours, moving to
    // the next one on every return to home. `%` wraps back to the first.
    val logoColors = bannerColors(colors)
    val logoColor = logoColors[homeVisits % logoColors.size]

    // The phone's back button/gesture goes back one screen, but only while
    // there is a screen to go back to (otherwise it leaves the app as usual).
    BackHandler(enabled = stack.size > 1, onBack = pop)
    // On the Stats and About tabs "back" returns to the feed list first, then
    // leaves the app.
    BackHandler(
        enabled = stack.size == 1 && (stack.last() == Screen.Stats || stack.last() == Screen.About),
    ) {
        stack = listOf(Screen.Home)
    }

    // On the two-pane home, "back" first lifts a topic filter (a topic label was
    // tapped) so the full feed list returns, then leaves the app as usual.
    BackHandler(
        enabled = expanded && stack.size == 1 && stack.last() == Screen.Home && filterTopic != null,
    ) {
        filterTopic = null
    }

    // Provide the theme colours to everything below.
    CompositionLocalProvider(LocalTuxwireColors provides colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                // Keep content out from under the status/navigation bars, and
                // lift it above the on-screen keyboard.
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when {
                // The theme list could not even load: show why.
                themesResult.isFailure -> MonoText(
                    "error: ${themesResult.exceptionOrNull()?.explain()}",
                    colors.accentBreaking,
                )
                // The database is still opening.
                dbState == null -> MonoText("opening database...", colors.textMuted)
                // The database failed to open: show why.
                dbState?.isFailure == true -> MonoText(
                    "error: ${dbState?.exceptionOrNull()?.explain()}",
                    colors.accentBreaking,
                )
                // Normal case: show the current screen.
                db != null -> Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = stack.last(),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        label = "screen",
                        // Going deeper slides the new screen in from the right while the
                        // old one leaves to the left; going back does the reverse.
                        transitionSpec = {
                            if (targetState.depth == initialState.depth) {
                                // Same depth (switching tabs): a quick cross-fade.
                                fadeIn() togetherWith fadeOut()
                            } else if (targetState.depth > initialState.depth) {
                                slideInHorizontally { width -> width } togetherWith
                                    slideOutHorizontally { width -> -width }
                            } else {
                                slideInHorizontally { width -> -width } togetherWith
                                    slideOutHorizontally { width -> width }
                            }
                        },
                    ) { screen ->
                        // `when` over a sealed class must cover every screen.
                        // Forms and reading pages stay a centred column on wide screens.
                        CappedWidth(enabled = screen.capsWidth) {
                            when (screen) {
                                // Home: every feed. A feed opens its articles; a topic
                                // label opens that topic's feeds.
                                Screen.Home -> {
                                    // The right-hand pane of the two-pane home: the
                                    // selected feed's articles, or every feed's newest
                                    // ("all feeds"). Only drawn on a wide screen. `key`
                                    // gives each selection its own list, so its scroll
                                    // position starts fresh.
                                    val rightPaneContent: @Composable () -> Unit = {
                                        key(paneKey) {
                                            ArticlesScreen(
                                                db = db,
                                                topic = selectedFeed?.topic ?: "",
                                                feedName = selectedFeed?.feedName ?: "",
                                                feedUrl = selectedFeed?.feedUrl ?: "",
                                                scrollMemory = scrollMemory,
                                                scrollKey = paneKey,
                                                newArticles = newArticles,
                                                // An article opens full screen, as on a phone.
                                                // From "all feeds" there is no one feed to name
                                                // in the breadcrumb, so it says "all feeds".
                                                onOpenArticle = { id ->
                                                    push(
                                                        Screen.Article(
                                                            selectedFeed?.topic ?: "all",
                                                            selectedFeed?.feedName ?: "all feeds",
                                                            selectedFeed?.feedUrl ?: "",
                                                            id,
                                                        ),
                                                    )
                                                },
                                                allFeeds = selectedFeed == null,
                                                embedded = true,
                                                reloadKey = paneVersion,
                                                onChanged = { paneVersion++ },
                                                freshFeeds = freshFeeds,
                                            )
                                        }
                                    }
                                    // Home: every feed. On a phone a feed opens its
                                    // articles on a new screen and a topic label opens that
                                    // topic's feeds. On a wide screen a feed fills the right
                                    // pane instead and a topic label narrows the list, so
                                    // you can move through feeds without going back.
                                    FeedsScreen(
                                        db = db,
                                        topic = null,
                                        themeName = themeName ?: "",
                                        onCycleTheme = cycleTheme,
                                        bannerColor = logoColor,
                                        scrollMemory = scrollMemory,
                                        scrollKey = screen.encode(),
                                        onHome = goHome,
                                        onBack = pop,
                                        freshFeeds = freshFeeds,
                                        newArticles = newArticles,
                                        onOpenFeed = { feed ->
                                            freshFeeds.clear(feed.url)
                                            val target = Screen.Articles(feed.topic, feed.name, feed.url)
                                            if (expanded) selectedFeed = target else push(target)
                                        },
                                        onOpenTopic = { name ->
                                            if (expanded) filterTopic = name else push(Screen.Feeds(name))
                                        },
                                        onOpenOpml = { push(Screen.Opml) },
                                        onOpenSaved = { push(Screen.Saved) },
                                        onOpenReadLater = { push(Screen.ReadLater) },
                                        onOpenSync = { push(Screen.Sync) },
                                        onAddFeed = { push(Screen.AddFeed("")) },
                                        onEditFeed = { push(Screen.EditFeed(it.id)) },
                                        rightPane = if (expanded) rightPaneContent else null,
                                        selectedFeedUrl = if (expanded) selectedFeed?.feedUrl else null,
                                        onSelectAll = if (expanded) ({ selectedFeed = null }) else null,
                                        filterTopic = if (expanded) filterTopic else null,
                                        onClearFilter = { filterTopic = null },
                                        reloadKey = paneVersion,
                                        onChanged = { paneVersion++ },
                                        // Refresh once when the app opens on a wide screen.
                                        autoRefresh = expanded && !autoRefreshDone,
                                        onAutoRefreshStarted = { autoRefreshDone = true },
                                    )
                                }
                                // The Stats tab.
                                Screen.Stats -> StatsScreen(db = db)
                                // The About tab.
                                Screen.About -> AboutScreen()
                            // Import and export of feed lists.
                                Screen.Opml -> OpmlScreen(
                                    db = db,
                                    onHome = goHome,
                                    onBack = pop,
                                )
                                // Optional CouchDB sync settings (off by default).
                                Screen.Sync -> SyncScreen(
                                    db = db,
                                    onHome = goHome,
                                    onBack = pop,
                                )
                                // Saved articles and notes. Opening one shows its article
                                // page (note, edit, delete); "saved" stands in for the
                                // topic and the article's source for the feed name in the
                                // breadcrumb, and there is no feed address.
                                Screen.Saved -> SavedScreen(
                                    db = db,
                                    scrollMemory = scrollMemory,
                                    scrollKey = screen.encode(),
                                    onHome = goHome,
                                    onBack = pop,
                                    onOpenArticle = { push(Screen.Article("saved", it.source, "", it.id)) },
                                )
                                // The Read Later list, with the paste box. Pasting a link
                                // opens the question about it.
                                Screen.ReadLater -> ReadLaterScreen(
                                    db = db,
                                    scrollMemory = scrollMemory,
                                    scrollKey = screen.encode(),
                                    onHome = goHome,
                                    onBack = pop,
                                    onOpenLink = { push(Screen.Link(address = it)) },
                                )
                                // The question about one link, pasted or shared.
                                is Screen.Link -> LinkScreen(
                                    db = db,
                                    address = screen.address,
                                    titleHint = screen.titleHint,
                                    fromShare = screen.fromShare,
                                    onHome = goHome,
                                    onBack = pop,
                                    // Replace this screen with the add-feed form, so
                                    // "back" from there does not return to the question.
                                    onAddAsFeed = {
                                        stack = stack.dropLast(1) + Screen.AddFeed("", screen.address)
                                    },
                                    // Show the list with the new link on it: if the
                                    // question was opened from the list, just go back
                                    // to it (it reloads); if it was shared from another
                                    // app, put the list in this screen's place.
                                    onAddedToList = {
                                        if (stack.getOrNull(stack.size - 2) == Screen.ReadLater) {
                                            pop()
                                        } else {
                                            stack = stack.dropLast(1) + Screen.ReadLater
                                        }
                                    },
                                    // Show the Saved list, in this screen's place.
                                    onSaved = { stack = stack.dropLast(1) + Screen.Saved },
                                )
                                // One topic's feeds.
                                is Screen.Feeds -> FeedsScreen(
                                    db = db,
                                    topic = screen.topic,
                                    themeName = "",
                                    onCycleTheme = {},
                                    bannerColor = logoColor,
                                    scrollMemory = scrollMemory,
                                    scrollKey = screen.encode(),
                                    onHome = goHome,
                                    onBack = pop,
                                    freshFeeds = freshFeeds,
                                    newArticles = newArticles,
                                    onOpenFeed = {
                                        freshFeeds.clear(it.url)
                                        push(Screen.Articles(screen.topic, it.name, it.url))
                                    },
                                    onOpenTopic = {},
                                    onOpenOpml = {},
                                    onOpenSaved = {},
                                    onOpenReadLater = {},
                                    onOpenSync = {},
                                    onAddFeed = { push(Screen.AddFeed(screen.topic)) },
                                    onEditFeed = { push(Screen.EditFeed(it.id)) },
                                )
                                is Screen.Articles -> ArticlesScreen(
                                    db = db,
                                    topic = screen.topic,
                                    feedName = screen.feedName,
                                    feedUrl = screen.feedUrl,
                                    scrollMemory = scrollMemory,
                                    scrollKey = screen.encode(),
                                    newArticles = newArticles,
                                    onHome = goHome,
                                    onBack = pop,
                                    onOpenArticle = {
                                        push(
                                            Screen.Article(
                                                screen.topic,
                                                screen.feedName,
                                                screen.feedUrl,
                                                it,
                                            ),
                                        )
                                    },
                                )
                                is Screen.Article -> ArticleScreen(
                                    db = db,
                                    articleId = screen.articleId,
                                    topic = screen.topic,
                                    feedName = screen.feedName,
                                    onHome = goHome,
                                    onBack = pop,
                                )
                                is Screen.AddFeed -> AddFeedScreen(
                                    db = db,
                                    startTopic = screen.topic,
                                    startAddress = screen.address,
                                    onHome = goHome,
                                    onBack = pop,
                                    onDone = pop,
                                )
                                is Screen.EditFeed -> EditFeedScreen(
                                    db = db,
                                    feedId = screen.feedId,
                                    onHome = goHome,
                                    onBack = pop,
                                    onDone = pop,
                                )
                            }
                        }
                    }
                    // The tab bar shows only on the three top-level screens (the
                    // feed list, Stats and About); deeper screens have their own
                    // bars. `stack.size == 1` means nothing is open on top of a tab.
                    val top = stack.last()
                    if (stack.size == 1 && (top == Screen.Home || top == Screen.Stats || top == Screen.About)) {
                        TabBar(
                            selected = when (top) {
                                Screen.Stats -> Tab.STATS
                                Screen.About -> Tab.ABOUT
                                else -> Tab.FEEDS
                            },
                            onSelect = { tab ->
                                // Switching tabs starts that tab afresh, as if
                                // "home" had been pressed.
                                scrollMemory.clear()
                                stack = listOf(
                                    when (tab) {
                                        Tab.STATS -> Screen.Stats
                                        Tab.ABOUT -> Screen.About
                                        Tab.FEEDS -> Screen.Home
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
