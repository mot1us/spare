package io.github.mot1us.spare

import org.json.JSONObject
import java.net.URI

/** Read-only Stremio metadata client. Never sends IPTV credentials or playback URLs. */
object MetadataAddon {
    fun validate(url: String): String {
        val value = url.trim().replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
        val uri = URI(value)
        require(value.length <= 8192 && Network.allowed(value, false) && uri.path.endsWith("/manifest.json") && uri.rawQuery == null && uri.fragment == null) { "Use the configured HTTPS manifest.json URL" }
        return value
    }
    fun manifest(url: String): JSONObject {
        val root = JSONObject(Network.get(validate(url), limit = 512 * 1024, timeoutMs = 5000).toString(Charsets.UTF_8))
        require(root.has("id") && root.has("resources")); return root
    }
    fun enrich(url: String, item: OnDemand, get: (String) -> String = { Network.get(it, limit = 2 * 1024 * 1024, timeoutMs = 5000).toString(Charsets.UTF_8) }): OnDemand {
        val base = validate(url).removeSuffix("/manifest.json")
        val type = if (item.kind == "movie") "movie" else "series"
        val id = item.externalId.takeIf { validId(it) } ?: search(base, type, item, get).singleOrNull()?.externalId ?: return item
        val meta = JSONObject(get("$base/meta/$type/${Importers.encoded(id)}.json")).optJSONObject("meta") ?: return item
        fun image(key: String) = meta.optString(key).take(4096).takeIf { Network.allowed(it, false) }.orEmpty()
        fun list(key: String) = meta.optJSONArray(key)?.let { a -> (0 until minOf(a.length(), 20)).map { a.optString(it).take(80) }.joinToString(", ") }.orEmpty()
        return item.copy(externalId = id, poster = image("poster").ifBlank { item.poster }, backdrop = image("background").ifBlank { item.backdrop },
            description = meta.optString("description").take(4096).ifBlank { item.description }, year = meta.optString("year").take(16).ifBlank { item.year },
            genre = list("genres").ifBlank { item.genre }, runtime = meta.optString("runtime").take(40).ifBlank { item.runtime }, cast = list("cast").ifBlank { item.cast })
    }
    private fun validId(id: String) = id.matches(Regex("(?:tt[0-9]{5,12}|tmdb:[0-9]{1,20}|tvdb:[0-9]{1,20})"))
    private fun title(value: String) = SportsCatalog.normalize(value.replace(Regex("\\b(?:19|20)[0-9]{2}\\b"), "").replace(Regex("\\b(?:FHD|UHD|HD|4K|1080p|720p|2160p)\\b", RegexOption.IGNORE_CASE), ""))
    private fun search(base: String, type: String, item: OnDemand, get: (String) -> String): List<OnDemand> {
        val manifest = JSONObject(get("$base/manifest.json")); val catalogs = manifest.optJSONArray("catalogs") ?: return emptyList()
        val matches = linkedMapOf<String, OnDemand>(); var requested = 0
        for (index in 0 until minOf(catalogs.length(), 100)) {
            val catalog = catalogs.optJSONObject(index) ?: continue
            if (catalog.optString("type") != type) continue
            val extra = catalog.optJSONArray("extra"); val supported = catalog.optJSONArray("extraSupported")
            val searchable = (0 until (extra?.length() ?: 0)).any { extra?.optJSONObject(it)?.optString("name") == "search" } || (0 until (supported?.length() ?: 0)).any { supported?.optString(it) == "search" }
            if (!searchable || requested++ >= 2) continue
            val id = catalog.optString("id").take(256); if (id.isBlank()) continue
            val results = JSONObject(get("$base/catalog/$type/${Importers.encoded(id)}/search=${Importers.encoded(item.title.take(200))}.json")).optJSONArray("metas") ?: continue
            for (at in 0 until minOf(results.length(), 100)) {
                val j = results.optJSONObject(at) ?: continue; val match = j.optString("id")
                val year = j.optString("releaseInfo").ifBlank { j.optString("year") }.take(4)
                if (validId(match) && title(j.optString("name")) == title(item.title) && (item.year.isBlank() || year == item.year.take(4))) matches[match] = item.copy(externalId = match)
            }
        }
        return matches.values.toList()
    }
    fun live(url: String, programme: Programme): OnDemand? {
        if (programme.title.length < 4 || Regex("\\b(news|football|baseball|highlights|live sports)\\b", RegexOption.IGNORE_CASE).containsMatchIn(programme.title)) return null
        val base = validate(url).removeSuffix("/manifest.json")
        val get: (String) -> String = { Network.get(it, limit = 2 * 1024 * 1024, timeoutMs = 4000).toString(Charsets.UTF_8) }
        val item = OnDemand("epg:${programme.channel}:${programme.start}", programme.title, "", description = programme.description,
            year = Regex("\\b(?:19|20)[0-9]{2}\\b").find(programme.title)?.value.orEmpty())
        val matches = listOf("movie", "series").flatMap { type -> runCatching { search(base, type, item.copy(kind = type), get) }.getOrDefault(emptyList()) }
        return matches.singleOrNull()?.let { enrich(url, it, get) }
    }
}
