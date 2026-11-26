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
    fun detail(entry: Entry): String {
        val time = "${GuideClock.dayTime(entry.programme.start)}–${GuideClock.time(entry.programme.end)}"
        return when (entry.state) {
            State.NOW -> "Now · $time"
            State.CATCHUP -> "$time · Catch-up"
            State.PAST -> "$time · No catch-up"
            State.LATER -> time
        }
    }
}
