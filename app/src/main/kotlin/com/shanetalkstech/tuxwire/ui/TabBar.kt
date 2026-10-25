package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The two top-level views the tab bar switches between. */
enum class Tab(val label: String) {
    /** The feed list (the home screen). */
    FEEDS("Feeds"),

    /** The Stats screen. */
    STATS("Stats"),
}

/**
 * The bar at the very bottom of the two top-level screens. It has a thin line
 * above it, like the other bars, and two equal halves; the [selected] tab is
 * drawn bold in the accent colour and the other muted, the same way the
 * terminal app marks the chosen option. Each half is a full-width, finger-sized
 * tap target.
 *
 * @param selected the tab to highlight.
 * @param onSelect called with the tab that was tapped.
 */
@Composable
fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = LocalTuxwireColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // The separating line.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.panelBorder),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            for (tab in Tab.entries) {
                val isSelected = tab == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MonoText(
                        text = tab.label,
                        color = if (isSelected) colors.accentSelected else colors.textMuted,
                        bold = isSelected,
                        size = 14.sp,
                    )
                }
            }
        }
    }
}
