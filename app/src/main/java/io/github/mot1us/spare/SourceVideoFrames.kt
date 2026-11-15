package io.github.mot1us.spare

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ThrowingRunnable
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/** Observes existing decoder input without reading payloads or creating another stream connection. */
@UnstableApi
class SourceVideoFrames(private val delegate: MediaCodecAdapter.Factory, private val token: () -> Int) : MediaCodecAdapter.Factory {
    @Volatile private var reading = -1 to 0f
    fun rate(epoch: Int): Float = reading.takeIf { it.first == epoch }?.second ?: 0f

    /** The decoding speed the hardware decoder was asked for, or 0 when the stream stated its own frame rate. */
    @Volatile var operatingRate = 0f
        private set

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        if (MimeTypes.isVideo(configuration.format.sampleMimeType)) operatingRate = requestSpeed(configuration)
        val adapter = delegate.createAdapter(configuration)
        return if (MimeTypes.isVideo(configuration.format.sampleMimeType)) wrapVideo(adapter) else adapter
    }

    /**
     * Live transport streams rarely declare a frame rate, so the player never tells the decoder how fast it must run and
     * some TV decoders then plan for 30 frames a second and fall behind on 50/60 fps channels whenever the device is busy.
     * Ask for 60 when the decoder supports that size at 60; a stream that declares its rate keeps the player's value.
     */
    private fun requestSpeed(configuration: MediaCodecAdapter.Configuration): Float {
        val media = configuration.mediaFormat
        if (media.containsKey(MediaFormat.KEY_OPERATING_RATE)) return 0f
        val width = configuration.format.width.takeIf { it > 0 } ?: media.getIntegerOr(MediaFormat.KEY_WIDTH)
        val height = configuration.format.height.takeIf { it > 0 } ?: media.getIntegerOr(MediaFormat.KEY_HEIGHT)
        if (width <= 0 || height <= 0) return 0f
        if (!runCatching { configuration.codecInfo.isVideoSizeAndRateSupportedV21(width, height, 60.0) }.getOrDefault(false)) return 0f
        media.setFloat(MediaFormat.KEY_OPERATING_RATE, 60f)
        return 60f
    }
    private fun MediaFormat.getIntegerOr(key: String) = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    internal fun wrapVideo(adapter: MediaCodecAdapter): MediaCodecAdapter {
        val cadence = InputCadence()
        fun observe(ptsUs: Long, flags: Int) {
            if (flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) return
            val epoch = token()
            val measured = cadence.observe(epoch, ptsUs)
            if (reading.first != epoch || reading.second != measured) reading = epoch to measured
        }
        return object : MediaCodecAdapter by adapter {
            // Kotlin delegation inherits these Java defaults unless we forward them explicitly.
            override fun <E : Exception> useBuffer(runnable: ThrowingRunnable<E>) = adapter.useBuffer(runnable)

            override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener): Boolean =
                adapter.registerOnBufferAvailableListener(listener)

            override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
                adapter.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
                if (size > 0) observe(presentationTimeUs, flags)
            }
            override fun queueSecureInputBuffer(index: Int, offset: Int, info: CryptoInfo, presentationTimeUs: Long, flags: Int) {
                adapter.queueSecureInputBuffer(index, offset, info, presentationTimeUs, flags)
                observe(presentationTimeUs, flags)
            }
        }
    }
}
