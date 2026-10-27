package io.github.mot1us.spare

import android.media.MediaCodec
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/** Observes existing decoder input without reading payloads or creating another stream connection. */
@UnstableApi
class SourceVideoFrames(private val delegate: MediaCodecAdapter.Factory, private val token: () -> Int) : MediaCodecAdapter.Factory {
    @Volatile private var reading = -1 to 0f
    fun rate(epoch: Int): Float = reading.takeIf { it.first == epoch }?.second ?: 0f

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val adapter = delegate.createAdapter(configuration)
        if (!MimeTypes.isVideo(configuration.format.sampleMimeType)) return adapter
        val cadence = InputCadence()
        fun observe(ptsUs: Long, flags: Int) {
            if (flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) return
            val epoch = token()
            val measured = cadence.observe(epoch, ptsUs)
            if (reading.first != epoch || reading.second != measured) reading = epoch to measured
        }
        return object : MediaCodecAdapter by adapter {
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
