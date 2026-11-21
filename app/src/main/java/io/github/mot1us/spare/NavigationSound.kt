package io.github.mot1us.spare

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A soft tick as the cursor moves, in place of Android's navigation click, which Library's rows and grids played on every
 * poster. Spare moves the cursor itself (see MainActivity.moveFocus) so the system click never plays; this plays instead,
 * unless Settings › Guide › Navigation sound is Off or the TV's own system sounds are off.
 */
internal object NavigationSound {
    /** Quiet beside a playing channel: the tick is a short, low "tock", not a click. */
    private const val VOLUME = 0.35f
    private var pool: SoundPool? = null
    private var tick = 0
    @Volatile private var loaded = false
    private var lastAt = 0L
    /** Ticks played, for tests. */
    @Volatile internal var played = 0

    fun prepare(context: Context) {
        if (pool != null) return
        val file = File(context.cacheDir, "navigation-tick-1.wav")
        runCatching { if (!file.exists()) file.writeBytes(wav()) }.onFailure { return }
        pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().also { sounds ->
            sounds.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
            tick = sounds.load(file.path, 1)
        }
    }

    /** [fast]: the key is held and the cursor is flying past; ticks thin out so they never become a buzz. */
    fun play(context: Context, fast: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (fast && now - lastAt < 110) return
        lastAt = now
        if (Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return
        played++
        if (loaded) pool?.play(tick, if (fast) VOLUME * .6f else VOLUME, if (fast) VOLUME * .6f else VOLUME, 0, 0, 1f)
    }

    /** 30 ms at 48 kHz: a low tone and its octave with a soft attack and a fast decay. */
    internal fun samples(rate: Int = 48000): ShortArray = ShortArray(rate * 30 / 1000) { i ->
        val t = i.toDouble() / rate
        val envelope = (1 - exp(-t / 0.0012)) * exp(-t / 0.0065)
        val tone = sin(2 * PI * 740 * t) * 0.75 + sin(2 * PI * 1480 * t) * 0.18
        (tone * envelope * 0.42 * Short.MAX_VALUE).toInt().toShort()
    }

    private fun wav(rate: Int = 48000): ByteArray {
        val pcm = samples(rate)
        return ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size * 2); pcm.forEach(::putShort)
        }.array()
    }
}
