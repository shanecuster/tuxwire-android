package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.FeedRemovalRequest
import uniffi.tuxwire_ffi.syncTestConnection

/**
 * The optional sync screen: copy saved articles and notes to a CouchDB server
 * the person runs themselves, and receive the ones saved on their other
 * devices.
 *
 * Rules this screen keeps:
 * - Sync is **off** until the person turns it on and saves. Nothing is sent or
 *   fetched before that, and "sync now" is the only thing that runs it (there
 *   is no background sync).
 * - The main switch syncs saved articles, their notes and the Read Later list
 *   (links to read later, and whether each was opened). Which feed articles you
 *   have read is synced only if the **read marks** switch is on too (off by
 *   default, and only while the main switch is on): the address of each article
 *   opened in the last 90 days is then sent to your server.
 * - The **feed list** is synced only if its own, separate switch is on (off by
 *   default), and only while the main switch is on. Feeds another device added
 *   are added here; a feed another device *removed* is never removed on its
 *   own: a question appears first, and only "yes, remove" removes it
 *   (its articles are kept either way).
 * - The password is typed into a masked box and stored encrypted by [SyncStore]
 *   (Android Keystore). It is never shown, logged or put in messages.
 * - Changing the server address or database starts sync afresh
 *   (`resetSyncState`); articles and notes on this phone are never touched.
 * - "forget everything" asks first, then wipes the saved settings.
 *
 * The edits on screen are a draft: they only take effect when "save" is tapped,
 * and "sync now" is dimmed while there are unsaved edits so it can never run
 * with settings different from what is shown.
 *
 * @param db the open database.
 * @param onHome jumps back to the home screen.
 * @param onBack goes back one screen.
 */
@Composable
fun SyncScreen(
    db: Database,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalTuxwireColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SyncStore(context) }

    // What is saved on the phone right now. Reloaded after "save" and "forget".
    var saved by remember { mutableStateOf(store.load()) }

    // The draft being edited. The text boxes use `remember` rather than
    // `rememberSaveable` on purpose: the password must not be copied into
    // Android's saved-state bundle when the screen rotates.
    var enabled by remember { mutableStateOf(saved.enabled) }
    var url by remember { mutableStateOf(saved.url) }
    var database by remember { mutableStateOf(saved.database) }
    var username by remember { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    // The separate "also sync the feed list" switch (part of the draft).
    var feeds by remember { mutableStateOf(saved.feeds) }
    // The separate "also sync which articles I opened" switch (part of the draft).
    var readMarks by remember { mutableStateOf(saved.readMarks) }

    // True while the "forget everything?" question is showing.
    var confirmingForget by rememberSaveable { mutableStateOf(false) }
    // The last result line, and whether it was a failure (drawn in red).
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    // True while a network step runs, so a button cannot be tapped twice.
    var busy by remember { mutableStateOf(false) }
    // Feeds another device removed that this phone still has. Each one waits
    // for a yes or no; nothing is removed until the person says yes.
    var removals by remember { mutableStateOf<List<FeedRemovalRequest>>(emptyList()) }

    // On opening the screen, show any removal question still waiting from an
    // earlier sync. This reads only the local database (no network), and
    // only when feed sync is on.
    LaunchedEffect(saved.enabled, saved.feeds) {
        removals = if (saved.enabled && saved.feeds) onIo { db.pendingFeedRemovals() } else emptyList()
    }

    // The draft as one value, to compare with what is saved.
    val draft = SyncSettings(
        enabled, url.trim(), database.trim(), username.trim(), password, feeds, readMarks,
    )
    val dirty = draft != saved
    // "Ready" means every box is filled, so the buttons make sense.
    val filled = draft.url.isNotEmpty() && draft.database.isNotEmpty() &&
        draft.username.isNotEmpty() && draft.password.isNotEmpty()

    // Runs `work` in the background with the busy flag and error handling in
    // one place. `work` returns the line to show.
    fun run(work: suspend () -> String) {
        scope.launch {
            busy = true
            try {
                message = work()
                isError = false
            } catch (e: CancellationException) {
                // Never swallow cancellation; it must propagate.
                throw e
            } catch (t: Throwable) {
                // Core's messages are fixed plain sentences that never contain
                // the address or the password.
                message = "error: ${t.explain()}"
                isError = true
            } finally {
                busy = false
            }
        }
    }

    PinnedLayout(
        top = { TopBar(path = "tuxwire / sync (optional)", onHome = onHome) },
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.isNotEmpty()) {
                    MonoText(message, if (isError) colors.accentBreaking else colors.textMuted)
                }

                // A feed another device removed: ask about the first one. Only
                // "yes, remove" takes it out; its articles stay either way.
                val asked = removals.firstOrNull()
                if (asked != null && saved.enabled && saved.feeds) {
                    TuiPanel(title = "feed removed on another device") {
                        MonoText(
                            "Another device removed \"${asked.name}\" (${asked.url}). " +
                                "Remove it from this phone too? Its articles are kept.",
                            colors.textPrimary,
                        )
                        if (removals.size > 1) {
                            MonoText("${removals.size - 1} more waiting", colors.textMuted)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TuiButton(
                                label = "yes, remove",
                                enabled = !busy,
                                onClick = {
                                    run {
                                        onIo { db.confirmFeedRemoval(asked.url) }
                                        removals = removals.drop(1)
                                        "removed \"${asked.name}\""
                                    }
                                },
                            )
                            TuiButton(
                                label = "keep it",
                                enabled = !busy,
                                onClick = {
                                    run {
                                        onIo { db.keepFeed(asked.url) }
                                        removals = removals.drop(1)
                                        "kept \"${asked.name}\""
                                    }
                                },
                            )
                        }
                    }
                }

                // The question before the saved settings are wiped.
                if (confirmingForget) {
                    TuiPanel(title = "forget sync settings?") {
                        MonoText(
                            "This turns sync off and deletes the saved address, login and " +
                                "password from this phone. Your saved articles, notes and Read Later " +
                                "links, here and on your server, are not touched.",
                            colors.textPrimary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TuiButton(
                                label = "yes, forget",
                                onClick = {
                                    confirmingForget = false
                                    store.clear()
                                    saved = store.load()
                                    enabled = false
                                    url = ""
                                    database = ""
                                    username = ""
                                    password = ""
                                    feeds = false
                                    readMarks = false
                                    removals = emptyList()
                                    // Start afresh if sync is ever set up again.
                                    run {
                                        onIo { db.resetSyncState() }
                                        "forgotten; sync is off"
                                    }
                                },
                            )
                            TuiButton(
                                label = "cancel",
                                onClick = {
                                    confirmingForget = false
                                    message = "kept"
                                    isError = false
                                },
                            )
                        }
                    }
                }

                BottomBar {
                    TuiButton(label = "back", onClick = onBack)
                    TuiButton(
                        label = "save",
                        enabled = dirty && !busy,
                        onClick = {
                            run {
                                // A new server or database means the old
                                // bookkeeping no longer applies.
                                val moved = draft.url != saved.url || draft.database != saved.database
                                if (moved) onIo { db.resetSyncState() }
                                store.save(draft)
                                saved = store.load()
                                if (draft.enabled) "saved; sync is on" else "saved; sync is off"
                            }
                        },
                    )
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // What this does, in plain words.
            MonoText(
                "Optional. Copies your saved articles, notes and Read Later links to a " +
                    "CouchDB server that you run, so your devices share them. Which feed " +
                    "articles you have read is shared only if \"read marks\" below is on. " +
                    "Nothing is sent unless sync is on and you tap \"sync now\".",
                colors.textMuted,
            )

            // The on/off switch.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MonoText("sync:", colors.textPrimary, bold = true)
                TuiButton(
                    label = if (enabled) "on" else "off",
                    enabled = !busy,
                    onClick = { enabled = !enabled },
                )
            }

            // The separate feed-list switch, with what it does in plain words.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MonoText("feed list:", colors.textPrimary, bold = true)
                TuiButton(
                    label = if (feeds) "on" else "off",
                    enabled = !busy,
                    onClick = { feeds = !feeds },
                )
            }
            MonoText(
                "Also copies your list of feeds (names, addresses, topics) so a feed added " +
                    "on one device appears on the others. Your feed addresses are sent to " +
                    "your server only if this is on. A feed another device removed is only " +
                    "removed here if you say yes.",
                colors.textDim,
            )

            // The separate read-marks switch, with what it does in plain words.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MonoText("read marks:", colors.textPrimary, bold = true)
                TuiButton(
                    label = if (readMarks) "on" else "off",
                    enabled = !busy,
                    onClick = { readMarks = !readMarks },
                )
            }
            MonoText(
                "Also shares which articles you have opened, so one you read on another " +
                    "device shows as read here. The address of each article opened in the " +
                    "last 90 days is sent to your server only if this is on. Nothing is " +
                    "deleted from your server, and an article cannot be marked unread again.",
                colors.textDim,
            )

            TuiField("server address (https://...)", url, { url = it }, keyboardType = KeyboardType.Uri)
            TuiField("database", database, { database = it })
            TuiField("username", username, { username = it })
            TuiField("password", password, { password = it }, secret = true)

            if (dirty) MonoText("unsaved changes: tap save", colors.accentSelected)
            MonoText(
                "The password is stored encrypted on this phone only.",
                colors.textDim,
            )

            // Actions. "test connection" uses what is typed; "sync now" uses
            // what is saved, and only while sync is on.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TuiButton(
                    label = "test connection",
                    enabled = filled && !busy,
                    onClick = {
                        run {
                            message = "testing..."
                            isError = false
                            // A `suspend` function (network), so it is called
                            // directly; `onIo` is only for blocking calls.
                            syncTestConnection(draft.url, draft.database, draft.username, draft.password)
                            "connection works"
                        }
                    },
                )
                TuiButton(
                    label = "sync now",
                    enabled = saved.enabled && !dirty && filled && !busy,
                    onClick = {
                        run {
                            message = "syncing..."
                            isError = false
                            val articles = db.syncNow(
                                saved.enabled,
                                saved.readMarks,
                                saved.url,
                                saved.database,
                                saved.username,
                                saved.password,
                            )
                            // The feed list, only if its own switch is saved on.
                            if (saved.feeds) {
                                val feedResult = db.syncFeedsNow(
                                    saved.enabled,
                                    saved.feeds,
                                    saved.url,
                                    saved.database,
                                    saved.username,
                                    saved.password,
                                )
                                removals = feedResult.removals
                                var line = "synced: $articles; feeds: ${feedResult.report}"
                                if (feedResult.added > 0u) {
                                    line += "; ${feedResult.added} new feed(s) added" +
                                        " (refresh to load their articles)"
                                }
                                if (feedResult.removals.isNotEmpty()) {
                                    line += "; ${feedResult.removals.size} removal(s) to answer"
                                }
                                line
                            } else {
                                "synced: $articles"
                            }
                        }
                    },
                )
            }

            TuiButton(
                label = "forget everything",
                enabled = !busy,
                onClick = { confirmingForget = true },
            )
        }
    }
}
