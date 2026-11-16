package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * First launch: add a playlist (from a phone or the TV keyboard), pick sports, a look, and how the guide behaves.
 * Every step can be skipped, and every choice stays in Settings.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class OnboardingPanel(context: Context, private val store: SecureStore, private val button: (String, () -> Unit) -> Button,
    private val hasPlaylist: () -> Boolean, private val addPlaylist: (PhoneSetupResult?) -> Unit,
    private val appearance: () -> Unit, private val finished: () -> Unit) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(22), dp(28), dp(20)) }
    private val progress = TextView(context).apply { tag = "onboarding-step"; textSize = 12f; setTextColor(Color.LTGRAY) }
    private val title = TextView(context).apply { tag = "onboarding-title"; textSize = 26f; setTextColor(Color.WHITE) }
    private val hint = TextView(context).apply { textSize = 14f; setTextColor(Color.LTGRAY) }
    private val content = FrameLayout(context)
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private val phone = PhoneSetupView(context) { result -> addPlaylist(result) }
    private var step = 0
    private var firstControl: View? = null
    private val leagues = linkedSetOf<String>()
    val opened get() = isVisible

    init {
        tag = "onboarding"; visibility = GONE; isClickable = true
        card.addView(progress); card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        card.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(16) })
        card.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(actions, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        addView(card, LayoutParams(-1, -1).apply { setMargins(dp(64), dp(36), dp(64), dp(36)) })
    }

    fun open() {
        leagues.clear(); leagues.addAll(if (store.sportsLeaguesChosen) store.sportsLeagues else DEFAULT_LEAGUES)
        visibility = VISIBLE; show(if (hasPlaylist()) 1 else 0)
    }

    /** A playlist was saved from step one. */
    fun playlistAdded() { if (opened && step == 0) show(1) }
    /** The Add playlist form closed without saving: offer a fresh phone code. */
    fun playlistCancelled() { if (opened && step == 0) show(0) }

    fun pause() = phone.stop()
    fun resume() { if (opened && step == 0 && !hasPlaylist()) phone.start() }

    fun refocus() { firstControl?.requestFocus() }
    fun back() { if (step > 0) show(step - 1) }

    private fun show(next: Int) {
        step = next
        background = GuideTheme.guideBackground(96)
        card.background = GuideTheme.glass(resources.displayMetrics.density)
        listOf(title).forEach { it.typeface = AppFont.face(true) }; listOf(progress, hint).forEach { it.typeface = AppFont.face() }
        progress.text = "Step ${next + 1} of 4  ·  Change any of this later in Settings"
        content.removeAllViews(); actions.removeAllViews(); phone.stop()
        val first: View = when (next) {
            0 -> playlistStep()
            1 -> sportsStep()
            2 -> themeStep()
            else -> guideStep()
        }
        content.alpha = 0f; content.animate().alpha(1f).setDuration(220).start()
        firstControl = first; first.requestFocus()
    }

    private fun action(label: String, tag: String, click: () -> Unit) = button(label, click).also { view ->
        view.tag = tag; actions.addView(view, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(10) })
    }

    private fun playlistStep(): View {
        if (hasPlaylist()) {
            title.text = "Your playlist is ready"
            hint.text = "Next, a few quick choices."
            content.addView(TextView(context).apply { text = "✓  Playlist added"; textSize = 18f; setTextColor(Color.WHITE); typeface = AppFont.face(true) })
            return action("Continue", "onboarding-next") { show(1) }
        }
        title.text = "Welcome to Spare"
        hint.text = "Add your IPTV playlist to start. The easiest way is from your phone."
        content.addView(phone, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        phone.start()
        action("Skip for now", "onboarding-skip") { show(1) }
        return action("Type it on the TV", "onboarding-type") { phone.stop(); addPlaylist(null) }.also { it.nextFocusLeftId = View.NO_ID }
    }

    /** A grid of buttons with remote-friendly rows. */
    private fun grid(columns: Int, count: Int, height: Int, build: (Int) -> Button): List<Button> {
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val buttons = (0 until count).map(build)
        buttons.chunked(columns).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { row.addView(it, LinearLayout.LayoutParams(0, dp(height), 1f).apply { marginEnd = dp(8) }) }
            repeat(columns - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, dp(height), 1f).apply { marginEnd = dp(8) }) }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        content.addView(rows)
        return buttons
    }

    private fun sportsStep(): View {
        title.text = "Which sports do you watch?"
        hint.text = "Sports shows live games from these. OK turns one on or off."
        val all = SportsLeagues.all
        lateinit var next: Button
        lateinit var controls: List<Button>
        fun paint() {
            controls.forEachIndexed { index, control ->
                val on = all[index].id in leagues
                control.isSelected = on; control.text = (if (on) "✓  " else "") + all[index].name
                control.contentDescription = all[index].name + if (on) ", on" else ", off"
            }
            next.isEnabled = leagues.isNotEmpty()
        }
        controls = grid(3, all.size, 38) { index ->
            button(all[index].name) { if (!leagues.add(all[index].id)) leagues.remove(all[index].id); paint() }.apply { tag = "onboarding-league:${all[index].id}" }
        }
        next = action("Continue", "onboarding-next") { if (leagues.isNotEmpty()) { store.sportsLeagues = leagues.toSet(); show(2) } }
        paint()
        return controls.first()
    }

    private fun themeStep(): View {
        title.text = "Pick a look"
        hint.text = "OK applies it right away, so you can try a few."
        val presets = ThemePresets.all
        lateinit var controls: List<Button>
        fun paint() = controls.forEachIndexed { index, control ->
            val on = ThemePresets.applied(store, presets[index])
            control.text = (if (on) "✓  " else "") + presets[index].name
            control.contentDescription = "${presets[index].name}, ${presets[index].description}${if (on) ", applied" else ""}"
        }
        controls = grid(4, presets.size, 38) { index ->
            val preset = presets[index]
            button(preset.name) { ThemePresets.apply(store, preset); appearance(); restyle(); paint() }.apply {
                tag = "onboarding-theme:${preset.name}"
                val colors = GuideTheme.palettes.first { it.name == preset.palette }
                val swatch = LayerDrawable(arrayOf(
                    GradientDrawable().apply { setColor(colors.background or 0xFF000000.toInt()); cornerRadius = dp(3).toFloat(); setStroke(dp(1), 0x33FFFFFF) },
                    GradientDrawable().apply { setColor(colors.accent); cornerRadius = dp(2).toFloat() })).apply {
                    setLayerInset(1, dp(9), dp(3), dp(3), dp(3)); setBounds(0, 0, dp(20), dp(16))
                }
                setCompoundDrawables(swatch, null, null, null); compoundDrawablePadding = dp(8)
            }
        }
        paint()
        action("Continue", "onboarding-next") { show(3) }
        return controls.firstOrNull { ThemePresets.applied(store, presets[controls.indexOf(it)]) } ?: controls.first()
    }

    private fun guideStep(): View {
        title.text = "How should the guide work?"
        hint.text = "Pick one in each row."
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val first = mutableListOf<Button>()
        fun choice(label: String, options: List<String>, current: () -> Int, choose: (Int) -> Unit) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(Color.WHITE); typeface = AppFont.face() }, LinearLayout.LayoutParams(0, -2, 0.9f))
            val buttons = mutableListOf<Button>()
            fun paint() = buttons.forEachIndexed { index, control ->
                control.isSelected = index == current(); control.text = (if (index == current()) "✓  " else "") + options[index]
                control.contentDescription = "$label: ${options[index]}${if (index == current()) ", selected" else ""}"
            }
            options.forEachIndexed { index, option ->
                buttons += button(option) { choose(index); paint() }.apply { tag = "onboarding-guide:$label:$option" }
                row.addView(buttons.last(), LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
            }
            paint(); first += buttons[current()]
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        choice("Guide background", listOf("See-through", "Solid"), { if (store.guideOpacity in 0..99) 0 else 1 }) { index ->
            store.guideOpacity = if (index == 0) SecureStore.DEFAULT_GUIDE_OPACITY else 100; appearance(); restyle()
        }
        choice("Channel while browsing", listOf("Small preview", "Behind the guide"), { if (store.smallPreview) 0 else 1 }) { index ->
            store.smallPreview = index == 0; appearance()
        }
        choice("Text size", listOf("Standard", "Large"), { if (store.guideTextScale > 100) 1 else 0 }) { index ->
            store.guideTextScale = if (index == 0) 100 else 115; appearance()
        }
        choice("OK on a channel", listOf("Plays full screen", "Preview first"), { if (store.guideOpenFullscreen) 0 else 1 }) { index ->
            store.guideOpenFullscreen = index == 0
        }
        content.addView(rows)
        action("Start watching", "onboarding-done") { visibility = GONE; phone.stop(); finished() }
        return first.first()
    }

    private fun restyle() { background = GuideTheme.guideBackground(96); card.background = GuideTheme.glass(resources.displayMetrics.density) }

    /** Up from the step's buttons stays inside the panel; Back walks back a step. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** Pro leagues start on; college sports are one press away. */
        val DEFAULT_LEAGUES = setOf("NFL", "MLB", "NBA", "NHL", "EPL", "MLS", "UCL")
    }
}
