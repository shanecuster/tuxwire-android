package com.shanetalkstech.tuxwire.ui

import androidx.compose.runtime.saveable.Saver

/**
 * Every screen the app can show.
 *
 * `sealed` means this is a closed list: the compiler knows every possibility,
 * so a `when` over a [Screen] must handle each one (adding a screen later
 * without handling it is a compile error, not a runtime surprise).
 *
 * The drill-down is Topics, then Feeds (one topic's feeds), then Articles (one
 * feed's articles). [AddFeed] opens on top of either of the first two.
 *
 * @property depth how far "to the right" this screen sits. Used to slide
 *   forward (towards a bigger depth) or backward when switching screens.
 */
sealed class Screen(val depth: Int) {

    /** The first screen: every topic. */
    data object Topics : Screen(0)

    /** The feeds filed under [topic]. */
    data class Feeds(val topic: String) : Screen(1)

    /** The articles of one feed, with the names needed for the header. */
    data class Articles(val topic: String, val feedName: String, val feedUrl: String) : Screen(2)

    /** The add-feed form. [topic] pre-fills the topic box (empty for none). */
    data class AddFeed(val topic: String) : Screen(3)

    /**
     * Turns this screen into text so Android can save it (for example across
     * a screen rotation). A kind letter, then each field, joined by [SEPARATOR].
     */
    fun encode(): String = when (this) {
        Topics -> "T"
        is Feeds -> listOf("F", topic).joinToString(SEPARATOR)
        is Articles -> listOf("A", topic, feedName, feedUrl).joinToString(SEPARATOR)
        is AddFeed -> listOf("N", topic).joinToString(SEPARATOR)
    }

    companion object {
        /**
         * A character that never appears in names or addresses, used to join the
         * fields in [encode]. (Unit Separator, a control character meant for this.)
         */
        private const val SEPARATOR = "\u001F"

        /**
         * The reverse of [encode]. Anything unrecognised becomes [Topics], so a
         * damaged saved state can never crash the app.
         */
        fun decode(text: String): Screen {
            // `split` cuts the text at each separator into a list of fields.
            val parts = text.split(SEPARATOR)
            return when (parts[0]) {
                "F" -> Feeds(parts.getOrElse(1) { "" })
                "A" -> Articles(
                    parts.getOrElse(1) { "" },
                    parts.getOrElse(2) { "" },
                    parts.getOrElse(3) { "" },
                )
                "N" -> AddFeed(parts.getOrElse(1) { "" })
                else -> Topics
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
            restore = { saved -> saved.map { decode(it) }.ifEmpty { listOf(Topics) } },
        )
    }
}
