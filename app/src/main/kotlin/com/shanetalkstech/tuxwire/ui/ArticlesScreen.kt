package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.ArticleInfo
import uniffi.tuxwire_ffi.Database

/**
 * The third screen: one feed's articles, newest first. Tapping an article
 * marks it read, then opens its summary screen; an article with no summary
 * skips that screen and opens straight in the browser. "refresh" downloads
 * just this feed.
 *
 * The top bar (home button and breadcrumb) and the bottom bar ("back" and
 * "refresh") stay put; only the list between them scrolls.
 *
 * On a wide screen (an unfolded foldable, a tablet) the same list is also the
 * right-hand pane of the home screen. Then [embedded] is `true`: the screen
 * draws no bars of its own (the home screen's bars span both panes) and shows
 * a small header with the name and a refresh button instead. With [allFeeds]
 * it lists the newest articles of every feed together, like the terminal app's
 * "All feeds" row.
 *
 * @param db the open database.
 * @param topic the feed's topic (for the header only).
 * @param feedName the feed's name (for the header only).
 * @param feedUrl the feed's address, which identifies its articles.
 * @param scrollMemory remembers where the list was scrolled to, so coming back
 *   from an article finds the list in the same place (handy in a long feed).
 * @param scrollKey which entry of [scrollMemory] belongs to this list.
 * @param newArticles the articles a refresh brought in since the app opened. An
 *   unread one is drawn in the "new" blue; a refresh here adds to it.
 * @param onHome jumps back to the home screen.
 * @param onBack goes back to the feed list.
 * @param onOpenArticle opens the summary screen for the article with this ID.
 * @param allFeeds list the newest articles from every feed instead of one feed
 *   ([feedUrl] is then ignored). A refresh then refreshes every feed.
 * @param embedded draw as the right-hand pane of the two-pane home screen: no
 *   top or bottom bar, a small header instead ([onHome] and [onBack] are unused).
 * @param reloadKey changing this number reloads the list. The two-pane home
 *   bumps it when something outside this pane (such as a refresh in the feed
 *   list) may have changed the articles.
 * @param onChanged called after this list changed something the rest of the
 *   screen shows (a refresh, or an article marked read), so the feed list's
 *   unread counts can be reloaded.
 * @param freshFeeds when given, an all-feeds refresh adds the feeds that got new
 *   articles to it, so the feed list marks them.
 */
@Composable
fun ArticlesScreen(
    db: Database,
    topic: String,
    feedName: String,
    feedUrl: String,
    scrollMemory: ScrollMemory,
    scrollKey: String,
    newArticles: NewArticles,
    onOpenArticle: (Long) -> Unit,
    onHome: () -> Unit = {},
    onBack: () -> Unit = {},
    allFeeds: Boolean = false,
    embedded: Boolean = false,
    reloadKey: Int = 0,
    onChanged: () -> Unit = {},
    freshFeeds: FreshFeeds? = null,
) {
    val colors = LocalTuxwireColors.current
    val context = LocalContext.current

    // The list's scroll position. If this screen was reached by "back", start
    // from where the list was left (the first row on screen, and how far into
    // it); otherwise the memory has nothing and the list starts at the top.
    // The `remember` reads the memory once, before the tracker below starts
    // writing the new position over it.
    val saved = remember(scrollKey) { scrollMemory.get(scrollKey) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.first ?: 0,
        initialFirstVisibleItemScrollOffset = saved?.offset ?: 0,
    )
    // Keep writing down where the list is, so leaving this screen is remembered.
    TrackLazyScroll(state = listState, memory = scrollMemory, key = scrollKey)

    // Bumped after a refresh so the list loads again.
    var version by remember { mutableIntStateOf(0) }
    val articles by produceState<Result<List<ArticleInfo>>?>(
        null, db, feedUrl, allFeeds, version, reloadKey,
    ) {
        value = runCatching {
            onIo {
                // "All feeds" is capped (the newest few hundred); one feed is not.
                if (allFeeds) db.listAllArticles(ALL_FEEDS_LIMIT) else db.listArticles(feedUrl)
            }
        }
    }

    // IDs of articles tapped on this screen. They are drawn as read at once,
    // without waiting for the list to reload from the database.
    var readNow by remember { mutableStateOf(setOf<Long>()) }

    // The same idea for articles saved from this list: drawn as saved at once.
    var savedNow by remember { mutableStateOf(setOf<Long>()) }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Downloads this feed (or every feed, in all-feeds mode) and reloads the
    // list. Used by the "refresh" button, wherever that button is drawn.
    val doRefresh: () -> Unit = {
        busy = true
        message = ""
        scope.launch {
            try {
                val summary = if (allFeeds) db.refreshAll() else db.refreshFeed(feedUrl)
                message = summary.describe()
                // Remember which articles are new, so they are drawn in the
                // "new" blue once the list reloads.
                newArticles.add(summary.newArticleIds)
                // After refreshing everything, mark the feeds that got something.
                if (allFeeds) freshFeeds?.add(summary.feedsWithNew)
                version++
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                message = "error: ${t.explain()}"
            } finally {
                busy = false
            }
        }
    }

    // The list itself (loading text, error, empty note, or the rows). It is a
    // value, not inline, so the two layouts below can both place it.
    val listContent: @Composable () -> Unit = {
        val loaded = articles
        when {
            loaded == null -> MonoText("loading...", colors.textMuted)
            loaded.isFailure -> MonoText(
                "error: ${loaded.exceptionOrNull()?.explain()}",
                colors.accentBreaking,
            )
            loaded.getOrThrow().isEmpty() -> MonoText(
                "No articles yet. Tap refresh to download them.",
                colors.textMuted,
            )
            // `LazyColumn` only builds the rows that are on screen, so a feed
            // with hundreds of articles stays fast.
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(loaded.getOrThrow(), key = { it.id }) { article ->
                    val isRead = article.read || article.id in readNow
                    val isSaved = article.saved || article.id in savedNow
                    // The article on the left, its save button on the right.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            TuiRow(
                                onClick = {
                                    // Show it as read straight away, then save that
                                    // in the background. The save finishes before we
                                    // move on so the list is right when we come back.
                                    readNow = readNow + article.id
                                    scope.launch {
                                        runCatching { onIo { db.markRead(article.id) } }
                                        // The feed list's unread counts changed too.
                                        onChanged()
                                        if (article.hasSummary) {
                                            onOpenArticle(article.id)
                                        } else if (!openInBrowser(context, article.url)) {
                                            message = "could not open this address"
                                        }
                                    }
                                },
                            ) {
                                // A leading "*" marks unread articles in plain text.
                                // Same priority as the terminal app: read first, then
                                // new (arrived since launch and still unread), then
                                // plain unread.
                                MonoText(
                                    text = (if (isRead) "  " else "* ") + article.title,
                                    color = when {
                                        isRead -> colors.accentRead
                                        newArticles.has(article.id) -> colors.accentNew
                                        else -> colors.accentUnread
                                    },
                                    bold = !isRead,
                                )
                                MonoText(
                                    text = "  " + formatPublished(article.published),
                                    color = colors.textMuted,
                                )
                            }
                        }
                        // Saves the article for good (add a note later from its
                        // page or from the Saved list). Dim once saved.
                        TuiButton(
                            label = if (isSaved) "saved" else "save",
                            enabled = !isSaved,
                            onClick = {
                                savedNow = savedNow + article.id
                                scope.launch {
                                    try {
                                        onIo { db.saveArticle(article.id) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (t: Throwable) {
                                        // It did not save: undo the early "saved" look.
                                        savedNow = savedNow - article.id
                                        message = "error: ${t.explain()}"
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (embedded) {
        // The right-hand pane: a header line, then the list. No bars here; the
        // home screen's own bars sit under and over both panes.
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MonoText(
                    text = if (allFeeds) "all feeds" else "$topic / $feedName",
                    color = colors.accentSelected,
                    bold = true,
                    modifier = Modifier.weight(1f),
                )
                TuiButton(
                    label = if (busy) "refreshing..." else "refresh",
                    enabled = !busy,
                    onClick = doRefresh,
                )
            }
            if (message.isNotEmpty()) MonoText(message, colors.textMuted)
            // `weight(1f)` gives the list all the height the header left over.
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                listContent()
            }
        }
    } else {
        PinnedLayout(
            top = { TopBar(path = "tuxwire / $topic / $feedName", onHome = onHome) },
            bottom = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (message.isNotEmpty()) MonoText(message, colors.textMuted)
                    BottomBar {
                        TuiButton(label = "back", onClick = onBack)
                        TuiButton(
                            label = if (busy) "refreshing..." else "refresh",
                            enabled = !busy,
                            onClick = doRefresh,
                        )
                    }
                }
            },
        ) {
            listContent()
        }
    }
}

/**
 * How many articles the "all feeds" list shows at most: the newest few hundred.
 * (A phone does not need thousands of rows, and keeping it small keeps the
 * list quick to build.)
 */
private val ALL_FEEDS_LIMIT = 500u
