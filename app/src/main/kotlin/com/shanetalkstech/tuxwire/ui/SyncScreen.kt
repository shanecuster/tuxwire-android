package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
 * - Only saved articles and their notes are synced; read/unread is not.
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

    // True while the "forget everything?" question is showing.
    var confirmingForget by rememberSaveable { mutableStateOf(false) }
    // The last result line, and whether it was a failure (drawn in red).
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    // True while a network step runs, so a button cannot be tapped twice.
    var busy by remember { mutableStateOf(false) }

    // The draft as one value, to compare with what is saved.
    val draft = SyncSettings(enabled, url.trim(), database.trim(), username.trim(), password)
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

                // The question before the saved settings are wiped.
                if (confirmingForget) {
                    TuiPanel(title = "forget sync settings?") {
                        MonoText(
                            "This turns sync off and deletes the saved address, login and " +
                                "password from this phone. Your saved articles and notes, here " +
                                "and on your server, are not touched.",
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
                "Optional. Copies your saved articles and notes to a CouchDB server " +
                    "that you run, so your devices share them. Read/unread is not synced. " +
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
                            onIo {
                                syncTestConnection(draft.url, draft.database, draft.username, draft.password)
                            }
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
                            "synced: " + db.syncNow(
                                saved.enabled,
                                saved.url,
                                saved.database,
                                saved.username,
                                saved.password,
                            )
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
