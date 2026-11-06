package io.github.mot1us.spare

/** Session-only coverage evidence. Never stores a player or assumes that a stream is online. */
class SportsStreamCache(private val capacity: Int = 128, private val lifetimeMs: Long = 120000, private val clock: () -> Long = System::currentTimeMillis) {
    class Context(val source: String, val generation: Int, val guide: Guide, val favorites: Set<String>)
    data class Match(val game: SportsGame, val warmed: Map<String, List<Programme>> = emptyMap(), val autoReady: Boolean = true) {
        fun current(game: SportsGame) = copy(game = game.copy(broadcasts = this.game.broadcasts,
            candidates = this.game.candidates, titleMatches = this.game.titleMatches, coverageComplete = this.game.coverageComplete))
        fun confirmed(now: Long) = game.titleMatches.isNotEmpty() || game.broadcasts.any { game.upcoming(now) || now in it.programme.start until it.programme.end }
        fun needsRefresh(previous: Guide, current: Guide, now: Long): Boolean {
            if (previous.channels !== current.channels && previous.channels != current.channels) return true
            if (previous.programmes === current.programmes) return false
            val channels = (game.broadcasts.map { it.channel } + game.titleMatches).distinctBy { it.id }
            val keys = channels.map { previous.key(it) }.toSet()
            val changed = keys.any { current.programmes[it] != previous.programmes[it] && current.programmes[it] != warmed[it] }
            if (changed) {
                val next = SportsSchedule.match(game, current.copy(channels = channels), emptySet(), now)
                fun evidence(value: SportsGame) = value.broadcasts.map { listOf(it.channel.id, it.programme.title, it.programme.start, it.programme.end) }.toSet()
                if (evidence(next) != evidence(game) || next.titleMatches != game.titleMatches) return true
            }
            // A newly loaded feed can improve an unresolved search, but must not
            // discard a confirmed game and make a click scan the whole playlist again.
            return !confirmed(now) && SportsSchedule.newlyConfirmed(game, previous, current, now, warmed.keys + keys)
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
    private data class Entry(val signature: Signature, val match: Match, val expires: Long, val displayUntil: Long)
    private var context: Context? = null
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private data class Published(val context: Context?, val entries: Map<String, Entry>)
    @Volatile private var published = Published(null, emptyMap())
    private fun publish() { published = Published(context, LinkedHashMap(entries)) }
    /** Read-only fast path: validate only known feeds; never wait on a background scan. */
    fun peek(source: String, generation: Int, guide: Guide, favorites: Set<String>, game: SportsGame, now: Long, forAuto: Boolean = true): Match? {
        val saved = published; val owner = saved.context ?: return null
        if (owner.source != source || owner.generation != generation || owner.guide.channels !== guide.channels || owner.favorites != favorites) return null
        if (!game.live(now) && !(game.upcoming(now) && !forAuto)) return null
        val entry = saved.entries[game.key] ?: return null
        if (entry.signature != signature(game) || entry.displayUntil <= now) return null
        val confirmed = entry.match.confirmed(now)
        if (owner.guide.programmes !== guide.programmes && (!confirmed || entry.match.needsRefresh(owner.guide, guide, now))) return null
        if (entry.match.game.broadcasts.isNotEmpty() && !confirmed) return null
        if (entry.expires <= now && !confirmed) return null
        return entry.match.takeIf { !forAuto || it.autoReady }?.current(game)
    }

    private fun signature(game: SportsGame) = Signature(game.key, game.state, game.start, game.end, game.teams.map { it.id }, game.networks)
    @Synchronized fun use(source: String, generation: Int, guide: Guide, favorites: Set<String>): Context {
        val previous = context; val now = clock()
        if (previous != null && previous.source == source && previous.generation == generation && previous.guide.channels == guide.channels && previous.favorites == favorites) {
            if (previous.guide.programmes === guide.programmes && previous.guide.channels === guide.channels) return previous
            entries.entries.removeAll { (_, entry) -> entry.match.needsRefresh(previous.guide, guide, now) }
            // Incorporate only newly supplied EPG rows off-thread. Existing ready feeds
            // remain published while a full-guide download adds better alternatives.
            if (entries.values.any { it.match.confirmed(now) }) {
                val changed = guide.programmes.filter { (key, rows) -> rows != previous.guide.programmes[key] }
                val channels = guide.channels.filter { guide.key(it) in changed }
                if (channels.isNotEmpty()) {
                    val delta = Guide(channels, changed)
                    entries.replaceAll { _, entry ->
                        if (!entry.match.confirmed(now)) entry else {
                            val extra = SportsSchedule.match(entry.match.game, delta, favorites, now)
                            val original = entry.match.game
                            val broadcasts = (original.broadcasts + extra.broadcasts).distinctBy { it.channel.id }
                            val ids = broadcasts.map { it.channel.id }.toSet()
                            entry.copy(match = entry.match.copy(game = original.copy(broadcasts = broadcasts,
                                titleMatches = (original.titleMatches + extra.titleMatches).distinctBy { it.id }.filterNot { it.id in ids },
                                candidates = original.candidates.filterNot { it.id in ids })))
                        }
                    }
                }
            }
            return Context(source, generation, guide, favorites).also { context = it; publish() }
        }
        return Context(source, generation, guide, favorites).also { context = it; entries.clear(); publish() }
    }
    @Synchronized fun current(token: Context) = context === token
    @Synchronized fun get(token: Context, game: SportsGame, now: Long, forAuto: Boolean = true, allowStale: Boolean = false): Match? {
        if (context !== token || !game.live(now) && !(game.upcoming(now) && !forAuto)) return null
        val entry = entries[game.key] ?: return null
        if (entry.displayUntil <= now || entry.signature != signature(game)) { entries.remove(game.key); publish(); return null }
        val confirmed = entry.match.confirmed(now)
        if (entry.match.game.broadcasts.isNotEmpty() && !confirmed) return null
        // Confirmed coverage lasts for this game, not a two-minute background timer.
        // Empty/broadcaster-only searches still retry so new coverage can be discovered.
        if (entry.expires <= now && (!allowStale || forAuto) && !confirmed) return null
        return entry.match.takeIf { !forAuto || it.autoReady }?.current(game)
    }
    @Synchronized fun put(token: Context, match: Match, now: Long): Boolean {
        val active = context ?: return false
        if (Thread.currentThread().isInterrupted || !(match.game.live(now) || match.game.upcoming(now)) || !match.game.coverageComplete) return false
        if (active !== token && (active.source != token.source || active.generation != token.generation || active.guide.channels != token.guide.channels || active.favorites != token.favorites || match.needsRefresh(token.guide, active.guide, now))) return false
        val existing = entries[match.game.key]
        if (existing?.match?.autoReady == true && !match.autoReady) return false
        val boundary = if (match.game.upcoming(now)) match.game.start else match.game.end
        val expires = minOf(now + lifetimeMs, boundary)
        val found = match.game.broadcasts.isNotEmpty() || match.game.titleMatches.isNotEmpty() || match.game.candidates.isNotEmpty()
        entries[match.game.key] = Entry(signature(match.game), match, expires, if (found) boundary else expires)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        publish(); return true
    }
    @Synchronized fun clear() { context = null; entries.clear(); publish() }
}

/** A maximum of three automatic attempts; ordinary channel changes abandon the plan. */
class SportsPlaybackPlan(val game: SportsGame, candidates: List<Channel>, val backgroundRecovery: Boolean = false) {
    val candidates = candidates.distinctBy { it.id }.take(12)
    private val attempted = mutableSetOf<String>()
    var channel: Channel? = null; private set
    var accepted = false
    val attemptedIds get() = attempted.toSet()
    fun next(available: (Channel) -> Boolean): Channel? = if (attempted.size >= 3) null else candidates.firstOrNull { it.id !in attempted && available(it) }?.also {
        attempted.add(it.id); channel = it; accepted = false
    }
}
