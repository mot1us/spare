package io.github.mot1us.spare

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The Library and Search use the same tile and grid geometry. */
internal class PosterGridLayout(context: Context, columns: Int = COLUMNS) : GridLayoutManager(context, columns) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
    companion object {
        /** Five portrait posters per row keeps 2:3 artwork uncropped while two rows stay on screen. */
        const val COLUMNS = 5
    }
}

internal class LibraryPosterTile(context: Context) : LinearLayout(context) {
    enum class Layout { GRID, SHELF, LIST }
    data class Content(
        val title: String, val subtitle: String, val poster: String, val badge: String = "",
        val initials: String = title.trim().split(Regex("\\s+"), limit = 3).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString(""),
    )
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply {
        textSize = size * GuideTheme.textScale
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val placeholder = label(24f).apply { gravity = Gravity.CENTER; setTextColor(0xFFBAC4D1.toInt()) }
    private val badge = label(8f).apply {
        setPadding(dp(5), dp(3), dp(5), dp(3)); setTextColor(Color.WHITE); typeface = AppFont.face(true)
        setBackgroundColor(0xDB151C25.toInt())
    }
    private val art = FrameLayout(context).apply {
        setBackgroundColor(0xFF202835.toInt())
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        addView(image, FrameLayout.LayoutParams(-1, -1))
        addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(4); marginStart = dp(4) })
    }
    private val title = label(12f).apply { maxLines = 2 }
    private val detail = label(9f)
    private val captions = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), 0)
        addView(title, LayoutParams(-1, -2))
        addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(3) })
    }
    private var layout: Layout? = null
    private var generation = 0
    private var identity = ""
    private var bound: Content? = null
    private var boundHttp = false

    init {
        isFocusableInTouchMode = true; isClickable = true
        setPadding(dp(7), dp(7), dp(7), dp(7))
        addView(art); addView(captions)
        appearance()
    }

    fun configure(next: Layout) {
        if (next == layout) return
        layout = next
        orientation = if (next == Layout.LIST) HORIZONTAL else VERTICAL
        val height = when (next) { Layout.GRID -> -2; Layout.SHELF -> dp(158); Layout.LIST -> dp(55) }
        layoutParams = RecyclerView.LayoutParams(if (next == Layout.SHELF) dp(120) else -1, height).apply {
            marginEnd = dp(8); bottomMargin = dp(GuideTheme.rowGapDp + 5)
        }
        art.layoutParams = if (next == Layout.LIST) LayoutParams(dp(70), -1).apply { marginEnd = dp(8) } else LayoutParams(-1, 0, 1f)
        captions.layoutParams = if (next == Layout.LIST) LayoutParams(0, -1, 1f) else LayoutParams(-1, dp(43))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Grid tiles take their height from the column width so posters keep their 2:3 shape.
        if (layout == Layout.GRID && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            val height = gridHeight(MeasureSpec.getSize(widthMeasureSpec), resources.displayMetrics.density)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun appearance() {
        background = GuideTheme.background(resources.displayMetrics.density, GuideTheme.current.background or 0x66000000)
        title.typeface = AppFont.face(true); detail.typeface = AppFont.face()
        badge.typeface = AppFont.face(true); placeholder.typeface = AppFont.face(true)
        title.textSize = 12f * GuideTheme.textScale; detail.textSize = 9f * GuideTheme.textScale
        tint()
    }

    fun bind(key: String, content: Content, images: LogoLoader, http: Boolean, requestPoster: (((String) -> Unit) -> Unit)? = null) {
        val previous = bound
        title.text = content.title
        detail.text = content.subtitle
        detail.visibility = if (content.subtitle.isBlank()) GONE else VISIBLE
        badge.text = content.badge
        badge.visibility = if (content.badge.isBlank()) GONE else VISIBLE
        art.visibility = if (layout == Layout.LIST && content.poster.isBlank()) GONE else VISIBLE
        if (key == identity && content == previous && http == boundHttp) return
        identity = key; bound = content; boundHttp = http
        val request = ++generation
        placeholder.text = content.initials
        placeholder.visibility = VISIBLE
        var requested = false
        images.bind(image, content.poster, http) { bitmap ->
            if (generation == request) {
                placeholder.visibility = if (bitmap == null) VISIBLE else INVISIBLE
                if (bitmap == null && !requested && requestPoster != null && (content.poster.isBlank() || images.unavailable(content.poster))) {
                    requested = true
                    requestPoster { fallback ->
                        if (generation == request && fallback.isNotBlank() && fallback != content.poster) {
                            images.bind(image, fallback, http) { found ->
                                if (generation == request) placeholder.visibility = if (found == null) VISIBLE else INVISIBLE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        tint()
    }

    private fun tint() {
        title.setTextColor(if (hasFocus()) GuideTheme.focusText else Color.WHITE)
        detail.setTextColor(if (hasFocus()) GuideTheme.focusText else Color.LTGRAY)
    }
}

/** Height of a grid tile whose poster area is [width] wide: 2:3 artwork plus the caption block. */
internal fun gridHeight(width: Int, density: Float): Int {
    val padding = (7 * density).toInt() * 2
    val art = ((width - padding).coerceAtLeast(0) * 3) / 2
    return art + (43 * density).toInt() + padding
}

/** Library and Search share one encrypted, bounded poster cache, separate from guide logos. */
internal fun posterFiles(context: Context) = GuideLogoFiles(context, "poster-art-", 600, 64L * 1024 * 1024)

internal class PosterHolder(val box: LibraryPosterTile) : RecyclerView.ViewHolder(box)
