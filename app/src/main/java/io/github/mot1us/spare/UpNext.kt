package io.github.mot1us.spare

/**
 * Choose the one episode to offer for each series you follow: an unfinished episode to resume,
 * the next unwatched episode after your furthest point, or a newly released one.
 * Pure logic: callers supply provider episodes, completion and saved positions.
 */
object UpNext {
    data class Pick(val series: OnDemand, val episode: OnDemand, val label: String, val fresh: Boolean, val resume: Boolean, val recency: Long)

    private fun code(episode: OnDemand) = "S${episode.season} E${episode.episode}"

    fun pick(series: OnDemand, episodes: List<OnDemand>, watched: Set<String>, progress: List<WatchProgress>, added: Set<String>): Pick? {
        val ordered = episodes.filter { it.kind == "episode" }.sortedWith(compareBy({ it.season }, { it.episode }))
        if (ordered.isEmpty()) return null
        val mine = progress.filter { it.item.kind == "episode" && it.item.seriesId == series.id }
        val latest = mine.maxByOrNull { it.updated }
        val recency = latest?.updated ?: 0L
        if (latest != null && latest.item.id !in watched && latest.duration > 0 && latest.position in 1 until (latest.duration * 0.92).toLong()) {
            val episode = ordered.firstOrNull { it.id == latest.item.id } ?: latest.item
            return Pick(series, episode, "Resume ${code(episode)}", episode.id in added, true, recency)
        }
        val reached = mine.map { it.item.id }.toSet()
        val anchor = ordered.indexOfLast { it.id in watched || it.id in reached }
        if (anchor >= 0) {
            val next = ordered.drop(anchor + 1).firstOrNull { it.id !in watched } ?: return null
            val fresh = next.id in added
            return Pick(series, next, "${if (fresh) "New" else "Next"} · ${code(next)}", fresh, false, recency)
        }
        // Saved but never started: only newly released episodes are worth surfacing.
        val first = ordered.firstOrNull { it.id in added && it.id !in watched } ?: return null
        return Pick(series, first, "New · ${code(first)}", true, false, recency)
    }

    /** Newly released first, then the series you watched most recently. */
    fun order(picks: List<Pick>) = picks.sortedWith(compareByDescending<Pick> { it.fresh }.thenByDescending { it.recency })
}
