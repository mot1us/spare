package io.github.mot1us.spare


/**
 * Event channels ("NFL Game Pass 11: SEA @ LV", "PPV 3: NO EVENT") are renamed by the provider as games start, so a
 * channel list saved hours earlier still shows "NO EVENT". While Sports is open, Spare reads just those categories
 * again from Xtream providers (a small request) and uses the fresh names for finding a game's channel.
 */
object EventChannelNames {
    private val numbered = Regex("^[^:|]{2,60}?\\b\\d{1,3}\\s*[:|]")
    private val idle = Regex("\\bno event\\b|\\bnot live\\b|\\boff ?air\\b", RegexOption.IGNORE_CASE)
    private const val MIN_EVENT_CHANNELS = 3
    const val MAX_CATEGORIES = 8
    const val REFRESH_MS = 10 * 60000L

    @Volatile private var names: Map<String, String> = emptyMap()
    @Volatile var version = 0; private set

    /** The freshest known name of a channel, for matching it to a game. */
    fun name(channel: Channel): String = names[channel.id] ?: channel.name
    fun renamed(channel: Channel) = channel.id in names
    /** Renamed channels of [channels]: their saved names cannot have put them in any name index. */
    fun renamedIn(channels: List<Channel>): List<Channel> = if (names.isEmpty()) emptyList() else channels.filter { it.id in names }

    fun looksLikeEvent(name: String) = idle.containsMatchIn(name) || numbered.containsMatchIn(name)

    /** Categories holding several numbered or "no event" channels: the provider's event slots. */
    fun eventGroups(channels: List<Channel>): Set<String> = channels.asSequence().filter { !it.media && looksLikeEvent(it.name) }
        .groupingBy { it.group }.eachCount().filterValues { it >= MIN_EVENT_CHANNELS }.keys.take(MAX_CATEGORIES).toSet()

    /** Fresh names by native channel ID ("xc:123") for the given category names of one Xtream playlist. */
    fun fetch(source: Source, groups: Set<String>, get: (String) -> String = { url -> Network.get(url, source.allowHttp, 4 * 1024 * 1024, timeoutMs = 8000).toString(Charsets.UTF_8) }): Map<String, String> {
        if (source.kind != "xtream" || groups.isEmpty()) return emptyMap()
        val categories = Importers.xtreamArray(get(Importers.endpoint(source, "player_api.php", "get_live_categories")))
        val wanted = (0 until minOf(categories.length(), 20000)).mapNotNull { i -> categories.optJSONObject(i) }
            .filter { it.optString("category_name").take(128) in groups }.map { it.optString("category_id") }
            .filter { it.matches(Regex("[0-9]{1,12}")) }.take(MAX_CATEGORIES)
        val fresh = HashMap<String, String>()
        for (id in wanted) {
            if (Thread.currentThread().isInterrupted) break
            val streams = Importers.xtreamArray(get(Importers.endpoint(source, "player_api.php", "get_live_streams") + "&category_id=$id"))
            for (i in 0 until minOf(streams.length(), 5000)) {
                val stream = streams.optJSONObject(i) ?: continue
                val streamId = stream.optString("stream_id").takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: continue
                stream.optString("name").trim().take(256).takeIf { it.isNotBlank() }?.let { fresh["xc:$streamId"] = it }
            }
        }
        return fresh
    }

    /**
     * Record fresh names for the guide's channels (matched by playlist and native ID). Returns true when any name the
     * Sports matcher sees changed.
     */
    fun update(channels: List<Channel>, playlist: (Channel) -> String?, fresh: Map<String, Map<String, String>>): Boolean {
        val next = HashMap(names)
        var changed = false
        for (channel in channels) {
            val name = fresh[playlist(channel)]?.get(channel.nativeId) ?: continue
            if (name != channel.name || channel.id in next) {
                if (next[channel.id] != name) changed = true
                if (name == channel.name) next.remove(channel.id) else next[channel.id] = name
            }
        }
        if (changed) { names = next; version++ }
        return changed
    }

    fun clear() { if (names.isNotEmpty()) { names = emptyMap(); version++ } }
}
