package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import androidx.core.widget.doAfterTextChanged
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.net.URI

@SuppressLint("SetTextI18n")
object SourceDialog {
    fun show(activity: Activity, existing: Source?, save: (Source) -> Unit, remove: (Source) -> Unit): AlertDialog {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(16)) }
        fun input(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            form.addView(TextView(activity).apply { text = label; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(4)) })
            return EditText(activity).apply {
                id = View.generateViewId(); tag = label
                setSingleLine(true); inputType = type; setText(value)
                isFocusableInTouchMode = true
                imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                (form.getChildAt(form.childCount - 1) as TextView).labelFor = id
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(12) }
                form.addView(this)
            }
        }
        val name = input("Name (optional)", existing?.name.orEmpty())
        val kinds = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val m3u = RadioButton(activity).apply { id = View.generateViewId(); text = "M3U URL" }
        val xtream = RadioButton(activity).apply { id = View.generateViewId(); text = "Xtream Codes" }
        kinds.addView(m3u, RadioGroup.LayoutParams(-2, -2).apply { marginEnd = dp(12) }); kinds.addView(xtream); form.addView(kinds)
        val url = input("Playlist URL", existing?.url.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val addressLabel = form.getChildAt(form.indexOfChild(url) - 1) as TextView
        val connection = TextView(activity).apply { textSize = 13f }; form.addView(connection)
        fun showConnection() {
            connection.text = when (runCatching { URI(url.text.toString().trim()).scheme?.lowercase() }.getOrNull()) {
                "https" -> "Server connection: HTTPS (encrypted)"
                "http" -> "Server connection: HTTP (unencrypted)"
                else -> "Include https:// or http:// and the provider's port if supplied."
            }
        }
        url.doAfterTextChanged { showConnection() }; showConnection()
        val user = input("Xtream username", existing?.username.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val password = input("Xtream password", existing?.password.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val showPassword = CheckBox(activity).apply {
            id = View.generateViewId(); tag = "playlist-show-password"; text = "Show password"
            setOnCheckedChangeListener { _, checked ->
                val position = password.selectionStart.coerceAtLeast(0)
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(position.coerceAtMost(password.length()))
            }
        }
        form.addView(showPassword)
        val epg = input("Optional XMLTV URL (overrides provider guide)", existing?.epgUrl.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val allow = CheckBox(activity).apply { id = View.generateViewId(); text = "Allow HTTP-only links from this provider"; isChecked = existing?.allowHttp == true }
        form.addView(allow)
        form.addView(TextView(activity).apply {
            text = "This option does not change your HTTPS server address. Leave it off unless the provider needs HTTP links. HTTP can expose credentials and viewing requests.\n\nUse the TV keyboard or paired Google TV phone remote."; textSize = 13f
        })
        val error = TextView(activity).apply { textSize = 14f }; form.addView(error)
        var updateFocus: () -> Unit = {}
        kinds.setOnCheckedChangeListener { _, checked ->
            val isXtream = checked == xtream.id
            addressLabel.text = if (isXtream) "Server URL (include the provider's port)" else "Playlist URL"
            listOf(user, password).forEach { field ->
                field.visibility = if (isXtream) View.VISIBLE else View.GONE
                form.getChildAt(form.indexOfChild(field) - 1).visibility = field.visibility
            }
            showPassword.visibility = if (isXtream) View.VISIBLE else View.GONE
            updateFocus()
        }
        kinds.check(if (existing?.kind == "xtream") xtream.id else m3u.id)
        val dialog = AlertDialog.Builder(activity).setTitle(if (existing == null) "Add playlist" else "Edit playlist")
            .setView(ScrollView(activity).apply { addView(form) }).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Remove") { _, _ -> remove(existing) } }.create()
        // Match the app's panels instead of the stock black system dialog.
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(GuideTheme.glass(density), (24 * density).toInt()))
        fun configureDialog() {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            updateFocus = {
                val isXtream = kinds.checkedRadioButtonId == xtream.id
                val fields = listOf(name, url) + (if (isXtream) listOf(user, password) else emptyList()) + epg
                val controls = listOf<View>(name, if (isXtream) xtream else m3u, url) +
                    (if (isXtream) listOf(user, password, showPassword) else emptyList()) + listOf(epg, allow, saveButton)
                controls.forEachIndexed { index, view ->
                    view.isFocusableInTouchMode = true
                    val before = controls[(index - 1).coerceAtLeast(0)]
                    val after = controls[(index + 1).coerceAtMost(controls.lastIndex)]
                    view.nextFocusUpId = before.id; view.nextFocusDownId = after.id; view.nextFocusForwardId = after.id
                    view.setOnKeyListener { _, key, event ->
                        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (event.action == KeyEvent.ACTION_DOWN) (if (key == KeyEvent.KEYCODE_DPAD_UP) before else after).requestFocus()
                            true
                        } else false
                    }
                }
                fields.forEachIndexed { index, field ->
                    val after = fields.getOrNull(index + 1) ?: saveButton
                    field.nextFocusForwardId = after.id
                    field.imeOptions = (if (index == fields.lastIndex) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT) or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    field.setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_NEXT || action == EditorInfo.IME_ACTION_DONE) {
                            after.requestFocus()
                            if (after === saveButton) (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
                            true
                        } else false
                    }
                }
                cancelButton.nextFocusUpId = allow.id
            }
            updateFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = url.text.toString().trim()
                val guideAddress = epg.text.toString().trim()
                if (!Network.allowed(address, allow.isChecked) || (guideAddress.isNotBlank() && !Network.allowed(guideAddress, allow.isChecked))) {
                    error.text = "Enter a valid HTTPS address, or explicitly allow HTTP for this source."; return@setOnClickListener
                }
                val isXtream = kinds.checkedRadioButtonId == xtream.id
                if (isXtream && (user.text.isEmpty() || password.text.isEmpty() || URI(address).rawQuery != null || URI(address).fragment != null)) {
                    error.text = "Enter a server base URL without a query, plus username and password."; return@setOnClickListener
                }
                val source = Source(existing?.id ?: java.util.UUID.randomUUID().toString(), name.text.toString().trim().take(64).ifBlank { URI(address).host },
                    if (isXtream) "xtream" else "m3u", address, if (isXtream) user.text.toString() else "", if (isXtream) password.text.toString() else "", guideAddress, allow.isChecked, existing?.enabled ?: true, existing?.maxStreams ?: 0)
                save(source); dialog.dismiss()
            }
        }
        dialog.show()
        configureDialog()
        dialog.window?.setLayout((760 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        return dialog
    }
}
