package io.github.mot1us.spare

import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Google TV Streamer (MediaTek) workarounds, matched the way other open-source IPTV players match it:
 * the firmware codename, or Google's public model name. Every other TV keeps Spare's normal behaviour.
 */
@UnstableApi
internal object StreamerQuirks {
    /** Device tests set this before launching the activity to exercise the Streamer path on the emulator. */
    @Volatile internal var override: Boolean? = null

    val active: Boolean get() = override ?: matches(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)

    fun matches(manufacturer: String?, model: String?, device: String?): Boolean =
        device.orEmpty().trim().equals("kirkwood", ignoreCase = true) ||
            manufacturer.orEmpty().trim().equals("google", ignoreCase = true) &&
            model.orEmpty().contains("google tv streamer", ignoreCase = true)

    /**
     * Live TS join policy. Elsewhere Spare starts on any complete picture so a tune shows video sooner. The
     * Streamer's decoder can take a stream joined that way and never show video while audio plays on, so
     * there Media3's own policy waits for a full restart (IDR) frame.
     */
    fun tsFlags(streamer: Boolean): Int = if (streamer) 0 else
        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES

    /**
     * The rate asked of Android for video at [rate] fps. A 25 or 30 fps request is half of a 50 or 60 Hz
     * mode, and on the Streamer that per-app rate breaks frame pacing, so it asks for the full mode instead.
     * The 24 fps film family is not a fraction of either and is requested as it is.
     */
    fun displayRate(rate: Float, streamer: Boolean): Float =
        if (streamer && rate in 24.5f..32.5f) rate * 2 else rate
}
