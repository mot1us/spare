package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject

/** URLs may contain credentials; serialize only inside SecureStore's encrypted files. */
data class OnDemand(val id: String, val title: String, val category: String, val url: String = "", val kind: String = "movie",
    val description: String = "", val seriesId: String = "", val season: Int = 0, val episode: Int = 0) {
    fun json() = JSONObject().put("id", id).put("title", title).put("category", category).put("url", url).put("kind", kind)
        .put("description", description).put("seriesId", seriesId).put("season", season).put("episode", episode)
    companion object {
        fun from(j: JSONObject) = OnDemand(j.getString("id"), j.getString("title"), j.optString("category"), j.optString("url"), j.optString("kind", "movie"), j.optString("description"), j.optString("seriesId"), j.optInt("season"), j.optInt("episode"))
    }
}
data class WatchProgress(val item: OnDemand, val position: Long, val duration: Long, val updated: Long) {
    fun json() = JSONObject().put("item", item.json()).put("position", position).put("duration", duration).put("updated", updated)
    companion object {
        fun from(j: JSONObject) = WatchProgress(OnDemand.from(j.getJSONObject("item")), j.getLong("position"), j.getLong("duration"), j.getLong("updated"))
    }
}

object VodCatalog {
    private val numeric = Regex("[0-9]{1,20}")
    fun load(source: Source, kind: String): List<OnDemand> {
        require(source.kind == "xtream" && kind in setOf("movie", "series"))
        val prefix = if (kind == "movie") "vod" else "series"
        val names = runCatching {
            val categories = Importers.xtreamArray(Network.get(Importers.endpoint(source, "player_api.php", "get_${prefix}_categories"), source.allowHttp, 2 * 1024 * 1024).toString(Charsets.UTF_8))
            require(categories.length() <= 20000)
            (0 until categories.length()).mapNotNull { categories.optJSONObject(it)?.let { j -> j.optString("category_id") to j.optString("category_name").take(128) } }.toMap()
        }.getOrDefault(emptyMap())
        val action = if (kind == "movie") "get_vod_streams" else "get_series"
        return parse(Importers.xtreamArray(Network.get(Importers.endpoint(source, "player_api.php", action), source.allowHttp).toString(Charsets.UTF_8)), source, kind, names)
    }
    fun parse(entries: JSONArray, source: Source, kind: String, categories: Map<String, String>): List<OnDemand> {
        require(entries.length() <= Importers.MAX_CHANNELS && kind in setOf("movie", "series"))
        return (0 until entries.length()).mapNotNull { index ->
            val j = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = j.optString(if (kind == "series") "series_id" else "stream_id")
            if (!numeric.matches(id)) return@mapNotNull null
            OnDemand("$kind:$id", j.optString("name").take(256).ifBlank { "Untitled" }, categories[j.optString("category_id")] ?: "Other",
                if (kind == "movie") streamUrl(source, "movie", id, j.optString("container_extension")) else "", kind, j.optString("plot").take(2048))
        }.distinctBy { it.id }
    }
    fun episodes(source: Source, series: OnDemand): List<OnDemand> {
        val id = series.id.removePrefix("series:"); require(numeric.matches(id))
        val root = JSONObject(Network.get(Importers.endpoint(source, "player_api.php", "get_series_info") + "&series_id=$id", source.allowHttp, 8 * 1024 * 1024).toString(Charsets.UTF_8))
        return parseEpisodes(root, source, series)
    }
    fun parseEpisodes(root: JSONObject, source: Source, series: OnDemand): List<OnDemand> {
        val seasons = root.optJSONObject("episodes") ?: if (root.optJSONArray("episodes")?.length() == 0) return emptyList() else throw SourceFailure(SourceProblem.RESPONSE)
        require(seasons.length() <= 1000)
        val result = mutableListOf<OnDemand>()
        seasons.keys().forEach { key ->
            val entries = seasons.optJSONArray(key) ?: return@forEach
            require(result.size + entries.length() <= 20000)
            for (index in 0 until entries.length()) {
                val j = entries.optJSONObject(index) ?: continue
                val id = j.optString("id"); if (!numeric.matches(id)) continue
                val season = j.optInt("season", key.toIntOrNull() ?: 0).coerceIn(0, 999)
                val episode = j.optInt("episode_num", index + 1).coerceIn(0, 99999)
                result.add(OnDemand("episode:$id", j.optString("title").take(256).ifBlank { "${series.title} · Episode $episode" }, "Season $season",
                    streamUrl(source, "series", id, j.optString("container_extension")), "episode", j.optJSONObject("info")?.optString("plot").orEmpty().take(2048), series.id, season, episode))
            }
        }
        return result.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.episode }))
    }
    fun streamUrl(source: Source, folder: String, id: String, extension: String): String {
        require(folder in setOf("movie", "series") && numeric.matches(id))
        val ext = extension.lowercase().takeIf { it in setOf("mp4", "mkv", "m4v", "avi", "mov", "ts", "m3u8", "webm") } ?: "mp4"
        return "${Importers.serverBase(source.url)}/$folder/${Importers.encoded(source.username)}/${Importers.encoded(source.password)}/$id.$ext"
    }
}
