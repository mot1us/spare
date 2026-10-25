package io.github.mot1us.spare

import android.annotation.SuppressLint
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Installs only the updater's already signature/hash-verified private APK. */
@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("SetTextI18n")
class UpdateInstallActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("update-install", MODE_PRIVATE) }
    @Volatile private var sessionId = -1
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48) }
        layout.addView(ProgressBar(this))
        layout.addView(TextView(this).apply { setText("Installing Spare…\nAndroid will close Spare to finish. Reopen it from your apps."); textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 24, 0, 0) })
        setContentView(layout)
        sessionId = preferences.getInt("session", -1)
        if (intent.action == STATUS) handleStatus(intent)
        else if (savedInstanceState == null) stage()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleStatus(intent) }
    private fun stage() {
        val file = File(cacheDir, "updates/spare.apk")
        if (!file.isFile || !packageManager.canRequestPackageInstalls()) { failed("Allow Spare to install updates in Android settings, then try again."); return }
        worker.execute {
            try {
                // Abandon only our own stale unfinished sessions from interrupted updates.
                packageManager.packageInstaller.mySessions.filter { Build.VERSION.SDK_INT < 29 || !it.isCommitted }.forEach { runCatching { packageManager.packageInstaller.abandonSession(it.sessionId) } }
                sessionId = packageManager.packageInstaller.createSession(parameters(packageName, file.length()))
                preferences.edit(commit = true) { putInt("session", sessionId) } // Persist before commit can replace/kill this process.
                packageManager.packageInstaller.openSession(sessionId).use { session ->
                    session.openWrite("spare.apk", 0, file.length()).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                    val callback = Intent(this, UpdateInstallReceiver::class.java).setAction(STATUS)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val status = PendingIntent.getBroadcast(this, sessionId, callback, flags)
                    session.commit(status.intentSender)
                }
            } catch (_: Exception) {
                if (sessionId >= 0) runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
                runOnUiThread { failed("Android could not install this update. Your current app is unchanged. Try again.") }
            }
        }
    }
    @Suppress("DEPRECATION") // Typed parcelable access is API 33+, and Spare supports API 26.
    private fun handleStatus(result: Intent) {
        sessionId = preferences.getInt("session", -1)
        if (result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != sessionId || sessionId < 0) { failed("This installation is no longer active. Check Updates in Settings."); return }
        when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) failed("Android could not open its installation confirmation.")
                else runCatching { startActivity(confirmation) }.onFailure { failed("Android could not open its installation confirmation.") }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                preferences.edit { remove("session"); remove("result") }; File(cacheDir, "updates").deleteRecursively()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
            }
            else -> failed(if (result.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) == PackageInstaller.STATUS_FAILURE_ABORTED) "Update canceled. Your current app is unchanged." else "Android did not install the update. Your current app is unchanged. Check free space and try again.")
        }
    }
    private fun failed(message: String) {
        preferences.edit { remove("session"); remove("result") }
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("Update not installed").setMessage(message).setCancelable(false)
            .setPositiveButton("Return to Spare") { _, _ -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() }.show()
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    companion object {
        const val STATUS = "io.github.mot1us.spare.INSTALL_STATUS"
        fun parameters(packageName: String, size: Long) = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName); setSize(size); setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
    }
}
