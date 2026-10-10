package com.shanetalkstech.tuxwire.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.shanetalkstech.tuxwire.R
import uniffi.tuxwire_ffi.FfiException
import uniffi.tuxwire_ffi.RgbColor
import uniffi.tuxwire_ffi.ThemePalette

/**
 * The app's monospace font: JetBrains Mono Nerd Font, bundled in `res/font`.
 *
 * Every character is the same width, which is what makes box borders line up
 * like they do in a terminal. The "Nerd Font" part adds the icon glyphs that
 * tuxwire's icon table uses. Two weights are registered: regular and bold.
 */
val MonoFont: FontFamily = FontFamily(
    Font(R.font.jetbrainsmono_nerd_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_nerd_mono_bold, FontWeight.Bold),
)

/**
 * Converts a colour from the Rust side into a Compose [Color].
 *
 * Rust sends each channel as an unsigned byte (`UByte`, 0..255). Compose's
 * `Color(red, green, blue)` constructor wants plain `Int`s in the same range,
 * so each channel is widened with `toInt()`.
 */
fun RgbColor.toColor(): Color = Color(r.toInt(), g.toInt(), b.toInt())

/**
 * The same 11 colour "slots" as the terminal app's `Theme`, but as Compose
 * colours. UI code asks for a slot by name (never a hard-coded colour), so
 * switching theme repaints everything.
 */
data class TuxwireColors(
    /** Fill behind everything. */
    val background: Color,
    /** Lines of the boxes drawn around panels. */
    val panelBorder: Color,
    /** Normal text. */
    val textPrimary: Color,
    /** Secondary text. */
    val textMuted: Color,
    /** Faint text, such as hints. */
    val textDim: Color,
    /** Unread items. */
    val accentUnread: Color,
    /** Read items. */
    val accentRead: Color,
    /** Skipped items. */
    val accentSkipped: Color,
    /** Saved items. */
    val accentSaved: Color,
    /** Breaking-news items. */
    val accentBreaking: Color,
    /** The highlighted / selected row. */
    val accentSelected: Color,
)

/** Builds the Compose colour set from a palette received from Rust. */
fun ThemePalette.toTuxwireColors(): TuxwireColors = TuxwireColors(
    background = background.toColor(),
    panelBorder = panelBorder.toColor(),
    textPrimary = textPrimary.toColor(),
    textMuted = textMuted.toColor(),
    textDim = textDim.toColor(),
    accentUnread = accentUnread.toColor(),
    accentRead = accentRead.toColor(),
    accentSkipped = accentSkipped.toColor(),
    accentSaved = accentSaved.toColor(),
    accentBreaking = accentBreaking.toColor(),
    accentSelected = accentSelected.toColor(),
)

/**
 * A "CompositionLocal": a value any composable can read without it being passed
 * down through every function parameter. The home screen provides the current
 * theme here; panels and text read it with `LocalTuxwireColors.current`.
 *
 * The default (used only if nothing provides a value) is plain grey-on-black.
 */
val LocalTuxwireColors = compositionLocalOf {
    TuxwireColors(
        background = Color.Black,
        panelBorder = Color.Gray,
        textPrimary = Color.White,
        textMuted = Color.LightGray,
        textDim = Color.DarkGray,
        accentUnread = Color.White,
        accentRead = Color.Gray,
        accentSkipped = Color.Gray,
        accentSaved = Color.Yellow,
        accentBreaking = Color.Red,
        accentSelected = Color.Cyan,
    )
}

/**
 * Turns any error into a short message for the screen.
 *
 * Errors from Rust arrive as [FfiException.Failed] with the readable text in
 * `detail`. Anything else (for example the native library failing to load)
 * falls back to its class name and message so the problem is visible instead
 * of crashing the app.
 */
fun Throwable.explain(): String =
    (this as? FfiException.Failed)?.detail ?: "${this::class.simpleName}: ${this.message}"
