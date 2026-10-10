package com.shanetalkstech.tuxwire.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.launch
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.defaultThemeName
import uniffi.tuxwire_ffi.listThemes

/** The settings key under which the chosen theme's name is saved. */
private const val THEME_SETTING = "theme"

/**
 * The root of the whole app: opens the database, applies the saved theme, and
 * shows whichever screen the person is on, sliding between them.
 *
 * Navigation is a plain "back stack": a list of screens, the last one being
 * visible. Going deeper pushes a screen on the end; going back removes it. It
 * is kept in `rememberSaveable` so it survives rotation.
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- Themes ---------------------------------------------------------
    // The built-in themes come from Rust. A failure (such as the native library
    // not loading) is captured so it can be shown rather than crashing.
    val themesResult = remember { runCatching { listThemes() } }
    val themes = themesResult.getOrDefault(emptyList())

    // The chosen theme's name. `null` until the saved choice has been read.
    var themeName by rememberSaveable { mutableStateOf<String?>(null) }

    // --- The database ----------------------------------------------------
    // Open it once, in the background. The file lives in the app's private
    // storage folder; Rust is told the path because core never decides where
    // files live. `null` means "still opening".
    val dbState by produceState<Result<Database>?>(null) {
        value = runCatching {
            onIo { Database.open(File(context.filesDir, "tuxwire.db").path) }
        }
    }
    val db = dbState?.getOrNull()

    // Once the database is open, read the saved theme (or use the default).
    // `LaunchedEffect(db)` runs when `db` first becomes available.
    LaunchedEffect(db) {
        if (db != null && themeName == null) {
            themeName = runCatching { onIo { db.getSetting(THEME_SETTING) } }.getOrNull()
                ?: runCatching { defaultThemeName() }.getOrDefault("")
        }
    }

    // The colours for the current theme (the plain default set until known).
    val activeTheme = themes.firstOrNull { it.name == themeName } ?: themes.firstOrNull()
    val colors = activeTheme?.colors?.toTuxwireColors() ?: LocalTuxwireColors.current

    // Switches to the next theme in the list and saves the choice.
    val cycleTheme: () -> Unit = {
        if (themes.isNotEmpty() && db != null) {
            // `indexOfFirst` gives -1 when not found, so +1 starts at the first.
            val next = themes[(themes.indexOfFirst { it.name == themeName } + 1) % themes.size]
            themeName = next.name
            scope.launch { runCatching { onIo { db.setSetting(THEME_SETTING, next.name) } } }
        }
    }

    // --- Navigation -------------------------------------------------------
    var stack by rememberSaveable(stateSaver = Screen.StackSaver) {
        mutableStateOf(listOf<Screen>(Screen.Home))
    }
    // Counts how many times the person has come back to the home screen. The
    // logo colour is picked from this number, so it changes on every return.
    var homeVisits by rememberSaveable { mutableStateOf(0) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = {
        if (stack.size > 1) {
            stack = stack.dropLast(1)
            // Arriving back on the home screen counts as a visit.
            if (stack.last() == Screen.Home) homeVisits++
        }
    }
    // Throws away the whole back stack and shows the home screen.
    val goHome: () -> Unit = {
        if (stack.size > 1) {
            stack = listOf(Screen.Home)
            homeVisits++
        }
    }

    // The logo's colour: one of the current theme's accent colours, moving to
    // the next one on every return to home. `%` wraps back to the first.
    val logoColors = bannerColors(colors)
    val logoColor = logoColors[homeVisits % logoColors.size]

    // The phone's back button/gesture goes back one screen, but only while
    // there is a screen to go back to (otherwise it leaves the app as usual).
    BackHandler(enabled = stack.size > 1, onBack = pop)

    // Provide the theme colours to everything below.
    CompositionLocalProvider(LocalTuxwireColors provides colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                // Keep content out from under the status/navigation bars, and
                // lift it above the on-screen keyboard.
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when {
                // The theme list could not even load: show why.
                themesResult.isFailure -> MonoText(
                    "error: ${themesResult.exceptionOrNull()?.explain()}",
                    colors.accentBreaking,
                )
                // The database is still opening.
                dbState == null -> MonoText("opening database...", colors.textMuted)
                // The database failed to open: show why.
                dbState?.isFailure == true -> MonoText(
                    "error: ${dbState?.exceptionOrNull()?.explain()}",
                    colors.accentBreaking,
                )
                // Normal case: show the current screen.
                db != null -> AnimatedContent(
                    targetState = stack.last(),
                    modifier = Modifier.fillMaxSize(),
                    label = "screen",
                    // Going deeper slides the new screen in from the right while the
                    // old one leaves to the left; going back does the reverse.
                    transitionSpec = {
                        if (targetState.depth > initialState.depth) {
                            slideInHorizontally { width -> width } togetherWith
                                slideOutHorizontally { width -> -width }
                        } else {
                            slideInHorizontally { width -> -width } togetherWith
                                slideOutHorizontally { width -> width }
                        }
                    },
                ) { screen ->
                    // `when` over a sealed class must cover every screen.
                    when (screen) {
                        // Home: every feed. A feed opens its articles; a topic
                        // label opens that topic's feeds.
                        Screen.Home -> FeedsScreen(
                            db = db,
                            topic = null,
                            themeName = themeName ?: "",
                            onCycleTheme = cycleTheme,
                            bannerColor = logoColor,
                            onHome = goHome,
                            onBack = pop,
                            onOpenFeed = { push(Screen.Articles(it.topic, it.name, it.url)) },
                            onOpenTopic = { push(Screen.Feeds(it)) },
                            onAddFeed = { push(Screen.AddFeed("")) },
                        )
                        // One topic's feeds.
                        is Screen.Feeds -> FeedsScreen(
                            db = db,
                            topic = screen.topic,
                            themeName = "",
                            onCycleTheme = {},
                            bannerColor = logoColor,
                            onHome = goHome,
                            onBack = pop,
                            onOpenFeed = { push(Screen.Articles(screen.topic, it.name, it.url)) },
                            onOpenTopic = {},
                            onAddFeed = { push(Screen.AddFeed(screen.topic)) },
                        )
                        is Screen.Articles -> ArticlesScreen(
                            db = db,
                            topic = screen.topic,
                            feedName = screen.feedName,
                            feedUrl = screen.feedUrl,
                            onHome = goHome,
                            onBack = pop,
                            onOpenArticle = {
                                push(
                                    Screen.Article(
                                        screen.topic,
                                        screen.feedName,
                                        screen.feedUrl,
                                        it,
                                    ),
                                )
                            },
                        )
                        is Screen.Article -> ArticleScreen(
                            db = db,
                            articleId = screen.articleId,
                            topic = screen.topic,
                            feedName = screen.feedName,
                            onHome = goHome,
                            onBack = pop,
                        )
                        is Screen.AddFeed -> AddFeedScreen(
                            db = db,
                            startTopic = screen.topic,
                            onBack = pop,
                            onDone = pop,
                        )
                    }
                }
            }
        }
    }
}
