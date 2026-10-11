package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.TopicInfo
import uniffi.tuxwire_ffi.previewFeed

/**
 * The add-feed form: type an address, tap "check" to download it and fill in
 * the feed's own title, choose or type a topic, then "save".
 *
 * Checking first means a typo is caught here, with a clear message, instead of
 * as a feed that silently never works.
 *
 * @param db the open database.
 * @param startTopic a topic to pre-fill (empty for none).
 * @param onHome jumps straight to the feed list (the "home" button at the top).
 * @param onBack leaves without saving and returns to the previous screen (the
 *   "cancel" button at the bottom). There is no separate "back" button: cancel
 *   goes to exactly the same place.
 * @param onDone called after the feed was saved.
 */
@Composable
fun AddFeedScreen(
    db: Database,
    startTopic: String,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalTuxwireColors.current

    // What has been typed. `rememberSaveable` keeps it across rotation, so a
    // half-filled form is not lost.
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf(startTopic) }
    var status by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The topics that already exist, offered below the topic box so a tap
    // reuses one instead of typing it again (and mistyping it).
    val existing by produceState<List<TopicInfo>>(emptyList(), db) {
        value = runCatching { onIo { db.listTopics() } }.getOrDefault(emptyList())
    }

    // Saving needs all three boxes filled in.
    val canSave = !busy && url.isNotBlank() && name.isNotBlank() && topic.isNotBlank()

    // The same frame as the other screens: "home" pinned at the top, the
    // action buttons pinned at the bottom (above the keyboard, within thumb
    // reach), and only the form in the middle scrolls.
    PinnedLayout(
        top = { TopBar(path = "tuxwire / add feed", onHome = onHome) },
        bottom = {
            BottomBar {
                TuiButton(
                    label = "save",
                    enabled = canSave,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                // Save on a background thread, then leave the form.
                                onIo { db.addFeed(name, normalisedFeedUrl(url), topic) }
                                onDone()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                // For example "this feed is already added".
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
            TuiPanel(title = "feed") {
                TuiField(
                    label = "address",
                    value = url,
                    onValueChange = { url = it },
                    keyboardType = KeyboardType.Uri,
                )
                TuiButton(
                    label = if (busy) "checking..." else "check",
                    enabled = !busy && url.isNotBlank(),
                    onClick = {
                        busy = true
                        status = "checking..."
                        scope.launch {
                            try {
                                // Download and read the feed. If it works we learn its
                                // own title and how many articles it has.
                                val preview = previewFeed(normalisedFeedUrl(url))
                                // Only suggest a name if the person has not typed one.
                                if (name.isBlank()) name = preview.title
                                status = "found: ${preview.title} (${preview.entryCount} articles)"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                status = "error: ${t.explain()}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
                if (status.isNotEmpty()) {
                    MonoText(
                        text = status,
                        color = if (status.startsWith("error")) colors.accentBreaking else colors.textMuted,
                    )
                }
            }

            TuiPanel(title = "details") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TuiField(label = "name", value = name, onValueChange = { name = it })
                    TuiField(label = "topic", value = topic, onValueChange = { topic = it })
                }
                // Existing topics, tap to use one.
                if (existing.isNotEmpty()) {
                    MonoText("existing topics (tap to use):", colors.textMuted, Modifier.padding(top = 10.dp))
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
            }
        }
    }
}
