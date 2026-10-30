package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

/** Inline, device-only location editing. Playback and provider connections are never touched. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class LocalChannelsEditor(
    context: Context,
    initialLocation: String,
    private val onChanged: (String) -> Unit,
    private val onOpenGuide: () -> Unit,
) : LinearLayout(context), SettingsContent {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val handler = Handler(Looper.getMainLooper())
    private val keyboard get() = context.getSystemService(InputMethodManager::class.java)
    private val directory = LocalChannels(context.assets.open("local-stations.tsv").bufferedReader().use { it.readText() })
    private var lastPublished = LocalRegions.saved(initialLocation)
    private var choosing = false
    private var appendRegions: List<String>? = null
    private var matchCount = 0
    private var matching = false
    private val publishLater = Runnable { publish() }
    private val suggestions = LinearLayout(context).apply { orientation = VERTICAL; tag = "local-location-suggestions" }
    private val suggestionRows = mutableListOf<TextView>()
    private val matches = label("Enter a city or state to see local matches.", 12f).apply {
        tag = "local-location-matches"; setTextColor(Color.LTGRAY)
    }
    private val savedRegions = label("Saved regions · $lastPublished", 12f).apply { tag = "local-saved-regions"; maxLines = 3; visibility = if (lastPublished.isBlank()) GONE else VISIBLE }
    private val input: EditText = EditText(context).apply {
        tag = "local-location-input"; hint = "Seattle, WA · Seattle · Washington"; contentDescription = "City or state"
        typeface = AppFont.face(); textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(LocalRegions.MAX_LENGTH)); setSelectAllOnFocus(true)
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        showSoftInputOnFocus = false; setText(lastPublished)
        setOnClickListener { keyboard.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
        setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                publish(); hideKeyboard(); true
            } else false
        }
        setOnKeyListener { _, key, event -> when (key) {
            KeyEvent.KEYCODE_DPAD_CENTER -> { if (event.action == KeyEvent.ACTION_UP) keyboard.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); this@LocalChannelsEditor.findViewWithTag<View>("local-add-region").requestFocus() }; true }
            else -> false
        } }
        doAfterTextChanged {
            if (!choosing) {
                handler.removeCallbacks(publishLater)
                renderSuggestions()
                renderMatches()
                handler.postDelayed(publishLater, 500)
            }
        }
    }
    private val openGuide: TextView = option("Open Local guide").apply {
        tag = "local-open-guide"
        setOnClickListener { publish(); hideKeyboard(); onOpenGuide() }
        setOnKeyListener { _, key, event -> when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) (suggestionRows.lastOrNull() ?: input).requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        } }
    }

    val location: String get() = LocalRegions.saved((appendRegions.orEmpty() + LocalRegions.parse(input.text.toString())).joinToString("; "))

    init {
        orientation = VERTICAL; tag = "local-channels-editor"
        addView(label("Cities or states · Combine up to 8 regions", 12f), LayoutParams(-1, dp(28)))
        addView(savedRegions, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        addView(input, LayoutParams(-1, dp(48)))
        addView(option("Add another region").apply {
            tag = "local-add-region"
            setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) input.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) (suggestionRows.firstOrNull() ?: openGuide).requestFocus(); true }
                else -> false
            } }
            setOnClickListener {
                if (LocalRegions.parse(location).size < 8) {
                    appendRegions = LocalRegions.parse(location)
                    choosing = true; input.setSelectAllOnFocus(false); input.setText(""); input.hint = "Add another city or state"; choosing = false
                    input.requestFocus(); input.setSelection(input.length()); renderSuggestions(); keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }, LayoutParams(-1, dp(36)))
        addView(matches, LayoutParams(-1, dp(32)))
        addView(suggestions, LayoutParams(-1, -2))
        addView(openGuide, LayoutParams(-1, dp(42)).apply { topMargin = dp(10) })
        renderSuggestions()
    }

    fun updateMatches(count: Int, loading: Boolean) {
        matchCount = count.coerceAtLeast(0); matching = loading; renderMatches()
    }

    private fun renderMatches() {
        matches.text = when {
            location.isBlank() -> "Enter a city or state to see local matches."
            location != lastPublished -> "Updating local matches…"
            matching -> "Finding local channels…"
            matchCount <= 0 -> "No local channels match this location."
            matchCount == 1 -> "1 local channel matched"
            else -> "$matchCount local channels matched"
        }
    }

    fun focusEditor() { input.requestFocus() }
    override fun focusContent() = input.requestFocus()
    override fun handlesLeft(focused: View?) = focused === input

    private fun publish() {
        handler.removeCallbacks(publishLater)
        val value = location
        if (value != lastPublished) {
            lastPublished = value; matching = value.isNotBlank(); matchCount = 0; renderMatches(); onChanged(value)
            savedRegions.text = "Saved regions · $value"; savedRegions.visibility = if (value.isBlank()) GONE else VISIBLE
        } else renderMatches()
    }

    private fun choose(value: String) {
        // Move focus before replacing rows so Android cannot select a different settings page.
        input.requestFocus(); choosing = true
        val previous = if (appendRegions != null) "" else input.text.toString().substringBeforeLast(';', "").trim()
        input.setText(LocalRegions.saved(if (previous.isBlank()) value else "$previous; $value")); input.setSelection(input.length())
        choosing = false; renderSuggestions(); publish(); hideKeyboard()
    }

    private fun renderSuggestions() {
        suggestions.removeAllViews(); suggestionRows.clear()
        directory.locations(input.text.toString().substringAfterLast(';').trim(), 3).forEach { value ->
            val row = option(value).apply {
                tag = "local-location-suggestion:$value"; contentDescription = "Use $value"
                setOnClickListener { choose(value) }
                setOnKeyListener { view, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val index = suggestionRows.indexOf(view)
                            val next = if (key == KeyEvent.KEYCODE_DPAD_UP) index - 1 else index + 1
                            (suggestionRows.getOrNull(next) ?: if (next < 0) input else openGuide).requestFocus()
                        }; true
                    }
                    else -> false
                } }
            }
            suggestionRows.add(row); suggestions.addView(row, LayoutParams(-1, dp(38)).apply { bottomMargin = dp(4) })
        }
    }

    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; typeface = AppFont.face(); setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
    }
    private fun option(value: String) = label(value, 13f).apply {
        isFocusableInTouchMode = true; isClickable = true; setPadding(dp(12), 0, dp(12), 0)
        setTextColor(GuideTheme.ink()); background = GuideTheme.background(resources.displayMetrics.density, 0x66323D4B)
    }
    private fun hideKeyboard() { keyboard.hideSoftInputFromWindow(input.windowToken, 0) }

    override fun onDetachedFromWindow() {
        publish(); hideKeyboard(); super.onDetachedFromWindow()
    }
}
