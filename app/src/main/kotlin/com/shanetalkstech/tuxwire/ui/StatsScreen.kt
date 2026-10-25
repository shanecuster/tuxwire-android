package com.shanetalkstech.tuxwire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.TimeZone
import uniffi.tuxwire_ffi.Database
import uniffi.tuxwire_ffi.NameCountInfo
import uniffi.tuxwire_ffi.StatsInfo
import uniffi.tuxwire_ffi.WeekCountInfo

// The Stats tab. It follows the terminal app's Stats screen (the `g` key):
// six summary cards, a bar chart of reads per week, and the topics and sources
// read most. Every number is worked out by core and arrives in one `StatsInfo`;
// this file only lays them out, so the two apps cannot disagree.

/** The height of the tallest bar in the weekly chart. */
private val CHART_HEIGHT = 90.dp

/** One summary card's three texts, kept apart from drawing so they are simple to check. */
data class StatCard(val title: String, val big: String, val small: String)

/**
 * The six summary cards in reading order (Read, Unread, Feeds, Notes, Saved,
 * Streak), with the same wording as the terminal app.
 */
fun statCards(stats: StatsInfo): List<StatCard> {
    // "1 day" / "5 days".
    fun days(count: UInt) = "$count ${if (count == 1u) "day" else "days"}"
    return listOf(
        StatCard(
            "Read",
            "${stats.readTotal}",
            "week ${stats.readThisWeek} · month ${stats.readThisMonth}",
        ),
        StatCard("Unread", "${stats.unread}", "on this device"),
        StatCard("Feeds", "${stats.feeds}", "all feeds"),
        StatCard("Notes", "${stats.notes}", "articles with a note"),
        StatCard("Saved", "${stats.saved}", "kept for good"),
        StatCard("Streak", days(stats.streakCurrent), "best ${days(stats.streakBest)}"),
    )
}

/**
 * The Stats tab's content: a title line, then everything else in a scrolling
 * column. The numbers are worked out in the background each time the tab is
 * opened, so switching away and back shows fresh ones.
 *
 * @param db the open database.
 */
@Composable
fun StatsScreen(db: Database) {
    val colors = LocalTuxwireColors.current

    // Work out the numbers off the main thread. Core never reads the clock or
    // the time zone, so both are passed in: the current time in seconds, and the
    // phone's distance from UTC at this moment in seconds (it changes with
    // daylight saving, hence asking for this exact moment).
    val loaded by produceState<Result<StatsInfo>?>(null, db) {
        value = runCatching {
            onIo {
                val nowMillis = System.currentTimeMillis()
                val offsetSecs = TimeZone.getDefault().getOffset(nowMillis) / 1000
                db.computeStats(nowMillis / 1000, offsetSecs)
            }
        }
    }

    PinnedLayout(
        top = { MonoText("tuxwire / stats", colors.accentSelected, bold = true) },
        bottom = {},
    ) {
        val result = loaded
        when {
            result == null -> MonoText("loading...", colors.textMuted)
            result.isFailure -> MonoText(
                "error: ${result.exceptionOrNull()?.explain()}",
                colors.accentBreaking,
            )
            else -> StatsContent(result.getOrThrow())
        }
    }
}

/** Everything under the title: the cards, the chart and the two top lists. */
@Composable
private fun StatsContent(stats: StatsInfo) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The six cards, two to a row. `chunked(2)` cuts the list into pairs.
        for (pair in statCards(stats).chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (card in pair) {
                    // `weight(1f)` gives each card an equal share of the row.
                    TuiPanel(card.title, modifier = Modifier.weight(1f)) {
                        Column {
                            MonoText(
                                card.big,
                                LocalTuxwireColors.current.accentUnread,
                                bold = true,
                                size = 22.sp,
                            )
                            MonoText(card.small, LocalTuxwireColors.current.textMuted, size = 11.sp)
                        }
                    }
                }
            }
        }

        // The chart; its title says where the history starts.
        val since = stats.trackingSince
        TuiPanel(
            if (since != null) "Reads per week (since $since)" else "Reads per week (nothing recorded yet)",
            modifier = Modifier.fillMaxWidth(),
        ) {
            WeekChart(stats.weeks)
        }

        TopList("Top topics (read)", stats.topTopics)
        TopList("Top sources (read)", stats.topSources)
    }
}

/**
 * The reads-per-week bars, oldest week on the left. Each bar's height is its
 * share of the busiest week; the number sits above it (weeks with no reads show
 * no number). Only the first and last week's start dates are written under the
 * bars, because twelve dates would not fit across a phone.
 */
@Composable
private fun WeekChart(weeks: List<WeekCountInfo>) {
    val colors = LocalTuxwireColors.current
    // The tallest bar decides the scale; at least 1 so an empty chart divides safely.
    val most = (weeks.maxOfOrNull { it.reads } ?: 0u).coerceAtLeast(1u)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT + 14.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (week in weeks) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (week.reads > 0u) {
                        MonoText("${week.reads}", colors.textMuted, size = 9.sp)
                    }
                    // A bar is a coloured box; a week with no reads is a thin
                    // dim line so the gap is still visible.
                    val fraction = week.reads.toFloat() / most.toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (week.reads == 0u) 2.dp else CHART_HEIGHT * fraction)
                            .background(if (week.reads == 0u) colors.textDim else colors.accentUnread),
                    )
                }
            }
        }
        if (weeks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MonoText(weeks.first().weekStart, colors.textMuted, size = 10.sp)
                MonoText(weeks.last().weekStart, colors.textMuted, size = 10.sp)
            }
        }
    }
}

/** A ranked list of names with their read counts, or a hint when nothing was read. */
@Composable
private fun TopList(title: String, rows: List<NameCountInfo>) {
    val colors = LocalTuxwireColors.current
    TuiPanel(title, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rows.isEmpty()) {
                MonoText("Nothing read yet.", colors.textMuted)
            } else {
                // `withIndex` pairs each row with its position, for "1. name".
                for ((index, row) in rows.withIndex()) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        MonoText("${index + 1}. ${row.name}", colors.textPrimary, modifier = Modifier.weight(1f))
                        MonoText("${row.reads}", colors.accentUnread, bold = true)
                    }
                }
            }
        }
    }
}
