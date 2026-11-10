package io.github.mot1us.spare

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object GuideClock {
    @Volatile var use24Hour = false

    internal class Formats(val locale: Locale, val zone: ZoneId) {
        private val time12 = formatter("h:mm a")
        private val time24 = formatter("HH:mm")
        private val day12 = formatter("EEE h:mm a")
        private val day24 = formatter("EEE HH:mm")
        val sportsDay = formatter("EEE, MMM d")

        private fun formatter(pattern: String) = DateTimeFormatter.ofPattern(pattern, locale).withZone(zone)
        fun time(epoch: Long, clock24: Boolean) = (if (clock24) time24 else time12).format(Instant.ofEpochMilli(epoch))
        fun dayTime(epoch: Long, clock24: Boolean) = (if (clock24) day24 else day12).format(Instant.ofEpochMilli(epoch))
    }

    @Volatile private var cached: Formats? = null
    internal fun formats(zone: ZoneId = ZoneId.systemDefault()): Formats {
        val locale = Locale.getDefault()
        return cached?.takeIf { it.locale == locale && it.zone == zone }
            ?: Formats(locale, zone).also { cached = it }
    }

    fun time(epoch: Long, clock24: Boolean = use24Hour, zone: ZoneId = ZoneId.systemDefault()): String =
        formats(zone).time(epoch, clock24)

    fun sportsDate(epoch: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), clock24: Boolean = use24Hour): String {
        val day = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate(); val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val prefix = when (day) { today -> "Today · "; today.plusDays(1) -> "Tomorrow · "; else -> "" }
        val format = formats(zone)
        return prefix + format.sportsDay.format(day) + " · " + format.time(epoch, clock24)
    }

    fun dayTime(epoch: Long): String = formats().dayTime(epoch, use24Hour)
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
