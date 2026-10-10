package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.tuxwire_ffi.ArticleDetail
import uniffi.tuxwire_ffi.Database

/**
 * The fourth screen: one article's summary. Shows the title, where it came
 * from, when it was published, and the summary text, with a button that
 * opens the full article in the browser.
 *
 * The article was already marked read by the list screen before this opened,
 * so nothing here changes the database; it only reads. The top bar (home
 * button and breadcrumb) and the bottom bar ("back" and "open in browser")
 * stay put while the text scrolls.
 *
 * @param db the open database.
 * @param articleId which article to show.
 * @param topic the topic (for the header only).
 * @param feedName the feed's name (for the header only).
 * @param onHome jumps back to the home screen.
 * @param onBack goes back to the article list.
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

    // Load the article in the background. `null` means "still loading";
    // otherwise a Result holding either the article or the error.
    val loaded by produceState<Result<ArticleDetail>?>(null, db, articleId) {
        value = runCatching { onIo { db.articleDetail(articleId) } }
    }

    // Set when the browser could not be opened, so the person is told why.
    var message by remember { mutableStateOf("") }

    // The article once it has loaded; `null` while loading or after an error.
    val article = loaded?.getOrNull()

    PinnedLayout(
        top = { TopBar(path = "tuxwire / $topic / $feedName", onHome = onHome) },
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.isNotEmpty()) MonoText(message, colors.accentBreaking)
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
                    // `summary` is null when the feed sent none. The list only
                    // opens this screen when there is one, so null is rare
                    // (for example if the row changed meanwhile).
                    MonoText(shown.summary ?: "", colors.textPrimary)
                }
            }
        }
    }
}
