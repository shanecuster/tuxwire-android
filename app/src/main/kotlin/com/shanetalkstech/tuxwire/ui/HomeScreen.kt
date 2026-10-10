package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.coreVersion
import uniffi.tuxwire_ffi.defaultThemeName
import uniffi.tuxwire_ffi.listThemes
import uniffi.tuxwire_ffi.previewFeed
import java.io.File

/**
 * The milestone-1 "walking skeleton" screen.
 *
 * It is not the final app. Its job is to prove, on a real device, that the
 * three risky pieces work end to end:
 *  1. the Kotlin <-> Rust bridge (the theme list comes from Rust),
 *  2. SQLite compiled into the Rust library (opening a database file),
 *  3. networking + TLS from Rust (downloading and parsing a feed).
 *
 * The screen is styled like the terminal app: monospace text in bordered panels,
 * coloured by one of tuxwire's own themes.
 */
@Composable
fun HomeScreen() {
    // Ask Rust for the list of built-in themes. `remember` keeps the result so
    // it is computed once, not on every redraw. An error (e.g. the native
    // library failing to load) is captured so it can be shown as text.
    val themesResult = remember { runCatching { listThemes() } }
    val themes = themesResult.getOrDefault(emptyList())

    // Which theme is selected. `rememberSaveable` survives rotation. We store
    // the index, starting at the theme Rust says is the default.
    var themeIndex by rememberSaveable {
        mutableStateOf(
            runCatching {
                themes.indexOfFirst { it.name == defaultThemeName() }.coerceAtLeast(0)
            }.getOrDefault(0)
        )
    }

    // Convert the chosen palette to Compose colours (or fall back to the default
    // grey set when the theme list failed to load).
    val colors = themes.getOrNull(themeIndex)?.colors?.toTuxwireColors()
        ?: LocalTuxwireColors.current

    // Provide the colours to everything below this point.
    CompositionLocalProvider(LocalTuxwireColors provides colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                // Keep content out from under the status/navigation bars, and
                // lift it above the on-screen keyboard.
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Banner line.
            MonoText("tuxwire  ·  milestone 1", bold = true, color = colors.accentSelected)

            // If the themes failed to load, say why; the other panels will show
            // their own errors too, which is the point of this screen.
            themesResult.exceptionOrNull()?.let {
                MonoText("bridge error: ${it.explain()}", color = colors.accentBreaking)
            }

            CorePanel()
            ThemePanel(
                themeName = themes.getOrNull(themeIndex)?.name ?: "(none)",
                count = themes.size,
                onNext = { if (themes.isNotEmpty()) themeIndex = (themeIndex + 1) % themes.size },
            )
            FeedCheckPanel()
        }
    }
}

/**
 * Panel 1: opens the SQLite database and reports the core library version and
 * article count. Proves the native library loads and SQLite works on Android.
 */
@Composable
private fun CorePanel() {
    val colors = LocalTuxwireColors.current
    val context = LocalContext.current

    // `produceState` runs the block in the background once and exposes the
    // result as state: null while loading, then the text to show. The database
    // work happens on the IO thread so the UI never freezes.
    val status by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            try {
                // `filesDir` is the app's private storage folder. Rust is told
                // the full path, because core never decides where files live.
                val path = File(context.filesDir, "tuxwire.db").path
                // `.use { }` closes the database handle when the block ends.
                Database.open(path).use { db ->
                    "core ${coreVersion()}\narticles in db: ${db.articleCount()}"
                }
            } catch (t: Throwable) {
                "error: ${t.explain()}"
            }
        }
    }

    TuiPanel(title = "core") {
        MonoText(status ?: "loading…", color = colors.textPrimary)
    }
}

/**
 * Panel 2: shows the current theme with colour swatches. Tap to switch to the
 * next theme.
 *
 * @param themeName name of the active theme.
 * @param count how many themes exist.
 * @param onNext called when the panel is tapped.
 */
@Composable
private fun ThemePanel(themeName: String, count: Int, onNext: () -> Unit) {
    val colors = LocalTuxwireColors.current

    TuiPanel(title = "theme", modifier = Modifier.clickable(onClick = onNext)) {
        MonoText("$themeName  ($count available)", color = colors.textPrimary)
        MonoText("tap to switch", color = colors.textDim)

        // One small square per colour slot.
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                colors.panelBorder, colors.textPrimary, colors.textMuted,
                colors.accentUnread, colors.accentRead, colors.accentSaved,
                colors.accentBreaking, colors.accentSelected,
            ).forEach { swatch ->
                Box(Modifier.size(20.dp).background(swatch))
            }
        }
    }
}

/**
 * Panel 3: type a feed address, tap "fetch", and Rust downloads and parses it.
 * Proves networking, TLS certificates and the feed parser work on Android.
 */
@Composable
private fun FeedCheckPanel() {
    val colors = LocalTuxwireColors.current

    // The text in the box, and the result line shown under it.
    var url by rememberSaveable { mutableStateOf("https://archlinux.org/feeds/news/") }
    var result by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // A scope tied to this composable, for starting background work from a tap.
    val scope = rememberCoroutineScope()

    TuiPanel(title = "feed check") {
        // The editable address line.
        BasicTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontFamily = MonoFont, fontSize = 13.sp),
            cursorBrush = SolidColor(colors.accentSelected),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.textDim)
                .padding(8.dp),
        )

        // The "fetch" button: just tappable text in a box.
        BasicText(
            text = if (busy) "[ fetching… ]" else "[ fetch ]",
            style = TextStyle(
                color = colors.accentSelected,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            ),
            modifier = Modifier
                .padding(top = 10.dp)
                .clickable(enabled = !busy) {
                    busy = true
                    result = ""
                    scope.launch {
                        // `previewFeed` is a Kotlin `suspend fun` generated from
                        // an async Rust function; it does not block the UI.
                        result = try {
                            val p = previewFeed(url.trim())
                            buildString {
                                appendLine("${p.title}  (${p.entryCount} entries)")
                                p.latestTitles.forEach { appendLine("• $it") }
                            }.trimEnd()
                        } catch (e: CancellationException) {
                            // Never swallow cancellation: it must propagate so
                            // the coroutine can stop properly.
                            throw e
                        } catch (t: Throwable) {
                            "error: ${t.explain()}"
                        } finally {
                            busy = false
                        }
                    }
                },
        )

        if (result.isNotEmpty()) {
            MonoText(result, color = colors.textPrimary, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * Small helper: monospace text with the app's font, so every call site is one
 * line.
 *
 * @param text what to show.
 * @param color text colour (a theme slot).
 * @param bold whether to use the bold weight.
 * @param modifier optional layout tweaks.
 */
@Composable
private fun MonoText(
    text: String,
    color: Color,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontFamily = MonoFont,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
        ),
        modifier = modifier,
    )
}
