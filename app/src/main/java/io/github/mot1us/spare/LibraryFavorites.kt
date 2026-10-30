package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject

/** Playlist-local collection and completed episodes. Persist only through encrypted storage. */
data class LibraryFavorites(val entries: List<Entry> = emptyList(), val watched: Set<String> = emptySet()) {
    data class Entry(val item: OnDemand, val episodes: Set<String> = emptySet(), val added: Set<String> = emptySet(), val checked: Long = 0) {
        fun unwatched(watched: Set<String>) = episodes.count { it !in watched }
        fun label(watched: Set<String>): String {
            val fresh = added.count { it !in watched }
            return when {
                item.kind != "series" -> "Movie"
                checked == 0L -> "Episodes not checked yet"
                fresh > 0 -> "$fresh new · ${unwatched(watched)} unwatched"
                episodes.isEmpty() -> "No episodes supplied by provider"
                unwatched(watched) == 0 -> "All caught up"
                else -> "${unwatched(watched)} unwatched ${if (unwatched(watched) == 1) "episode" else "episodes"}"
            }
        }
    }
    fun contains(id: String) = entries.any { it.item.id == id }
    fun save(item: OnDemand, enabled: Boolean): LibraryFavorites {
        require(item.kind in setOf("movie", "series"))
        val old = entries.firstOrNull { it.item.id == item.id }
        require(!enabled || old != null || entries.size < 200) { "Up to 200 Library favorites" }
        return copy(entries = entries.filterNot { it.item.id == item.id } + if (enabled) listOf(old?.copy(item = item) ?: Entry(item)) else emptyList())
    }
    fun update(series: OnDemand, episodes: List<OnDemand>, now: Long): LibraryFavorites {
        val ids = episodes.filter { it.kind == "episode" && it.seriesId == series.id }.takeLast(5000).map { it.id }.toSet()
        return copy(entries = entries.map { entry -> if (entry.item.id != series.id) entry else entry.copy(
            item = series, episodes = ids, added = if (entry.checked == 0L) emptySet() else (entry.added + (ids - entry.episodes)).intersect(ids), checked = now) })
    }
    fun completed(ids: Collection<String>) = copy(watched = (watched + ids).toList().takeLast(10000).toSet())
    fun unwatched(ids: Collection<String>) = copy(watched = watched - ids.toSet())
    fun json() = JSONObject().put("watched", JSONArray(watched.toList())).put("entries", JSONArray(entries.map { entry ->
        JSONObject().put("item", entry.item.json()).put("episodes", JSONArray(entry.episodes.toList())).put("added", JSONArray(entry.added.toList())).put("checked", entry.checked)
    }))
    companion object {
        fun from(json: JSONObject): LibraryFavorites {
            fun ids(array: JSONArray?, max: Int): Set<String> {
                require(array == null || array.length() <= max)
                return array?.let { values -> List(values.length()) { values.getString(it) }.filter { it.matches(Regex("episode:[0-9]{1,20}")) }.toSet() }.orEmpty()
            }
            val entries = json.optJSONArray("entries") ?: JSONArray(); require(entries.length() <= 200)
            return LibraryFavorites(List(entries.length()) { index -> val entry = entries.getJSONObject(index); val item = OnDemand.from(entry.getJSONObject("item"))
                require(item.kind in setOf("movie", "series"))
                Entry(item, ids(entry.optJSONArray("episodes"), 5000), ids(entry.optJSONArray("added"), 5000), entry.optLong("checked"))
            }.distinctBy { it.item.id }, ids(json.optJSONArray("watched"), 10000))
        }
    }
}
