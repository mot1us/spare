package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class PlaybackBufferBudgetTest {
    @Test fun mainAndMultiviewLeaveHeapRoomForGuideAndDecoders() {
        val mib = 1024 * 1024L
        for (heap in listOf(128L, 192L, 256L, 384L, 512L)) {
            val main = PlaybackLoadControl.bufferBytes(heap * mib)
            val pane = PlaybackLoadControl.bufferBytes(heap * mib, multiview = true)
            assertTrue(main in (32 * mib)..(96 * mib)); assertTrue(pane in (8 * mib)..(24 * mib))
            assertTrue("Four views leave more than half the heap for non-buffer allocations", main + 3L * pane < heap * mib / 2)
        }
        assertEquals(48 * mib, PlaybackLoadControl.bufferBytes(192 * mib).toLong())
        assertEquals(96 * mib, PlaybackLoadControl.bufferBytes(1024 * mib).toLong())
    }
    @Test fun recoveringBufferGrowthAvoidsUnnecessaryReconnectButCannotHideAnEndlessStall() {
        val progress = PlaybackProgress()
        assertFalse(progress.stalled(1, 0, 0, null, true, true))
        assertFalse(progress.stalled(1, 44000, 0, null, true, true, 4000))
        assertFalse(progress.stalled(1, 46000, 0, null, true, true, 5000))
        assertFalse(progress.stalled(1, 55000, 0, null, true, true, 5000))
        assertTrue("A stopped download still reaches recovery", progress.stalled(1, 56000, 0, null, true, true, 5000))
        assertTrue("Even a trickling source has a finite recovery deadline", progress.stalled(1, 90000, 0, null, true, true, 6000))
        progress.reset()
        assertFalse(progress.stalled(2, 0, 0, 100, false, true, 5000))
        assertTrue("Buffer growth does not conceal a frozen READY decoder", progress.stalled(2, 21000, 0, 100, false, true, 6000))
    }
}
