package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.FeedInfo

/**
 * A list of feeds. This one screen plays two roles:
 *
 * - **Home** (`topic == null`): the app's first screen, showing *every* feed
 *   alphabetically. Each feed shows its topic underneath its name; tapping
 *   that topic opens the same screen filtered to it. Home also carries the
 *   "refresh all" button and the theme switcher.
 * - **One topic** (`topic == "linux"`): only that topic's feeds, with a back
 *   button.
 *
 * Tapping a feed's name slides to its articles; "x" (after a confirmation)
 * unsubscribes from it.
 *
 * @param db the open database.
 * @param topic the topic to show, or `null` for every feed (the home screen).
 * @param themeName the active theme's name (shown on home only).
 * @param onCycleTheme called when the theme panel is tapped (home only).
 * @param onBack goes back; `null` hides the back button (home has nowhere to
 *   go back to).
 * @param onOpenFeed called with the tapped feed.
 * @param onOpenTopic called with a topic's name when a topic label is tapped.
 * @param onAddFeed called when "+ add feed" is tapped.
 */
@Composable
fun FeedsScreen(
    db: Database,
    topic: String?,
    themeName: String,
    onCycleTheme: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenFeed: (FeedInfo) -> Unit,
    onOpenTopic: (String) -> Unit,
    onAddFeed: () -> Unit,
) {
    val colors = LocalTuxwireColors.current

    // Bumped to make the list load again, for example after a removal or a
    // refresh that brings in new articles.
    var version by remember { mutableIntStateOf(0) }

    // Load the feeds on a background thread. `produceState` runs the block
    // whenever `db`, `topic` or `version` changes and exposes the latest answer
    // as state: null while the first load runs, then a `Result` that is either
    // the feeds or the error. Keeping the old value while reloading means the
    // list does not flicker empty.
    val feeds by produceState<Result<List<FeedInfo>>?>(null, db, topic, version) {
        value = runCatching {
            onIo { if (topic == null) db.listAllFeeds() else db.listFeedsInTopic(topic) }
        }
    }

    // The feed the person asked to remove, waiting for a "yes". `null` means
    // no removal is pending.
    var pendingRemoval by remember { mutableStateOf<FeedInfo?>(null) }

    // State for the refresh button: whether one is running, and what it said.
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(path = if (topic == null) "tuxwire" else "tuxwire / $topic", onBack = onBack)

        val loaded = feeds
        when {
            loaded == null -> MonoText("loading...", colors.textMuted)
            loaded.isFailure -> MonoText(
                "error: ${loaded.exceptionOrNull()?.explain()}",
                colors.accentBreaking,
            )
            // On the home screen an empty list means the app is brand new.
            loaded.getOrThrow().isEmpty() && topic == null -> TuiPanel(title = "no feeds yet") {
                MonoText("Add your first feed to get started.", colors.textPrimary)
                TuiButton(label = "+ add your first feed", onClick = onAddFeed)
            }
            loaded.getOrThrow().isEmpty() -> MonoText("No feeds in this topic.", colors.textMuted)
            else -> TuiPanel(title = "feeds") {
                for (feed in loaded.getOrThrow()) {
                    // Each feed: its name and topic on the left, a remove button
                    // on the right.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            TuiRow(onClick = { onOpenFeed(feed) }) {
                                MonoText(
                                    text = feed.name,
                                    color = if (feed.unread > 0u) colors.accentUnread else colors.accentRead,
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
            }
        }

        // The confirmation, shown only while a removal is pending. Removing a
        // feed keeps the articles already fetched from it.
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

        // Buttons. Refresh-all only makes sense on home, once there is a feed.
        val hasFeeds = loaded?.getOrNull()?.isNotEmpty() == true
        if (hasFeeds || topic != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TuiButton(
                    label = if (topic == null) "+ add feed" else "+ add feed here",
                    onClick = onAddFeed,
                )
                if (topic == null && hasFeeds) {
                    TuiButton(
                        label = if (busy) "refreshing..." else "refresh all",
                        enabled = !busy,
                        onClick = {
                            // Start the refresh in the background and report back.
                            busy = true
                            message = ""
                            scope.launch {
                                try {
                                    // `refreshAll` is a Kotlin `suspend fun`, generated
                                    // from an async Rust function. It waits for the
                                    // network without freezing the screen.
                                    message = db.refreshAll().describe()
                                    version++
                                } catch (e: CancellationException) {
                                    // Never swallow cancellation; it must propagate
                                    // so the coroutine can stop properly.
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
            }
        }
        if (message.isNotEmpty()) MonoText(message, colors.textMuted)

        // Home only: tapping this panel switches to the next theme (and
        // remembers it).
        if (topic == null) {
            TuiPanel(title = "theme", modifier = Modifier.clickable(onClick = onCycleTheme)) {
                MonoText(themeName, colors.textPrimary)
                MonoText("tap to switch", colors.textDim)
            }
        }
    }
}
