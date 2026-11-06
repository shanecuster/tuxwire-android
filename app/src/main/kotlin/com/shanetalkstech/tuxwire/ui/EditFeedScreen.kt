package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.FeedInfo
import uniffi.tuxwire_ffi.TopicInfo

/**
 * The edit-feed form: change a feed's name and topic, for example to fix a
 * typo without removing the feed and adding it again.
 *
 * The feed's address is shown but cannot be changed here. A feed's articles are
 * found by its address, so changing it would leave every article already
 * fetched behind, and sync uses the address as the feed's identity. A wrong
 * address is fixed by removing the feed and adding it again.
 *
 * @param db the open database.
 * @param feedId the database ID of the feed being edited.
 * @param onHome jumps straight to the feed list (the "home" button at the top).
 * @param onBack leaves without saving (the "cancel" button at the bottom).
 * @param onDone called after the changes were saved.
 */
@Composable
fun EditFeedScreen(
    db: Database,
    feedId: Long,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalTuxwireColors.current

    // The feed as it is stored right now. `produceState` loads it on a
    // background thread: `null` while loading, then a `Result` that holds the
    // feed (or `null` inside it if the feed no longer exists) or the error.
    val loaded by produceState<Result<FeedInfo?>?>(null, db, feedId) {
        value = runCatching {
            onIo { db.listAllFeeds().firstOrNull { it.id == feedId } }
        }
    }
    val feed = loaded?.getOrNull()

    // What has been typed. `rememberSaveable` keeps it across rotation, so a
    // half-finished edit is not lost.
    var name by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    // True once the boxes have been filled from the stored feed. Saved too, so
    // a rotation does not overwrite what the person has typed since.
    var filled by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // When the feed has loaded for the first time, put its current name and
    // topic in the boxes so the person edits them instead of retyping.
    LaunchedEffect(feed) {
        if (feed != null && !filled) {
            name = feed.name
            topic = feed.topic
            filled = true
        }
    }

    // The topics that already exist, offered below the topic box so a tap
    // reuses one instead of typing it again (and mistyping it).
    val existing by produceState<List<TopicInfo>>(emptyList(), db) {
        value = runCatching { onIo { db.listTopics() } }.getOrDefault(emptyList())
    }

    // Saving needs both boxes filled in, and only makes sense if something
    // actually changed. Core trims spaces, so compare the trimmed text.
    val changed = feed != null && (name.trim() != feed.name || topic.trim() != feed.topic)
    val canSave = !busy && filled && name.isNotBlank() && topic.isNotBlank() && changed

    // The same frame as the other screens: "home" pinned at the top, the
    // buttons pinned at the bottom, and only the form in the middle scrolls.
    PinnedLayout(
        top = { TopBar(path = "tuxwire / edit feed", onHome = onHome) },
        bottom = {
            BottomBar {
                TuiButton(
                    label = "save",
                    enabled = canSave,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                // Save on a background thread. `false` means
                                // the feed was removed in the meantime (for
                                // example by sync), so there was nothing to
                                // update.
                                val updated = onIo { db.updateFeed(feedId, name, topic) }
                                if (updated) {
                                    onDone()
                                } else {
                                    status = "error: this feed no longer exists"
                                }
                            } catch (e: CancellationException) {
                                // Never swallow cancellation; it must propagate
                                // so the coroutine can stop properly.
                                throw e
                            } catch (t: Throwable) {
                                status = "error: ${t.explain()}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
                TuiButton(label = "cancel", onClick = onBack)
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                loaded == null -> MonoText("loading...", colors.textMuted)
                loaded?.isFailure == true -> MonoText(
                    "error: ${loaded?.exceptionOrNull()?.explain()}",
                    colors.accentBreaking,
                )
                feed == null -> MonoText("This feed no longer exists.", colors.textMuted)
                else -> {
                    TuiPanel(title = "details") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TuiField(label = "name", value = name, onValueChange = { name = it })
                            TuiField(label = "topic", value = topic, onValueChange = { topic = it })
                        }
                        // Existing topics, tap to use one.
                        if (existing.isNotEmpty()) {
                            MonoText(
                                "existing topics (tap to use):",
                                colors.textMuted,
                                Modifier.padding(top = 10.dp),
                            )
                            for (t in existing) {
                                MonoText(
                                    text = t.name,
                                    color = colors.accentUnread,
                                    modifier = Modifier
                                        .clickable { topic = t.name }
                                        .padding(vertical = 6.dp),
                                )
                            }
                        }
                        if (status.isNotEmpty()) {
                            MonoText(
                                text = status,
                                color = colors.accentBreaking,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }

                    // The address, shown for reference but not editable.
                    TuiPanel(title = "address") {
                        MonoText(feed.url, colors.textMuted)
                        MonoText(
                            "The address cannot be changed here. To use a " +
                                "different one, remove this feed and add it again.",
                            colors.textMuted,
                            Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
