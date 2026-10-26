package io.github.mot1us.spare

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object GuideClock {
    var use24Hour = false
    fun time(epoch: Long, clock24: Boolean = use24Hour, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(if (clock24) "HH:mm" else "h:mm a", Locale.getDefault()).withZone(zone).format(Instant.ofEpochMilli(epoch))
    fun sportsDate(epoch: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), clock24: Boolean = use24Hour): String {
        val day = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate(); val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val prefix = when (day) { today -> "Today · "; today.plusDays(1) -> "Tomorrow · "; else -> "" }
        return prefix + DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()).format(day) + " · " + time(epoch, clock24, zone)
    }
    fun dayTime(epoch: Long): String = DateTimeFormatter.ofPattern(if (use24Hour) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault())
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch))
}

/** Browsing a future programme on one channel must never move the rest of the guide. */
class GuideTimeline {
    private val windows = linkedMapOf<String, Long>()
    fun start(channel: String?, now: Long = System.currentTimeMillis()): Long = maxOf(windows[channel] ?: 0, live(now))
    fun ahead(channel: String?, now: Long = System.currentTimeMillis()) = start(channel, now) > live(now)
    fun move(channel: String, amount: Int, now: Long = System.currentTimeMillis()) {
        val next = (start(channel, now) + amount * SLOT).coerceIn(live(now), live(now) + 48 * 3600000L)
        windows.remove(channel)
        if (next > live(now)) windows[channel] = next
        while (windows.size > 5000) windows.remove(windows.keys.first())
    }
    fun reset(channel: String?) { windows.remove(channel) }
    fun clear() = windows.clear()
    companion object { const val SLOT = 1800000L; fun live(now: Long) = now / SLOT * SLOT }
}
