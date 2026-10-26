package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView

/** A second connection exists only while this explicit two-stream view is open. */
@UnstableApi
@SuppressLint("SetTextI18n")
class Multiview(private val root: FrameLayout, private val primary: PlayerView, private val main: ExoPlayer,
    private val button: (String, () -> Unit) -> Button, private val factory: DataSource.Factory,
    private val store: SecureStore, private val fullScreen: (Channel) -> Unit, private val back: () -> Unit) {
    private val context = root.context
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private var second: ExoPlayer? = null
    private var pane: PlayerView? = null
    private var controls: LinearLayout? = null
    private var savedLayout: android.view.ViewGroup.LayoutParams? = null
    private var primaryVolume = 1f
    val active get() = second != null
    fun start(channel: Channel, url: String, primaryName: String) {
        close()
        primaryVolume = main.volume; savedLayout = primary.layoutParams
        val player = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory, DefaultExtractorsFactory().setTsSubtitleFormats(CaptionTracks.compatibility)).setLoadErrorHandlingPolicy(ProviderLoadErrors()))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30000, 45000, 1500, 8000).setTargetBufferBytes(32 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build())
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF).build()
        second = player; player.volume = 0f
        val view = PlayerView(context).apply { useController = false; setKeepContentOnPlayerReset(true); this.player = player; tag = "multiview-second" }
        pane = view; SubtitleAppearance.apply(view, store.subtitleSize, store.subtitleStyle)
        val width = (root.width / 2).coerceAtLeast(dp(200))
        primary.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.START)
        root.bringChildToFront(primary)
        root.addView(view, FrameLayout.LayoutParams(width, -1, Gravity.END))
        val status = TextView(context).apply { textSize = 12f; setTextColor(Color.WHITE); maxLines = 2; text = "$primaryName    |    ${channel.name}" }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val bar = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); background = GuideTheme.glass(context.resources.displayMetrics.density); tag = "multiview-controls" }
        val actions = LinearLayout(context)
        val left = button("Listen left") { main.volume = primaryVolume; player.volume = 0f; status.text = "Audio: $primaryName" }
        val right = button("Listen right") { main.volume = 0f; player.volume = primaryVolume; status.text = "Audio: ${channel.name}" }
        actions.addView(left); actions.addView(right)
        actions.addView(button("Full screen left") { close(); fullScreenMain() })
        actions.addView(button("Full screen right") { close(); fullScreen(channel) })
        actions.addView(button("Guide") { close(); back() })
        bar.addView(status); bar.addView(progress, LinearLayout.LayoutParams(-1, dp(3))); bar.addView(actions)
        controls = bar; root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { marginStart = dp(18); marginEnd = dp(18); bottomMargin = dp(12) })
        var alternate = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { progress.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE }
            override fun onPlayerError(error: PlaybackException) {
                val next = if (!alternate && PlaybackErrors.httpFailure(error)?.responseCode in setOf(404, 415)) StreamUrls.alternate(url) else null
                if (next != null) { alternate = true; player.setMediaItem(MediaItem.fromUri(next)); player.prepare(); player.play() }
                else { progress.visibility = View.GONE; status.text = "Second stream: ${PlaybackErrors.message(error)} Your provider must allow two simultaneous connections." }
            }
        })
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play(); left.requestFocus()
    }
    var fullScreenMain: () -> Unit = {}
    fun appearance() { pane?.let { SubtitleAppearance.apply(it, store.subtitleSize, store.subtitleStyle) } }
    fun close() {
        if (!active) return
        pane?.player = null; second?.release(); second = null
        pane?.let(root::removeView); pane = null; controls?.let(root::removeView); controls = null
        savedLayout?.let { primary.layoutParams = it }; savedLayout = null; main.volume = primaryVolume
    }
}
