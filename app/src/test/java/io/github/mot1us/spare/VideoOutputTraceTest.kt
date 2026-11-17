package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutputTraceTest {
    @Test fun separatesScreenTransitionsFromFrozenVideoWithAnAdvancingClock() {
        val trace = VideoOutputTrace()
        fun sample(screen: VideoOutputTrace.Screen, time: Long, position: Long, rendered: Int) =
            trace.sample(1, screen, time, position, rendered)

        sample(VideoOutputTrace.Screen.FULLSCREEN, 0, 0, 0)
        sample(VideoOutputTrace.Screen.FULLSCREEN, 1000, 1000, 60)
        sample(VideoOutputTrace.Screen.SPORTS, 2000, 2000, 120)
        sample(VideoOutputTrace.Screen.SPORTS, 3000, 3000, 120)
        sample(VideoOutputTrace.Screen.SPORTS, 4000, 4000, 120)
        sample(VideoOutputTrace.Screen.SPORTS, 5000, 5000, 180)
        trace.pause()
        sample(VideoOutputTrace.Screen.SPORTS, 10000, 5000, 180)
        sample(VideoOutputTrace.Screen.SPORTS, 11000, 5000, 180)
        val (fullscreen, sports) = trace.lines()
        assertTrue(fullscreen, fullscreen.contains("Full screen: 60.0 buffers/s"))
        assertTrue(sports, sports.contains("Sports: 20.0 buffers/s") && sports.contains("2000 ms without video output"))
        trace.sample(2, VideoOutputTrace.Screen.LIBRARY, 12000, 0, 0)
        assertTrue(trace.lines().isEmpty())
    }

    @Test fun playbackClockJumpsCannotCreatePicturePause() {
        val trace = VideoOutputTrace()
        val screen = VideoOutputTrace.Screen.LIBRARY
        trace.sample(1, screen, 1000, 1000, 10)
        trace.sample(1, screen, 2000, 20000, 10)
        trace.sample(1, screen, 3000, 19500, 10)
        assertEquals(emptyList<String>(), trace.lines())
    }
}
