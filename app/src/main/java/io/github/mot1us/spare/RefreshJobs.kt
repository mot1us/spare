package io.github.mot1us.spare

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import java.util.concurrent.Executors

/** Android owns interval timing while the app is closed or the TV sleeps. */
object RefreshJobs {
    fun configure(context: Context, sources: List<Source>) {
        val scheduler = context.getSystemService(JobScheduler::class.java); val store = SecureStore(context)
        val service = ComponentName(context, RefreshJobService::class.java)
        val existing = scheduler.allPendingJobs.filter { it.service == service }
        val wanted = mutableSetOf<Int>()
        sources.forEachIndexed { index, source ->
            val p = store.refreshPolicy(source); val hours = listOf(p.playlistHours, p.epgHours).filter { it > 0 }.minOrNull() ?: return@forEachIndexed
            val id = 4100 + index; wanted.add(id)
            if (existing.any { it.id == id && it.extras.getString("source") == source.id && it.intervalMillis == hours * 3600000L }) return@forEachIndexed
            scheduler.schedule(JobInfo.Builder(id, service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setPeriodic(hours * 3600000L).setExtras(PersistableBundle().apply { putString("source", source.id) }).build())
        }
        existing.filter { it.id !in wanted }.forEach { scheduler.cancel(it.id) }
    }
}
class RefreshJobService : JobService() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopped = mutableSetOf<Int>()
    override fun onStartJob(params: JobParameters): Boolean {
        stopped.remove(params.jobId)
        val app = applicationContext
        loader.execute {
            val store = SecureStore(app); val source = runCatching { store.sources().firstOrNull { it.id == params.extras.getString("source") } }.getOrNull()
            handler.post {
                if (params.jobId in stopped) return@post
                if (source == null) { jobFinished(params, false); return@post }
                val p = store.refreshPolicy(source); val now = System.currentTimeMillis()
                val playlist = p.playlistDue(store.playlistRefreshed(source), now); val epg = p.epgDue(store.epgRefreshed(source), now)
                if (!playlist && !epg) jobFinished(params, false)
                else PlaylistRefresh.request(app, source, playlist, epg) { result -> if (params.jobId !in stopped) jobFinished(params, result.error.isNotBlank()) }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.add(params.jobId); return true }
    companion object { private val loader = Executors.newSingleThreadExecutor() }
}
