package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.fetchLinkTitle
import uniffi.tuxwire_ffi.isWebAddress

/**
 * The question asked about one link, every time: read it later, save it, or
 * (for a link shared from another app) add it as a feed.
 *
 * The page's title is looked up in the background so the link can be shown by
 * name instead of by address. If the page cannot be reached, the address is
 * used as the title; the title box can be edited either way. A title that came
 * with a shared link is used as it is, with no lookup. Nothing is stored until
 * one of the buttons is tapped.
 *
 * @param db the open database.
 * @param address the link.
 * @param titleHint a title that came with the link (empty for none).
 * @param fromShare whether the link was shared from another app, which adds the
 *   "add as a feed" choice (the way shared addresses worked before Read Later).
 * @param onHome jumps back to the home screen.
 * @param onBack goes back without doing anything (the cancel button).
 * @param onAddAsFeed opens the add-feed form for this address.
 * @param onAddedToList called after the link was put on the Read Later list.
 * @param onSaved called after the link was saved.
 */
@Composable
fun LinkScreen(
    db: Database,
    address: String,
    titleHint: String,
    fromShare: Boolean,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onAddAsFeed: () -> Unit,
    onAddedToList: () -> Unit,
    onSaved: () -> Unit,
) {
    val colors = LocalTuxwireColors.current
    val scope = rememberCoroutineScope()

    // Whether this really is a web address. Core makes the same check again
    // when storing, so this only decides what the screen offers.
    val valid = remember(address) { isWebAddress(address) }

    // The title being shown (editable), whether a lookup is still running, and
    // a message. A lookup only runs when no title came with the link.
    var title by rememberSaveable { mutableStateOf(titleHint) }
    var lookingUp by remember { mutableStateOf(valid && title.isBlank()) }
    var message by remember { mutableStateOf("") }
    // Set while a button's action is running, so a double tap does nothing.
    var busy by remember { mutableStateOf(false) }

    // Look the title up once when the screen opens. `fetchLinkTitle` never
    // fails: no title found (or no network) comes back as `null`. The person's
    // own typing wins: the answer is only used if the box is still empty.
    LaunchedEffect(address) {
        if (valid && title.isBlank()) {
            try {
                val found = fetchLinkTitle(address)
                if (title.isBlank() && found != null) title = found
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A title is only a courtesy: carry on with the address.
            }
        }
        lookingUp = false
    }

    // Runs one of the storing actions in the background. While it runs `busy`
    // is set (so a second tap does nothing), and any failure becomes a message
    // instead of a crash. `suspend` marks a block that may pause, which the
    // database calls need.
    val perform: (suspend () -> Unit) -> Unit = { action ->
        busy = true
        message = ""
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                message = "error: ${t.explain()}"
            } finally {
                busy = false
            }
        }
    }

    PinnedLayout(
        top = { TopBar(path = "tuxwire / link", onHome = onHome) },
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.isNotEmpty()) MonoText(message, colors.textMuted)
                BottomBar { TuiButton(label = "cancel", onClick = onBack, enabled = !busy) }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TuiPanel(title = "what to do with this link?") {
                MonoText(address, colors.textPrimary)
            }

            if (!valid) {
                // Not a link at all: say so, and offer nothing but cancel.
                MonoText(
                    "That is not a web address (it must start with http:// or https://).",
                    colors.accentBreaking,
                )
            } else {
                TuiField(label = "title (optional)", value = title, onValueChange = { title = it })
                if (lookingUp) {
                    MonoText("looking up the page title...", colors.textMuted, size = 12.sp)
                }

                // Wait for the lookup so the link is stored with its name. It
                // gives up after ten seconds by itself.
                val ready = !lookingUp && !busy
                TuiButton(
                    label = "read later",
                    enabled = ready,
                    onClick = {
                        perform {
                            val added = onIo { db.addReadLater(title, address) }
                            if (added) {
                                onAddedToList()
                            } else {
                                message = "already on your Read Later list"
                            }
                        }
                    },
                )
                MonoText(
                    "Keeps it on a list to open later. Opened links stay on the list until you drop them.",
                    colors.textMuted,
                    size = 12.sp,
                )
                TuiButton(
                    label = "save",
                    enabled = ready,
                    onClick = {
                        perform {
                            onIo { db.saveLink(title, address) }
                            onSaved()
                        }
                    },
                )
                MonoText(
                    "Keeps it for good with your saved articles, where you can add a note.",
                    colors.textMuted,
                    size = 12.sp,
                )
                if (fromShare) {
                    TuiButton(label = "add as feed", enabled = !busy, onClick = onAddAsFeed)
                    MonoText(
                        "For a page that is a feed or has one: follow it for new articles.",
                        colors.textMuted,
                        size = 12.sp,
                    )
                }
            }
        }
    }
}
