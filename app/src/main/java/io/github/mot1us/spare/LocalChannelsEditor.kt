package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

/** Explicit region rows keep IME drafts separate from saved locations. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class LocalChannelsEditor(context: Context, initialLocation: String, private val onChanged: (String) -> Unit,
    private val onOpenGuide: () -> Unit) : LinearLayout(context), SettingsContent {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val keyboard get() = context.getSystemService(InputMethodManager::class.java)
    private val directory = LocalChannels(context.assets.open("local-stations.tsv").bufferedReader().use { it.readText() })
    private val regions = LocalRegions.parse(initialLocation).toMutableList()
    private var editing: String? = null
    private var count = 0
    private var loading = false
    private val savedRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val savedSummary = label("", 12f).apply { tag = "local-saved-regions"; maxLines = 2 }
    private val savedScroll = ScrollView(context).apply { isFillViewport = false; addView(savedRows) }
    private val suggestions = LinearLayout(context).apply { orientation = VERTICAL; tag = "local-location-suggestions" }
    private val suggestionRows = mutableListOf<TextView>()
    private val matches = label("", 12f).apply { tag = "local-location-matches"; setTextColor(Color.LTGRAY) }
    private val input = EditText(context).apply {
        tag = "local-location-input"; hint = "Add a city or state"; contentDescription = "City or state"
        typeface = AppFont.face(); textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(80)); setSelectAllOnFocus(true)
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI; showSoftInputOnFocus = false
        setOnClickListener { keyboard.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
        setOnEditorActionListener { _, action, event -> if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) { commit(text.toString()); true } else false }
        setOnKeyListener { _, key, event -> when (key) {
            KeyEvent.KEYCODE_DPAD_CENTER -> { if (event.action == KeyEvent.ACTION_UP) keyboard.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); addRegion.requestFocus() }; true }
            else -> false
        } }
        doAfterTextChanged { renderSuggestions(); addRegion.text = if (editing == null) "Add region" else "Save changes" }
    }
    private val addRegion: TextView = option("Add region").apply {
        tag = "local-add-region"
        setOnClickListener { if (input.text.isNotBlank()) commit(input.text.toString()) else { editing = null; input.requestFocus(); keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) } }
        setOnKeyListener { _, key, event -> when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) input.requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) (suggestionRows.firstOrNull() ?: openGuide).requestFocus(); true }
            else -> false
        } }
    }
    private val openGuide: TextView = option("Open Local guide").apply {
        tag = "local-open-guide"; setOnClickListener { hideKeyboard(); onOpenGuide() }
        setOnKeyListener { _, key, event -> when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) (suggestionRows.lastOrNull() ?: addRegion).requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        } }
    }
    val location get() = LocalRegions.saved(regions.joinToString("; "))
    init {
        orientation = VERTICAL; tag = "local-channels-editor"
        addView(label("Cities or states · Up to 8 regions", 12f), LayoutParams(-1, dp(26)))
        addView(savedSummary, LayoutParams(-1, -2))
        addView(savedScroll, LayoutParams(-1, -2))
        addView(input, LayoutParams(-1, dp(46)))
        addView(addRegion, LayoutParams(-1, dp(36)))
        addView(matches, LayoutParams(-1, dp(30)))
        addView(suggestions, LayoutParams(-1, -2))
        addView(openGuide, LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        renderRegions(); renderMatches()
    }
    private fun commit(value: String) {
        val saved = LocalRegions.parse(value).firstOrNull() ?: return
        val index = regions.indexOf(editing)
        if (index >= 0) regions[index] = saved else if (regions.size < 8) regions.add(saved) else return
        val unique = LocalRegions.parse(location); regions.clear(); regions.addAll(unique)
        input.requestFocus(); editing = null; input.setText(""); hideKeyboard(); publish()
    }
    private fun publish() { count = 0; loading = regions.isNotEmpty(); renderRegions(); renderMatches(); onChanged(location) }
    private fun renderRegions() {
        savedSummary.text = if (regions.isEmpty()) "No saved regions" else "Saved regions · $location"
        savedRows.removeAllViews()
        regions.forEachIndexed { index, value ->
            val row = LinearLayout(context)
            row.addView(option("Edit · $value").apply {
                tag = "local-edit:$index"; setOnClickListener { editing = value; input.setText(value); input.requestFocus(); input.selectAll(); keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
            }, LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
            row.addView(option("Remove").apply {
                tag = "local-remove:$index"; contentDescription = "Remove $value"; setOnClickListener { input.requestFocus(); regions.remove(value); if (editing == value) { editing = null; input.setText("") }; publish() }
            }, LayoutParams(dp(82), dp(36)))
            savedRows.addView(row, LayoutParams(-1, dp(40)))
        }
        savedScroll.layoutParams = LayoutParams(-1, dp(minOf(regions.size * 40, 120)))
    }
    fun updateMatches(count: Int, loading: Boolean) { this.count = count; this.loading = loading; renderMatches() }
    private fun renderMatches() { matches.text = when {
        regions.isEmpty() -> "Choose a city or state, then add it."
        loading -> "Finding local channels…"
        count == 1 -> "1 local channel matched"
        else -> "$count local channels matched"
    } }
    private fun renderSuggestions() {
        suggestions.removeAllViews(); suggestionRows.clear()
        directory.locations(input.text.toString(), 3).forEach { value ->
            val row = option(value).apply {
                tag = "local-location-suggestion:$value"; setOnClickListener { commit(value) }
                setOnKeyListener { view, key, event -> if (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if (event.action == KeyEvent.ACTION_DOWN) { val index = suggestionRows.indexOf(view) + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1; (suggestionRows.getOrNull(index) ?: if (index < 0) addRegion else openGuide).requestFocus() }; true
                } else false }
            }
            suggestionRows.add(row); suggestions.addView(row, LayoutParams(-1, dp(34)).apply { bottomMargin = dp(3) })
        }
    }
    fun focusEditor() { input.requestFocus() }
    override fun focusContent() = input.requestFocus()
    override fun handlesLeft(focused: View?) = focused === input
    private fun label(text: String, size: Float) = TextView(context).apply { this.text = text; textSize = size; typeface = AppFont.face(); setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
    private fun option(text: String) = label(text, 13f).apply { isFocusableInTouchMode = true; isClickable = true; setPadding(dp(10), 0, dp(10), 0); setTextColor(GuideTheme.ink()); background = GuideTheme.background(resources.displayMetrics.density, 0x66323D4B) }
    private fun hideKeyboard() { keyboard.hideSoftInputFromWindow(input.windowToken, 0) }
    override fun onDetachedFromWindow() { hideKeyboard(); super.onDetachedFromWindow() }
}
