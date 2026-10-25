package com.shanetalkstech.tuxwire.ui

import androidx.compose.runtime.saveable.Saver

/**
 * Every screen the app can show.
 *
 * `sealed` means this is a closed list: the compiler knows every possibility,
 * so a `when` over a [Screen] must handle each one (adding a screen later
 * without handling it is a compile error, not a runtime surprise).
 *
 * [Home] and [Stats] are the two tabs of the bottom tab bar; switching tabs
 * replaces the whole back stack with the chosen one.
 *
 * The drill-down is Home (every feed), then optionally Feeds (one topic's
 * feeds, reached by tapping a topic label), then Articles (one feed's
 * articles), then Article (one article's summary). [AddFeed] opens on top of
 * either of the first two, so it never sits beside [Article] in the stack and
 * the two can share a depth. [Opml] (import and export), [Sync] (optional
 * sync settings), [Saved] (saved articles and notes) and [ReadLater] (links to
 * read later) open from home; an article opened from [Saved] goes to
 * [Article] directly. [Link] is the question asked about one link, whether it
 * was pasted on the [ReadLater] screen or shared from another app.
 *
 * @property depth how far "to the right" this screen sits. Used to slide
 *   forward (towards a bigger depth) or backward when switching screens.
 */
sealed class Screen(val depth: Int) {

    /** The first screen: every feed, alphabetically. */
    data object Home : Screen(0)

    /** The Stats tab, a top-level sibling of [Home] (switched with the tab bar). */
    data object Stats : Screen(0)

    /** Import and export of OPML feed lists. */
    data object Opml : Screen(1)

    /** Saved articles and notes. */
    data object Saved : Screen(1)

    /** The optional sync settings. */
    data object Sync : Screen(1)

    /** The Read Later list: links saved to read later, and the paste box. */
    data object ReadLater : Screen(1)

    /** The feeds filed under [topic]. */
    data class Feeds(val topic: String) : Screen(1)

    /** The articles of one feed, with the names needed for the header. */
    data class Articles(val topic: String, val feedName: String, val feedUrl: String) : Screen(2)

    /** One article's summary, with the names needed for the header. */
    data class Article(
        val topic: String,
        val feedName: String,
        val feedUrl: String,
        val articleId: Long,
    ) : Screen(3)

    /**
     * The add-feed form. [topic] pre-fills the topic box and [address] the
     * address box (both empty for none). [address] is set when a page is
     * shared to tuxwire from another app.
     */
    data class AddFeed(val topic: String, val address: String = "") : Screen(3)

    /**
     * The question about one link: read it later, save it, or (when it was
     * shared from another app) add it as a feed. [address] is the link,
     * [titleHint] a title that came with it (empty for none), and [fromShare]
     * says it arrived through Android's Share menu, which adds the feed choice.
     */
    data class Link(
        val address: String,
        val titleHint: String = "",
        val fromShare: Boolean = false,
    ) : Screen(2)

    /**
     * Turns this screen into text so Android can save it (for example across
     * a screen rotation). A kind letter, then each field, joined by [SEPARATOR].
     */
    fun encode(): String = when (this) {
        Home -> "T"
        Stats -> "G"
        Opml -> "O"
        Saved -> "S"
        Sync -> "Y"
        ReadLater -> "L"
        is Link -> listOf("K", address, titleHint, if (fromShare) "1" else "0")
            .joinToString(SEPARATOR)
        is Feeds -> listOf("F", topic).joinToString(SEPARATOR)
        is Articles -> listOf("A", topic, feedName, feedUrl).joinToString(SEPARATOR)
        is Article -> listOf("R", topic, feedName, feedUrl, articleId.toString())
            .joinToString(SEPARATOR)
        is AddFeed -> listOf("N", topic, address).joinToString(SEPARATOR)
    }

    companion object {
        /**
         * A character that never appears in names or addresses, used to join the
         * fields in [encode]. (Unit Separator, a control character meant for this.)
         */
        private const val SEPARATOR = "\u001F"

        /**
         * The reverse of [encode]. Anything unrecognised becomes [Home], so a
         * damaged saved state can never crash the app.
         */
        fun decode(text: String): Screen {
            // `split` cuts the text at each separator into a list of fields.
            val parts = text.split(SEPARATOR)
            return when (parts[0]) {
                "G" -> Stats
                "O" -> Opml
                "S" -> Saved
                "Y" -> Sync
                "L" -> ReadLater
                "K" -> Link(
                    parts.getOrElse(1) { "" },
                    parts.getOrElse(2) { "" },
                    parts.getOrElse(3) { "0" } == "1",
                )
                "F" -> Feeds(parts.getOrElse(1) { "" })
                "A" -> Articles(
                    parts.getOrElse(1) { "" },
                    parts.getOrElse(2) { "" },
                    parts.getOrElse(3) { "" },
                )
                "R" -> Article(
                    parts.getOrElse(1) { "" },
                    parts.getOrElse(2) { "" },
                    parts.getOrElse(3) { "" },
                    // A damaged ID becomes 0, which finds no article and
                    // shows an error message instead of crashing.
                    parts.getOrElse(4) { "" }.toLongOrNull() ?: 0L,
                )
                "N" -> AddFeed(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
                else -> Home
            }
        }

        /**
         * Tells Compose how to save and restore the whole back stack: save as a
         * list of strings, restore by decoding each one. Used with
         * `rememberSaveable` so the person is not thrown back to the first screen
         * when the phone rotates.
         */
        val StackSaver: Saver<List<Screen>, List<String>> = Saver(
            save = { stack -> stack.map { it.encode() } },
            restore = { saved -> saved.map { decode(it) }.ifEmpty { listOf(Home) } },
        )
    }
}
