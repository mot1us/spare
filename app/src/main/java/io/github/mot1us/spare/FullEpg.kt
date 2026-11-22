package io.github.mot1us.spare

import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Read one provider guide off-thread, keeping stable stream IDs for existing guide/history caches. */
object FullEpg {
    private fun xmlId(source: Source, channel: Channel) =
        if (source.kind == "xtream" && source.epgUrl.isBlank()) channel.xmltvId else channel.guideId.ifBlank { channel.id }

    /** An Xtream channel's own stream number ("150130" for xc:150130). */
    internal fun streamNumber(channel: Channel): String? =
        channel.nativeId.takeIf { it.startsWith("xc:") }?.removePrefix("xc:")?.takeIf { it.matches(Regex("[0-9]{1,20}")) }

    /**
     * [scratch] holds the downloaded file while it is read: a TV-box CPU reads a full third-party guide (48 MB packed, about
     * 600 MB of XML) for minutes, and a server dropped the connection while Spare read it straight from the socket. Jellyfin
     * and Kodi also save the guide before reading it. [budget] caps the programmes kept, so a second guide that fills gaps
     * stays inside one guide's memory.
     */
    fun load(source: Source, channels: List<Channel>, url: String, scratch: File? = null, budget: Int = ProgrammeBudget.MAX,
        stage: (String) -> Unit = {}): Map<String, List<Programme>> {
        val aliases = channels.groupBy { xmlId(source, it) }.filterKeys { it.isNotBlank() }
        // A guide made for one provider can list its channels under that provider's stream numbers ("150130" for ESPN+ 19),
        // which the provider's own guide IDs never use. Such a number counts only when the guide's channel name agrees.
        val numbered = source.kind == "xtream" && source.epgUrl.isNotBlank()
        require(aliases.isNotEmpty() || numbered) { "Provider supplied no XMLTV channel IDs" }
        val history = HashMap<String, Int>()
        aliases.forEach { (key, list) -> history[key] = list.maxOf { it.catchupDays } }
        val keys = HashMap<String, String>()
        fun parse(input: java.io.InputStream) = Importers.xmltv(input, aliases.keys, historyDays = history, listed = { listed ->
            choose(channels, source, listed, numbered).also { chosen ->
                keys.putAll(chosen)
                channels.forEach { channel -> chosen[channel.id]?.let { history[it] = maxOf(history[it] ?: 0, channel.catchupDays) } }
            }.values.toSet()
        })
        val entries = if (scratch == null) {
            // XMLTV files are large and parsed while streaming: on a TV-box CPU parsing can outlast the
            // download. Only socket time counts, with a budget sized for a full guide on a slow provider.
            Network.consume(url, source.allowHttp, limit = MAX_DOWNLOAD, timeoutMs = 20000, networkTimeOnly = true, deadlineMs = Network.LARGE_DEADLINE_MS, stallBytes = Network.STALL_BYTES, read = ::parse)
        } else try {
            scratch.parentFile?.mkdirs()
            Network.consume(url, source.allowHttp, limit = MAX_DOWNLOAD, timeoutMs = 20000, networkTimeOnly = true, deadlineMs = Network.LARGE_DEADLINE_MS, stallBytes = Network.STALL_BYTES) { input ->
                scratch.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            stage(READING)
            scratch.inputStream().use(::parse)
        } finally { scratch.delete() }
        require(entries.isNotEmpty()) { "No programme data matched this playlist" }
        return map(channels, source, entries, keys, budget)
    }

    /**
     * Guide channels for lineup channels the guide does not list by ID: an Xtream stream number whose guide name agrees, then a
     * name that exactly one guide channel has. Kodi, StreamVault and others fall back to names the same way; a name two guide
     * channels share is never guessed.
     */
    internal fun choose(channels: List<Channel>, source: Source, listed: Map<String, List<String>>, numbered: Boolean): Map<String, String> {
        val chosen = HashMap<String, String>()
        val byName = HashMap<String, String?>()
        listed.forEach { (id, names) -> names.map(::nameKey).filter { it.length >= 3 }.distinct().forEach { key -> byName[key] = if (key in byName && byName[key] != id) null else id } }
        channels.forEach { channel ->
            val number = if (numbered) streamNumber(channel)?.takeIf { sameChannel(listed[it]?.firstOrNull(), channel) } else null
            // A channel the guide lists by its own ID keeps that ID; only its stream number may still stand in for it.
            val named = if (xmlId(source, channel).let { it.isNotBlank() && it in listed }) null else byName[nameKey(channel.name)]
            (number ?: named)?.let { chosen[channel.id] = it }
        }
        return chosen
    }

    /** A channel name without accents, a leading country tag ("US: ", "UK | ") or picture-quality tags, letters and digits only. */
    internal fun nameKey(value: String): String {
        val plain = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.replace(Regex("^[a-z]{2,3}\\s*[:|]\\s*"), "").replace(Regex("\\b(4k|uhd|fhd|hd|sd|hevc|h265|50fps|60fps)\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}+]"), "")
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

    internal fun map(channels: List<Channel>, source: Source, entries: Map<String, List<Programme>>, chosen: Map<String, String> = emptyMap(),
        budget: Int = ProgrammeBudget.MAX): Map<String, List<Programme>> {
        fun guideKey(channel: Channel): String? = xmlId(source, channel).takeIf { it in entries } ?: chosen[channel.id]?.takeIf { it in entries }
        val keys = HashMap<String, String>()
        val mapped = channels.distinctBy { it.guideId.ifBlank { it.id } }.filter { channel -> guideKey(channel)?.also { keys[channel.id] = it } != null }
        val total = mapped.sumOf { entries[keys.getValue(it.id)].orEmpty().size.toLong() }
        val limit = budget.coerceIn(1, ProgrammeBudget.MAX)
        val quota = (limit / mapped.size.coerceAtLeast(1)).coerceAtLeast(1)
        val now = System.currentTimeMillis()
        return buildMap {
            mapped.forEach { channel ->
                val key = channel.guideId.ifBlank { channel.id }
                if (!containsKey(key)) entries[keys.getValue(channel.id)]?.let { programmes ->
                    val kept = if (total <= limit) programmes else ProgrammeBudget.Window(quota, now).also { window -> programmes.forEach(window::add) }.entries()
                    put(key, kept.map { if (it.channel == key) it else it.copy(channel = key) })
                }
            }
        }
    }

    const val READING = "Reading programme guide…"
    private const val MAX_DOWNLOAD = 64 * 1024 * 1024
}
