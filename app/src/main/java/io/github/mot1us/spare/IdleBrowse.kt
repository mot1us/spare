package io.github.mot1us.spare

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/** Warms a bounded selection of saved art and scoreboards, one operation at a time. Owned by the UI thread. */
internal class IdleBrowse(context: Context, private val worker: ExecutorService, private val library: LibraryCatalog,
    private val scores: (LiveSportsSnapshot) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val posters = posterFiles(context)
    private val crests = ArtworkFiles(context)
    private val queue = ArrayDeque<() -> (() -> Unit)?>()
    private val posterUrls = mutableSetOf<String>()
    private val crestUrls = mutableSetOf<String>()
    private var pending: Future<*>? = null
    private var generation = 0
    private var nextAt = 0L
    private var playlist: Source? = null
    private var leagues = emptyList<String>()
    private var day: LocalDate? = null

    fun step(source: Source?, enabled: List<String>, today: LocalDate = LocalDate.now()) {
        if (source != playlist || enabled != leagues || today != day) {
            pause()
            playlist = source; leagues = enabled; day = today
            queue.clear(); posterUrls.clear(); crestUrls.clear()
            if (source?.kind == "xtream") {
                addPosters(source, "movie")
                addPosters(source, "series")
            }
            enabled.forEach(::addScoreboard)
        }
        val now = SystemClock.elapsedRealtime()
        val work = queue.firstOrNull() ?: return
        if (pending != null || now < nextAt) return
        val token = generation
        pending = worker.submit {
            val apply = runCatching { work() }.getOrNull()
            if (!Thread.currentThread().isInterrupted) handler.post {
                if (token == generation) {
                    queue.removeFirst()
                    pending = null
                    nextAt = SystemClock.elapsedRealtime() + 1000
                    apply?.invoke()
                }
            }
        }
    }

    fun pause() {
        val task = pending ?: return
        ++generation
        pending = null
        task.cancel(true)
    }

    private fun addPosters(source: Source, mode: String) {
        queue.addLast {
            val urls = library.idlePosters(source, mode)
            val apply = {
                urls.filter(posterUrls::add).forEach { addImage(posters, it, source.allowHttp) }
            }
            apply
        }
    }

    private fun addScoreboard(league: String) {
        queue.addLast {
            val snapshot = SportsSchedule.warmToday(league, System.currentTimeMillis())
            val logos = snapshot.events.asSequence().filter { it.state == "in" || it.state == "pre" }
                .take(4).flatMap { it.logos.values.asSequence() }.filter { it.isNotBlank() }.distinct().take(8).toList()
            val apply = {
                scores(snapshot)
                logos.forEach { url -> if (crestUrls.size < 16 && crestUrls.add(url)) addImage(crests, url, false) }
            }
            apply
        }
    }

    private fun addImage(cache: ImageFiles, url: String, http: Boolean) {
        queue.addLast {
            if (Network.allowed(url, http)) cache.bytes(url) { Network.get(url, http, 2 * 1024 * 1024, timeoutMs = 2000) }
            null
        }
    }
}
