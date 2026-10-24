package com.shanetalkstech.tuxwire.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.FoundBy
import uniffi.tuxwire_ffi.TopicInfo
import uniffi.tuxwire_ffi.previewFeed
import uniffi.tuxwire_ffi.previewFeedFile

/**
 * The add-feed form: type a feed address, or just a blog's address, tap "check"
 * to find and download the feed and fill in its own title, choose or type a
 * topic, then "save".
 *
 * Checking first means a typo is caught here, with a clear message, instead of
 * as a feed that silently never works.
 *
 * @param db the open database.
 * @param startTopic a topic to pre-fill (empty for none).
 * @param startAddress an address to pre-fill and check at once (empty for none);
 *   set when the page was shared to tuxwire from another app.
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
    startAddress: String,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalTuxwireColors.current

    // What has been typed. `rememberSaveable` keeps it across rotation, so a
    // half-filled form is not lost.
    var url by rememberSaveable { mutableStateOf(startAddress) }
    var name by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf(startTopic) }
    var status by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // True once the feed has been saved, so it cannot be saved a second time
    // while the form is still open (for example after a failed first download).
    var saved by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // A feed file the person picked, kept in memory only (a whole file is too big
    // to save across rotation; after a rotation the person simply picks again).
    // `null` means no file is picked and the form works on the address as before.
    var pickedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pickedLabel by remember { mutableStateOf("") }

    // The topics that already exist, offered below the topic box so a tap
    // reuses one instead of typing it again (and mistyping it).
    val existing by produceState<List<TopicInfo>>(emptyList(), db) {
        value = runCatching { onIo { db.listTopics() } }.getOrDefault(emptyList())
    }

    // Saving needs all three boxes filled in, and only works once.
    val canSave = !busy && !saved && url.isNotBlank() && name.isNotBlank() && topic.isNotBlank()

    // Finds the feed for the address in the box and fills in the details. It is a
    // local function (a value holding a block of code) because two things run
    // it: the "check" button, and the form itself when it opens with an address
    // already filled in (from the Share menu).
    val checkAddress: () -> Unit = {
        busy = true
        status = "checking..."
        scope.launch {
            try {
                // Find the feed for what was typed (a feed address, or a
                // web page that has a feed), then download and read it.
                // If it works we learn its own title and how many
                // articles it has. Core adds "https://" when no scheme
                // was typed, so the text is passed on as it is.
                val preview = previewFeed(url.trim())
                // The feed may live at a different address than the page
                // that was typed. Put the real one in the box, so what
                // is saved is exactly what the person can see.
                url = preview.url
                // Only suggest a name if the person has not typed one.
                if (name.isBlank()) name = preview.title
                // Say how it was found when it was not typed directly,
                // so a changed address is never a surprise.
                val how = when (preview.foundBy) {
                    FoundBy.DIRECT -> ""
                    FoundBy.ADVERTISED -> "\nfound through the page's own feed link"
                    FoundBy.GUESSED -> "\nthe page lists no feed; found one at a common address"
                }
                status = "found: ${preview.title} (${preview.entryCount} articles)$how"
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                status = "error: ${t.explain()}"
            } finally {
                busy = false
            }
        }
    }

    // The file picker for "import from file", for sites that block programs but
    // show the feed in a browser: save the feed from the browser, then pick it
    // here. `OpenDocument` shows the system chooser and needs no storage
    // permission; it gives back the file's address, or null if the person backed
    // out. `*/*` allows any type because phones label feed files inconsistently.
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            busy = true
            status = "reading the file..."
            scope.launch {
                try {
                    // Read the bytes and let Rust check them: a saved
                    // "Checking your browser" page fails here with a message
                    // naming the file, and nothing is stored.
                    val label = onIo { displayNameOf(context, uri) }
                    val bytes = onIo { readFeedFileBytes(context, uri) }
                    val preview = onIo { previewFeedFile(bytes, label) }
                    pickedBytes = bytes
                    pickedLabel = label
                    // The box shows the key the feed will be stored under.
                    url = preview.url
                    if (name.isBlank()) name = preview.title
                    status = "found in $label: ${preview.title} (${preview.entryCount} articles)\n" +
                        "imported once; to update it later, import a newer copy"
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    pickedBytes = null
                    status = "error: ${t.explain()}"
                } finally {
                    busy = false
                }
            }
        }
    }

    // Opened from the Share menu with an address: check it straight away, so the
    // person only has to pick a topic and save. `status.isEmpty()` keeps this
    // from running again after the phone is rotated (the status is saved).
    LaunchedEffect(Unit) {
        if (startAddress.isNotBlank() && status.isEmpty()) checkAddress()
    }

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
                            // A picked file is imported from its bytes: no
                            // download, so none of the steps below apply.
                            val picked = pickedBytes
                            if (picked != null) {
                                try {
                                    val report = onIo {
                                        db.importFeedFile(picked, pickedLabel, name, topic)
                                    }
                                    saved = true
                                    status = "imported: ${report.added} new of ${report.total} articles"
                                    busy = false
                                    onDone()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    status = "error: ${t.explain()}"
                                    busy = false
                                }
                                return@launch
                            }

                            // The box holds an `import:` key but the file is gone
                            // (the phone was rotated, which clears it): saving
                            // would try to download a made-up address.
                            if (url.trim().startsWith("import:")) {
                                status = "error: pick the file again"
                                busy = false
                                return@launch
                            }

                            // The address exactly as it will be stored. The same
                            // text is used to save the feed and then to fetch it.
                            val address = normalisedFeedUrl(url)

                            // Step 1: save the feed on a background thread. A
                            // failure here (for example "this feed is already
                            // added") stops everything and is shown in red.
                            try {
                                onIo { db.addFeed(name, address, topic) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                status = "error: ${t.explain()}"
                                busy = false
                                return@launch
                            }

                            // Step 2: the feed is saved, so a second tap on "save"
                            // must not try to add it again. Download its articles
                            // now, so the person never sees a freshly added feed
                            // with nothing in it and thinks the app is broken.
                            saved = true
                            status = "saved. downloading articles..."

                            // `problem` is `null` when all went well, otherwise a
                            // short reason. A failed download is reported in the
                            // summary (`failed`); a thrown error is caught too.
                            // The feed stays saved either way.
                            val problem: String? = try {
                                val summary = db.refreshFeed(address)
                                if (summary.failed > 0u) {
                                    summary.messages.firstOrNull() ?: "the download failed"
                                } else {
                                    null
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                t.explain()
                            }
                            busy = false

                            // Step 3: on success leave the form. On a problem, stay
                            // and say so (the feed's articles arrive on the next
                            // refresh), because leaving would hide the message.
                            if (problem == null) {
                                onDone()
                            } else {
                                status = "saved, but its articles could not be " +
                                    "downloaded yet ($problem). They will arrive " +
                                    "on the next refresh."
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
                    label = "feed or page address",
                    value = url,
                    // Typing here means the person wants an address, not the
                    // picked file, so the pick is forgotten.
                    onValueChange = {
                        url = it
                        pickedBytes = null
                    },
                    keyboardType = KeyboardType.Uri,
                )
                TuiButton(
                    label = if (busy) "checking..." else "check",
                    enabled = !busy && url.isNotBlank(),
                    onClick = checkAddress,
                )
                if (status.isNotEmpty()) {
                    MonoText(
                        text = status,
                        color = if (status.startsWith("error") || status.startsWith("saved, but")) {
                            colors.accentBreaking
                        } else {
                            colors.textMuted
                        },
                    )
                }
            }

            TuiPanel(title = "or a saved feed file") {
                MonoText(
                    "Some sites refuse apps but show their feed in a browser. " +
                        "Open the feed in your browser, save it, then choose the file " +
                        "here. This imports it once; import a newer copy to update.",
                    colors.textMuted,
                )
                TuiButton(
                    label = "choose a file",
                    enabled = !busy && !saved,
                    onClick = { pickLauncher.launch(arrayOf("*/*")) },
                )
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

/** The biggest feed file we will read into memory: 20 MiB, the same limit as a download. */
private const val MAX_FEED_FILE_BYTES = 20 * 1024 * 1024

/**
 * Reads the bytes of the file at [uri], refusing anything bigger than
 * [MAX_FEED_FILE_BYTES] without loading it all first.
 *
 * Bytes, not text: Rust reads the feed's own encoding line, so accented
 * letters in an ISO-8859-1 feed survive. Runs on a background thread (the
 * caller wraps it in `onIo`).
 */
private fun readFeedFileBytes(context: Context, uri: Uri): ByteArray {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("could not open that file")
    // `use` closes the stream when the block ends, even if it throws.
    return input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            // Stop as soon as the limit is passed, so a huge file is never
            // read into memory in full.
            if (out.size() > MAX_FEED_FILE_BYTES) {
                throw IllegalStateException("this file is too big to be a feed (the limit is 20 MB)")
            }
        }
        out.toByteArray()
    }
}

/**
 * The file's name as the person knows it ("index.rss"), used in messages. The
 * file picker's address is not readable, so the name is asked of the system;
 * if it will not say, a plain fallback is used.
 */
private fun displayNameOf(context: Context, uri: Uri): String {
    // `query` returns a small table with one row for this file; `use` closes it.
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }
    return name ?: "that file"
}
