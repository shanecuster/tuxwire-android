package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
 * @param db the open database.
 * @param topic the feed's topic (for the header only).
 * @param feedName the feed's name (for the header only).
 * @param feedUrl the feed's address, which identifies its articles.
 * @param onBack goes back to the feed list.
 * @param onOpenArticle opens the summary screen for the article with this ID.
 */
@Composable
fun ArticlesScreen(
    db: Database,
    topic: String,
    feedName: String,
    feedUrl: String,
    onBack: () -> Unit,
    onOpenArticle: (Long) -> Unit,
) {
    val colors = LocalTuxwireColors.current
    val context = LocalContext.current

    // Bumped after a refresh so the list loads again.
    var version by remember { mutableIntStateOf(0) }
    val articles by produceState<Result<List<ArticleInfo>>?>(null, db, feedUrl, version) {
        value = runCatching { onIo { db.listArticles(feedUrl) } }
    }

    // IDs of articles tapped on this screen. They are drawn as read at once,
    // without waiting for the list to reload from the database.
    var readNow by remember { mutableStateOf(setOf<Long>()) }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(path = "tuxwire / $topic / $feedName", onBack = onBack)

        // Refresh button and its result line.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TuiButton(
                label = if (busy) "refreshing..." else "refresh",
                enabled = !busy,
                onClick = {
                    busy = true
                    message = ""
                    scope.launch {
                        try {
                            message = db.refreshFeed(feedUrl).describe()
                            version++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            message = "error: ${t.explain()}"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
        if (message.isNotEmpty()) MonoText(message, colors.textMuted)

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
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(loaded.getOrThrow(), key = { it.id }) { article ->
                    val isRead = article.read || article.id in readNow
                    TuiRow(
                        onClick = {
                            // Show it as read straight away, then save that
                            // in the background. The save finishes before we
                            // move on so the list is right when we come back.
                            readNow = readNow + article.id
                            scope.launch {
                                runCatching { onIo { db.markRead(article.id) } }
                                if (article.hasSummary) {
                                    onOpenArticle(article.id)
                                } else if (!openInBrowser(context, article.url)) {
                                    message = "could not open this address"
                                }
                            }
                        },
                    ) {
                        // A leading "*" marks unread articles in plain text.
                        MonoText(
                            text = (if (isRead) "  " else "* ") + article.title,
                            color = if (isRead) colors.accentRead else colors.accentUnread,
                            bold = !isRead,
                        )
                        MonoText(
                            text = "  " + formatPublished(article.published),
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}
