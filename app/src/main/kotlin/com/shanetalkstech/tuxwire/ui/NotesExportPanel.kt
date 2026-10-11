package com.shanetalkstech.tuxwire.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database

/** The settings key under which the "include articles without notes" choice is kept. */
private const val INCLUDE_UNNOTED_SETTING = "export_include_unnoted"

/**
 * The panel that saves your saved articles and notes to one Markdown file.
 *
 * The file has two sections: saved articles without a note first, then your
 * notes in full (the text is built by Rust, so it is the same file the
 * terminal app writes). A switch chooses whether the first section is
 * included; with it off, only the notes are saved. The choice is remembered.
 *
 * The file is saved with Android's own file picker, so the app needs no
 * storage permission and the person chooses where it goes: the phone, or
 * Google Drive if the Drive app is installed (it appears in the picker).
 * Nothing leaves the phone unless the person picks such a place.
 *
 * Exporting only reads: it never changes or deletes a note.
 *
 * @param db the open database.
 */
@Composable
fun NotesExportPanel(db: Database) {
    val colors = LocalTuxwireColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The switch: whether saved articles without a note are included.
    var includeUnnoted by remember { mutableStateOf(true) }
    // What the last action said, and whether it was an error (drawn in red).
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    // True while the text is being built or written, so the button cannot be
    // tapped twice.
    var busy by remember { mutableStateOf(false) }
    // The export text, held while the file picker is open and then written
    // to the file the person chooses.
    var pendingText by remember { mutableStateOf<String?>(null) }

    // Read the remembered choice once. Anything other than an explicit
    // "false" (including nothing saved yet) means "include everything".
    LaunchedEffect(db) {
        val saved = runCatching { onIo { db.getSetting(INCLUDE_UNNOTED_SETTING) } }.getOrNull()
        includeUnnoted = saved != "false"
    }

    // The file picker. `CreateDocument` lets the person choose where to save a
    // new file and gives back its address (`Uri`), or null if they backed out.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri: Uri? ->
        // Take the held text and clear it, so it is never written twice.
        val text = pendingText
        pendingText = null
        if (uri != null && text != null) {
            scope.launch {
                busy = true
                try {
                    onIo { writeText(context, uri, text) }
                    message = "saved your notes"
                    isError = false
                } catch (e: CancellationException) {
                    // Never swallow cancellation; it must propagate.
                    throw e
                } catch (t: Throwable) {
                    message = "error: ${t.explain()}"
                    isError = true
                } finally {
                    busy = false
                }
            }
        }
    }

    TuiPanel(title = "export notes") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoText(
                "Save your saved articles and notes as one Markdown file. " +
                    "Choose where it goes, for example Google Drive.",
                colors.textPrimary,
            )

            // The switch, drawn as a terminal-style checkbox. Tapping it flips
            // the choice and remembers it.
            MonoText(
                text = (if (includeUnnoted) "[x]" else "[ ]") +
                    " include saved articles without notes",
                color = colors.accentSelected,
                modifier = Modifier
                    .clickable {
                        val next = !includeUnnoted
                        includeUnnoted = next
                        scope.launch {
                            runCatching { onIo { db.setSetting(INCLUDE_UNNOTED_SETTING, next.toString()) } }
                        }
                    }
                    .padding(vertical = 6.dp),
            )

            TuiButton(
                label = if (busy) "working..." else "save to a file",
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        message = ""
                        try {
                            // Build the text first, so there is no empty file
                            // left behind when there is nothing to save.
                            val text = onIo { db.exportSaved(includeUnnoted) }
                            if (text.isEmpty()) {
                                message = if (includeUnnoted) {
                                    "nothing is saved yet"
                                } else {
                                    "no notes yet"
                                }
                                isError = false
                            } else {
                                pendingText = text
                                launcher.launch("tuxwire-notes-${LocalDate.now()}.md")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            message = "error: ${t.explain()}"
                            isError = true
                        } finally {
                            busy = false
                        }
                    }
                },
            )

            if (message.isNotEmpty()) {
                MonoText(message, if (isError) colors.accentBreaking else colors.textMuted)
            }
        }
    }
}
