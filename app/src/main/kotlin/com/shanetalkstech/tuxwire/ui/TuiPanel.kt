package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A titled box, like the bordered panels in the terminal app.
 *
 * A thin border is drawn around [content], and [title] sits on the top edge,
 * with a background-coloured patch behind it so the line appears broken by the
 * title. Colours come from [LocalTuxwireColors], so the panel follows the theme.
 *
 * @param title text shown on the top border.
 * @param modifier optional layout tweaks from the caller.
 * @param content what goes inside the box (a column, so children stack down).
 */
@Composable
fun TuiPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Read the current theme colours.
    val colors = LocalTuxwireColors.current

    // Outer box: top padding leaves room for the title that overlaps the edge.
    Box(modifier = modifier.padding(top = 9.dp)) {
        // The bordered area holding the content.
        Column(
            modifier = Modifier
                .border(width = 1.dp, color = colors.panelBorder)
                .padding(horizontal = 10.dp, vertical = 12.dp),
        ) {
            content()
        }

        // The title, nudged up by half a line so it straddles the border. The
        // background fill hides the border line behind the text.
        BasicText(
            text = title,
            style = TextStyle(
                color = colors.accentSelected,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            ),
            modifier = Modifier
                .offset(x = 10.dp, y = (-9).dp)
                .background(colors.background)
                .padding(horizontal = 4.dp),
        )
    }
}
