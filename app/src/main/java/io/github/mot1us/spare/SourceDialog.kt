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
    fun show(activity: Activity, existing: Source?, save: (Source) -> Unit, remove: (Source) -> Unit,
        prefill: PhoneSetupResult? = null, closedWithoutSaving: () -> Unit = {}): AlertDialog {
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
        // Typing long links with a remote is slow, so a new playlist can be filled in from a phone instead.
        val fromPhone = if (existing == null) android.widget.Button(activity).apply {
            id = View.generateViewId(); tag = "playlist-from-phone"; isAllCaps = false; text = "Fill in from my phone (scan a QR code)"
            isFocusableInTouchMode = true; stateListAnimator = null; textSize = 15f
            // Same clear focus as the rest of Spare: white when highlighted, dark otherwise.
            fun shape(color: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = 6 * density }
            background = android.graphics.drawable.StateListDrawable().apply {
                setEnterFadeDuration(Motion.enter); setExitFadeDuration(Motion.exit)
                addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE)); addState(intArrayOf(), shape(Color.rgb(40, 44, 52)))
            }
            setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE)))
            form.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(12) })
        } else null
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
        val allow = CheckBox(activity).apply { id = View.generateViewId(); text = "Allow HTTP-only links from this provider"; isChecked = existing?.allowHttp ?: true }
        form.addView(allow)
        form.addView(TextView(activity).apply {
            text = "On by default for new playlists: most providers send streams over plain HTTP, and without it they fail to play. It does not change an HTTPS server address. Turn it off only if your provider uses HTTPS throughout; HTTP links are not encrypted.\n\nUse the TV keyboard or paired Google TV phone remote."; textSize = 13f
        })
        // Rarely needed options stay folded away, so the everyday form is unchanged; they open when already in use.
        val advanced = CheckBox(activity).apply { id = View.generateViewId(); tag = "playlist-advanced"; text = "Show advanced options"
            isChecked = existing?.backupUrl.orEmpty().isNotBlank() || existing?.userAgent.orEmpty().isNotBlank() }
        form.addView(advanced)
        val backup = input("Optional backup server URL (used when the main server can't be reached)", existing?.backupUrl.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val agent = input("Optional User-Agent (only if your provider asks for one)", existing?.userAgent.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val error = TextView(activity).apply { textSize = 14f }; form.addView(error)
        fun showAdvanced() {
            val isXtream = kinds.checkedRadioButtonId == xtream.id
            mapOf(backup to (advanced.isChecked && isXtream), agent to advanced.isChecked).forEach { (field, shown) ->
                field.visibility = if (shown) View.VISIBLE else View.GONE
                form.getChildAt(form.indexOfChild(field) - 1).visibility = field.visibility
            }
        }
        var updateFocus: () -> Unit = {}
        kinds.setOnCheckedChangeListener { _, checked ->
            val isXtream = checked == xtream.id
            addressLabel.text = if (isXtream) "Server URL (include the provider's port)" else "Playlist URL"
            listOf(user, password).forEach { field ->
                field.visibility = if (isXtream) View.VISIBLE else View.GONE
                form.getChildAt(form.indexOfChild(field) - 1).visibility = field.visibility
            }
            showPassword.visibility = if (isXtream) View.VISIBLE else View.GONE
            showAdvanced(); updateFocus()
        }
        kinds.check(if (existing?.kind == "xtream") xtream.id else m3u.id)
        fun fill(result: PhoneSetupResult) {
            if (result.name.isNotBlank()) name.setText(result.name.take(64))
            kinds.check(if (result.xtream) xtream.id else m3u.id)
            url.setText(result.url); user.setText(result.username); password.setText(result.password); epg.setText(result.epg)
            error.text = ""
        }
        prefill?.let(::fill)
        advanced.setOnCheckedChangeListener { _, _ -> showAdvanced(); updateFocus() }
        val dialog = AlertDialog.Builder(activity).setTitle(if (existing == null) "Add playlist" else "Edit playlist")
            .setView(ScrollView(activity).apply { isFocusable = false; addView(form) }).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Remove") { _, _ -> remove(existing) } }.create()
        // Match the app's panels instead of the stock black system dialog.
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(GuideTheme.glass(density), (24 * density).toInt()))
        var saved = false
        dialog.setOnDismissListener { if (!saved) closedWithoutSaving() }
        fromPhone?.setOnClickListener {
            lateinit var phoneDialog: AlertDialog
            val view = PhoneSetupView(activity) { result ->
                fill(result); phoneDialog.dismiss()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
            }.apply { setPadding(dp(24), dp(16), dp(24), dp(8)) }
            phoneDialog = AlertDialog.Builder(activity).setTitle("Fill in from my phone").setView(view).setNegativeButton("Cancel", null).create()
            phoneDialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(GuideTheme.glass(density), (24 * density).toInt()))
            phoneDialog.setOnDismissListener { view.stop() }
            phoneDialog.show(); view.start()
            phoneDialog.window?.setLayout((720 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun configureDialog() {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            updateFocus = {
                val isXtream = kinds.checkedRadioButtonId == xtream.id
                val extras = if (advanced.isChecked) (if (isXtream) listOf(backup) else emptyList()) + agent else emptyList()
                val fields = listOf(name, url) + (if (isXtream) listOf(user, password) else emptyList()) + listOf(epg) + extras
                val controls = listOfNotNull<View>(fromPhone, name, if (isXtream) xtream else m3u, url) +
                    (if (isXtream) listOf(user, password, showPassword) else emptyList()) + listOf(epg, allow, advanced) + extras + saveButton
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
                val userAgent = Network.userAgent(agent.text.toString())
                if (agent.text.isNotBlank() && userAgent.isEmpty()) {
                    error.text = "The User-Agent must be plain text on one line, up to 200 characters."; return@setOnClickListener
                }
                val backupAddress = if (isXtream) backup.text.toString().trim() else ""
                if (backupAddress.isNotBlank() && (!Network.allowed(backupAddress, allow.isChecked) || runCatching { URI(backupAddress).rawQuery != null || URI(backupAddress).fragment != null }.getOrDefault(true))) {
                    error.text = "Enter the backup server's base URL (HTTPS, or allow HTTP for this source), without a query."; return@setOnClickListener
                }
                val source = Source(existing?.id ?: java.util.UUID.randomUUID().toString(), name.text.toString().trim().take(64).ifBlank { URI(address).host },
                    if (isXtream) "xtream" else "m3u", address, if (isXtream) user.text.toString() else "", if (isXtream) password.text.toString() else "", guideAddress, allow.isChecked, existing?.enabled ?: true, existing?.maxStreams ?: 0,
                    userAgent, backupAddress)
                saved = true; save(source); dialog.dismiss()
            }
        }
        dialog.show()
        configureDialog()
        // Start the remote on the first control (the phone option for a new playlist), never on the scroll container.
        if (prefill != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() else fromPhone?.requestFocus()
        dialog.window?.setLayout((760 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        return dialog
    }
}
