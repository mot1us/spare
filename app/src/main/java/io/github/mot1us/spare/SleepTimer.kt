package io.github.mot1us.spare

/** Sleep timer choices cycle Off → 30 → 60 → 90 → 120 minutes → Off. */
object SleepTimer {
    val choices = listOf(30, 60, 90, 120)

    /** The next choice after the one running now (rounded up), or 0 to turn it off. */
    fun next(endsAt: Long, now: Long): Int {
        if (endsAt <= now) return choices.first()
        val remaining = ((endsAt - now + 59_999) / 60_000).toInt()
        return choices.firstOrNull { it > remaining } ?: 0
    }

    fun label(endsAt: Long, now: Long): String {
        if (endsAt <= now) return "Off"
        val minutes = ((endsAt - now + 59_999) / 60_000).toInt()
        return "Stops playback in $minutes min · select to change"
    }
}
