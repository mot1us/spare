package io.github.mot1us.spare

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

class Updater(private val activity: Activity, private val store: SecureStore, private val badge: (Release?) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var release: Release? = null
    private var pending: File? = null
    @Volatile private var closed = false
    private var busy = false
    private var progress: AlertDialog? = null
    fun check(manual: Boolean = false) {
        if (busy) return
        if (BuildConfig.DEBUG) {
            if (manual) message("Development build", "Automatic updates are enabled in signed release builds.")
            return
        }
        if (!manual && System.currentTimeMillis() - store.lastUpdateCheck < 86400000) return
        busy = true
        worker.execute {
            val result = runCatching {
                val metadata = if (BuildConfig.PRERELEASE_UPDATES) {
                    val releases = JSONArray(Network.get("https://api.github.com/repos/mot1us/spare/releases?per_page=10", limit = 256 * 1024).toString(Charsets.UTF_8))
                    Release.testingManifest(releases)
                } else "https://github.com/mot1us/spare/releases/latest/download/update.json"
                metadata?.let { url ->
                    val j = JSONObject(Network.get(url, limit = 16384).toString(Charsets.UTF_8))
                    Release.parse(j).takeIf { it.code > BuildConfig.VERSION_CODE }
                }
            }
            if (!closed) handler.post {
                busy = false
                result.onSuccess {
                    store.lastUpdateCheck = System.currentTimeMillis(); release = it; badge(it)
                    if (manual) { if (it == null) message("Up to date", "You have the latest release.") else offer() }
                }.onFailure { if (manual) message("Update check unavailable", "Could not check GitHub Releases. Try again later.") }
            }
        }
    }
    fun offer() {
        val candidate = release ?: return check(true)
        if (busy) return
        AlertDialog.Builder(activity).setTitle("Update to ${candidate.version}")
            .setMessage("Download and install the signed Spare update? Android may ask for permission or confirmation. Playback will stop while you install.")
            .setPositiveButton("Update") { _, _ -> download(candidate) }.setNegativeButton("Later", null).show()
    }
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
    fun resume() { if (pending != null && activity.packageManager.canRequestPackageInstalls()) install() }
    private fun install() {
        val file = pending ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())) }
                .onFailure { message("Permission required", "Allow Spare to install updates in Android’s special app access settings, then try again.") }
            return
        }
        pending = null
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { message("Installer unavailable", "Android could not open its package installer.") }
    }
    private fun message(title: String, text: String) {
        if (!activity.isFinishing && !closed) AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }
    fun close() { closed = true; progress?.dismiss(); worker.shutdownNow(); handler.removeCallbacksAndMessages(null) }
}
