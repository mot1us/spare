package io.github.mot1us.spare

import org.json.JSONArray

/** Confirmed replay references; SecureStore encrypts the per-playlist file. No stream probes. */
object SportsReplayHistory {
    private const val MAX_BYTES = 2 * 1024 * 1024
    fun encode(matches: Map<String, List<SportsReplay>>, now: Long): ByteArray {
        val entries = JSONArray()
        var bytes = 2 // JSON array brackets, plus UTF-8 row bytes and commas below.
        val hashes = mutableMapOf<String, String>()
        matches.entries.toList().takeLast(256).asReversed().forEach { (game, choices) ->
            if (game.length <= 512) choices.filter { Catchup.available(it.channel, it.programme, now) }.take(4).forEach { replay ->
                val row = JSONArray().put(game).put(replay.channel.id).put(hashes.getOrPut(replay.channel.url) { Channel.stableId(replay.channel.url) })
                    .put(replay.programme.copy(description = "", artwork = "").json())
                val size = row.toString().toByteArray(Charsets.UTF_8).size + if (entries.length() == 0) 0 else 1
                if (bytes + size <= MAX_BYTES) { entries.put(row); bytes += size }
            }
        }
        return entries.toString().toByteArray()
    }
    fun decode(bytes: ByteArray?, guide: Guide, now: Long): Map<String, List<SportsReplay>> {
        if (bytes == null) return emptyMap()
        require(bytes.size <= MAX_BYTES)
        val entries = JSONArray(bytes.toString(Charsets.UTF_8)); require(entries.length() <= 1024)
        val channels = guide.channels.associateBy { it.id }
        val hashes = mutableMapOf<String, String>()
        val matches = linkedMapOf<String, MutableList<SportsReplay>>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONArray(index); val game = entry.getString(0); require(game.length <= 512)
            val channel = channels[entry.getString(1)] ?: continue
            if (entry.getString(2) != hashes.getOrPut(channel.id) { Channel.stableId(channel.url) }) continue
            val programme = Programme.from(entry.getJSONArray(3))
            if (Catchup.available(channel, programme, now)) matches.getOrPut(game) { mutableListOf() }.add(SportsReplay(channel, programme))
        }
        return matches
    }
}
