package io.github.mot1us.spare

import java.time.Instant
import java.time.ZoneId

/** Hold Left while watching: one channel's programmes from the last few days through what is next, catch-up marked. */
object ProgrammeHistory {
    enum class State { CATCHUP, NOW, PAST, LATER }
    data class Entry(val programme: Programme, val state: State, val replay: String?, val day: String)
    /** How many days back the list asks the provider for: the channel's archive, and at least three. */
    fun days(channel: Channel) = maxOf(channel.catchupDays, 3).coerceAtMost(7)
    /**
     * Oldest first. [archived]: the starts the provider says it archives, or null before it answers; once known, only
     * those are offered as catch-up. A programme on now is "Now" even when its start could be replayed.
     */
    fun entries(channel: Channel, programmes: List<Programme>, archived: Set<Long>?, http: Boolean, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Entry> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return programmes.filter { it.end > it.start }.distinctBy { it.start }.sortedBy { it.start }.map { programme ->
            val date = Instant.ofEpochMilli(programme.start).atZone(zone).toLocalDate()
            val day = when (date) { today -> "Today"; today.minusDays(1) -> "Yesterday"; today.plusDays(1) -> "Tomorrow"; else -> GuideClock.formats(zone).sportsDay.format(date) }
            val replay = if (archived == null || programme.start in archived) Catchup.url(channel, programme, http, now) else null
            val state = when {
                programme.start <= now && programme.end > now -> State.NOW
                replay != null -> State.CATCHUP
                programme.end <= now -> State.PAST
                else -> State.LATER
            }
            Entry(programme, state, replay.takeIf { state == State.CATCHUP }, day)
        }
    }
    /** Where the cursor starts: the programme on now, else the latest one that has ended. */
    fun focus(entries: List<Entry>): Entry? = entries.firstOrNull { it.state == State.NOW } ?: entries.lastOrNull { it.state == State.CATCHUP || it.state == State.PAST } ?: entries.firstOrNull()
    /** The row's times; the tag before them ([status]) says what OK does. */
    fun detail(entry: Entry, now: Long): String {
        val time = "${GuideClock.dayTime(entry.programme.start)}–${GuideClock.time(entry.programme.end)}"
        return when (entry.state) {
            State.NOW -> "$time · ${span(entry.programme.end - now)} left"
            State.PAST -> "$time · No catch-up"
            State.CATCHUP, State.LATER -> time
        }
    }
    /** Live with how far it has run, Catch-up, Aired (no catch-up), or upcoming: [next] says how soon it starts. */
    fun status(entry: Entry, next: Boolean, now: Long): RowStatus = when (entry.state) {
        State.NOW -> RowStatus("Live", RowStatus.Tone.LIVE, (now - entry.programme.start).toFloat() / (entry.programme.end - entry.programme.start))
        State.CATCHUP -> RowStatus("Catch-up", RowStatus.Tone.CATCHUP)
        State.PAST -> RowStatus("Aired", RowStatus.Tone.AIRED)
        State.LATER -> RowStatus(if (next) "In ${span(entry.programme.start - now)}" else "Upcoming", RowStatus.Tone.UPCOMING)
    }
    /** Whole minutes, rounded up: "49 min", "2 h 12 min"; from six hours, hours only. */
    internal fun span(ms: Long): String {
        val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        return when {
            minutes < 60 -> "$minutes min"
            minutes < 360 && minutes % 60 != 0L -> "${minutes / 60} h ${minutes % 60} min"
            else -> "${minutes / 60} h"
        }
    }
}
