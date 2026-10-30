package io.github.mot1us.spare

/** Match a live programme to provider VOD. Channel archives and timeshift URLs are never used. */
object VodStart {
    data class Request(val query: String, val year: String = "", val season: Int? = null, val episode: Int? = null)
    private val year = Regex("[\\[(]((?:19|20)[0-9]{2})[\\])]")
    private val episode = Regex("\\b(?:S([0-9]{1,3})\\s*E([0-9]{1,4})|([0-9]{1,3})x([0-9]{1,4}))\\b", RegexOption.IGNORE_CASE)
    private val prefix = Regex("^(?:(?:US|USA|UK|EN|ENG|CA|AU|Movie|Film|Cinema)\\s*[|:]\\s*)+", RegexOption.IGNORE_CASE)
    private val quality = Regex("\\b(?:Dolby\\s*Vision|HDR10\\+?|HDR|SDR|DV|FHD|UHD|HD|SD|4K|1080[pi]|720p|2160p|HEVC|AVC|AV1|[HX]264|[HX]265)\\b", RegexOption.IGNORE_CASE)
    private fun clean(value: String) = quality.replace(year.replace(prefix.replace(value, ""), ""), "").trim(' ', '-', ':', '|', '(', ')', '[', ']')
    private fun release(item: OnDemand) = Regex("(?:19|20)[0-9]{2}").find(item.year)?.value ?: year.find(item.title)?.groupValues?.get(1).orEmpty()
    fun titleKey(item: OnDemand): String {
        val stamp = release(item)
        val value = clean(item.title)
        return ChannelSearch.key(if (stamp.isNotBlank()) value.removeSuffix(" $stamp").trimEnd(' ', '-') else value)
    }
    fun request(programme: Programme): Request? {
        val raw = programme.title.take(512)
        val numbering = episode.find(raw) ?: episode.find(programme.description.take(2048))
        val name = clean(episode.find(raw)?.let { raw.substring(0, it.range.first) } ?: raw)
        if (name.length < 2 || SportsCatalog.normalize(name) in setOf("movie", "film", "untitled", "no programme information")) return null
        val numbers = numbering?.groupValues
        return Request(name, year.find(raw)?.groupValues?.get(1).orEmpty(),
            numbers?.let { it[1].ifBlank { it[3] }.toIntOrNull() }, numbers?.let { it[2].ifBlank { it[4] }.toIntOrNull() })
    }
    fun matches(request: Request, items: List<OnDemand>) = items.filter { item ->
        item.kind in setOf("movie", "series") && titleKey(item) == ChannelSearch.key(request.query) &&
            (request.year.isBlank() || release(item).isBlank() || release(item) == request.year) && (request.episode == null || item.kind == "series")
    }
    fun choices(request: Request, items: List<OnDemand>): List<OnDemand> = matches(request, items)
        .groupBy { it.kind to release(it) }.values.mapNotNull { best(request, it) }.take(4)
    /** Remakes or movie/series collisions require a choice; quality variants of one title do not. */
    fun best(request: Request, items: List<OnDemand>): OnDemand? {
        val found = matches(request, items)
        if (found.map { it.kind }.distinct().size != 1 || found.map(::release).filter { it.isNotBlank() }.distinct().size > 1) return null
        // A provider may omit the year on one quality variant of the same title.
        val dated = found.filter { release(it) == request.year }.takeIf { request.year.isNotBlank() && it.isNotEmpty() } ?: found
        return dated.maxByOrNull { item -> when {
            Regex("\\b(4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE).containsMatchIn(item.title) -> 3
            Regex("\\b(fhd|1080p)\\b", RegexOption.IGNORE_CASE).containsMatchIn(item.title) -> 2
            Regex("\\b(hd|720p)\\b", RegexOption.IGNORE_CASE).containsMatchIn(item.title) -> 1
            else -> 0
        } }
    }
}
