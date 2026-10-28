package io.github.mot1us.spare

/** Session-only coverage evidence. Never stores a player or assumes that a stream is online. */
class SportsStreamCache(private val capacity: Int = 48, private val lifetimeMs: Long = 120000) {
    class Context(val source: String, val generation: Int, val guide: Guide, val favorites: Set<String>)
    data class Match(val game: SportsGame, val warmed: Map<String, List<Programme>> = emptyMap(), val autoReady: Boolean = true) {
        fun current(game: SportsGame) = copy(game = game.copy(broadcasts = this.game.broadcasts,
            candidates = this.game.candidates, titleMatches = this.game.titleMatches, coverageComplete = this.game.coverageComplete))
    }
    private data class Signature(val key: String, val state: String, val start: Long, val end: Long, val teams: List<String>, val networks: List<String>)
    private data class Entry(val signature: Signature, val match: Match, val expires: Long, val guideKeys: Set<String>)
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
            } }
            return Context(source, generation, guide, favorites).also { context = it }
        }
        return Context(source, generation, guide, favorites).also { context = it; entries.clear() }
    }
    @Synchronized fun current(token: Context) = context === token
    @Synchronized fun get(token: Context, game: SportsGame, now: Long, forAuto: Boolean = true): Match? {
        if (context !== token || !game.live(now)) return null
        val entry = entries[game.key] ?: return null
        if (entry.expires <= now || entry.signature != signature(game)) { entries.remove(game.key); return null }
        return entry.match.takeIf { !forAuto || it.autoReady }?.current(game)
    }
    @Synchronized fun put(token: Context, match: Match, now: Long): Boolean {
        val active = context ?: return false
        if (Thread.currentThread().isInterrupted || !match.game.live(now) || !match.game.coverageComplete) return false
        val keys = (match.game.broadcasts.map { it.channel } + match.game.titleMatches + match.game.candidates).map { token.guide.key(it) }.toSet()
        if (active !== token && (active.source != token.source || active.generation != token.generation || active.guide.channels !== token.guide.channels || active.favorites != token.favorites ||
            keys.isEmpty() && active.guide.programmes != token.guide.programmes || keys.any { key -> active.guide.programmes[key] != token.guide.programmes[key] &&
                (match.warmed[key] == null || active.guide.programmes[key] != match.warmed[key]) })) return false
        val existing = entries[match.game.key]
        if (existing?.match?.autoReady == true && !match.autoReady) return false
        val boundary = (match.game.broadcasts.asSequence().map { it.programme.end } + match.warmed.values.asSequence().flatten()
            .flatMap { sequenceOf(it.start, it.end) }).filter { it > now }.minOrNull() ?: Long.MAX_VALUE
        entries[match.game.key] = Entry(signature(match.game), match, minOf(now + lifetimeMs, boundary), keys)
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
