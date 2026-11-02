package com.shanetalkstech.tuxwire.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The About tab: a small page of links about the app itself. It lets people
// share the app, rate it on Google Play, read the privacy policy, find the
// source code and see which version they have. It touches no database and
// nothing is sent anywhere: every row just hands a link to another app (the
// share sheet, Google Play or the browser).

/** The app's public page on Google Play. Used for sharing and as the rating fallback. */
private const val PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=com.shanetalkstech.tuxwire"

/**
 * The same page in the form that opens the Google Play app directly. The
 * `market://` scheme is the one Play registers for itself; phones without Play
 * (some stores or emulators) cannot open it, which is why [rateApp] falls back
 * to [PLAY_STORE_URL] in the browser.
 */
private const val PLAY_STORE_APP_URI = "market://details?id=com.shanetalkstech.tuxwire"

/** The privacy policy, published from the project's public repository. */
private const val PRIVACY_URL =
    "https://github.com/shanecuster/tuxwire-android/blob/master/PRIVACY.md"

/** The app's public source code. */
private const val SOURCE_URL = "https://github.com/shanecuster/tuxwire-android"

/** The message put in the share sheet. The link is what people actually tap. */
private const val SHARE_MESSAGE =
    "tuxwire, a terminal-style RSS reader for Android: $PLAY_STORE_URL"

/**
 * Opens Android's share sheet so the person can send the app's Play Store link
 * through any app they have (messages, mail, chat, ...). Nothing is sent by
 * tuxwire itself; the chosen app does the sending.
 *
 * @param context used to start the share sheet.
 */
fun shareApp(context: Context) {
    // ACTION_SEND with plain text is the standard "share this" request.
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, SHARE_MESSAGE)
    }
    // `createChooser` shows the share sheet even when the person has picked a
    // default app for sharing before.
    try {
        context.startActivity(Intent.createChooser(send, "Share tuxwire"))
    } catch (e: ActivityNotFoundException) {
        // No app can share text: nothing useful to do, and nothing to crash over.
    }
}

/**
 * Opens tuxwire's page in the Google Play app, where the person can leave a
 * rating. If the Play app is not there, the same page opens in the browser.
 *
 * @param context used to start the other app.
 */
fun rateApp(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_APP_URI)))
    } catch (e: ActivityNotFoundException) {
        // No Play app: use the web page instead.
        openInBrowser(context, PLAY_STORE_URL)
    }
}

/**
 * The version name shown on the About tab (for example "0.2.0").
 *
 * It is read from the installed package, so it can never disagree with the
 * version Android and Google Play show. Falls back to "unknown" if Android
 * cannot say.
 *
 * @param context used to ask Android about this app's package.
 */
fun appVersionName(context: Context): String = try {
    // `?:` ("elvis") picks the right side when the left side is null.
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
} catch (e: Exception) {
    "unknown"
}

/**
 * The About tab's content: a title line, then a scrolling column of panels
 * holding the tappable rows.
 */
@Composable
fun AboutScreen() {
    val colors = LocalTuxwireColors.current
    // The Android context is how the app asks Android to open other apps.
    val context = LocalContext.current

    PinnedLayout(
        top = { MonoText("tuxwire / about", colors.accentSelected, bold = true) },
        bottom = {},
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Spread the word and rate the app.
            TuiPanel(title = "spread the word", modifier = Modifier.fillMaxWidth()) {
                Column {
                    AboutRow("share tuxwire", "send the Play Store link to a friend") {
                        shareApp(context)
                    }
                    AboutRow("rate on Google Play", "opens the store page") {
                        rateApp(context)
                    }
                }
            }

            // The policy and the code, both public.
            TuiPanel(title = "open source and privacy", modifier = Modifier.fillMaxWidth()) {
                Column {
                    AboutRow("privacy policy", "what the app does with your data") {
                        openInBrowser(context, PRIVACY_URL)
                    }
                    AboutRow("source code", "free software under the MIT licence") {
                        openInBrowser(context, SOURCE_URL)
                    }
                }
            }

            // Which version is installed, handy when reporting a problem.
            TuiPanel(title = "version", modifier = Modifier.fillMaxWidth()) {
                MonoText("tuxwire ${appVersionName(context)}", colors.textMuted)
            }
        }
    }
}

/**
 * One tappable row on the About tab: a bold line, a muted line under it
 * explaining what it does.
 *
 * @param title the row's main line.
 * @param detail the muted explanation under it.
 * @param onClick called when the row is tapped.
 */
@Composable
private fun AboutRow(title: String, detail: String, onClick: () -> Unit) {
    val colors = LocalTuxwireColors.current
    TuiRow(onClick = onClick) {
        MonoText(title, colors.accentSelected, bold = true, size = 14.sp)
        MonoText(detail, colors.textMuted, size = 12.sp)
    }
}
