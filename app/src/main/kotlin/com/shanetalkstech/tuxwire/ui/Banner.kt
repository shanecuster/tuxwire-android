package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The word "tuxwire" drawn with half-block characters, three rows tall.
 *
 * Each letter is 3 columns wide. `▀` fills the top half of a character cell,
 * `▄` the bottom half and `█` the whole cell, so three rows of text give a
 * compact logo (the same style as the zerohour banner). It is the app's
 * name, so it is kept here as a fixed picture rather than looked up in the
 * icon table.
 */
private val TUXWIRE_BANNER: List<String> = listOf(
    "▀█▀ █ █ █ █ █ █  ▄  █▀█ █▀▀",
    " █  █ █ ▄▀▄ █▄█  █  █▀▄ █▀▀",
    " ▀  ▀▀▀ ▀ ▀ ▀▀▀ ▀▀▀ ▀ ▀ ▀▀▀",
)

/**
 * Which theme colours the logo cycles through, in order.
 *
 * These are the theme's accent slots. `distinct()` drops repeats, because
 * some themes give two slots the same colour and showing it twice in a row
 * would look like the logo had not changed.
 */
fun bannerColors(colors: TuxwireColors): List<Color> = listOf(
    colors.accentUnread,
    colors.accentSelected,
    colors.accentSaved,
    colors.accentBreaking,
    colors.accentRead,
    colors.accentSkipped,
).distinct()

/**
 * Draws the logo, centred, in one colour.
 *
 * @param color the logo colour (see [bannerColors]).
 */
@Composable
fun Banner(color: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The three rows are joined into one multi-line text so they stack
        // with no gap between them, which keeps the blocks joined up.
        MonoText(text = TUXWIRE_BANNER.joinToString("\n"), color = color, size = 20.sp)
    }
}
