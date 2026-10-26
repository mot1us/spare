package io.github.mot1us.spare

import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.core.view.isVisible

/** Only settled guide focus downloads artwork. Stale results never replace the current programme. */
class ProgrammeArtwork(private val image: ImageView) {
    private val handler = Handler(Looper.getMainLooper())
    private val pictures = LogoLoader(1280, 8 * 1024 * 1024)
    private var key = ""
    private var task: Runnable? = null
    fun show(url: String, http: Boolean) {
        val next = "$http:$url"; if (next == key) return
        key = next; task?.let(handler::removeCallbacks); image.setImageDrawable(null); image.isVisible = false
        if (url.isBlank()) return
        task = Runnable {
            fun draw() { if (key == next) { val bitmap = pictures.get(url, http) {}; image.setImageBitmap(bitmap); image.isVisible = bitmap != null } }
            val bitmap = pictures.get(url, http, ::draw); image.setImageBitmap(bitmap); image.isVisible = bitmap != null
        }.also { handler.postDelayed(it, 250) }
    }
    fun pause() { key = ""; task?.let(handler::removeCallbacks); pictures.pause() }
    fun resume() { pictures.resume() }
    fun close() { handler.removeCallbacksAndMessages(null); pictures.close() }
}
