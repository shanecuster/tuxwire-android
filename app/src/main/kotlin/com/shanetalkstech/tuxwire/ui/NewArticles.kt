package com.shanetalkstech.tuxwire.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * The articles a refresh brought in since the app was opened.
 *
 * This is the app's version of the terminal app's `new_article_ids`. The lists
 * draw an article in the "new" blue while it is in this set and still unread;
 * once it is read (or saved) it takes that colour instead, and when the app is
 * closed and opened again the set starts empty, so last session's articles are
 * plain unread. There is no timer: "new" only means "arrived since launch".
 *
 * The set only ever grows while the app runs (an article leaving the "new" look
 * is decided by the list, which checks read and saved first), and it lives only
 * in memory. Nothing is written to the database. It survives rotating or
 * folding the phone through [Saver].
 */
class NewArticles {
    /**
     * The database IDs of the new articles. Backed by Compose state, so
     * assigning a new set makes every list reading it redraw. It is replaced,
     * never changed in place, because Compose only notices a new value.
     */
    var ids: Set<Long> by mutableStateOf(emptySet())
        private set

    /** Adds the articles a refresh just brought in, keeping any already here. */
    fun add(newIds: Collection<Long>) {
        if (newIds.isNotEmpty()) ids = ids + newIds
    }

    /** True when the article with this ID arrived since the app was opened. */
    fun has(id: Long): Boolean = id in ids

    companion object {
        /**
         * Lets the set survive a screen recreation (rotation, folding or
         * unfolding): it is saved as a plain list of numbers and rebuilt from it.
         */
        val Saver: Saver<NewArticles, Any> = listSaver(
            save = { it.ids.toList() },
            // `listSaver` hands the items back as `List<Any>`; keep the numbers.
            restore = { saved -> NewArticles().also { it.add(saved.filterIsInstance<Long>()) } },
        )
    }
}
