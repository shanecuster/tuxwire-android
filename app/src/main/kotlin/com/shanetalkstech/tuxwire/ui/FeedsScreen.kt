package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.FeedInfo

/**
 * The second screen: the feeds filed under one topic. Tapping a feed slides to
 * its articles; "remove" (after a confirmation) unsubscribes from it.
 *
 * @param db the open database.
 * @param topic which topic's feeds to show.
 * @param onBack goes back to the topic list.
 * @param onOpenFeed called with the tapped feed.
 * @param onAddFeed called when "add feed here" is tapped.
 */
@Composable
fun FeedsScreen(
    db: Database,
    topic: String,
    onBack: () -> Unit,
    onOpenFeed: (FeedInfo) -> Unit,
    onAddFeed: () -> Unit,
) {
    val colors = LocalTuxwireColors.current

    // Bumped after a removal so the list loads again (see `TopicsScreen`).
    var version by remember { mutableIntStateOf(0) }
    val feeds by produceState<Result<List<FeedInfo>>?>(null, db, topic, version) {
        value = runCatching { onIo { db.listFeedsInTopic(topic) } }
    }

    // The feed the person asked to remove, waiting for a "yes". `null` means
    // no removal is pending.
    var pendingRemoval by remember { mutableStateOf<FeedInfo?>(null) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(path = "tuxwire / $topic", onBack = onBack)

        val loaded = feeds
        when {
            loaded == null -> MonoText("loading...", colors.textMuted)
            loaded.isFailure -> MonoText(
                "error: ${loaded.exceptionOrNull()?.explain()}",
                colors.accentBreaking,
            )
            loaded.getOrThrow().isEmpty() -> MonoText("No feeds in this topic.", colors.textMuted)
            else -> TuiPanel(title = "feeds") {
                for (feed in loaded.getOrThrow()) {
                    // Each feed: a tappable name and count, with a remove button.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TuiRow(onClick = { onOpenFeed(feed) }, modifier = Modifier.weight(1f)) {
                            MonoText(
                                text = feed.name,
                                color = if (feed.unread > 0u) colors.accentUnread else colors.accentRead,
                                bold = true,
                            )
                            MonoText("${feed.unread} unread", colors.textMuted)
                        }
                        TuiButton(label = "x", onClick = { pendingRemoval = feed })
                    }
                }
            }
        }

        // The confirmation line, shown only while a removal is pending. Removing
        // a feed keeps the articles already fetched from it.
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

        if (message.isNotEmpty()) MonoText(message, colors.accentBreaking)
        TuiButton(label = "+ add feed here", onClick = onAddFeed)
    }
}
