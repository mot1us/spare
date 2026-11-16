package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable

/** The TV side of "Set up from my phone": a QR code for the one-time form and what to do with it. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class PhoneSetupView(context: Context, private val received: (PhoneSetupResult) -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private var server: PhoneSetupServer? = null
    /** Codes last ten minutes; a fresh one replaces an expired code while this screen stays open. */
    private val renew = Runnable { if (server != null) start() }
    private val code = ImageView(context).apply { tag = "phone-setup-qr"; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "QR code for setting up from your phone" }
    private val title = TextView(context).apply { text = "Scan with your phone's camera"; textSize = 18f; setTextColor(Color.WHITE) }
    private val steps = TextView(context).apply {
        text = "1. Make sure your phone is on the same Wi-Fi as this TV.\n2. Scan the code and fill in your playlist.\n3. Press Send, then check the details here and choose Save."
        textSize = 13f; setTextColor(Color.LTGRAY); setLineSpacing(0f, 1.2f)
    }
    private val link = TextView(context).apply { tag = "phone-setup-link"; textSize = 11f; setTextColor(Color.GRAY) }
    private val status = TextView(context).apply { tag = "phone-setup-status"; textSize = 13f; setTextColor(Color.WHITE) }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(code, LayoutParams(dp(176), dp(176)))
        val text = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), 0, 0, 0) }
        text.addView(title); text.addView(steps, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        text.addView(status, LayoutParams(-1, -2).apply { topMargin = dp(12) }); text.addView(link, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(text, LayoutParams(0, -2, 1f))
    }

    /** Starts a fresh one-time link; any earlier link stops working. */
    fun start() {
        stop()
        title.typeface = AppFont.face(true); steps.typeface = AppFont.face(); status.typeface = AppFont.face()
        status.text = "Starting…"; link.text = ""; code.setImageDrawable(null)
        title.text = "Scan with your phone's camera"; steps.visibility = VISIBLE
        code.animate().cancel(); code.scaleX = 1f; code.scaleY = 1f; code.alpha = 1f
        lateinit var next: PhoneSetupServer
        next = PhoneSetupServer({ main.post(it) }, { result -> if (server != null) { stop(); status.text = "Received. Check the details and choose Save."; received(result) } }, LIFETIME_MS,
            opened = { if (server === next) connected() })
        server = next
        // Finding the address and opening the port touch the network, so they stay off the screen thread.
        Thread({
            val url = next.start()
            main.post {
                if (server !== next) { next.stop(); return@post }
                if (url == null) {
                    status.text = "Connect this TV to Wi-Fi or Ethernet to set up from your phone."
                    return@post
                }
                code.setImageDrawable(QrCode.encode(url).bitmap().toDrawable(resources).apply { isFilterBitmap = false; setAntiAlias(false) })
                code.setBackgroundColor(Color.WHITE)
                status.text = "Waiting for your phone… (this code works once, for 10 minutes)"
                link.text = "Or open $url"
                main.postDelayed(renew, LIFETIME_MS - 5000)
            }
        }, "spare-phone-setup-start").start()
    }

    /** The phone opened the form: shrink the code and point the viewer at their phone. */
    private fun connected() {
        main.removeCallbacks(renew)
        title.text = "✓ Phone connected"
        steps.visibility = GONE; link.text = ""
        status.text = "Fill in the form on your phone and press Send. The details appear here for you to check before saving."
        code.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.35f).setDuration(260).start()
    }

    fun stop() { main.removeCallbacks(renew); server?.stop(); server = null }

    companion object { private const val LIFETIME_MS = 10 * 60_000L }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}
