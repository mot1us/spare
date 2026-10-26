package io.github.mot1us.spare

import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Read-only Stremio metadata. Queries contain title/year or ID, never provider playback details. */
object MetadataAddon {
    private val defaultGet: (String) -> String = { Network.get(it, limit = 2 * 1024 * 1024, timeoutMs = 5000).toString(Charsets.UTF_8) }
    private val manifests = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    fun validate(url: String): String {
        val value = url.trim().replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
        val uri = URI(value)
        require(value.length <= 8192 && Network.allowed(value, false) && uri.path.endsWith("/manifest.json") && uri.rawQuery == null && uri.fragment == null) { "Use the configured HTTPS manifest.json URL" }
        return value
    }
    fun manifest(url: String): JSONObject {
        val key = validate(url); val now = System.currentTimeMillis()
        manifests[key]?.takeIf { now - it.first in 0 until 3600000 }?.let { return it.second }
        val root = JSONObject(Network.get(key, limit = 512 * 1024, timeoutMs = 5000).toString(Charsets.UTF_8))
        require(root.has("id") && root.has("resources")); if (manifests.size >= 4) manifests.clear(); manifests[key] = now to root; return root
    }
    fun enrich(url: String, item: OnDemand, get: (String) -> String = defaultGet): OnDemand {
        val base = validate(url).removeSuffix("/manifest.json"); val type = if (item.kind == "movie") "movie" else "series"
        fun metadata(id: String): JSONObject? = runCatching { JSONObject(get("$base/meta/$type/${Importers.encoded(id)}.json")).optJSONObject("meta") }.getOrNull()
        val direct = item.externalId.takeIf(::validId)
        var id = direct; var meta = direct?.let(::metadata)
        var preview = item
        if (meta == null) {
            val match = search(base, type, item, get).singleOrNull() ?: return item
            id = match.externalId; preview = match; meta = metadata(match.externalId)
        }
        val result = meta ?: return preview
        fun image(key: String) = result.optString(key).take(4096).takeIf { Network.allowed(it, false) }.orEmpty()
        fun list(key: String) = result.optJSONArray(key)?.let { a -> (0 until minOf(a.length(), 20)).map { a.optString(it).take(80) }.joinToString(", ") }.orEmpty()
        return preview.copy(externalId = id.orEmpty(), poster = image("poster").ifBlank { preview.poster }, backdrop = image("background").ifBlank { preview.backdrop },
            description = result.optString("description").take(4096).ifBlank { preview.description }, year = result.optString("year").take(16).ifBlank { preview.year },
            genre = list("genres").ifBlank { preview.genre }, runtime = result.optString("runtime").take(40).ifBlank { preview.runtime }, cast = list("cast").ifBlank { preview.cast })
    }
    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,159}")) && id !in setOf("null", "undefined", "0")
    private fun year(item: OnDemand) = Regex("(?:19|20)[0-9]{2}").find(item.year)?.value ?: Regex("\\b(?:19|20)[0-9]{2}\\b").find(item.title)?.value.orEmpty()
    private fun title(value: String) = SportsCatalog.normalize(value
        .replace(Regex("^(?:(?:US|USA|UK|EN|ENG|CA|AU)\\s*[|:]\\s*)+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\b(?:19|20)[0-9]{2}\\b"), "").replace(Regex("\\b(?:FHD|UHD|HD|4K|1080p|720p|2160p)\\b", RegexOption.IGNORE_CASE), ""))
    private fun search(base: String, type: String, item: OnDemand, get: (String) -> String): List<OnDemand> {
        val manifest = if (get === defaultGet) manifest("$base/manifest.json") else JSONObject(get("$base/manifest.json"))
        val catalogs = manifest.optJSONArray("catalogs") ?: return emptyList(); val matches = linkedMapOf<String, OnDemand>(); var requested = 0
        for (index in 0 until minOf(catalogs.length(), 100)) {
            val catalog = catalogs.optJSONObject(index) ?: continue; if (catalog.optString("type") != type) continue
            val extra = catalog.optJSONArray("extra"); val supported = catalog.optJSONArray("extraSupported")
            val searchable = (0 until (extra?.length() ?: 0)).any { extra?.optJSONObject(it)?.optString("name") == "search" || extra?.optString(it) == "search" } || (0 until (supported?.length() ?: 0)).any { supported?.optString(it) == "search" }
            if (!searchable || requested++ >= 2) continue
            val id = catalog.optString("id").take(256); if (id.isBlank()) continue
            val query = title(item.title).take(200)
            val results = runCatching { JSONObject(get("$base/catalog/$type/${Importers.encoded(id)}/search=${Importers.encoded(query)}.json")).optJSONArray("metas") }.getOrNull() ?: continue
            for (at in 0 until minOf(results.length(), 100)) {
                val j = results.optJSONObject(at) ?: continue; val match = j.optString("id")
                val release = j.optString("releaseInfo").ifBlank { j.optString("year") }.take(4)
                if (validId(match) && title(j.optString("name")) == title(item.title) && (year(item).isBlank() || release == year(item))) {
                    fun image(key: String) = j.optString(key).take(4096).takeIf { Network.allowed(it, false) }.orEmpty()
                    matches[match] = item.copy(externalId = match, poster = image("poster").ifBlank { item.poster }, backdrop = image("background").ifBlank { item.backdrop }, description = j.optString("description").take(4096).ifBlank { item.description })
                }
            }
        }
        return matches.values.toList()
    }
    fun live(url: String, programme: Programme): OnDemand? {
        if (programme.title.length < 4 || Regex("\\b(news|football|baseball|basketball|hockey|soccer|highlights|live sports)\\b", RegexOption.IGNORE_CASE).containsMatchIn(programme.title)) return null
        val base = validate(url).removeSuffix("/manifest.json")
        val item = OnDemand("epg:${programme.channel}:${programme.start}", programme.title, "", description = programme.description)
        val matches = listOf("movie", "series").flatMap { type -> runCatching { search(base, type, item.copy(kind = type), defaultGet) }.getOrDefault(emptyList()) }
        return matches.singleOrNull()?.let { enrich(url, it) }
    }
}
