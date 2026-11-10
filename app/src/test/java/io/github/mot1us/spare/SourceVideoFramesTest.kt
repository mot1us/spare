package io.github.mot1us.spare

import androidx.media3.common.util.ThrowingRunnable
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import java.io.IOException
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class SourceVideoFramesTest {
    @Test fun videoWrapperForwardsCodecDefaultsAndPreservesExceptions() {
        val unused = Proxy.newProxyInstance(MediaCodecAdapter::class.java.classLoader, arrayOf(MediaCodecAdapter::class.java)) { _, method, _ ->
            error("Unexpected codec call ${method.name}")
        } as MediaCodecAdapter
        var withinDelegate = false
        var received: MediaCodecAdapter.OnBufferAvailableListener? = null
        val adapter = object : MediaCodecAdapter by unused {
            override fun <E : Exception> useBuffer(runnable: ThrowingRunnable<E>) {
                withinDelegate = true
                try { runnable.run() } finally { withinDelegate = false }
            }
            override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener): Boolean {
                received = listener
                return true
            }
        }
        val wrapped = SourceVideoFrames({ adapter }) { 1 }.wrapVideo(adapter)
        val listener = object : MediaCodecAdapter.OnBufferAvailableListener {}
        assertTrue(wrapped.registerOnBufferAvailableListener(listener))
        assertSame(listener, received)
        val failure = IOException("fixture")
        val thrown = assertThrows(IOException::class.java) {
            wrapped.useBuffer<IOException> { assertTrue(withinDelegate); throw failure }
        }
        assertSame(failure, thrown)
        assertFalse(withinDelegate)
    }
}
