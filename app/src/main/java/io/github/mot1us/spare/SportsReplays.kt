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
        val hints = game.teams.map { (it.aliases + it.name.split(' ').filter { word -> word.length >= 4 }).distinct() }
        val parsed = hashMapOf<Triple<String, String, String>, Boolean>()
        val result = mutableListOf<SportsReplay>()
        for (channel in guide.channels) {
            if (Thread.currentThread().isInterrupted) break
            if (!eligible(game, channel, now)) continue
            val context = channel.name + " " + channel.group
            val match = guide.programmes[guide.key(channel)].orEmpty().asSequence().filter { p ->
                Catchup.available(channel, p, now) && abs(p.start - game.start) <= 90 * 60000L && p.end > game.start &&
                    hints.all { aliases -> aliases.any { p.title.contains(it, true) || p.description.contains(it, true) } }
            }.filter { p -> parsed.getOrPut(Triple(p.title, p.description, context)) { SportsGuide.teams(p, context)?.map { it.id }?.toSet() == target } }
                .minByOrNull { abs(it.start - game.start) }
            if (match != null) result.add(SportsReplay(channel, match))
        }
        return rank(result)
    }
    /** Live-channel quality is a preference for its archive, never an eligibility rule. */
    fun rank(choices: List<SportsReplay>, quality: Map<String, VideoInfo> = emptyMap(), favorites: Set<String> = emptySet()): List<SportsReplay> =
        choices.distinctBy { it.channel.id }.sortedWith(compareByDescending<SportsReplay> { it.channel.quality(quality[it.channel.id])?.height ?: 0 }
            .thenByDescending { SportsStreams.preferenceRank(it.channel, quality[it.channel.id]) }
            .thenByDescending { it.channel.quality(quality[it.channel.id])?.fps?.takeIf { fps -> fps.isFinite() && fps > 0f } ?: 0f }
            .thenByDescending { it.channel.id in favorites }
            .thenByDescending { it.expires })
    fun best(choices: List<SportsReplay>, quality: Map<String, VideoInfo> = emptyMap(), favorites: Set<String> = emptySet()): SportsReplay? =
        rank(choices, quality, favorites).firstOrNull()
    /** Only a few likely broadcaster/team archives are queried when saved EPG is missing. */
    fun candidates(game: SportsGame, guide: Guide, favorites: Set<String>, now: Long): List<Channel> {
        data class Candidate(val channel: Channel, val score: Int, val family: String)
        fun candidate(channel: Channel): Candidate {
            val name = channel.sportsKey
            val teams = game.teams.filter { team -> team.aliases.any { alias -> SportsCatalog.normalize(alias).let { it.length >= 3 && " $it " in name } } }
            val networks = game.networks.filter { SportsStreams.broadcasterMatches(it, channel) }
            val score = teams.size * 4 + if (networks.isNotEmpty()) 2 else 0
            return Candidate(channel, score, networks.firstOrNull()?.let(SportsCatalog::normalize) ?: teams.joinToString { it.id })
        }
        val sorted = guide.channels.asSequence().filter { eligible(game, it, now) }.distinctBy { it.id }.map(::candidate).filter { it.score > 0 }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.channel.quality(null)?.height ?: 0 }
                .thenByDescending { SportsStreams.preferenceRank(it.channel, null) }.thenByDescending { it.channel.id in favorites }).toList()
        // Share the budget between broadcasters and rate/resolution variants.
        // A shared EPG ID never proves that two provider archives are interchangeable.
        val varied = sorted.distinctBy { entry ->
            val info = entry.channel.quality(null)
            Triple(entry.family, info?.height ?: 0, info?.fps?.takeIf { it.isFinite() && it > 0f }?.let(FrameRateFilter::bucket) ?: -1)
        }
        return (varied + sorted).distinctBy { it.channel.id }.take(6).map { it.channel }
    }
}

object SportsText {
    fun score(game: SportsGame, hidden: Boolean) = if (hidden && game.state == "post") game.title else game.scoreLine
    fun status(game: SportsGame, hidden: Boolean, now: Long) = if (hidden && game.state == "post") "Completed" else game.statusLine(now)
}
