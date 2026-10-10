package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The frame shared by every main screen: a bar pinned at the top, a bar
 * pinned at the bottom, and a middle that takes all the space left over.
 *
 * Only the middle scrolls (the caller makes it scrollable), so the controls
 * in the two bars never move. The bottom bar sits where a thumb rests, which
 * makes the app usable with one hand on a large phone.
 *
 * @param top what to show in the top bar (title, home button, ...).
 * @param bottom what to show in the bottom bar (usually a [BottomBar], with
 *   any status message above it).
 * @param content the middle. It is given all the remaining height, and
 *   should scroll itself if it can be taller than that.
 */
@Composable
fun PinnedLayout(
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        top()
        Rule()
        // `weight(1f)` means "take whatever height the other children left".
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
            content()
        }
        Rule()
        bottom()
    }
}

/**
 * A row of controls spread evenly across the width, for the bottom bar.
 *
 * `SpaceEvenly` puts equal gaps before, between and after the children, so
 * two buttons sit at roughly a third and two thirds of the width.
 *
 * @param content the buttons, laid out left to right.
 */
@Composable
fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The top line of every screen except home: a "home" button that jumps
 * straight back to the feed list, then where you are ("tuxwire / linux").
 *
 * @param path the breadcrumb text.
 * @param onHome called by the home button.
 */
@Composable
fun TopBar(path: String, onHome: () -> Unit) {
    val colors = LocalTuxwireColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TuiButton(label = "home", onClick = onHome)
        MonoText(path, colors.accentSelected, bold = true)
    }
}

/** A thin line in the theme's border colour, separating a bar from the middle. */
@Composable
private fun Rule() {
    val colors = LocalTuxwireColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.panelBorder),
    )
}
