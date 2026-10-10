package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.TopicInfo

/**
 * The first screen: every topic, with how many feeds and unread articles each
 * has. Tapping a topic slides to its feeds.
 *
 * With no feeds yet it shows an "add your first feed" prompt instead; the app
 * deliberately starts empty rather than pushing content on the person.
 *
 * @param db the open database.
 * @param themeName the active theme's name, shown on the theme row.
 * @param onCycleTheme called when the theme row is tapped (switch to the next).
 * @param onOpenTopic called with the topic's name when a topic is tapped.
 * @param onAddFeed called when "add feed" is tapped.
 */
@Composable
fun TopicsScreen(
    db: Database,
    themeName: String,
    onCycleTheme: () -> Unit,
    onOpenTopic: (String) -> Unit,
    onAddFeed: () -> Unit,
) {
    val colors = LocalTuxwireColors.current

    // `version` is bumped to make the list below load again, for example after
    // a refresh brings in new articles.
    var version by remember { mutableIntStateOf(0) }

    // Load the topics on a background thread. `produceState` runs the block
    // whenever `db` or `version` changes and exposes the latest answer as
    // state: null while the first load runs, then a `Result` that is either the
    // topics or the error. Keeping the old value while reloading means the list
    // does not flicker empty after a refresh.
    val topics by produceState<Result<List<TopicInfo>>?>(null, db, version) {
        value = runCatching { onIo { db.listTopics() } }
    }

    // State for the refresh button: whether one is running, and what it said.
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(path = "tuxwire", onBack = null)

        // What to draw depends on the load: still loading, failed, or loaded.
        val loaded = topics
        when {
            loaded == null -> MonoText("loading...", colors.textMuted)
            loaded.isFailure -> MonoText(
                "error: ${loaded.exceptionOrNull()?.explain()}",
                colors.accentBreaking,
            )
            loaded.getOrThrow().isEmpty() -> TuiPanel(title = "no feeds yet") {
                MonoText("Add your first feed to get started.", colors.textPrimary)
                TuiButton(label = "+ add your first feed", onClick = onAddFeed)
            }
            else -> TuiPanel(title = "topics") {
                // One tappable row per topic: its name, then a muted count line.
                for (topic in loaded.getOrThrow()) {
                    TuiRow(onClick = { onOpenTopic(topic.name) }) {
                        MonoText(
                            text = topic.name,
                            color = if (topic.unread > 0u) colors.accentUnread else colors.accentRead,
                            bold = true,
                        )
                        MonoText(
                            text = "${topic.feedCount} feeds, ${topic.unread} unread",
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }

        // Buttons only make sense once there is at least one feed.
        if (loaded?.getOrNull()?.isNotEmpty() == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TuiButton(label = "+ add feed", onClick = onAddFeed)
                TuiButton(
                    label = if (busy) "refreshing..." else "refresh all",
                    enabled = !busy,
                    onClick = {
                        // Start the refresh in the background and report back.
                        busy = true
                        message = ""
                        scope.launch {
                            try {
                                // `refreshAll` is a Kotlin `suspend fun`, generated from
                                // an async Rust function. It waits for the network
                                // without freezing the screen.
                                message = db.refreshAll().describe()
                                version++
                            } catch (e: CancellationException) {
                                // Never swallow cancellation; it must propagate so
                                // the coroutine can stop properly.
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
        }

        // Tapping this panel switches to the next theme (and remembers it).
        TuiPanel(title = "theme", modifier = Modifier.clickable(onClick = onCycleTheme)) {
            MonoText(themeName, colors.textPrimary)
            MonoText("tap to switch", colors.textDim)
        }
    }
}
