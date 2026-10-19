package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long (in milliseconds) to wait for a list to be drawn before giving up on
 * putting it back where it was. See [RestoreAndTrackScroll].
 */
private const val RESTORE_WAIT_MS = 1000L

/**
 * Where one list was scrolled to.
 *
 * A `data class` is a small bundle of values that Kotlin gives a sensible
 * `equals` (two positions with the same values count as equal). That matters
 * below, because the tracking code only reacts when the position really changes.
 *
 * @property first for a [LazyListState] list, the number of the first row on
 *   screen; for a plain scrolling column, how many pixels it is scrolled.
 * @property offset for a [LazyListState] list, how many pixels of that first row
 *   are scrolled off the top (always 0 for a plain scrolling column).
 * @property extra anything else needed to make the position mean the same thing
 *   when it comes back. The saved-articles list keeps its search text and its
 *   "notes only" switch here, because a row number only makes sense for the
 *   same filtered list. Empty when not needed.
 */
data class ScrollPosition(val first: Int, val offset: Int, val extra: String = "")

/**
 * Remembers where each list was scrolled to while the person is on a screen
 * further along, so that going back puts the list in the same place.
 *
 * Why this exists: a screen's own scroll position lives only as long as the
 * screen is on display. Opening an article removes the article list from the
 * screen, and its position goes with it. This object lives in [AppRoot], which
 * never leaves the screen, so it can hold the positions in the meantime.
 *
 * Each list is filed under a key, the text form of its [Screen] (see
 * [Screen.encode]), so every list has its own place.
 *
 * Which entries are kept is decided by the back stack ([keepOnly]): a list is
 * remembered only while the screen sits in the stack, which means "back" finds
 * its place but opening the same list afresh starts at the top.
 */
class ScrollMemory {
    /** The remembered positions, by screen key. */
    private val positions = mutableMapOf<String, ScrollPosition>()

    /**
     * The keys that may be written to, or `null` before the first [keepOnly]
     * call (then anything may be). It stops a screen that is sliding away from
     * writing its position back in after it was forgotten.
     */
    private var allowed: Set<String>? = null

    /** Returns the position remembered for [key], or `null` if there is none. */
    fun get(key: String): ScrollPosition? = positions[key]

    /**
     * Remembers [position] for [key], unless [key] is no longer allowed (its
     * screen has left the back stack).
     */
    fun put(key: String, position: ScrollPosition) {
        // Copy to a local value so Kotlin can check it for null once and then use it.
        val permitted = allowed
        if (permitted == null || key in permitted) {
            positions[key] = position
        }
    }

    /**
     * Forgets every entry whose key is not in [keys], and from now on refuses
     * writes for any other key. Called with the screens currently in the back
     * stack, so the screen that was just left (by "back") is forgotten.
     */
    fun keepOnly(keys: Set<String>) {
        allowed = keys
        // `retainAll` removes everything that is not in the given set.
        positions.keys.retainAll(keys)
    }

    /** Forgets every position. Used when the person jumps to the home screen. */
    fun clear() {
        positions.clear()
    }

    companion object {
        /** How many strings one entry takes when saved: key, first, offset, extra. */
        private const val FIELDS_PER_ENTRY = 4

        /**
         * Tells Compose how to save the memory across a screen rotation or a
         * fold: as one flat list of strings (four per entry), and back again.
         * Used with `rememberSaveable`. A damaged entry is skipped instead of
         * crashing the app.
         */
        val Saver: Saver<ScrollMemory, List<String>> = Saver(
            save = { memory ->
                // `flatMap` turns every entry into four strings and joins them
                // all into one list.
                memory.positions.flatMap { (key, p) ->
                    listOf(key, p.first.toString(), p.offset.toString(), p.extra)
                }
            },
            restore = { saved ->
                val memory = ScrollMemory()
                // `chunked` cuts the flat list back into groups of four.
                for (chunk in saved.chunked(FIELDS_PER_ENTRY)) {
                    if (chunk.size == FIELDS_PER_ENTRY) {
                        memory.positions[chunk[0]] = ScrollPosition(
                            first = chunk[1].toIntOrNull() ?: 0,
                            offset = chunk[2].toIntOrNull() ?: 0,
                            extra = chunk[3],
                        )
                    }
                }
                memory
            },
        )
    }
}

/**
 * Keeps [memory] up to date with where a `LazyColumn` list is scrolled to.
 * Draws nothing; it only watches.
 *
 * Put the list back where it was by creating its state from the remembered
 * position (`rememberLazyListState(first, offset)`) and call this next to it.
 *
 * @param state the list's scroll state.
 * @param memory where positions are kept.
 * @param key which screen's entry to update (see [Screen.encode]).
 * @param extra extra detail stored with the position (see [ScrollPosition.extra]).
 */
@Composable
fun TrackLazyScroll(
    state: LazyListState,
    memory: ScrollMemory,
    key: String,
    extra: String = "",
) {
    // `rememberUpdatedState` lets the long-running watcher below always read the
    // newest `extra` without being restarted whenever it changes.
    val currentExtra by rememberUpdatedState(extra)

    // `LaunchedEffect` starts a background job when this appears and stops it
    // when it leaves, or when any of its keys changes.
    LaunchedEffect(state, memory, key) {
        // `snapshotFlow` turns "whatever Compose state this reads" into a stream
        // that produces a new value each time one of them changes. It skips
        // repeats, which is why [ScrollPosition] compares by value.
        snapshotFlow {
            ScrollPosition(
                first = state.firstVisibleItemIndex,
                offset = state.firstVisibleItemScrollOffset,
                extra = currentExtra,
            )
        }.collect { position ->
            memory.put(key, position)
        }
    }
}

/**
 * Puts a plain scrolling column back where it was, then keeps [memory] up to
 * date with where it is scrolled to. Draws nothing.
 *
 * A column that is built from loaded data cannot be put back straight away: until
 * the data arrives there is almost nothing in it, so it cannot scroll, and an
 * early "scroll to 1500" would be cut back to 0. So this waits until [ready]
 * and until the column has been drawn tall enough, and only then scrolls.
 * Tracking starts after that, so the first (top) position is not written over
 * the remembered one.
 *
 * @param state the column's scroll state.
 * @param memory where positions are kept.
 * @param key which screen's entry to use (see [Screen.encode]).
 * @param ready `true` once the column's content has been loaded.
 */
@Composable
fun RestoreAndTrackScroll(
    state: ScrollState,
    memory: ScrollMemory,
    key: String,
    ready: Boolean,
) {
    // Read the remembered position once, as this screen appears, before the
    // tracking below starts overwriting it.
    val saved = remember(key) { memory.get(key) }

    LaunchedEffect(state, memory, key, ready) {
        // Nothing to do until the content exists. (`return@LaunchedEffect` leaves
        // just this background job, not the whole function.)
        if (!ready) return@LaunchedEffect

        if (saved != null && saved.first > 0) {
            // Wait (at most [RESTORE_WAIT_MS]) until the column is tall enough to
            // scroll at all. If the list has become too short to scroll, the
            // wait times out and the list simply stays at the top.
            withTimeoutOrNull(RESTORE_WAIT_MS) {
                snapshotFlow { state.maxValue }.first { it > 0 }
            }
            // `coerceAtMost` keeps the target inside the list, in case it has
            // become shorter since (for example after a feed was removed).
            state.scrollTo(saved.first.coerceAtMost(state.maxValue))
        }

        // From here on, write down every change of position.
        snapshotFlow { ScrollPosition(first = state.value, offset = 0) }
            .collect { position -> memory.put(key, position) }
    }
}
