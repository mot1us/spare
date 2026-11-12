package io.github.mot1us.spare

/** Read one provider guide off-thread, keeping stable stream IDs for existing guide/history caches. */
object FullEpg {
    private fun xmlId(source: Source, channel: Channel) =
        if (source.kind == "xtream" && source.epgUrl.isBlank()) channel.xmltvId else channel.guideId.ifBlank { channel.id }

    fun load(source: Source, channels: List<Channel>, url: String): Map<String, List<Programme>> {
        val aliases = channels.groupBy { xmlId(source, it) }.filterKeys { it.isNotBlank() }
        require(aliases.isNotEmpty()) { "Provider supplied no XMLTV channel IDs" }
        val history = aliases.mapValues { (_, feeds) -> feeds.maxOf { it.catchupDays } }
        // XMLTV files are large and parsed while streaming: on a TV-box CPU parsing can outlast the
        // download. Only socket time counts, with a budget sized for a full guide on a slow provider.
        val entries = Network.consume(url, source.allowHttp, limit = 64 * 1024 * 1024, timeoutMs = 20000, networkTimeOnly = true, deadlineMs = 180000) {
            Importers.xmltv(it, aliases.keys, historyDays = history)
        }
        require(entries.isNotEmpty()) { "No programme data matched this playlist" }
        return map(channels, source, entries)
    }

    internal fun map(channels: List<Channel>, source: Source, entries: Map<String, List<Programme>>): Map<String, List<Programme>> {
        val mapped = channels.distinctBy { it.guideId.ifBlank { it.id } }.filter { xmlId(source, it) in entries }
        val total = mapped.sumOf { entries[xmlId(source, it)].orEmpty().size.toLong() }
        val quota = (ProgrammeBudget.MAX / mapped.size.coerceAtLeast(1)).coerceAtLeast(1)
        val now = System.currentTimeMillis()
        return buildMap {
            mapped.forEach { channel ->
                val key = channel.guideId.ifBlank { channel.id }
                if (!containsKey(key)) entries[xmlId(source, channel)]?.let { programmes ->
                    val kept = if (total <= ProgrammeBudget.MAX) programmes else ProgrammeBudget.Window(quota, now).also { window -> programmes.forEach(window::add) }.entries()
                    put(key, kept.map { if (it.channel == key) it else it.copy(channel = key) })
                }
            }
        }
    }
}
