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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

/** Search belongs to the guide. Speech capture uses the configured system service without leaving this UI. */
@SuppressLint("ViewConstructor") // Constructed with callbacks in code; never inflated from XML.
class GuideSearchBar(
    context: Context,
    makeButton: (String, () -> Unit) -> Button,
    onChanged: () -> Unit,
    onVoice: () -> Unit,
    onResults: () -> Unit,
    onClose: () -> Unit,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val keyboard get() = context.getSystemService(InputMethodManager::class.java)
    val input = EditText(context).apply {
        tag = "guide-search-input"; hint = "Search channels, movies, series and episodes"; contentDescription = "Search playlist"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true); textSize = 14f; filters = arrayOf(InputFilter.LengthFilter(128))
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        showSoftInputOnFocus = false
        setOnClickListener { keyboard.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
        setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                hideKeyboard(); onResults(); true
            } else false
        }
        doAfterTextChanged { onChanged() }
    }
    val voice = makeButton("Voice", onVoice).apply { tag = "guide-search-voice"; contentDescription = "Search by voice" }
    private val hint = TextView(context).apply { textSize = 11f; setTextColor(Color.LTGRAY); tag = "guide-search-hint" }
    init {
        orientation = VERTICAL; tag = "guide-search"; visibility = View.GONE
        setPadding(dp(160), dp(2), 0, dp(6))
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(input, LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(12) })
        row.addView(voice)
        row.addView(makeButton("Clear") { input.setText(""); input.requestFocus() })
        row.addView(makeButton("Done") { hideKeyboard(); onClose() })
        addView(row); addView(hint, LayoutParams(-1, dp(20)))
        for (i in 0 until row.childCount) row.getChildAt(i).setOnKeyListener { view, code, event ->
            when {
                view === input && code == KeyEvent.KEYCODE_DPAD_CENTER -> { if (event.action == KeyEvent.ACTION_UP) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT); true }
                code == KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); onResults() }; true }
                else -> false
            }
        }
        message("Speak a title or channel name, or select the field to type.")
    }
    fun query() = input.text.toString().trim().take(128)
    fun setQuery(value: String) { if (input.text.toString() != value) { input.setText(value); input.setSelection(input.length()) } }
    fun message(value: String) { hint.text = value }
    fun hideKeyboard() { keyboard.hideSoftInputFromWindow(input.windowToken, 0) }
}
