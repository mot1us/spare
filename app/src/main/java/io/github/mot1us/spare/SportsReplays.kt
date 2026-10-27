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
        return result.distinctBy { it.channel.id }
    }
    /** Only a few likely broadcaster/team archives are queried when saved EPG is missing. */
    fun candidates(game: SportsGame, guide: Guide, favorites: Set<String>, now: Long): List<Channel> {
        val networks = game.networks.map(SportsCatalog::normalize).filter { it.length >= 3 }
        fun score(channel: Channel): Int {
            val name = channel.sportsKey
            val teams = game.teams.count { team -> team.aliases.any { name.contains(" ${SportsCatalog.normalize(it)} ") } }
            return teams * 4 + if (networks.any { name.contains(it) }) 2 else 0
        }
        return guide.channels.asSequence().filter { eligible(game, it, now) }.map { it to score(it) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Channel, Int>> { it.second }.thenByDescending { it.first.id in favorites })
            .distinctBy { guide.key(it.first) }.take(6).map { it.first }.toList()
    }
}

object SportsText {
    fun score(game: SportsGame, hidden: Boolean) = if (hidden && game.state == "post") game.title else game.scoreLine
    fun status(game: SportsGame, hidden: Boolean, now: Long) = if (hidden && game.state == "post") "Completed" else game.statusLine(now)
}
