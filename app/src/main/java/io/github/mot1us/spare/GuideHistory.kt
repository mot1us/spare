package io.github.mot1us.spare

/** A live-guide refresh must not erase programmes still available from the archive. */
object GuideHistory {
    fun merge(saved: List<Programme>, incoming: List<Programme>, days: Int, now: Long): List<Programme> {
        val cutoff = now - if (days > 0) days.coerceAtMost(30) * 86400000L else 3600000L
        val entries = (saved + incoming).associateBy { it.start }.values.filter {
            it.end > it.start && if (days > 0 && it.end <= now) it.start >= cutoff else it.end > cutoff
        }.sortedBy { it.start }
        val future = entries.filter { it.end > now }.take(72)
        return entries.filter { it.end <= now }.takeLast((if (days > 0) 500 else 72) - future.size) + future
    }
}
