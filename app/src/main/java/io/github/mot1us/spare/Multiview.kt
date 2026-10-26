package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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

/** Up to three extra players exist only during an explicitly opened multiview session. */
@UnstableApi
@SuppressLint("SetTextI18n")
class Multiview(private val root: FrameLayout, private val primary: PlayerView, private val main: ExoPlayer,
    private val button: (String, () -> Unit) -> Button, private val factory: DataSource.Factory,
    private val store: SecureStore, private val fullScreen: (Channel) -> Unit, private val back: () -> Unit) {
    private val context = root.context
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private data class Extra(val channel: Channel, val player: ExoPlayer, val view: PlayerView, var error: String = "")
    private val extras = mutableListOf<Extra>()
    private var overlay: FrameLayout? = null
    private val tiles = mutableListOf<FrameLayout>()
    private var savedLayout: android.view.ViewGroup.LayoutParams? = null
    private var primaryVolume = 1f
    private var primaryName = ""
    private var primaryId = ""
    private var selected = 0
    private var fourUp = false
    private var status: TextView? = null
    private var addButton: Button? = null
    private var removeButton: Button? = null
    private var twoButton: Button? = null
    var browsing = false
        private set
    val active get() = overlay != null
    val visible get() = active && !browsing
    val count get() = if (active) 1 + extras.size else 0
    var fullScreenMain: () -> Unit = {}
    var addGame: () -> Unit = {}
    var addChannel: () -> Unit = {}
    var finished: () -> Unit = {}
    fun contains(id: String) = active && (id == primaryId || extras.any { it.channel.id == id })
    fun start(channel: Channel, url: String, primaryName: String, primaryId: String = "") {
        if (!active) open(primaryName, primaryId)
        if (contains(channel.id)) { present(); return }
        if (extras.size >= 3) { present(); status?.text = "Four streams are already open. Select a view and choose Remove view to replace it."; return }
        val player = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory, DefaultExtractorsFactory().setTsSubtitleFormats(CaptionTracks.compatibility)).setLoadErrorHandlingPolicy(ProviderLoadErrors()))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(25000, 45000, 1500, 8000).setTargetBufferBytes(24 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build())
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF).build()
        player.volume = 0f
        val view = PlayerView(context).apply { useController = false; setKeepContentOnPlayerReset(true); this.player = player; tag = when (extras.size) { 0 -> "multiview-second"; 1 -> "multiview-third"; else -> "multiview-fourth" } }
        SubtitleAppearance.apply(view, store.subtitleSize, store.subtitleStyle)
        val extra = Extra(channel, player, view); extras.add(extra); root.addView(view)
        if (extras.size > 1) fourUp = true
        var alternate = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { if (visible) updateStatus() }
            override fun onPlayerError(error: PlaybackException) {
                val next = if (!alternate && PlaybackErrors.httpFailure(error)?.responseCode in setOf(404, 415)) StreamUrls.alternate(url) else null
                if (next != null) { alternate = true; player.setMediaItem(MediaItem.fromUri(next)); player.prepare(); player.play() }
                else { extra.error = if (error.errorCodeName.contains("DECOD")) "Device decoder limit reached. Remove a view or use Two-up." else PlaybackErrors.message(error) + " Check the provider connection limit."; rebuild() }
            }
        })
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play(); selected = extras.size; present()
    }
    fun open(name: String, id: String) {
        if (active) { present(); return }
        primaryName = name; primaryId = id; primaryVolume = main.volume; savedLayout = primary.layoutParams; selected = 0; fourUp = false
        overlay = FrameLayout(context).apply { tag = "multiview-controls" }; root.addView(overlay, FrameLayout.LayoutParams(-1, -1)); rebuild()
    }
    fun chooseGame() { browse(addGame) }
    private fun browse(action: () -> Unit) {
        if (extras.size >= 3) { status?.text = "Four streams are open. Remove a view first."; return }
        browsing = true; overlay?.visibility = View.GONE; extras.forEach { it.view.visibility = View.INVISIBLE }
        savedLayout?.let { primary.layoutParams = it }; action()
    }
    fun present() {
        if (!active) return
        browsing = false; extras.forEach { it.view.visibility = View.VISIBLE }; rebuild()
    }
    private fun select(index: Int) {
        if (index >= count) return
        selected = index; main.volume = if (index == 0) primaryVolume else 0f
        extras.forEachIndexed { at, extra -> extra.player.volume = if (index == at + 1) primaryVolume else 0f }; updateStatus()
    }
    private fun updateStatus() {
        if (!active) return
        val name = if (selected == 0) primaryName else extras.getOrNull(selected - 1)?.channel?.name.orEmpty()
        val state = if (selected == 0) main.playbackState else extras.getOrNull(selected - 1)?.player?.playbackState
        val error = extras.getOrNull(selected - 1)?.error.orEmpty()
        status?.text = if (error.isNotBlank()) error else "Audio: $name${if (state == Player.STATE_BUFFERING) " · Buffering" else ""} · OK Full screen · Back Sports"
        removeButton?.isEnabled = selected > 0; removeButton?.alpha = if (selected > 0) 1f else .35f; twoButton?.isEnabled = count <= 2; twoButton?.alpha = if (count <= 2) 1f else .35f
    }
    private fun rebuild() {
        val surface = overlay ?: return
        if (browsing) return
        surface.removeAllViews(); tiles.clear(); surface.visibility = View.VISIBLE
        val columns = 2; val rows = if (fourUp) 2 else 1
        val width = root.width / columns; val height = (root.height - dp(92)).coerceAtLeast(dp(200)) / rows
        fun params(index: Int) = FrameLayout.LayoutParams(width, height).apply { leftMargin = index % columns * width; topMargin = index / columns * height }
        primary.layoutParams = params(0); root.bringChildToFront(primary)
        extras.forEachIndexed { index, extra -> extra.view.tag = when (index) { 0 -> "multiview-second"; 1 -> "multiview-third"; else -> "multiview-fourth" }; extra.view.layoutParams = params(index + 1); root.bringChildToFront(extra.view) }
        repeat(columns * rows) { index ->
            val tile = FrameLayout(context).apply {
                tag = "multiview-tile:$index"; isFocusableInTouchMode = true; isClickable = true
                background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), GuideTheme.current.accent) }); addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), 0x44FFFFFF) }) }
                val name = if (index == 0) primaryName else extras.getOrNull(index - 1)?.channel?.name ?: "＋ Add channel"
                val title = TextView(context).apply { text = name; textSize = 11f; setTextColor(Color.WHITE); setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(0x99000000.toInt()); maxLines = 1 }
                addView(title, FrameLayout.LayoutParams(-1, dp(26), Gravity.TOP))
                extras.getOrNull(index - 1)?.error?.takeIf { it.isNotBlank() }?.let { message -> addView(TextView(context).apply { text = message; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(20), dp(20), dp(20), dp(20)); setBackgroundColor(0xCC000000.toInt()) }, FrameLayout.LayoutParams(-1, -1)) }
                contentDescription = "View ${index + 1}. $name. Focus for audio, OK for full screen."
                setOnFocusChangeListener { _, focused -> if (focused && index < count) select(index) }
                setOnClickListener { if (index >= count) browse(addChannel) else { val channel = extras.getOrNull(index - 1)?.channel; close(); if (channel == null) fullScreenMain() else fullScreen(channel) } }
                setOnKeyListener { _, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) { val next = when (key) { KeyEvent.KEYCODE_DPAD_LEFT -> if (index % 2 == 1) index - 1 else index; KeyEvent.KEYCODE_DPAD_RIGHT -> if (index % 2 == 0) index + 1 else index; KeyEvent.KEYCODE_DPAD_UP -> (index - 2).coerceAtLeast(0); else -> index + 2 }; if (next >= tiles.size) addButton?.requestFocus() else tiles.getOrNull(next)?.requestFocus() }; true
                    }
                    else -> false
                } }
            }
            tiles.add(tile); surface.addView(tile, params(index))
        }
        val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = GuideTheme.glass(context.resources.displayMetrics.density); setPadding(dp(12), dp(7), dp(12), dp(7)) }
        status = TextView(context).apply { textSize = 11f; setTextColor(Color.WHITE); maxLines = 2 }; controls.addView(status)
        val actions = LinearLayout(context)
        addButton = button("Add game") { chooseGame() }.apply { tag = "multiview-add-game" }
        val addAny = button("Add channel") { browse(addChannel) }.apply { tag = "multiview-add-channel" }
        twoButton = button("Two-up") { if (count <= 2) { fourUp = false; rebuild() } }.apply { tag = "multiview-two-up" }
        val four = button("Four-up") { fourUp = true; rebuild() }.apply { tag = "multiview-four-up" }
        removeButton = button("Remove view") { if (selected > 0) { val extra = extras.removeAt(selected - 1); extra.view.player = null; extra.player.release(); root.removeView(extra.view); selected = 0; if (count <= 2) fourUp = false; select(0); rebuild() } }.apply { tag = "multiview-remove" }
        listOf(addButton!!, addAny, twoButton!!, four, removeButton!!, button("Close") { close(); back() }).forEach { control -> actions.addView(control, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6) }); control.setOnKeyListener { _, key, event -> if (key == KeyEvent.KEYCODE_DPAD_UP) { if (event.action == KeyEvent.ACTION_DOWN) tiles.getOrNull(selected)?.requestFocus(); true } else false } }
        controls.addView(actions); surface.addView(controls, FrameLayout.LayoutParams(-1, dp(82), Gravity.BOTTOM).apply { marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(6) })
        root.bringChildToFront(surface); select(selected.coerceAtMost(count - 1)); tiles.getOrNull(selected)?.requestFocus()
    }
    fun appearance() { extras.forEach { SubtitleAppearance.apply(it.view, store.subtitleSize, store.subtitleStyle) } }
    fun close() {
        if (!active) return
        extras.forEach { it.view.player = null; it.player.release(); root.removeView(it.view) }; extras.clear()
        overlay?.let(root::removeView); overlay = null; tiles.clear(); savedLayout?.let { primary.layoutParams = it }; savedLayout = null
        main.volume = primaryVolume; browsing = false; finished()
    }
}
