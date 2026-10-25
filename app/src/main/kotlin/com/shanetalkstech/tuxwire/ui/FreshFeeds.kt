package com.shanetalkstech.tuxwire.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * The feeds that got new articles in a refresh and have not been opened yet.
 *
 * The feed list draws a small `*` beside each of these, so with a long list you
 * can see at a glance where the new articles are. A feed leaves the set the
 * moment it is opened (whether or not anything in it is read).
 *
 * Feeds are identified by their address, which never changes, rather than by
 * name. The set lives only in memory (and survives rotating the phone, see
 * [Saver]); nothing is written to the database, so leaving the app starts clean.
 */
class FreshFeeds {
    /**
     * The addresses of the marked feeds. Backed by Compose state, so assigning
     * a new set makes every screen reading it redraw. It is replaced, never
     * changed in place, because Compose only notices a new value.
     */
    var urls: Set<String> by mutableStateOf(emptySet())
        private set

    /** Marks [feeds] as having new articles, keeping any already marked. */
    fun add(feeds: Collection<String>) {
        if (feeds.isNotEmpty()) urls = urls + feeds
    }

    /** Clears the mark on [url] because that feed was opened. */
    fun clear(url: String) {
        if (url in urls) urls = urls - url
    }

    /** True when [url] has new articles that were not looked at yet. */
    fun has(url: String): Boolean = url in urls

    companion object {
        /**
         * Lets the set survive rotation (which recreates the screen): it is
         * saved as a plain list of addresses and rebuilt from it.
         */
        val Saver: Saver<FreshFeeds, Any> = listSaver(
            save = { it.urls.toList() },
            // `listSaver` hands the saved items back as `List<Any>`, so keep only
            // the strings (they all are) to get the `List<String>` `add` wants.
            restore = { saved -> FreshFeeds().also { it.add(saved.filterIsInstance<String>()) } },
        )
    }
}
