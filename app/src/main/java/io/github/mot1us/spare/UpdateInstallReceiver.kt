package io.github.mot1us.spare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.edit
import java.io.File

/** Explicit, private status callback survives Android replacing/killing our process. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = context.getSharedPreferences("update-install", Context.MODE_PRIVATE)
        val expected = preferences.getInt("session", -1)
        if (intent.action != UpdateInstallActivity.STATUS || expected < 0 || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != expected) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            preferences.edit { remove("session"); remove("result") }
            File(context.cacheDir, "updates").deleteRecursively()
            return // Android may prohibit auto-reopening after replacement; the user reopens from the launcher.
        }
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) preferences.edit {
            putString("result", if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update canceled. Your current app is unchanged." else "Android did not install the update. Check free space and try again.")
        }
        val callback = Intent(context, UpdateInstallActivity::class.java).setAction(UpdateInstallActivity.STATUS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.extras?.let(callback::putExtras)
        runCatching { context.startActivity(callback) }
    }
}
