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
 * The word "tuxwire" drawn in the classic "figlet standard" lettering, five
 * rows tall and 37 columns wide.
 *
 * It was made once with the `figlet` program and pasted in as a fixed
 * picture, so nothing generates it at run time. It uses plain ASCII only, so
 * it looks the same in every font. Each row is padded with spaces to the
 * same width so the rows line up when stacked. The strings are written as
 * raw strings (`"""..."""`) so the many backslashes need no escaping.
 */
private val TUXWIRE_BANNER: List<String> = listOf(
    """ _                        _          """,
    """| |_ _   ___  ____      _(_)_ __ ___ """,
    """| __| | | \ \/ /\ \ /\ / / | '__/ _ \""",
    """| |_| |_| |>  <  \ V  V /| | | |  __/""",
    """ \__|\__,_/_/\_\  \_/\_/ |_|_|  \___|""",
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
        // The rows are joined into one multi-line text so they stack with no
        // gap between them. 13sp makes the 37 columns about 290dp wide, which
        // fits a 360dp-wide phone with room for the side margins.
        MonoText(text = TUXWIRE_BANNER.joinToString("\n"), color = color, size = 13.sp)
    }
}
