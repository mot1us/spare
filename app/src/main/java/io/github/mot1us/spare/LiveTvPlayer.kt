package io.github.mot1us.spare

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

internal enum class LiveTvPlayer(val label: String) {
    AUTO("Automatic"), BUILT_IN("Built-in · ExoPlayer"), VLC("VLC for Android");

    fun prefersVlc(device: String, model: String) = when (this) {
        AUTO -> device.equals("kirkwood", true) || model.equals("Google TV Streamer", true)
        BUILT_IN -> false
        VLC -> true
    }

    companion object {
        private const val PACKAGE = "org.videolan.vlc"
        private val component get() = ComponentName(PACKAGE, "$PACKAGE.gui.video.VideoPlayerActivity")

        // VLC owns all redirects, segments and headers once it receives the address.
        fun canOpen(source: Source, url: String) = source.enabled && source.allowHttp &&
            source.userAgent.isBlank() && Network.allowed(url, source.allowHttp)

        fun installed(manager: PackageManager) = try {
            manager.getActivityInfo(component, 0).let { it.exported && it.enabled && it.applicationInfo.enabled }
        } catch (_: PackageManager.NameNotFoundException) { false }

        // Use VLC's URI extra so the provider address stays out of the intent's printable data field.
        fun intent(url: String, title: String): Intent = Intent(Intent.ACTION_VIEW)
            .setComponent(component).putExtra("item_location", url.toUri()).putExtra("title", title)
    }
}
