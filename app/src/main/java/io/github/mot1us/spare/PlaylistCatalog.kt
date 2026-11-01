package io.github.mot1us.spare

/** Provider-scoped identities and guide views, preserving the original playlist's legacy IDs. */
class PlaylistCatalog(val legacySource: String) {
    var sources: List<Source> = emptyList(); private set
    fun configure(next: List<Source>) { sources = next.filter { it.enabled } }
    fun key(source: String, native: String) = if (source == legacySource) native else "playlist:$source:$native"
    fun rawKey(source: String, key: String) = if (source == legacySource) key else key.removePrefix("playlist:$source:")
    fun scope(source: Source, channel: Channel): Channel = channel.native().let { raw -> if (source.id == legacySource) raw else raw.copy(id = key(source.id, raw.id), guideId = key(source.id, raw.guideId.ifBlank { raw.id }), playlistId = source.id, providerId = raw.id, providerGuideId = raw.guideId) }
    fun scope(source: Source, entries: List<Programme>) = entries.map { it.copy(channel = key(source.id, it.channel)) }
    fun view(source: Source, guide: Guide) = if (source.id == legacySource) guide else Guide(guide.channels.map { scope(source, it) }, guide.programmes.map { (id, entries) -> key(source.id, id) to scope(source, entries) }.toMap(), guide.categoryOrder)
    fun belongs(source: Source, id: String) = if (source.id == legacySource) !id.startsWith("playlist:") else id.startsWith("playlist:${source.id}:")
    fun owner(channel: Channel) = sources.firstOrNull { it.id == channel.playlistId.ifBlank { legacySource } }
    fun extract(source: Source, guide: Guide): Guide {
        val channels = guide.channels.filter { it.playlistId == source.id || it.playlistId.isBlank() && source.id == legacySource }
        val keys = channels.map { guide.key(it) }.toSet()
        return Guide(channels.map(Channel::native), guide.programmes.filterKeys { it in keys }.map { (key, entries) ->
            rawKey(source.id, key) to entries.map { it.copy(channel = rawKey(source.id, it.channel)) }
        }.toMap(), if (source.id == legacySource) guide.categoryOrder else emptyList())
    }
    companion object {
        /** Share finite lookup budgets across owners instead of exhausting the first playlist. */
        fun interleave(channels: List<Channel>): List<Channel> {
            val groups = channels.groupBy { it.playlistId }.values.toList()
            if (groups.size <= 1) return channels
            return buildList(channels.size) { for (index in 0 until groups.maxOf { it.size }) groups.forEach { it.getOrNull(index)?.let(::add) } }
        }
    }

}
