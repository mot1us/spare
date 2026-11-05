package io.github.mot1us.spare

/** Read one provider guide off-thread, keeping stable stream IDs for existing guide/history caches. */
object FullEpg {
    private fun xmlId(source: Source, channel: Channel) =
        if (source.kind == "xtream" && source.epgUrl.isBlank()) channel.xmltvId else channel.guideId.ifBlank { channel.id }

    fun load(source: Source, channels: List<Channel>, url: String): Map<String, List<Programme>> {
        val aliases = channels.groupBy { xmlId(source, it) }.filterKeys { it.isNotBlank() }
        require(aliases.isNotEmpty()) { "Provider supplied no XMLTV channel IDs" }
        val history = aliases.mapValues { (_, feeds) -> feeds.maxOf { it.catchupDays } }
        val entries = Network.consume(url, source.allowHttp, limit = 64 * 1024 * 1024, timeoutMs = 20000) {
            Importers.xmltv(it, aliases.keys, historyDays = history)
        }
        require(entries.isNotEmpty()) { "No programme data matched this playlist" }
        return map(channels, source, entries)
    }

    internal fun map(channels: List<Channel>, source: Source, entries: Map<String, List<Programme>>): Map<String, List<Programme>> {
        var count = 0
        return buildMap {
            channels.forEach { channel ->
                val key = channel.guideId.ifBlank { channel.id }
                if (!containsKey(key)) entries[xmlId(source, channel)]?.let { programmes ->
                    count += programmes.size; require(count <= 200000) { "Guide has too many programmes" }
                    put(key, programmes.map { if (it.channel == key) it else it.copy(channel = key) })
                }
            }
        }
    }
}
