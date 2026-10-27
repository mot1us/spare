package io.github.mot1us.spare

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import kotlin.math.abs

object VideoRate {
    private val rates = listOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f)
    fun normalize(value: Float): Float {
        if (!value.isFinite() || value !in 10f..120f) return 0f
        val nearest = rates.minBy { abs(it - value) }
        return if (abs(nearest - value) <= .04f) nearest else value
    }
    fun resolve(declared: Float, measured: Float, codec: Float = 0f, interlaced: Boolean = false, input: Float = 0f): Float {
        fun nominal(value: Float) = normalize(value).takeIf { rate -> rate > 0 && rates.any { abs(it - rate) <= .2f } } ?: 0f
        // Render callbacks can omit frames. A valid source rate describes the
        // stream; callback cadence is only a fallback when metadata is absent.
        val metadata = nominal(declared); val decoded = nominal(codec)
        val sample = normalize(measured)
        // Codec output often rounds 29.97 to 30. Prefer a stable timestamp rate
        // within the same family, while preserving explicit higher source rates.
        // Input samples describe the stream even when the decoder/display drops
        // every other frame, or the container's declared rate is wrong.
        val sourceSamples = nominal(input)
        val frames = when { sourceSamples > 0 -> sourceSamples; metadata > 0 -> metadata; sample > 0 && (decoded == 0f || abs(sample - decoded) < 1f) -> sample; decoded > 0 -> decoded; else -> sample }
        return if (interlaced && (abs(frames - 25) < .1f || abs(frames - 29.97f) < .1f || abs(frames - 30) < .1f)) normalize(frames * 2) else frames
    }
    @UnstableApi fun interlaced(format: Format): Boolean {
        if (format.sampleMimeType == MimeTypes.VIDEO_MPEG2) return format.initializationData.any { bytes ->
            // MPEG-2 sequence_extension: identifier 1, then profile/level,
            // then progressive_sequence at bit 12 of the extension payload.
            (0 until bytes.size - 5).any { at ->
                bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 1.toByte() && bytes[at + 3] == 0xB5.toByte() &&
                    (bytes[at + 4].toInt() and 0xF0) == 0x10 && (bytes[at + 5].toInt() and 0x08) == 0
            }
        }
        if (format.sampleMimeType != MimeTypes.VIDEO_H264) return false
        return format.initializationData.asSequence().filter { it.size in 4..65536 }.any { bytes ->
            if ((bytes[0].toInt() and 31) == 7 && runCatching { !NalUnitUtil.parseSpsNalUnit(bytes, 0, bytes.size).frameMbsOnlyFlag }.getOrDefault(false)) return@any true
            (0 until bytes.size - 4).any { at ->
                val prefix = if (bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 1.toByte()) 3
                    else if (bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 0.toByte() && bytes[at + 3] == 1.toByte()) 4 else 0
                prefix > 0 && at + prefix < bytes.size && bytes[at + prefix].toInt() and 31 == 7 && runCatching { !NalUnitUtil.parseSpsNalUnit(bytes, at + prefix, bytes.size).frameMbsOnlyFlag }.getOrDefault(false)
            }
        }
    }

    @UnstableApi fun observation(format: Format, measured: Float, codecRate: Float, header: AvcHeader.Info, input: Float = 0f): VideoInfo {
        val scan = if (header.known) header.interlaced else interlaced(format)
        val declared = header.fps.takeIf { it > 0 } ?: format.frameRate
        val encoded = resolve(declared, measured, codecRate, input = input)
        val motion = resolve(declared, measured, codecRate, scan, input)
        val evidence = when { input > 0 -> "input timestamps"; header.fps > 0 -> "SPS timing"; declared > 0 -> "stream"; measured > 0 -> "timestamps"; codecRate > 0 -> "decoder"; else -> "unverified" }
        return VideoInfo(format.width, format.height, motion, scan, encoded, evidence)
    }
}
