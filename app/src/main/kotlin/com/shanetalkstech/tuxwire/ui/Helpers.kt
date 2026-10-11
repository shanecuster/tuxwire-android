package com.shanetalkstech.tuxwire.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.tuxwire_ffi.RefreshSummary

/**
 * Runs [block] on a background thread and returns its result.
 *
 * Every call into the Rust database touches a file on disk, and Android forbids
 * (and users hate) doing that on the main thread that draws the screen. Wrapping
 * each call in `onIo { ... }` moves it to `Dispatchers.IO`, a pool of threads
 * meant for waiting on disks and networks.
 *
 * It is a `suspend` function: the calling coroutine pauses until the block
 * finishes, without blocking the screen, then carries on with the result.
 */
suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/**
 * Describes what a refresh did, in one short line (plus the first error, if
 * any feed failed), for showing under the refresh button.
 *
 * The counts arrive from Rust as `UInt` (unsigned), which is why the numbers
 * below are written `0u`.
 */
fun RefreshSummary.describe(): String = when {
    // Every feed failed because the network is unreachable.
    offline -> "offline: could not reach any feed"
    // Nothing was tried because there are no feeds.
    attempted == 0u -> "no feeds to refresh"
    // All fine: say how many new articles, or that nothing changed.
    failed == 0u -> if (newArticles == 0u) "up to date" else "$newArticles new"
    // Some feeds failed: count them and show the first reason.
    else -> "$newArticles new, $failed of $attempted feeds failed" +
        (messages.firstOrNull()?.let { "\n$it" } ?: "")
}

/**
 * Formats a publish time (seconds since 1970, as Rust sends it) for the
 * article list, in the phone's own time zone, e.g. "Oct 9, 14:30".
 */
fun formatPublished(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))

/**
 * Opens [url] in the phone's web browser. Returns `true` if a browser was
 * started, `false` if the address was not a web address or no app can show it.
 *
 * Only `http` and `https` addresses are opened. A feed is outside data we do
 * not control, and an address using some other scheme (`file:`, `intent:` and
 * so on) could ask Android to do something unexpected, so those are refused.
 */
fun openInBrowser(context: Context, url: String): Boolean {
    // Turn the text into a `Uri` (Android's address type) and check its scheme.
    val uri = Uri.parse(url)
    if (uri.scheme != "http" && uri.scheme != "https") return false

    // ACTION_VIEW means "show this to the user"; Android picks the browser.
    // If no app can handle it, `startActivity` throws, which we turn into
    // `false` instead of crashing.
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * Turns whatever the person typed into a feed address: trims spaces and adds
 * `https://` when no scheme was typed, so "example.com/feed" works.
 */
fun normalisedFeedUrl(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

/**
 * Picks the web address out of text shared from another app. Most apps share
 * just the address, but some share a title and the address together ("Great
 * post https://example.com/p"), so the first `http://` or `https://` word wins.
 * Full stops, commas and similar are trimmed off its end (they are almost
 * always the sentence's punctuation, not part of the address). With no address
 * in the text, the text itself is returned (trimmed) and the add-feed form
 * will say it is not a web address.
 */
fun firstWebAddress(text: String): String {
    // `\S+` means "one or more characters that are not blank space".
    val found = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text)
    return (found?.value ?: text).trim().trimEnd('.', ',', ';', ':', '!', '?')
}
