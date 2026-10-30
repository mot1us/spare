package io.github.mot1us.spare

/** Session-only coverage evidence. Never stores a player or assumes that a stream is online. */
class SportsStreamCache(private val capacity: Int = 48, private val lifetimeMs: Long = 120000) {
    class Context(val source: String, val generation: Int, val guide: Guide, val favorites: Set<String>)
    data class Match(val game: SportsGame, val warmed: Map<String, List<Programme>> = emptyMap(), val autoReady: Boolean = true) {
        fun current(game: SportsGame) = copy(game = game.copy(broadcasts = this.game.broadcasts,
            candidates = this.game.candidates, titleMatches = this.game.titleMatches, coverageComplete = this.game.coverageComplete))
        fun needsRefresh(previous: Guide, current: Guide, now: Long): Boolean {
            if (previous.channels !== current.channels) return true
            if (previous.programmes === current.programmes) return false
            val keys = (game.broadcasts.map { it.channel } + game.titleMatches + game.candidates).map { previous.key(it) }.toSet() + warmed.keys
            return keys.any { current.programmes[it] != previous.programmes[it] && current.programmes[it] != warmed[it] } ||
                SportsSchedule.newlyConfirmed(game, previous, current, now, warmed.keys)
        }
        fun mergeGuide(guide: Guide, now: Long): Pair<Guide, Match> {
            if (warmed.isEmpty()) return guide to this
            val retention = mutableMapOf<String, Int>()
            guide.channels.forEach { channel ->
                val key = guide.key(channel)
                if (key in warmed) retention[key] = maxOf(retention[key] ?: 0, channel.catchupDays)
            }
            val merged = warmed.mapValues { (key, entries) -> GuideHistory.merge(guide.programmes[key].orEmpty(), entries, retention[key] ?: 0, now) }
            return guide.copy(programmes = guide.programmes + merged) to copy(warmed = merged)
        }
    }
    private data class Signature(val key: String, val state: String, val start: Long, val end: Long, val teams: List<String>, val networks: List<String>)
    private data class Entry(val signature: Signature, val match: Match, val expires: Long, val displayUntil: Long, val guideKeys: Set<String>)
    private var context: Context? = null
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private fun signature(game: SportsGame) = Signature(game.key, game.state, game.start, game.end, game.teams.map { it.id }, game.networks)
    @Synchronized fun use(source: String, generation: Int, guide: Guide, favorites: Set<String>): Context {
        val previous = context
        if (previous != null && previous.source == source && previous.generation == generation && previous.guide.channels === guide.channels && previous.favorites == favorites) {
            if (previous.guide.programmes === guide.programmes) return previous
            entries.entries.removeAll { (_, entry) -> entry.guideKeys.isEmpty() || entry.guideKeys.any { key ->
                guide.programmes[key] != previous.guide.programmes[key] &&
                    (entry.match.warmed[key] == null || guide.programmes[key] != entry.match.warmed[key])
            } || SportsSchedule.newlyConfirmed(entry.match.game, previous.guide, guide, System.currentTimeMillis(), entry.match.warmed.keys) }
            return Context(source, generation, guide, favorites).also { context = it }
        }
        return Context(source, generation, guide, favorites).also { context = it; entries.clear() }
    }
    @Synchronized fun current(token: Context) = context === token
    @Synchronized fun get(token: Context, game: SportsGame, now: Long, forAuto: Boolean = true, allowStale: Boolean = false): Match? {
        if (context !== token || !game.live(now) && !(game.upcoming(now) && !forAuto)) return null
        val entry = entries[game.key] ?: return null
        if (entry.displayUntil <= now || entry.signature != signature(game)) { entries.remove(game.key); return null }
        // A refresh becoming due must not erase an already discovered channel from its card.
        // Automatic playback still requires a fresh match; stale evidence is display-only.
        if (entry.expires <= now && (!allowStale || forAuto)) return null
        return entry.match.takeIf { !forAuto || it.autoReady }?.current(game)
    }
    @Synchronized fun put(token: Context, match: Match, now: Long): Boolean {
        val active = context ?: return false
        if (Thread.currentThread().isInterrupted || !(match.game.live(now) || match.game.upcoming(now)) || !match.game.coverageComplete) return false
        val keys = (match.game.broadcasts.map { it.channel } + match.game.titleMatches + match.game.candidates).map { token.guide.key(it) }.toSet()
        if (active !== token && (active.source != token.source || active.generation != token.generation || active.guide.channels !== token.guide.channels || active.favorites != token.favorites ||
            keys.isEmpty() && active.guide.programmes != token.guide.programmes || keys.any { key -> active.guide.programmes[key] != token.guide.programmes[key] &&
                (match.warmed[key] == null || active.guide.programmes[key] != match.warmed[key]) })) return false
        val existing = entries[match.game.key]
        if (existing?.match?.autoReady == true && !match.autoReady) return false
        val boundary = (sequenceOf(match.game.end, if (match.game.upcoming(now)) match.game.start else Long.MAX_VALUE) + match.game.broadcasts.asSequence().map { it.programme.end } + match.warmed.values.asSequence().flatten()
            .flatMap { sequenceOf(it.start, it.end) }).filter { it > now }.minOrNull() ?: Long.MAX_VALUE
        val expires = minOf(now + lifetimeMs, boundary)
        val found = match.game.broadcasts.isNotEmpty() || match.game.titleMatches.isNotEmpty() || match.game.candidates.isNotEmpty()
        entries[match.game.key] = Entry(signature(match.game), match, expires, if (found) boundary else expires, keys)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return true
    }
    @Synchronized fun clear() { context = null; entries.clear() }
}

/** A maximum of three automatic attempts; ordinary channel changes abandon the plan. */
class SportsPlaybackPlan(val game: SportsGame, candidates: List<Channel>) {
    val candidates = candidates.distinctBy { it.id }.take(12)
    private val attempted = mutableSetOf<String>()
    var channel: Channel? = null; private set
    var accepted = false
    fun next(available: (Channel) -> Boolean): Channel? = if (attempted.size >= 3) null else candidates.firstOrNull { it.id !in attempted && available(it) }?.also {
        attempted.add(it.id); channel = it; accepted = false
    }
}
