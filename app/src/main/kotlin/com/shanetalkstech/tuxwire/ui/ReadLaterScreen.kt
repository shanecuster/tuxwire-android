package com.shanetalkstech.tuxwire.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.ReadLaterEntry
import uniffi.tuxwire_ffi.isWebAddress

/**
 * The Read Later list: links you want to come back to, kept apart from Saved.
 *
 * At the top is a box to paste or type a link; [ add ] opens the question
 * about it ([LinkScreen]: read later, save or cancel). Below are the links,
 * newest first. Tapping one opens it in the browser and marks it read; it stays
 * on the list, drawn in the same colour as a read article in a feed, until you
 * discard it. Each row also has [ save ] (keep it for good, with notes, and
 * take it off this list) and [ drop ] (take it off the list, after a question).
 *
 * If sync is turned on, this list is shared with your other devices like saved
 * articles are; with sync off it stays on this phone.
 *
 * @param db the open database.
 * @param scrollMemory remembers where the list was scrolled to.
 * @param scrollKey which entry of [scrollMemory] belongs to this list.
 * @param onHome jumps back to the home screen.
 * @param onBack goes back one screen.
 * @param onOpenLink opens the question about a pasted link.
 */
@Composable
fun ReadLaterScreen(
    db: Database,
    scrollMemory: ScrollMemory,
    scrollKey: String,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val colors = LocalTuxwireColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Where the list was scrolled to when it was last left (see SavedScreen).
    val saved = remember(scrollKey) { scrollMemory.get(scrollKey) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.first ?: 0,
        initialFirstVisibleItemScrollOffset = saved?.offset ?: 0,
    )
    TrackLazyScroll(state = listState, memory = scrollMemory, key = scrollKey)

    // Bumped after a change so the list loads again.
    var version by remember { mutableIntStateOf(0) }
    val loaded by produceState<Result<List<ReadLaterEntry>>?>(null, db, version) {
        value = runCatching { onIo { db.listReadLater() } }
    }

    // Addresses tapped on this screen: drawn as read at once, without waiting
    // for the list to reload from the database.
    var readNow by remember { mutableStateOf(setOf<String>()) }

    // The text in the paste box (kept across rotation), a message to show, and
    // the link the person asked to discard, waiting for a "yes".
    var pasted by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var pendingDiscard by remember { mutableStateOf<ReadLaterEntry?>(null) }

    PinnedLayout(
        top = { TopBar(path = "tuxwire / read later", onHome = onHome) },
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The question before a link is discarded, like the question
                // before a feed is removed. The wording says what happens with
                // sync on, because that is the surprising case.
                pendingDiscard?.let { item ->
                    TuiPanel(title = "discard?") {
                        MonoText("Take \"${item.title}\" off your Read Later list?", colors.textPrimary)
                        MonoText(
                            "Nothing is deleted from the web. With sync on, your other devices drop it too.",
                            colors.textMuted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TuiButton(
                                label = "yes, discard",
                                onClick = {
                                    pendingDiscard = null
                                    scope.launch {
                                        message = try {
                                            onIo { db.discardReadLater(item.url) }
                                            version++
                                            "discarded"
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (t: Throwable) {
                                            "error: ${t.explain()}"
                                        }
                                    }
                                },
                            )
                            TuiButton(label = "cancel", onClick = { pendingDiscard = null })
                        }
                    }
                }
                if (message.isNotEmpty()) MonoText(message, colors.textMuted)
                BottomBar { TuiButton(label = "back", onClick = onBack) }
            }
        },
    ) {
        val result = loaded
        when {
            result == null -> MonoText("loading...", colors.textMuted)
            result.isFailure -> MonoText(
                "error: ${result.exceptionOrNull()?.explain()}",
                colors.accentBreaking,
            )
            else -> {
                val entries = result.getOrThrow()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The paste box and its button.
                    item {
                        Column {
                            TuiField(
                                label = "paste or type a link",
                                value = pasted,
                                onValueChange = {
                                    pasted = it
                                    message = ""
                                },
                                keyboardType = KeyboardType.Uri,
                            )
                            TuiButton(
                                label = "add",
                                enabled = pasted.isNotBlank(),
                                onClick = {
                                    // Pull the address out of whatever was pasted
                                    // (some apps copy a title and an address).
                                    val address = firstWebAddress(pasted)
                                    if (isWebAddress(address)) {
                                        message = ""
                                        onOpenLink(address)
                                    } else {
                                        message = "that is not a web address " +
                                            "(it must start with http:// or https://)"
                                    }
                                },
                            )
                        }
                    }
                    item {
                        MonoText(
                            text = "${entries.size} to read, ${entries.count { !it.read }} unread",
                            color = colors.textMuted,
                            size = 12.sp,
                        )
                    }
                    if (entries.isEmpty()) {
                        item {
                            MonoText(
                                "Nothing to read later. Paste a link above, or share a page to tuxwire.",
                                colors.textMuted,
                            )
                        }
                    }
                    items(entries, key = { it.url }) { item ->
                        val isRead = item.read || item.url in readNow
                        // The link on the left, its two buttons on the right.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                TuiRow(
                                    onClick = {
                                        // Show it as read straight away, then
                                        // record that and open the page.
                                        readNow = readNow + item.url
                                        scope.launch {
                                            runCatching { onIo { db.markReadLaterRead(item.url) } }
                                            if (!openInBrowser(context, item.url)) {
                                                message = "could not open this address"
                                            }
                                        }
                                    },
                                ) {
                                    // Same look as an article in a feed: unread is
                                    // bold with a leading "*", read is plain.
                                    MonoText(
                                        text = (if (isRead) "  " else "* ") + item.title,
                                        color = if (isRead) colors.accentRead else colors.accentUnread,
                                        bold = !isRead,
                                    )
                                    MonoText(
                                        text = "  ${hostOf(item.url)}  added " +
                                            formatPublished(item.addedAt),
                                        color = colors.textMuted,
                                        size = 12.sp,
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                TuiButton(
                                    label = "save",
                                    onClick = {
                                        scope.launch {
                                            message = try {
                                                onIo { db.promoteReadLater(item.url) }
                                                version++
                                                "saved: add a note from Saved"
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (t: Throwable) {
                                                "error: ${t.explain()}"
                                            }
                                        }
                                    },
                                )
                                TuiButton(
                                    label = "drop",
                                    onClick = { pendingDiscard = item },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The site part of an address ("example.com" for "https://example.com/a/b"),
 * for the small line under a link's title. Falls back to the whole address when
 * it cannot be split (which core's checks make rare).
 */
internal fun hostOf(url: String): String = Uri.parse(url).host ?: url
