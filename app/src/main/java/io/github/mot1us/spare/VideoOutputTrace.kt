package io.github.mot1us.spare

/** A debug build compares codec output across screens without changing the player or logging per frame. */
internal class VideoOutputTrace {
    enum class Screen(val label: String) {
        FULLSCREEN("Full screen"), GUIDE("Guide"), SPORTS("Sports"), LIBRARY("Library"),
    }

    private data class Sample(val screen: Screen, val timeMs: Long, val positionMs: Long, val rendered: Int)
    private class Output {
        var videoBuffers = 0L
        var sampledMs = 0L
        var lowestRate = Double.POSITIVE_INFINITY
        var longestPicturePauseMs = 0L
        var longestSampleMs = 0L
    }

    private var attempt: Int? = null
    private var previous: Sample? = null
    private var picturePauseMs = 0L
    private val byScreen = linkedMapOf<Screen, Output>()

    fun sample(token: Int, screen: Screen, nowMs: Long, positionMs: Long, rendered: Int) {
        if (attempt != token) {
            reset()
            attempt = token
        }
        val prior = previous
        previous = Sample(screen, nowMs, positionMs, rendered)
        if (prior == null || prior.screen != screen || rendered < prior.rendered) {
            picturePauseMs = 0
            return
        }
        val elapsed = nowMs - prior.timeMs
        val positionChange = positionMs - prior.positionMs
        // Exclude transitions, seeks and samples during which the playback clock stopped.
        if (elapsed <= 0 || positionChange < elapsed / 2 || positionChange > elapsed + 500) {
            picturePauseMs = 0
            return
        }
        val count = rendered - prior.rendered
        val output = byScreen.getOrPut(screen, ::Output)
        output.videoBuffers += count
        output.sampledMs += elapsed
        output.lowestRate = minOf(output.lowestRate, count * 1000.0 / elapsed)
        output.longestSampleMs = maxOf(output.longestSampleMs, elapsed)
        picturePauseMs = if (count == 0) picturePauseMs + elapsed else 0
        output.longestPicturePauseMs = maxOf(output.longestPicturePauseMs, picturePauseMs)
    }

    fun pause() {
        previous = null
        picturePauseMs = 0
    }

    fun reset() {
        pause()
        attempt = null
        byScreen.clear()
    }

    fun lines(): List<String> = byScreen.map { (screen, output) ->
        String.format(
            java.util.Locale.US,
            "%s: %.1f buffers/s avg, %.1f lowest · %d ms longest sample · %d ms without video output while clock advanced · %.1f s sampled",
            screen.label, output.videoBuffers * 1000.0 / output.sampledMs, output.lowestRate,
            output.longestSampleMs, output.longestPicturePauseMs, output.sampledMs / 1000.0,
        )
    }
}
