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
    fun resolve(declared: Float, measured: Float, codec: Float = 0f, interlaced: Boolean = false): Float {
        val sample = normalize(measured); val metadata = normalize(declared).takeIf { value -> rates.any { abs(it - value) <= .2f } } ?: 0f; val decoded = normalize(codec)
        val frames = when { sample > 0 -> sample; decoded > 0 && metadata > 0 && abs(decoded - metadata) > 1 -> decoded; metadata > 0 -> metadata; else -> decoded }
        return if (interlaced && (abs(frames - 25) < .1f || abs(frames - 29.97f) < .1f || abs(frames - 30) < .1f)) normalize(frames * 2) else frames
    }
    @UnstableApi fun interlaced(format: Format): Boolean {
        if (format.sampleMimeType != MimeTypes.VIDEO_H264) return false
        return format.initializationData.asSequence().filter { it.size in 4..65536 }.any { bytes ->
            (0 until bytes.size - 4).any { at ->
                val prefix = if (bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 1.toByte()) 3
                    else if (bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 0.toByte() && bytes[at + 3] == 1.toByte()) 4 else 0
                prefix > 0 && at + prefix < bytes.size && bytes[at + prefix].toInt() and 31 == 7 && runCatching { !NalUnitUtil.parseSpsNalUnit(bytes, at + prefix, bytes.size).frameMbsOnlyFlag }.getOrDefault(false)
            }
        }
    }
}
