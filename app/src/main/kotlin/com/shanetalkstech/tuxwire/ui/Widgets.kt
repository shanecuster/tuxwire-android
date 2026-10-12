package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Font settings that switch off ligatures.
 *
 * JetBrains Mono can merge some character pairs into one fancy symbol (for
 * example `|>` becomes an arrow and `->` becomes a longer arrow). A terminal
 * never does that, and it breaks the ASCII-art logo, so every piece of text
 * in the app turns the feature off. `liga` and `calt` are the two OpenType
 * features that do the merging; `0` means off.
 */
const val NO_LIGATURES: String = "liga 0, calt 0"

/**
 * Text in the app's monospace font. Every piece of text in the app goes
 * through this, so the terminal look is set in one place.
 *
 * @param text what to show.
 * @param color a theme colour (take it from [LocalTuxwireColors]).
 * @param modifier optional layout tweaks, such as padding or a click handler.
 * @param bold whether to use the bold weight.
 * @param size the font size.
 * @param centered when true, the text is centred within the width it is
 *   given (use together with a modifier that fills the width).
 */
@Composable
fun MonoText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    size: TextUnit = 13.sp,
    centered: Boolean = false,
) {
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontFamily = MonoFont,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = size,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            fontFeatureSettings = NO_LIGATURES,
        ),
        modifier = modifier,
    )
}

/**
 * A tappable "[ label ]", the terminal way of drawing a button.
 *
 * @param label the words inside the brackets.
 * @param onClick called when tapped (ignored while [enabled] is false).
 * @param modifier optional layout tweaks.
 * @param enabled when false the button is drawn dim and does nothing.
 */
@Composable
fun TuiButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalTuxwireColors.current
    MonoText(
        text = "[ $label ]",
        color = if (enabled) colors.accentSelected else colors.textDim,
        bold = true,
        size = 14.sp,
        // `clickable` makes any element respond to taps. The generous vertical
        // padding makes the tap target big enough for a finger.
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

/**
 * One tappable line in a list (a topic, a feed or an article).
 *
 * @param onClick called when the row is tapped.
 * @param modifier optional layout tweaks.
 * @param content the row's contents, laid out vertically.
 */
@Composable
fun TuiRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        content()
    }
}

/**
 * A labelled, single-line text box drawn with a plain border.
 *
 * @param label small caption shown above the box.
 * @param value the current text (the screen owns it).
 * @param onValueChange called with the new text whenever the person types.
 * @param modifier optional layout tweaks.
 * @param keyboardType which on-screen keyboard to offer (e.g. the web-address
 *   keyboard, which has "/" and ".com" keys).
 */
@Composable
fun TuiField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = LocalTuxwireColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MonoText(label, colors.textMuted, size = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.textPrimary,
                fontFamily = MonoFont,
                fontSize = 13.sp,
                fontFeatureSettings = NO_LIGATURES,
            ),
            // The colour of the blinking cursor.
            cursorBrush = SolidColor(colors.accentSelected),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.textDim)
                .padding(8.dp),
        )
    }
}

/**
 * A labelled, multi-line text box drawn with a plain border, for longer text
 * such as a note. It starts [minLines] lines tall and grows as the person types.
 *
 * @param label small caption shown above the box.
 * @param value the current text (the screen owns it).
 * @param onValueChange called with the new text whenever the person types.
 * @param modifier optional layout tweaks.
 * @param minLines how many lines tall the box is when empty.
 */
@Composable
fun TuiTextArea(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 6,
) {
    val colors = LocalTuxwireColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MonoText(label, colors.textMuted, size = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            // Unlike `TuiField`, Enter starts a new line instead of finishing.
            singleLine = false,
            minLines = minLines,
            textStyle = TextStyle(
                color = colors.textPrimary,
                fontFamily = MonoFont,
                fontSize = 13.sp,
                fontFeatureSettings = NO_LIGATURES,
            ),
            cursorBrush = SolidColor(colors.accentSelected),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.textDim)
                .padding(8.dp),
        )
    }
}
