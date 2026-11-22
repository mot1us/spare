package io.github.mot1us.spare

/** Read one provider guide off-thread, keeping stable stream IDs for existing guide/history caches. */
object FullEpg {
    private fun xmlId(source: Source, channel: Channel) =
        if (source.kind == "xtream" && source.epgUrl.isBlank()) channel.xmltvId else channel.guideId.ifBlank { channel.id }

    /** An Xtream channel's own stream number ("150130" for xc:150130). */
    internal fun streamNumber(channel: Channel): String? =
        channel.nativeId.takeIf { it.startsWith("xc:") }?.removePrefix("xc:")?.takeIf { it.matches(Regex("[0-9]{1,20}")) }

    fun load(source: Source, channels: List<Channel>, url: String): Map<String, List<Programme>> {
        val aliases = channels.groupBy { xmlId(source, it) }.filterKeys { it.isNotBlank() }
        // A guide made for one provider can list its channels under that provider's stream numbers ("150130" for ESPN+ 19),
        // which the provider's own guide IDs never use. Such a number counts only when the guide's channel name agrees.
        val numbered = if (source.kind == "xtream" && source.epgUrl.isNotBlank()) channels.groupBy { streamNumber(it).orEmpty() }.filterKeys { it.isNotEmpty() } else emptyMap()
        require(aliases.isNotEmpty() || numbered.isNotEmpty()) { "Provider supplied no XMLTV channel IDs" }
        val wanted = aliases.keys + numbered.keys
        val history = wanted.associateWith { key -> (aliases[key].orEmpty() + numbered[key].orEmpty()).maxOf { it.catchupDays } }
        val names = HashMap<String, String>()
        // XMLTV files are large and parsed while streaming: on a TV-box CPU parsing can outlast the
        // download. Only socket time counts, with a budget sized for a full guide on a slow provider.
        val entries = Network.consume(url, source.allowHttp, limit = 64 * 1024 * 1024, timeoutMs = 20000, networkTimeOnly = true, deadlineMs = Network.LARGE_DEADLINE_MS, stallBytes = Network.STALL_BYTES) {
            Importers.xmltv(it, wanted, historyDays = history, names = if (numbered.isEmpty()) null else names)
        }
        require(entries.isNotEmpty()) { "No programme data matched this playlist" }
        return map(channels, source, entries, names)
    }

    /**
     * The guide's name for a stream number names this channel: the same name, or for an event slot the same slot ("ESPN+ 19:"
     * in both), since the slot's game changes during the day. A number alone is not enough: guides list other providers'
     * stream numbers too.
     */
    internal fun sameChannel(guideName: String?, channel: Channel): Boolean {
        if (guideName.isNullOrBlank()) return false
        fun norm(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}+]+"), " ").trim()
        val lineup = listOf(channel.name, EventChannelNames.name(channel)).distinct()
        if (norm(guideName) in lineup.map(::norm)) return true
        fun slot(value: String) = Regex("^([^:|]{2,40}?\\b[0-9]{1,3})\\s*[:|]").find(value)?.groupValues?.get(1)?.let(::norm)
        val guideSlot = slot(guideName) ?: return false
        return lineup.any { slot(it) == guideSlot }
    }

    internal fun map(channels: List<Channel>, source: Source, entries: Map<String, List<Programme>>, names: Map<String, String> = emptyMap()): Map<String, List<Programme>> {
        fun guideKey(channel: Channel): String? = xmlId(source, channel).takeIf { it in entries }
            ?: streamNumber(channel)?.takeIf { it in entries && sameChannel(names[it], channel) }
        val keys = HashMap<String, String>()
        val mapped = channels.distinctBy { it.guideId.ifBlank { it.id } }.filter { channel -> guideKey(channel)?.also { keys[channel.id] = it } != null }
        val total = mapped.sumOf { entries[keys.getValue(it.id)].orEmpty().size.toLong() }
        val quota = (ProgrammeBudget.MAX / mapped.size.coerceAtLeast(1)).coerceAtLeast(1)
        val now = System.currentTimeMillis()
        return buildMap {
            mapped.forEach { channel ->
                val key = channel.guideId.ifBlank { channel.id }
                if (!containsKey(key)) entries[keys.getValue(channel.id)]?.let { programmes ->
                    val kept = if (total <= ProgrammeBudget.MAX) programmes else ProgrammeBudget.Window(quota, now).also { window -> programmes.forEach(window::add) }.entries()
                    put(key, kept.map { if (it.channel == key) it else it.copy(channel = key) })
                }
            }
        }
    }
}
