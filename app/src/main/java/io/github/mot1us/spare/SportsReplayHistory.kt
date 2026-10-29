package io.github.mot1us.spare

import org.json.JSONArray

/** Confirmed replay references; SecureStore encrypts the per-playlist file. No stream probes. */
object SportsReplayHistory {
    fun encode(matches: Map<String, List<SportsReplay>>, now: Long): ByteArray {
        val entries = JSONArray()
        matches.entries.toList().takeLast(256).forEach { (game, choices) ->
            if (game.length <= 512) choices.filter { Catchup.available(it.channel, it.programme, now) }.take(4).forEach { replay ->
                entries.put(JSONArray().put(game).put(replay.channel.id).put(Channel.stableId(replay.channel.url))
                    .put(replay.programme.copy(description = "", artwork = "").json()))
            }
        }
        return entries.toString().toByteArray()
    }
    fun decode(bytes: ByteArray?, guide: Guide, now: Long): Map<String, List<SportsReplay>> {
        if (bytes == null) return emptyMap()
        require(bytes.size <= 2 * 1024 * 1024)
        val entries = JSONArray(bytes.toString(Charsets.UTF_8)); require(entries.length() <= 1024)
        val channels = guide.channels.associateBy { it.id }
        val matches = linkedMapOf<String, MutableList<SportsReplay>>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONArray(index); val game = entry.getString(0); require(game.length <= 512)
            val channel = channels[entry.getString(1)] ?: continue
            if (entry.getString(2) != Channel.stableId(channel.url)) continue
            val programme = Programme.from(entry.getJSONArray(3))
            if (Catchup.available(channel, programme, now)) matches.getOrPut(game) { mutableListOf() }.add(SportsReplay(channel, programme))
        }
        return matches
    }
}
