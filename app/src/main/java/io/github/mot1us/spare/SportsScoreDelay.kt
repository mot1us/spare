package io.github.mot1us.spare

/** Display-only history. Matching, fixtures, reminders and playback always use the current game. */
class SportsScoreDelay(private val capacity: Int = 1024, private val framesPerGame: Int = 12) {
    private data class Value(val state: String, val scores: Map<String, String>, val status: String, val records: Map<String, String>, val situation: String, val possession: String)
    private data class Frame(val at: Long, val value: Value)
    private val history = linkedMapOf<String, ArrayDeque<Frame>>()
    private var lastTime = Long.MIN_VALUE
    @Synchronized fun observe(snapshot: LiveSportsSnapshot, now: Long) {
        if (now < lastTime) history.clear()
        lastTime = now
        snapshot.events.forEach { event ->
            if (event.state == "pre") return@forEach
            val league = event.league.ifBlank { event.teams.firstNotNullOfOrNull { SportsCatalog.byId[it]?.league }.orEmpty() }
            if (event.id.isBlank() || league.isBlank()) return@forEach
            val key = "$league:${event.id}"
            val frames = history.remove(key) ?: ArrayDeque()
            val value = Value(event.state, event.scores.toMap(), event.status, event.records.toMap(), event.situation, event.possession)
            if (frames.lastOrNull()?.value != value) frames.addLast(Frame(now, value))
            // Keep one anchor before the largest supported delay.
            while (frames.size > 1 && frames.elementAt(1).at < now - 330000) frames.removeFirst()
            while (frames.size > framesPerGame) frames.removeFirst()
            history[key] = frames
        }
        while (history.size > capacity) history.remove(history.keys.first())
    }
    @Synchronized fun display(game: SportsGame, seconds: Int, now: Long, wallNow: Long = System.currentTimeMillis()): SportsGame {
        val delay = seconds.coerceIn(0, MAX_SECONDS)
        if (delay == 0 || game.state == "pre" || game.state == "post" && wallNow - game.start > 12 * 3600000L) return game
        val frame = history[game.key]?.lastOrNull { it.at <= now - delay * 1000L }
        return if (frame == null) game.copy(scores = emptyMap(), records = emptyMap(), situation = "", possession = "", status = WAITING, state = if (game.state == "post") "hold" else game.state)
        else game.copy(scores = frame.value.scores, records = frame.value.records, status = frame.value.status, state = frame.value.state, situation = frame.value.situation, possession = frame.value.possession)
    }
    @Synchronized fun clear() { history.clear(); lastTime = Long.MIN_VALUE }
    companion object { const val MAX_SECONDS = 300; const val WAITING = "Waiting for delayed scores…" }
}
