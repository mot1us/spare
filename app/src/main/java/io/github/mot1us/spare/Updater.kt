package io.github.mot1us.spare

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

class Updater(private val activity: Activity, private val store: SecureStore,
    private val lookup: () -> Release? = { UpdateLookup.latest(BuildConfig.PRERELEASE_UPDATES) },
    private val enabled: Boolean = !BuildConfig.DEBUG,
    private val currentCode: Long = BuildConfig.VERSION_CODE.toLong(),
    private val badge: (Release?) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var release: Release? = null
    private var pending: File? = null
    @Volatile private var closed = false
    private var busy = false
    private var progress: AlertDialog? = null
    private var offerDialog: AlertDialog? = null
    private var foreground = false
    private var startupChecked = false
    private var pendingOffer = false
    fun check(manual: Boolean = false, startup: Boolean = false) {
        if (busy) return
        if (!enabled) {
            if (manual) message("Development build", "Automatic updates are enabled in signed release builds.")
            return
        }
        if (startup && startupChecked) return
        if (!manual && !startup && System.currentTimeMillis() - store.lastUpdateCheck < 86400000) return
        if (startup) startupChecked = true
        busy = true
        worker.execute {
            val result = runCatching {
                lookup()?.takeIf { it.code > currentCode }
            }
            if (!closed) handler.post {
                busy = false
                result.onSuccess {
                    store.lastUpdateCheck = System.currentTimeMillis(); release = it; badge(it)
                    if (manual) { if (it == null) message("Up to date", "You have the latest release.") else offer() }
                    else if (startup && it != null) { pendingOffer = true; showPendingOffer() }
                }.onFailure { if (manual) message("Update check unavailable", UpdateLookup.error(it)) }
            }
        }
    }
    fun offer() {
        val candidate = release ?: return check(true)
        if (!foreground) { pendingOffer = true; return }
        if (busy || offerDialog?.isShowing == true) return
        offerDialog = AlertDialog.Builder(activity).setTitle("Spare ${candidate.version} is available")
            .setMessage("Download and install the signed Spare update? Installation stays in Spare when Android allows it. Android may still require a one-time install permission or confirmation. Android closes Spare to finish installation; reopen it from your apps.")
            .setPositiveButton("Install now") { _, _ -> download(candidate) }.setNegativeButton("Later", null).create().also { dialog ->
                dialog.setOnDismissListener { offerDialog = null }; dialog.show()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
            }
    }
    private fun showPendingOffer() { if (pendingOffer && foreground && !busy && !activity.isFinishing) { pendingOffer = false; offer() } }
    fun pause() { foreground = false; if (offerDialog?.isShowing == true) { pendingOffer = true; offerDialog?.dismiss() } }
    private fun download(candidate: Release) {
        busy = true
        progress = AlertDialog.Builder(activity).setTitle("Spare update").setMessage("Downloading and verifying…").setCancelable(false).show()
        worker.execute {
            val result = runCatching {
                val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
                directory.listFiles()?.forEach { it.delete() }
                val bytes = Network.get(candidate.url, limit = 96 * 1024 * 1024)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(hash == candidate.sha256)
                val file = File(directory, "spare.apk").apply { writeBytes(bytes) }
                require(validPackage(file, candidate))
                file
            }
            if (!closed) handler.post {
                busy = false
                progress?.dismiss(); progress = null
                result.onSuccess { pending = it; install() }.onFailure {
                    File(activity.cacheDir, "updates").deleteRecursively()
                    message("Update not installed", "The download failed or could not be verified. Your current app has not changed.")
                }
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun validPackage(file: File, expected: Release): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = activity.packageManager.getPackageArchiveInfo(file.path, flags) ?: return false
        val installed = activity.packageManager.getPackageInfo(activity.packageName, flags)
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val theirs = if (Build.VERSION.SDK_INT >= 28) candidate.signingInfo?.apkContentsSigners else candidate.signatures
        val ours = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        return candidate.packageName == activity.packageName && candidate.versionName == expected.version && code == expected.code && code > BuildConfig.VERSION_CODE &&
            !theirs.isNullOrEmpty() && !ours.isNullOrEmpty() && theirs.map { it.toCharsString() }.toSet() == ours.map { it.toCharsString() }.toSet()
    }
    fun resume() {
        foreground = true
        val result = activity.getSharedPreferences("update-install", Activity.MODE_PRIVATE)
        result.getString("result", null)?.let { detail ->
            result.edit { remove("result"); remove("session") }; message("Update not installed", detail)
        }
        if (pending != null && activity.packageManager.canRequestPackageInstalls()) install()
        showPendingOffer()
    }
    private fun install() {
        if (pending == null) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())) }
                .onFailure { message("Permission required", "Allow Spare to install updates in Android’s special app access settings, then try again.") }
            return
        }
        pending = null
        runCatching { activity.startActivity(Intent(activity, UpdateInstallActivity::class.java)) }
            .onFailure { message("Installer unavailable", "Android could not start installation. Try again.") }
    }

    private fun message(title: String, text: String) {
        if (!activity.isFinishing && !closed) AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }
    fun close() { closed = true; offerDialog?.dismiss(); progress?.dismiss(); worker.shutdownNow(); handler.removeCallbacksAndMessages(null) }
}
