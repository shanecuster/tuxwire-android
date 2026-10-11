package com.shanetalkstech.tuxwire.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.OpmlReport

/** The biggest import file we will read into memory: 2 MiB (Rust enforces it too). */
private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024

/**
 * The import / export screen: bring in a feed list from another reader, or
 * save yours as a file.
 *
 * Files are chosen with Android's own file picker, so the app needs no storage
 * permission: it can only see the one file the person picks. The text of the
 * file is handed to Rust, which does all the OPML reading and writing.
 *
 * @param db the open database.
 * @param onHome jumps back to the home screen.
 * @param onBack goes back one screen.
 */
@Composable
fun OpmlScreen(
    db: Database,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalTuxwireColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // What the last action said, and whether it was an error (drawn in red).
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    // True while a file is being read or written, so buttons cannot be tapped twice.
    var busy by remember { mutableStateOf(false) }

    // Runs `work` in the background with the busy flag and error message
    // handled in one place, so the two launchers below stay short.
    val runTask: (suspend () -> String) -> Unit = { work ->
        scope.launch {
            busy = true
            try {
                message = work()
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

    // The file picker for importing. `OpenDocument` shows the system file
    // chooser and gives back the chosen file's address (`Uri`), or null if the
    // person backed out.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runTask {
                val text = onIo { readLimitedText(context, uri) }
                onIo { db.importOpml(text) }.describe()
            }
        }
    }

    // The file picker for exporting. `CreateDocument` lets the person choose
    // where to save a new file; it gives back the address to write to.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-opml"),
    ) { uri: Uri? ->
        if (uri != null) {
            runTask {
                val text = onIo { db.exportOpml() }
                onIo { writeText(context, uri, text) }
                "saved your feeds"
            }
        }
    }

    PinnedLayout(
        top = { TopBar(path = "tuxwire / import & export", onHome = onHome) },
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.isNotEmpty()) {
                    MonoText(message, if (isError) colors.accentBreaking else colors.textMuted)
                }
                BottomBar {
                    TuiButton(label = "back", onClick = onBack)
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TuiPanel(title = "import") {
                MonoText(
                    "Add feeds from an OPML file made by another feed reader. " +
                        "Folders become topics; feeds you already have are skipped.",
                    colors.textPrimary,
                )
                TuiButton(
                    label = "choose a file",
                    enabled = !busy,
                    // `*/*` allows any file type, because phones label OPML
                    // files inconsistently (xml, text, octet-stream).
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
            }
            TuiPanel(title = "export") {
                MonoText(
                    "Save all your feeds as an OPML file you can keep as a backup " +
                        "or open in another feed reader.",
                    colors.textPrimary,
                )
                TuiButton(
                    label = "save to a file",
                    enabled = !busy,
                    onClick = { exportLauncher.launch("tuxwire-feeds.opml") },
                )
            }
        }
    }
}

/**
 * Describes what an import did, in one short line for the screen.
 *
 * The counts arrive from Rust as `UInt` (unsigned), hence the `0u`.
 */
private fun OpmlReport.describe(): String {
    // `buildList` collects the parts that apply, then they are joined.
    val parts = buildList {
        add("$added added")
        if (alreadyThere > 0u) add("$alreadyThere already there")
        if (ignored > 0u) add("$ignored ignored")
    }
    return parts.joinToString(", ")
}

/**
 * Reads the text of the file at [uri], refusing anything bigger than
 * [MAX_IMPORT_BYTES] without loading it all first.
 *
 * Runs on a background thread (the caller wraps it in `onIo`).
 */
private fun readLimitedText(context: Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("could not open that file")
    // `use` closes the stream when the block ends, even if it throws, and
    // hands back the value of its last line, which is returned from here.
    return input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            // Stop as soon as the limit is passed, so a huge file is never
            // read into memory in full.
            if (out.size() > MAX_IMPORT_BYTES) {
                throw IllegalStateException(
                    "this file is too big to be a feed list (the limit is 2 MB)",
                )
            }
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }
}

/** Writes [text] to the file at [uri], replacing whatever it held. */
private fun writeText(context: Context, uri: Uri, text: String) {
    // The "wt" mode means write, and truncate (empty) the file first.
    val output = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw IllegalStateException("could not write to that file")
    output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
}
