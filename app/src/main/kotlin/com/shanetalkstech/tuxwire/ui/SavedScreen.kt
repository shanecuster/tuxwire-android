package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.SavedArticle

/** The longest piece of a note shown in the list; the full note is on the article's page. */
private const val NOTE_PREVIEW_CHARS = 160

/**
 * Your saved articles and notes in one place.
 *
 * Articles that have a note come first (newest first), then the rest. Each row
 * shows the title, where it came from, when it was saved, and the start of the
 * note. A search box looks through titles, sources and notes, and a switch
 * narrows the list to articles with notes. Tapping a row opens the article,
 * where the note can be read in full, edited or deleted. At the bottom is the
 * panel that exports everything to one Markdown file.
 *
 * @param db the open database.
 * @param onHome jumps back to the home screen.
 * @param onBack goes back one screen.
 * @param onOpenArticle opens the article page for the tapped row.
 */
@Composable
fun SavedScreen(
    db: Database,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onOpenArticle: (SavedArticle) -> Unit,
) {
    val colors = LocalTuxwireColors.current

    // Load the saved articles in the background (null while loading). This
    // screen is built fresh each time it is shown, so coming back from an
    // article page after editing a note shows the change.
    val loaded by produceState<Result<List<SavedArticle>>?>(null, db) {
        value = runCatching { onIo { db.listSavedArticles() } }
    }

    // The search text and the "notes only" switch. Saved across rotation.
    var query by rememberSaveable { mutableStateOf("") }
    var notesOnly by rememberSaveable { mutableStateOf(false) }

    PinnedLayout(
        top = { TopBar(path = "tuxwire / saved", onHome = onHome) },
        bottom = { BottomBar { TuiButton(label = "back", onClick = onBack) } },
    ) {
        val result = loaded
        when {
            result == null -> MonoText("loading...", colors.textMuted)
            result.isFailure -> MonoText(
                "error: ${result.exceptionOrNull()?.explain()}",
                colors.accentBreaking,
            )
            else -> {
                val all = result.getOrThrow()
                // Recompute only when the list, the search or the switch changes.
                val shown = remember(all, query, notesOnly) { filterSaved(all, query, notesOnly) }

                // `LazyColumn` only builds the rows that are on screen, so a
                // long list stays fast. Each `item { }` is one fixed block.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        TuiField(
                            label = "search titles, sources and notes",
                            value = query,
                            onValueChange = { query = it },
                        )
                    }
                    item {
                        // The switch between every saved article and only
                        // those with a note. The chosen one is bold.
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            MonoText(
                                text = "[ all saved ]",
                                color = if (notesOnly) colors.textMuted else colors.accentSelected,
                                bold = !notesOnly,
                                modifier = Modifier
                                    .clickable { notesOnly = false }
                                    .padding(vertical = 6.dp),
                            )
                            MonoText(
                                text = "[ notes only ]",
                                color = if (notesOnly) colors.accentSelected else colors.textMuted,
                                bold = notesOnly,
                                modifier = Modifier
                                    .clickable { notesOnly = true }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                    item {
                        MonoText(
                            text = "${all.size} saved, ${all.count { it.note != null }} with notes",
                            color = colors.textMuted,
                            size = 12.sp,
                        )
                    }
                    if (shown.isEmpty()) {
                        item {
                            MonoText(
                                text = if (all.isEmpty()) {
                                    "Nothing saved yet. Tap save on an article to keep it."
                                } else {
                                    "No saved articles match."
                                },
                                color = colors.textMuted,
                            )
                        }
                    }
                    items(shown, key = { it.id }) { article ->
                        TuiRow(onClick = { onOpenArticle(article) }) {
                            MonoText(article.title, colors.accentSaved, bold = true)
                            MonoText(
                                text = "${article.source}  saved " + formatPublished(article.savedAt),
                                color = colors.textMuted,
                                size = 12.sp,
                            )
                            // Copy the note into a local value so Kotlin can
                            // check it is not null once and then use it.
                            val note = article.note
                            if (note != null) {
                                MonoText("> " + notePreview(note), colors.textPrimary)
                            }
                        }
                    }
                    item { NotesExportPanel(db = db) }
                }
            }
        }
    }
}

/**
 * Narrows and orders the saved articles for display.
 *
 * Keeps only articles with a note when [notesOnly] is set, then only those
 * whose title, source or note contains the search text (capitals ignored).
 * Articles with a note come first, newest save first within each group.
 *
 * @param all every saved article.
 * @param query the search text; blank keeps everything.
 * @param notesOnly whether to drop articles that have no note.
 */
private fun filterSaved(
    all: List<SavedArticle>,
    query: String,
    notesOnly: Boolean,
): List<SavedArticle> {
    // Lower-case the search once instead of for every article.
    val needle = query.trim().lowercase()
    return all
        .filter { !notesOnly || it.note != null }
        .filter { article ->
            needle.isEmpty() ||
                article.title.lowercase().contains(needle) ||
                article.source.lowercase().contains(needle) ||
                (article.note?.lowercase()?.contains(needle) == true)
        }
        // Sort noted articles before the rest, then newest save first. The
        // `compareByDescending` / `thenByDescending` pair builds one ordering
        // from two rules.
        .sortedWith(
            compareByDescending<SavedArticle> { it.note != null }
                .thenByDescending { it.savedAt },
        )
}

/**
 * The start of a note for the list: line breaks become spaces, and a long note
 * is cut at [NOTE_PREVIEW_CHARS] characters with "..." on the end.
 */
private fun notePreview(note: String): String {
    val oneLine = note.replace('\n', ' ').replace('\r', ' ').trim()
    return if (oneLine.length <= NOTE_PREVIEW_CHARS) oneLine else oneLine.take(NOTE_PREVIEW_CHARS) + "..."
}
