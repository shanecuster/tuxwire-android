package com.shanetalkstech.tuxwire.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.ArticleDetail
import uniffi.tuxwire_ffi.Database

/**
 * One article: its summary, and the place to save it and keep a note on it.
 * Shows the title, where it came from, when it was published, your note (if
 * there is one) and the summary text. The bottom bar holds the actions.
 *
 * What can be done here:
 * - **save** keeps the article for good. Saved articles are permanent, as in
 *   the terminal app, so there is deliberately no "un-save".
 * - **add note / edit note** opens a text box. "save note" stores it (and
 *   saves the article if it was not saved yet); "cancel" throws the typing
 *   away and changes nothing. Saving an empty box clears the note.
 * - **delete note** (shown while editing a note that exists) asks first, then
 *   removes the note. The article stays saved.
 * - **open in browser** opens the full article.
 *
 * The top bar (home button and breadcrumb) and the bottom bars stay put while
 * the text scrolls.
 *
 * @param db the open database.
 * @param articleId which article to show.
 * @param topic the topic (for the header only).
 * @param feedName the feed's name (for the header only).
 * @param onHome jumps back to the home screen.
 * @param onBack goes back to the previous screen (the article list, or the
 *   Saved list).
 */
@Composable
fun ArticleScreen(
    db: Database,
    articleId: Long,
    topic: String,
    feedName: String,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalTuxwireColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumped after a save or a note change, so the article loads again and the
    // screen shows the new state.
    var version by remember { mutableIntStateOf(0) }

    // Load the article in the background. `null` means "still loading";
    // otherwise a Result holding either the article or the error.
    val loaded by produceState<Result<ArticleDetail>?>(null, db, articleId, version) {
        value = runCatching { onIo { db.articleDetail(articleId) } }
    }

    // Set when something could not be done, so the person is told why.
    var message by remember { mutableStateOf("") }

    // The note editor. These are `rememberSaveable` so a half-typed note
    // survives rotating the phone.
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    // True while the "delete this note?" question is showing.
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    // True while a save is running, so a button cannot be tapped twice.
    var busy by remember { mutableStateOf(false) }

    // The article once it has loaded; `null` while loading or after an error.
    val article = loaded?.getOrNull()

    // Runs `work` in the background with the busy flag handled in one place.
    // After it succeeds the article is loaded again; if it fails the reason
    // is shown. (A function declared inside another function: it can use the
    // variables above directly.)
    fun act(work: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                work()
                message = ""
                version++
            } catch (e: CancellationException) {
                // Never swallow cancellation; it must propagate.
                throw e
            } catch (t: Throwable) {
                message = "error: ${t.explain()}"
            } finally {
                busy = false
            }
        }
    }

    PinnedLayout(
        top = { TopBar(path = "tuxwire / $topic / $feedName", onHome = onHome) },
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.isNotEmpty()) MonoText(message, colors.accentBreaking)

                // The question before a note is deleted.
                if (confirmingDelete) {
                    TuiPanel(title = "delete note?") {
                        MonoText("Delete this note? The article stays saved.", colors.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TuiButton(
                                label = "yes, delete",
                                onClick = {
                                    confirmingDelete = false
                                    act {
                                        onIo { db.setNote(articleId, null) }
                                        editing = false
                                    }
                                },
                            )
                            TuiButton(label = "cancel", onClick = { confirmingDelete = false })
                        }
                    }
                }

                if (editing) {
                    // While writing a note: save it, delete it, or give up.
                    BottomBar {
                        TuiButton(
                            label = "save note",
                            enabled = !busy,
                            onClick = {
                                act {
                                    onIo { db.setNote(articleId, draft) }
                                    editing = false
                                }
                            },
                        )
                        if (article?.note != null) {
                            TuiButton(
                                label = "delete note",
                                enabled = !busy,
                                onClick = { confirmingDelete = true },
                            )
                        }
                        TuiButton(label = "cancel", onClick = { editing = false })
                    }
                } else {
                    // Reading: save the article, or add or edit its note.
                    BottomBar {
                        TuiButton(
                            label = if (article?.saved == true) "saved" else "save",
                            // Dim once saved, since saving is permanent.
                            enabled = article != null && !article.saved && !busy,
                            onClick = { act { onIo { db.saveArticle(articleId) } } },
                        )
                        TuiButton(
                            label = if (article?.note != null) "edit note" else "add note",
                            enabled = article != null && !busy,
                            onClick = {
                                draft = article?.note ?: ""
                                editing = true
                            },
                        )
                    }
                    BottomBar {
                        TuiButton(label = "back", onClick = onBack)
                        // Dim until the article has loaded, since the address
                        // comes from it.
                        TuiButton(
                            label = "open in browser",
                            enabled = article != null,
                            onClick = {
                                if (article != null && !openInBrowser(context, article.url)) {
                                    message = "could not open this address"
                                }
                            },
                        )
                    }
                }
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
                val shown = result.getOrThrow()

                // Everything here scrolls together, so a long summary can be
                // read on a small screen while the bars stay in place.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MonoText(shown.title, colors.textPrimary, bold = true, size = 16.sp)
                    MonoText(
                        text = "${shown.source}  " + formatPublished(shown.published),
                        color = colors.textMuted,
                    )

                    if (editing) {
                        // The note editor replaces the summary while writing.
                        TuiTextArea(
                            label = "your note",
                            value = draft,
                            onValueChange = { draft = it },
                        )
                    } else {
                        if (shown.saved) MonoText("saved", colors.accentSaved, bold = true)

                        // The note, in its own box, when there is one.
                        val note = shown.note
                        if (note != null) {
                            TuiPanel(title = "your note") {
                                MonoText(note, colors.textPrimary)
                            }
                        }

                        // `summary` is null when the feed sent none. Articles
                        // opened from the Saved list may have none.
                        MonoText(
                            text = shown.summary ?: "(this article has no summary)",
                            color = if (shown.summary == null) colors.textMuted else colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}
