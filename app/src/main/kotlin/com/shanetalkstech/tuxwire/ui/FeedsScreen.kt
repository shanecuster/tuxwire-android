package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.FeedInfo

/**
 * A list of feeds. This one screen plays two roles:
 *
 * - **Home** (`topic == null`): the app's first screen, showing *every* feed
 *   alphabetically. The top bar holds the logo and the theme switcher, the
 *   bottom bar holds "add feed" and "refresh all", and only the feed list
 *   between them scrolls. Each feed shows its topic underneath its name;
 *   tapping that topic opens the same screen filtered to it.
 * - **One topic** (`topic == "linux"`): only that topic's feeds. The top bar
 *   has a home button and the bottom bar has "back" and "add feed here".
 *
 * Tapping a feed's name slides to its articles; pressing and holding it opens
 * the edit form (name and topic); "x" (after a confirmation) unsubscribes
 * from it.
 *
 * @param db the open database.
 * @param topic the topic to show, or `null` for every feed (the home screen).
 * @param themeName the active theme's name (shown on home only).
 * @param onCycleTheme called when the theme panel is tapped (home only).
 * @param bannerColor the colour of the logo (home only).
 * @param scrollMemory remembers where the list was scrolled to, so coming back
 *   from a deeper screen finds the list in the same place.
 * @param scrollKey which entry of [scrollMemory] belongs to this list.
 * @param onHome jumps back to the home screen (topic page only).
 * @param onBack goes back one screen (topic page only).
 * @param freshFeeds the feeds that got new articles in a refresh and have not
 *   been opened yet; each is drawn with a `*` and in the "new" blue. A refresh
 *   here adds to it.
 * @param newArticles the articles a refresh brought in since the app opened. A
 *   refresh here adds to it (the article lists draw them in the "new" blue).
 * @param onOpenFeed called with the tapped feed.
 * @param onOpenTopic called with a topic's name when a topic label is tapped.
 * @param onOpenOpml opens the import / export screen (home only).
 * @param onOpenSaved opens the saved articles and notes screen (home only).
 * @param onOpenReadLater opens the Read Later list (home only).
 * @param onOpenSync opens the optional sync settings (home only).
 * @param onAddFeed called when "+ add feed" is tapped.
 * @param onEditFeed called with a feed when it is pressed and held.
 * @param rightPane when given, the screen is the **two-pane home** for wide
 *   screens (an unfolded foldable, a tablet): the feed list takes the left part
 *   of the middle and this content fills the right part. The top and bottom
 *   bars stay shared and span both panes. `null` (the default) is the ordinary
 *   one-pane screen.
 * @param selectedFeedUrl two-pane only: the address of the feed whose articles
 *   the right pane shows, highlighted in the list; `null` means "all feeds".
 * @param onSelectAll two-pane only: when not `null`, an "all feeds" row is
 *   drawn first in the list and calls this when tapped.
 * @param filterTopic two-pane only: when not `null`, the list shows just this
 *   topic's feeds (a topic label was tapped) with a row to clear the filter.
 * @param onClearFilter called when that row is tapped.
 * @param reloadKey changing this number reloads the feed list; the two-pane home
 *   bumps it when the right pane changed something (an article was read).
 * @param onChanged called after something here changed what the right pane
 *   shows (a refresh of every feed), so it can reload.
 * @param autoRefresh when `true`, refresh every feed once as soon as the list
 *   has loaded and there is at least one feed.
 * @param onAutoRefreshStarted called as that refresh starts, so the caller can
 *   set [autoRefresh] back to `false` and not do it twice.
 */
@Composable
fun FeedsScreen(
    db: Database,
    topic: String?,
    themeName: String,
    onCycleTheme: () -> Unit,
    bannerColor: Color,
    scrollMemory: ScrollMemory,
    scrollKey: String,
    onHome: () -> Unit,
    onBack: () -> Unit,
    freshFeeds: FreshFeeds,
    newArticles: NewArticles,
    onOpenFeed: (FeedInfo) -> Unit,
    onOpenTopic: (String) -> Unit,
    onOpenOpml: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenReadLater: () -> Unit,
    onOpenSync: () -> Unit,
    onAddFeed: () -> Unit,
    onEditFeed: (FeedInfo) -> Unit,
    rightPane: (@Composable () -> Unit)? = null,
    selectedFeedUrl: String? = null,
    onSelectAll: (() -> Unit)? = null,
    filterTopic: String? = null,
    onClearFilter: () -> Unit = {},
    reloadKey: Int = 0,
    onChanged: () -> Unit = {},
    autoRefresh: Boolean = false,
    onAutoRefreshStarted: () -> Unit = {},
) {
    val colors = LocalTuxwireColors.current

    // A faint background for the selected row (two-pane home), taken from the
    // theme's border colour so it suits every theme.
    val selectedRowBackground = Modifier.background(colors.panelBorder.copy(alpha = 0.35f))

    // Bumped to make the list load again, for example after a removal or a
    // refresh that brings in new articles.
    var version by remember { mutableIntStateOf(0) }

    // Load the feeds on a background thread. `produceState` runs the block
    // whenever `db`, `topic` or `version` changes and exposes the latest answer
    // as state: null while the first load runs, then a `Result` that is either
    // the feeds or the error. Keeping the old value while reloading means the
    // list does not flicker empty.
    // `dataTopic` is which topic's feeds to load. A topic page names it in `topic`;
    // the two-pane home keeps `topic` null (it is still the home screen, with its
    // banner) and names it in `filterTopic`. Both mean "only this topic's feeds".
    val dataTopic = topic ?: filterTopic
    val feeds by produceState<Result<List<FeedInfo>>?>(null, db, dataTopic, version, reloadKey) {
        value = runCatching {
            onIo { if (dataTopic == null) db.listAllFeeds() else db.listFeedsInTopic(dataTopic) }
        }
    }

    // How many unread links are on the Read Later list, for the home screen's
    // "read later (3)" label. Only home shows it. A failure just hides the
    // number (0), since the list itself reports errors when opened.
    val readLaterCount by produceState(0, db, topic) {
        value = if (topic == null) {
            runCatching { onIo { db.listReadLater().count { !it.read } } }.getOrDefault(0)
        } else {
            0
        }
    }

    // The feed the person asked to remove, waiting for a "yes". `null` means
    // no removal is pending.
    var pendingRemoval by remember { mutableStateOf<FeedInfo?>(null) }

    // State for the refresh button: whether one is running, and what it said.
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val loaded = feeds
    val hasFeeds = loaded?.getOrNull()?.isNotEmpty() == true

    // Downloads every feed in the background and reports back. Used by the
    // "refresh all" button and, on a wide screen, once when the app opens.
    val doRefreshAll: () -> Unit = {
        busy = true
        message = ""
        scope.launch {
            try {
                // `refreshAll` is a Kotlin `suspend fun`, generated from an async
                // Rust function. It waits for the network without freezing the
                // screen.
                val summary = db.refreshAll()
                message = summary.describe()
                // Mark the feeds that got something new, so they stand out in
                // the list below.
                freshFeeds.add(summary.feedsWithNew)
                // And the articles themselves, so the lists can draw them in
                // the "new" blue.
                newArticles.add(summary.newArticleIds)
                version++
                // Tell the right-hand pane (two-pane home) to reload as well.
                onChanged()
            } catch (e: CancellationException) {
                // Never swallow cancellation; it must propagate so the
                // coroutine can stop properly.
                throw e
            } catch (t: Throwable) {
                message = "error: ${t.explain()}"
            } finally {
                busy = false
            }
        }
    }

    // The refresh on opening. It waits until the feed list has loaded and there
    // is at least one feed to refresh, then starts exactly once: the caller flips
    // `autoRefresh` off through `onAutoRefreshStarted`, and this effect re-runs
    // when that happens but finds nothing left to do.
    LaunchedEffect(autoRefresh, hasFeeds) {
        if (autoRefresh && hasFeeds && !busy) {
            onAutoRefreshStarted()
            doRefreshAll()
        }
    }

    // The list's scroll position. It is created here, not inside the column
    // below, so the helper that follows can watch it and move it.
    val scrollState = rememberScrollState()

    // Put the list back where it was when this screen is reached by "back",
    // once the feeds have loaded, and keep writing down where it is.
    RestoreAndTrackScroll(
        state = scrollState,
        memory = scrollMemory,
        key = scrollKey,
        ready = loaded != null,
    )

    PinnedLayout(
        // Top bar. Home: the logo and a one-line theme switcher. A topic
        // page: the home button and where you are.
        top = {
            if (topic == null) {
                Banner(color = bannerColor)
                // One small line of rarely used controls: the theme switcher
                // on the left, import / export on the right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MonoText(
                        text = "theme: $themeName",
                        color = colors.textMuted,
                        size = 12.sp,
                        modifier = Modifier
                            .clickable(onClick = onCycleTheme)
                            .padding(vertical = 6.dp),
                    )
                    MonoText(
                        text = "import / export",
                        color = colors.textMuted,
                        size = 12.sp,
                        modifier = Modifier
                            .clickable(onClick = onOpenOpml)
                            .padding(vertical = 6.dp),
                    )
                    // Optional sync, tucked in with the other rarely used
                    // settings. Off until the person turns it on there.
                    MonoText(
                        text = "sync",
                        color = colors.textMuted,
                        size = 12.sp,
                        modifier = Modifier
                            .clickable(onClick = onOpenSync)
                            .padding(vertical = 6.dp),
                    )
                }
                // The ways in to Read Later and to saved articles and notes,
                // kept on their own line so they are easy to find.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MonoText(
                        text = if (readLaterCount > 0) "read later ($readLaterCount)" else "read later",
                        color = colors.accentUnread,
                        size = 12.sp,
                        bold = true,
                        modifier = Modifier
                            .clickable(onClick = onOpenReadLater)
                            .padding(vertical = 6.dp),
                    )
                    MonoText(
                        text = "saved articles & notes",
                        color = colors.accentSaved,
                        size = 12.sp,
                        bold = true,
                        modifier = Modifier
                            .clickable(onClick = onOpenSaved)
                            .padding(vertical = 6.dp),
                    )
                }
            } else {
                TopBar(path = "tuxwire / $topic", onHome = onHome)
            }
        },
        // Bottom bar, with the removal question and result message above it.
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The confirmation, shown only while a removal is pending.
                // Removing a feed keeps the articles already fetched from it.
                pendingRemoval?.let { feed ->
                    TuiPanel(title = "remove feed?") {
                        MonoText("Remove \"${feed.name}\"?", colors.textPrimary)
                        MonoText("Articles already fetched are kept.", colors.textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TuiButton(
                                label = "yes, remove",
                                onClick = {
                                    pendingRemoval = null
                                    scope.launch {
                                        message = try {
                                            onIo { db.removeFeed(feed.id) }
                                            version++
                                            ""
                                        } catch (t: Throwable) {
                                            "error: ${t.explain()}"
                                        }
                                    }
                                },
                            )
                            TuiButton(label = "cancel", onClick = { pendingRemoval = null })
                        }
                    }
                }
                if (message.isNotEmpty()) MonoText(message, colors.textMuted)

                BottomBar {
                    if (topic == null) {
                        TuiButton(label = "+ add feed", onClick = onAddFeed)
                        TuiButton(
                            label = if (busy) "refreshing..." else "refresh all",
                            enabled = !busy && hasFeeds,
                            onClick = doRefreshAll,
                        )
                    } else {
                        TuiButton(label = "back", onClick = onBack)
                        TuiButton(label = "+ add feed here", onClick = onAddFeed)
                    }
                }
            }
        },
    ) {
        // The middle: the only part that scrolls.
        // On a wide screen the feed list takes the left part and `rightPane` the
        // rest, with a thin line between them. Otherwise (one pane) the list
        // simply fills the width, as it always did.
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(if (rightPane != null) 0.38f else 1f)
                    .fillMaxHeight(),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when {
                        loaded == null -> MonoText("loading...", colors.textMuted)
                        loaded.isFailure -> MonoText(
                            "error: ${loaded.exceptionOrNull()?.explain()}",
                            colors.accentBreaking,
                        )
                        // On the home screen an empty list means the app is brand new.
                        loaded.getOrThrow().isEmpty() && dataTopic == null -> TuiPanel(title = "no feeds yet") {
                            MonoText("Add your first feed to get started.", colors.textPrimary)
                            TuiButton(label = "+ add your first feed", onClick = onAddFeed)
                        }
                        loaded.getOrThrow().isEmpty() ->
                            MonoText("No feeds in this topic.", colors.textMuted)
                        else -> TuiPanel(title = "feeds") {
                            // Two-pane home: the row that shows every feed's newest articles
                            // on the right. Highlighted while nothing else is selected.
                            if (onSelectAll != null) {
                                TuiRow(
                                    onClick = onSelectAll,
                                    modifier = if (selectedFeedUrl == null) selectedRowBackground else Modifier,
                                ) {
                                    MonoText("all feeds", colors.accentSelected, bold = true)
                                    MonoText("newest articles from every feed", colors.textMuted)
                                }
                            }
                            // Two-pane home: a topic label was tapped, so only that
                            // topic's feeds are listed. This row puts the full list back.
                            if (filterTopic != null) {
                                TuiRow(onClick = onClearFilter) {
                                    MonoText("[$filterTopic]", colors.accentSelected, bold = true)
                                    MonoText("tap to show every feed again", colors.textMuted)
                                }
                            }
                            for (feed in loaded.getOrThrow()) {
                                // Each feed: its name and topic on the left, a remove
                                // button on the right.
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        // A press-and-hold opens the edit form.
                                        TuiRow(
                                            onClick = { onOpenFeed(feed) },
                                            onLongClick = { onEditFeed(feed) },
                                            // Two-pane home: the feed shown on the right.
                                            modifier = if (feed.url == selectedFeedUrl) {
                                                selectedRowBackground
                                            } else {
                                                Modifier
                                            },
                                        ) {
                                            // A ` *` after the name, and the "new" blue, while
                                            // this feed has new articles that have not been
                                            // opened yet. Opening the feed clears the mark and
                                            // the name goes back to the unread (or read) colour.
                                            // Reading `freshFeeds.has` here is what makes the
                                            // list redraw when the set changes. (The row is a
                                            // column, so the marker sits in the name's own
                                            // text to stay on the same line.)
                                            val isFresh = freshFeeds.has(feed.url)
                                            MonoText(
                                                text = if (isFresh) "${feed.name} *" else feed.name,
                                                color = when {
                                                    isFresh -> colors.accentNew
                                                    feed.unread > 0u -> colors.accentUnread
                                                    else -> colors.accentRead
                                                },
                                                bold = true,
                                            )
                                            MonoText("${feed.unread} unread", colors.textMuted)
                                        }
                                        // On the home screen, the topic is its own tap
                                        // target that opens that topic's feeds. (Inside a
                                        // topic it would only repeat the header.)
                                        if (topic == null) {
                                            MonoText(
                                                text = "[${feed.topic}]",
                                                color = colors.accentSelected,
                                                size = 12.sp,
                                                modifier = Modifier
                                                    .clickable { onOpenTopic(feed.topic) }
                                                    .padding(bottom = 6.dp),
                                            )
                                        }
                                    }
                                    TuiButton(label = "x", onClick = { pendingRemoval = feed })
                                }
                            }
                            // A hint, because a long press has nothing on screen to
                            // show it is there.
                            MonoText(
                                text = "press and hold a feed to edit its name or topic",
                                color = colors.textMuted,
                                size = 12.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            if (rightPane != null) {
                // The divider line.
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.panelBorder),
                )
                Box(
                    modifier = Modifier
                        .weight(0.62f)
                        .fillMaxHeight()
                        .padding(start = 12.dp),
                ) {
                    rightPane()
                }
            }
        }
    }
}
