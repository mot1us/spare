package io.github.mot1us.spare

import kotlin.math.abs

/** A historical EPG match, not an inferred live-channel URL. */
data class SportsReplay(val channel: Channel, val programme: Programme) {
    val expires: Long get() = programme.start + channel.catchupDays * 86400000L
    fun item(game: SportsGame, http: Boolean, now: Long): OnDemand? = Catchup.url(channel, programme, http, now)?.let {
        OnDemand("catchup:${channel.id}:${programme.start}", game.title, channel.name, it, "catchup")
    }
}

object SportsReplays {
    fun eligible(game: SportsGame, days: Int, now: Long) = game.state == "post" && days > 0 && game.start > 0 && game.start <= now && game.start >= now - days * 86400000L
    private fun eligible(game: SportsGame, channel: Channel, now: Long) = !channel.media && Catchup.supported(channel) && eligible(game, channel.catchupDays, now)
    fun find(game: SportsGame, guide: Guide, now: Long): List<SportsReplay> {
        val target = game.teams.map { it.id }.toSet()
        val hints = game.teams.map { (it.aliases + it.name.split(' ').filter { word -> word.length >= 4 }).map(SportsCatalog::normalize).distinct() }
        val parsed = hashMapOf<Pair<String, String>, Boolean>()
        val result = mutableListOf<SportsReplay>()
        for (channel in guide.channels) {
            if (Thread.currentThread().isInterrupted) break
            if (!eligible(game, channel, now)) continue
            val match = guide.programmes[guide.key(channel)].orEmpty().asSequence().filter { p ->
                if (!Catchup.available(channel, p, now) || abs(p.start - game.start) > 90 * 60000L || p.end <= game.start) false else {
                    // Most listings are ASCII. Reject unrelated programmes without
                    // allocating and normalizing the entire description for every game.
                    hints.all { aliases -> aliases.any { p.title.contains(it, true) || p.description.contains(it, true) } } ||
                        ((p.title.any { it.code > 127 } || p.description.any { it.code > 127 }) &&
                            SportsCatalog.normalize(p.title + " " + p.description).let { text -> hints.all { aliases -> aliases.any { it in text } } })
                }
            }.filter { p -> parsed.getOrPut(p.title to p.description) { SportsGuide.teams(p, game.teams.first().league)?.map { it.id }?.toSet() == target } }
                .minByOrNull { abs(it.start - game.start) }
            if (match != null) result.add(SportsReplay(channel, match))
        }
        return rank(result)
    }
    /** Provider histories belong to a stream ID, even when XMLTV IDs are shared. */
    fun findListings(game: SportsGame, guide: Guide, listings: Map<String, List<Programme>>, now: Long): List<SportsReplay> =
        rank(guide.channels.asSequence().filter { listings.containsKey(it.id) }.flatMap { channel ->
            find(game, Guide(listOf(channel), mapOf(guide.key(channel) to listings.getValue(channel.id))), now).asSequence()
        }.toList())
    /** Live-channel quality is a preference for its archive, never an eligibility rule. */
    fun rank(choices: List<SportsReplay>, quality: Map<String, VideoInfo> = emptyMap(), favorites: Set<String> = emptySet()): List<SportsReplay> =
        choices.distinctBy { it.channel.id }.sortedWith(compareByDescending<SportsReplay> { it.channel.quality(quality[it.channel.id])?.height ?: 0 }
            .thenByDescending { SportsStreams.preferenceRank(it.channel, quality[it.channel.id]) }
            .thenByDescending { it.channel.quality(quality[it.channel.id])?.fps?.takeIf { fps -> fps.isFinite() && fps > 0f } ?: 0f }
            .thenByDescending { it.channel.id in favorites }
            .thenByDescending { it.expires })
    fun best(choices: List<SportsReplay>, quality: Map<String, VideoInfo> = emptyMap(), favorites: Set<String> = emptySet()): SportsReplay? =
        rank(choices, quality, favorites).firstOrNull()
    fun retained(choices: List<SportsReplay>, channels: Map<String, Channel>, now: Long): List<SportsReplay> = choices.mapNotNull { replay ->
        channels[replay.channel.id]?.takeIf { it.url == replay.channel.url && Catchup.available(it, replay.programme, now) }
            ?.let { SportsReplay(it, replay.programme) }
    }
    /** Search the archive inventory, prioritizing broadcasters/teams without excluding other feeds. */
    fun candidates(game: SportsGame, guide: Guide, favorites: Set<String>, now: Long, limit: Int = 6, knownFeeds: Set<String> = emptySet()): List<Channel> {
        data class Candidate(val channel: Channel, val score: Int, val family: String)
        val teamHints = game.teams.associateWith { team -> team.aliases.map(SportsCatalog::normalize).filter { it.length >= 3 }.map { " $it " } }
        val networkKeys = game.networks.associateWith(SportsStreams::broadcasterKey)
        val groupKeys = mutableMapOf<String, String>()
        val league = SportsCatalog.normalize(game.teams.first().league)
        fun candidate(channel: Channel): Candidate {
            val name = channel.sportsKey
            val teams = game.teams.filter { team -> teamHints.getValue(team).any { it in name } }
            val networks = game.networks.filter { SportsStreams.broadcasterMatchesKey(networkKeys.getValue(it), channel) }
            val context = groupKeys.getOrPut(channel.group) { SportsCatalog.normalize(channel.group) } + " " + name.trim()
            val score = (if (channel.id in knownFeeds) 1000 else 0) + teams.size * 4 + (if (networks.isNotEmpty()) 2 else 0) + (if (" $league " in " $context ") 1 else 0)
            val family = networks.firstOrNull()?.let(SportsCatalog::normalize) ?: teams.joinToString { it.id }.ifBlank { context }
            return Candidate(channel, score, family)
        }
        val sorted = Catchup.channels(guide).asSequence().filter { eligible(game, it, now) }.map(::candidate)
            .sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.channel.quality(null)?.height ?: 0 }
                .thenByDescending { SportsStreams.preferenceRank(it.channel, null) }.thenByDescending { it.channel.id in favorites }).toList()
        // Share the budget between broadcasters and rate/resolution variants.
        // A shared EPG ID never proves that two provider archives are interchangeable.
        val varied = sorted.groupBy { it.score }.values.flatMap { group ->
            group.distinctBy { entry ->
                val info = entry.channel.quality(null)
                Triple(entry.family, info?.height ?: 0, info?.fps?.takeIf { it.isFinite() && it > 0f }?.let(FrameRateFilter::bucket) ?: -1)
            } + group
        }
        return varied.distinctBy { it.channel.id }.take(limit.coerceIn(1, Importers.MAX_CHANNELS)).map { it.channel }
    }
}

object SportsText {
    fun score(game: SportsGame, hidden: Boolean) = if (hidden && game.state == "post") game.title else game.scoreLine
    fun status(game: SportsGame, hidden: Boolean, now: Long) = if (hidden && game.state == "post") "Completed" else game.statusLine(now)
}
