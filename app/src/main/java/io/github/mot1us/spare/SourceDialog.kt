package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.ViewGroup
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
    fun show(activity: Activity, existing: Source?, save: (Source) -> Unit, remove: (Source) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        fun input(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            form.addView(TextView(activity).apply { text = label; textSize = 14f })
            return EditText(activity).apply {
                inputType = type; setSingleLine(true); setText(value)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50 * density).toInt())
                form.addView(this)
            }
        }
        val name = input("Source name", existing?.name.orEmpty())
        val kinds = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val m3u = RadioButton(activity).apply { id = View.generateViewId(); text = "M3U URL" }
        val xtream = RadioButton(activity).apply { id = View.generateViewId(); text = "Xtream Codes" }
        kinds.addView(m3u); kinds.addView(xtream); form.addView(kinds)
        val url = input("Playlist URL / server base URL", existing?.url.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = input("Xtream username", existing?.username.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val password = input("Xtream password", existing?.password.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val epg = input("Optional XMLTV URL (overrides provider guide)", existing?.epgUrl.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val allow = CheckBox(activity).apply { text = "Allow unencrypted HTTP for this source"; isChecked = existing?.allowHttp == true }
        form.addView(allow)
        form.addView(TextView(activity).apply {
            text = "HTTP can expose your credentials and viewing requests on the network. HTTPS is preferred.\n\nYou can type with the TV keyboard or the paired Google TV phone remote."; textSize = 13f
        })
        val error = TextView(activity).apply { textSize = 14f }; form.addView(error)
        kinds.setOnCheckedChangeListener { _, checked -> user.isEnabled = checked == xtream.id; password.isEnabled = checked == xtream.id }
        kinds.check(if (existing?.kind == "xtream") xtream.id else m3u.id)
        val dialog = AlertDialog.Builder(activity).setTitle(if (existing == null) "Add source" else "Edit source")
            .setView(ScrollView(activity).apply { addView(form) }).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Remove") { _, _ -> remove(existing) } }.create()
        dialog.setOnShowListener {
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
                    if (isXtream) "xtream" else "m3u", address, if (isXtream) user.text.toString() else "", if (isXtream) password.text.toString() else "", guideAddress, allow.isChecked)
                save(source); dialog.dismiss()
            }
        }
        dialog.show()
        dialog.window?.setLayout((760 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
