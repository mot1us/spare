package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject

/** URLs may contain credentials; serialize only inside SecureStore's encrypted files. */
data class OnDemand(val id: String, val title: String, val category: String, val url: String = "", val kind: String = "movie",
    val description: String = "", val seriesId: String = "", val season: Int = 0, val episode: Int = 0, val poster: String = "", val backdrop: String = "", val year: String = "", val genre: String = "", val runtime: String = "", val cast: String = "", val externalId: String = "", val playlistId: String = "") {
    fun json() = JSONObject().put("id", id).put("title", title).put("category", category).put("url", url).put("kind", kind)
        .put("description", description).put("seriesId", seriesId).put("season", season).put("episode", episode).put("poster", poster).put("backdrop", backdrop).put("year", year).put("genre", genre).put("runtime", runtime).put("cast", cast).put("externalId", externalId).put("playlist", playlistId)
    companion object {
        fun from(j: JSONObject) = OnDemand(j.getString("id"), j.getString("title"), j.optString("category"), j.optString("url"), j.optString("kind", "movie"), j.optString("description"), j.optString("seriesId"), j.optInt("season"), j.optInt("episode"), j.optString("poster"), j.optString("backdrop"), j.optString("year"), j.optString("genre"), j.optString("runtime"), j.optString("cast"), j.optString("externalId"), j.optString("playlist"))
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
    private val yearPattern = Regex("(?:19|20)[0-9]{2}")
    private val imdbPattern = Regex("tt[0-9]{5,12}")
    private val containers = setOf("mp4", "mkv", "m4v", "avi", "mov", "ts", "m3u8", "webm")
    private val moviePosterKeys = listOf("stream_icon", "cover_big", "poster", "movie_image", "stream_icon")
    private val seriesPosterKeys = listOf("cover", "cover_big", "poster", "movie_image", "stream_icon")
    private fun posterKeys(kind: String) = if (kind == "movie") moviePosterKeys else seriesPosterKeys
    private class StreamBase(val url: String, val user: String, val password: String, val head: String, val tail: String)
    @Volatile private var streamBase: StreamBase? = null
    private fun base(source: Source): StreamBase {
        streamBase?.let { if (it.url == source.url && it.user == source.username && it.password == source.password) return it }
        return StreamBase(source.url, source.username, source.password, Importers.serverBase(source.url), "/${Importers.encoded(source.username)}/${Importers.encoded(source.password)}/").also { streamBase = it }
    }
    /** Publish bounded batches while the response is arriving, rather than buffering the whole JSON document. */
    fun stream(source: Source, kind: String, category: String? = null, lean: Boolean = false, batch: (List<OnDemand>) -> Unit) {
        require(source.kind == "xtream" && kind in setOf("movie", "series"))
        val action = if (kind == "movie") "get_vod_streams" else "get_series"
        require(category == null || numeric.matches(category))
        val fields = setOf("stream_id", "series_id", "name", "category_id", "container_extension", "direct_source", "stream_icon", "cover", "cover_big", "poster", "movie_image", "year", "releaseDate", "release_date", "tmdb_id", "tmdb", "tvdb_id", "imdb_id") +
            if (lean) emptySet() else setOf("plot", "backdrop_path", "genre", "cast")
        Network.consume(Importers.endpoint(source, "player_api.php", action) + (category?.let { "&category_id=$it" } ?: ""), source.allowHttp, limit = if (lean) 64 * 1024 * 1024 else 24 * 1024 * 1024) { input ->
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
                if (kind == "movie") playbackUrl(source, "movie", id, j) else "", kind, j.optString("plot").take(2048), poster = posterKeys(kind).asSequence().map { artwork(j.optString(it), source) }.firstOrNull { it.isNotBlank() }.orEmpty(),
                backdrop = artwork(j.optJSONArray("backdrop_path")?.optString(0).orEmpty(), source), year = listOf(j.optString("year"), j.optString("releaseDate"), j.optString("release_date")).mapNotNull { yearPattern.find(it)?.value }.firstOrNull().orEmpty(),
                genre = j.optString("genre").take(256), cast = j.optString("cast").take(1024), externalId = externalId(j))
        }.distinctBy { it.id }
    }
    fun episodes(source: Source, series: OnDemand, timeoutMs: Int = 6000): List<OnDemand> = parseEpisodes(seriesRoot(source, series, timeoutMs), source, series)
    private fun seriesRoot(source: Source, series: OnDemand, timeoutMs: Int): JSONObject {
        val id = series.id.removePrefix("series:"); require(numeric.matches(id))
        return JSONObject(Network.get(Importers.endpoint(source, "player_api.php", "get_series_info") + "&series_id=$id", source.allowHttp, 8 * 1024 * 1024, timeoutMs = timeoutMs).toString(Charsets.UTF_8))
    }
    /** One get_series_info response serves both the spotlight text and the episode list. */
    fun seriesInfo(source: Source, series: OnDemand, timeoutMs: Int = 6000): Pair<OnDemand, List<OnDemand>> {
        if (source.kind != "xtream" || series.kind != "series") return series to emptyList()
        val root = seriesRoot(source, series, timeoutMs)
        return seriesDetails(root, source, series) to runCatching { parseEpisodes(root, source, series) }.getOrDefault(emptyList())
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
                    playbackUrl(source, "series", id, j), "episode", j.optJSONObject("info")?.optString("plot").orEmpty().take(2048), series.id, season, episode, poster = artwork(j.optJSONObject("info")?.optString("movie_image").orEmpty(), source), runtime = j.optJSONObject("info")?.optString("duration").orEmpty().take(40), externalId = externalId(j.optJSONObject("info") ?: j)))
            }
        }
        return result.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.episode }))
    }
    fun details(source: Source, item: OnDemand): OnDemand {
        if (source.kind != "xtream" || item.kind != "movie") return item
        val id = item.id.removePrefix("movie:"); require(numeric.matches(id))
        val root = JSONObject(Network.get(Importers.endpoint(source, "player_api.php", "get_vod_info") + "&vod_id=$id", source.allowHttp, 2 * 1024 * 1024, timeoutMs = 6000).toString(Charsets.UTF_8))
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data")
        return item.copy(description = info.optString("plot").take(4096).ifBlank { item.description }, poster = artwork(info.optString("cover_big").ifBlank { info.optString("movie_image") }, source).ifBlank { item.poster },
            backdrop = artwork(info.optJSONArray("backdrop_path")?.optString(0).orEmpty(), source).ifBlank { item.backdrop },
            year = info.optString("releasedate").take(4).ifBlank { item.year }, genre = info.optString("genre").take(256).ifBlank { item.genre },
            runtime = info.optString("duration").take(40).ifBlank { item.runtime }, cast = info.optString("cast").take(1024).ifBlank { item.cast },
            externalId = externalId(info).ifBlank { item.externalId }, url = data?.let { playbackUrl(source, "movie", id, it, item.url) } ?: item.url)
    }
    /** Spotlight/details text for a series without its episode list being needed by the caller. */
    fun seriesDetails(source: Source, series: OnDemand, timeoutMs: Int = 6000): OnDemand = seriesInfo(source, series, timeoutMs).first
    fun seriesDetails(root: JSONObject, source: Source, series: OnDemand): OnDemand {
        val info = root.optJSONObject("info") ?: return series
        return series.copy(description = info.optString("plot").take(4096).ifBlank { series.description },
            backdrop = artwork(info.optJSONArray("backdrop_path")?.optString(0).orEmpty(), source).ifBlank { series.backdrop },
            poster = series.poster.ifBlank { artwork(info.optString("cover"), source) },
            year = series.year.ifBlank { Regex("(?:19|20)[0-9]{2}").find(info.optString("releaseDate") + " " + info.optString("release_date"))?.value.orEmpty() },
            genre = info.optString("genre").take(256).ifBlank { series.genre }, cast = info.optString("cast").take(1024).ifBlank { series.cast },
            runtime = info.optString("episode_run_time").take(12).takeIf { it.isNotBlank() && it != "0" }?.let { "$it min episodes" } ?: series.runtime)
    }
    /** One provider file per selection; never guess or probe alternate movie formats. */
    fun playbackUrl(source: Source, folder: String, id: String, data: JSONObject, fallback: String = ""): String {
        val direct = data.optString("direct_source").trim()
        if (direct.length in 1..16384 && Network.allowed(direct, source.allowHttp)) return direct
        val extension = data.optString("container_extension").trim()
        val resolvedId = data.optString("stream_id").takeIf { numeric.matches(it) } ?: id
        return if (extension.isNotEmpty() || fallback.isBlank()) streamUrl(source, folder, resolvedId, extension) else fallback
    }
    private fun artwork(value: String, source: Source) = value.take(4096).takeIf { Network.allowed(it, source.allowHttp) }.orEmpty()
    private fun externalId(info: JSONObject): String {
        val imdb = info.optString("imdb_id"); if (imdb.matches(imdbPattern)) return imdb
        val tmdb = info.optString("tmdb_id").ifBlank { info.optString("tmdb") }; return if (tmdb.matches(numeric)) "tmdb:$tmdb" else ""
    }
    fun streamUrl(source: Source, folder: String, id: String, extension: String): String {
        require(folder in setOf("movie", "series") && numeric.matches(id))
        val ext = extension.trim().lowercase(java.util.Locale.ROOT).takeIf { it in containers } ?: "mp4"
        return base(source).let { "${it.head}/$folder${it.tail}$id.$ext" }
    }
}
