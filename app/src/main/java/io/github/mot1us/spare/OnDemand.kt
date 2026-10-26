package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject

/** URLs may contain credentials; serialize only inside SecureStore's encrypted files. */
data class OnDemand(val id: String, val title: String, val category: String, val url: String = "", val kind: String = "movie",
    val description: String = "", val seriesId: String = "", val season: Int = 0, val episode: Int = 0, val poster: String = "", val backdrop: String = "", val year: String = "", val genre: String = "", val runtime: String = "", val cast: String = "", val externalId: String = "") {
    fun json() = JSONObject().put("id", id).put("title", title).put("category", category).put("url", url).put("kind", kind)
        .put("description", description).put("seriesId", seriesId).put("season", season).put("episode", episode).put("poster", poster).put("backdrop", backdrop).put("year", year).put("genre", genre).put("runtime", runtime).put("cast", cast).put("externalId", externalId)
    companion object {
        fun from(j: JSONObject) = OnDemand(j.getString("id"), j.getString("title"), j.optString("category"), j.optString("url"), j.optString("kind", "movie"), j.optString("description"), j.optString("seriesId"), j.optInt("season"), j.optInt("episode"), j.optString("poster"), j.optString("backdrop"), j.optString("year"), j.optString("genre"), j.optString("runtime"), j.optString("cast"), j.optString("externalId"))
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
    /** Publish bounded batches while the response is arriving, rather than buffering the whole JSON document. */
    fun stream(source: Source, kind: String, category: String? = null, batch: (List<OnDemand>) -> Unit) {
        require(source.kind == "xtream" && kind in setOf("movie", "series"))
        val action = if (kind == "movie") "get_vod_streams" else "get_series"
        require(category == null || numeric.matches(category))
        val fields = setOf("stream_id", "series_id", "name", "category_id", "container_extension", "plot", "stream_icon", "cover", "year", "releaseDate", "genre", "cast", "tmdb_id", "imdb_id")
        Network.consume(Importers.endpoint(source, "player_api.php", action) + (category?.let { "&category_id=$it" } ?: ""), source.allowHttp) { input ->
            android.util.JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                if (reader.peek() != android.util.JsonToken.BEGIN_ARRAY) throw SourceFailure(SourceProblem.RESPONSE)
                reader.beginArray(); val entries = mutableListOf<JSONObject>(); val seen = hashSetOf<String>(); var count = 0
                fun publish() {
                    val parsed = parse(JSONArray(entries), source, kind, emptyMap()).filter { seen.add(it.id) }
                    if (parsed.isNotEmpty()) batch(parsed)
                    entries.clear()
                }
                while (reader.hasNext()) {
                    if (++count > Importers.MAX_CHANNELS) throw SourceFailure(SourceProblem.LIMIT)
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Catalog import cancelled")
                    if (reader.peek() != android.util.JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                    val item = JSONObject(); reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name in fields && reader.peek() in setOf(android.util.JsonToken.STRING, android.util.JsonToken.NUMBER, android.util.JsonToken.BOOLEAN)) {
                            val value = if (reader.peek() == android.util.JsonToken.BOOLEAN) reader.nextBoolean().toString() else reader.nextString()
                            item.put(name, value.take(4096))
                        } else reader.skipValue()
                    }
                    reader.endObject(); entries.add(item)
                    if (entries.size == if (seen.isEmpty()) 8 else 64) publish()
                }
                reader.endArray(); publish()
                if (reader.peek() != android.util.JsonToken.END_DOCUMENT) throw SourceFailure(SourceProblem.RESPONSE)
            }
        }
    }
    fun load(source: Source, kind: String): List<OnDemand> {
        require(source.kind == "xtream" && kind in setOf("movie", "series"))
        val prefix = if (kind == "movie") "vod" else "series"
        val action = if (kind == "movie") "get_vod_streams" else "get_series"
        val entries = Importers.xtreamArray(Network.get(Importers.endpoint(source, "player_api.php", action), source.allowHttp).toString(Charsets.UTF_8))
        return parse(entries, source, kind, emptyMap())
    }
    fun categories(source: Source, kind: String): Map<String, String> {
        val prefix = if (kind == "movie") "vod" else "series"
        val entries = Importers.xtreamArray(Network.get(Importers.endpoint(source, "player_api.php", "get_${prefix}_categories"), source.allowHttp, 2 * 1024 * 1024, timeoutMs = 4000).toString(Charsets.UTF_8))
        require(entries.length() <= 20000)
        return (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.let { j -> j.optString("category_id") to j.optString("category_name").take(128) } }.toMap()
    }
    fun parse(entries: JSONArray, source: Source, kind: String, categories: Map<String, String>): List<OnDemand> {
        require(entries.length() <= Importers.MAX_CHANNELS && kind in setOf("movie", "series"))
        return (0 until entries.length()).mapNotNull { index ->
            val j = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = j.optString(if (kind == "series") "series_id" else "stream_id")
            if (!numeric.matches(id)) return@mapNotNull null
            OnDemand("$kind:$id", j.optString("name").take(256).ifBlank { "Untitled" }, categories[j.optString("category_id")] ?: j.optString("category_id").take(80).let { if (it.isBlank()) "Other" else "Category $it" },
                if (kind == "movie") streamUrl(source, "movie", id, j.optString("container_extension")) else "", kind, j.optString("plot").take(2048), poster = artwork(j.optString(if (kind == "movie") "stream_icon" else "cover"), source),
                backdrop = artwork(j.optJSONArray("backdrop_path")?.optString(0).orEmpty(), source), year = j.optString("year").ifBlank { j.optString("releaseDate").take(4) }.take(8),
                genre = j.optString("genre").take(256), cast = j.optString("cast").take(1024), externalId = externalId(j))
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
                    streamUrl(source, "series", id, j.optString("container_extension")), "episode", j.optJSONObject("info")?.optString("plot").orEmpty().take(2048), series.id, season, episode, poster = artwork(j.optJSONObject("info")?.optString("movie_image").orEmpty(), source), runtime = j.optJSONObject("info")?.optString("duration").orEmpty().take(40), externalId = externalId(j.optJSONObject("info") ?: j)))
            }
        }
        return result.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.episode }))
    }
    fun details(source: Source, item: OnDemand): OnDemand {
        if (source.kind != "xtream" || item.kind != "movie") return item
        val id = item.id.removePrefix("movie:"); require(numeric.matches(id))
        val root = JSONObject(Network.get(Importers.endpoint(source, "player_api.php", "get_vod_info") + "&vod_id=$id", source.allowHttp, 2 * 1024 * 1024, timeoutMs = 6000).toString(Charsets.UTF_8))
        val info = root.optJSONObject("info") ?: return item
        val data = root.optJSONObject("movie_data")
        return item.copy(description = info.optString("plot").take(4096).ifBlank { item.description }, poster = artwork(info.optString("cover_big").ifBlank { info.optString("movie_image") }, source).ifBlank { item.poster },
            backdrop = artwork(info.optJSONArray("backdrop_path")?.optString(0).orEmpty(), source).ifBlank { item.backdrop },
            year = info.optString("releasedate").take(4).ifBlank { item.year }, genre = info.optString("genre").take(256).ifBlank { item.genre },
            runtime = info.optString("duration").take(40).ifBlank { item.runtime }, cast = info.optString("cast").take(1024).ifBlank { item.cast },
            externalId = externalId(info).ifBlank { item.externalId }, url = data?.optString("container_extension")?.takeIf { it.isNotBlank() }?.let { streamUrl(source, "movie", id, it) } ?: item.url)
    }
    private fun artwork(value: String, source: Source) = value.take(4096).takeIf { Network.allowed(it, source.allowHttp) }.orEmpty()
    private fun externalId(info: JSONObject): String {
        val imdb = info.optString("imdb_id"); if (imdb.matches(Regex("tt[0-9]{5,12}"))) return imdb
        val tmdb = info.optString("tmdb_id").ifBlank { info.optString("tmdb") }; return if (tmdb.matches(numeric)) "tmdb:$tmdb" else ""
    }
    fun streamUrl(source: Source, folder: String, id: String, extension: String): String {
        require(folder in setOf("movie", "series") && numeric.matches(id))
        val ext = extension.lowercase().takeIf { it in setOf("mp4", "mkv", "m4v", "avi", "mov", "ts", "m3u8", "webm") } ?: "mp4"
        return "${Importers.serverBase(source.url)}/$folder/${Importers.encoded(source.username)}/${Importers.encoded(source.password)}/$id.$ext"
    }
}
