package io.github.mot1us.spare

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The Library and Search use the same tile and grid geometry. */
internal class PosterGridLayout(context: Context, columns: Int = COLUMNS) : GridLayoutManager(context, columns) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
    companion object {
        /** Seven portrait posters per row, like streaming apps, keeps 2:3 artwork uncropped below the spotlight. */
        const val COLUMNS = 7
    }
}

internal class LibraryPosterTile(context: Context, private val artworkOnly: Boolean) : LinearLayout(context) {
    constructor(context: Context) : this(context, artworkOnly = false)

    enum class Layout { GRID, SHELF, LIST, ROW }
    data class Content(val title: String, val subtitle: String, val poster: String, val badge: String = "")
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply {
        textSize = size * GuideTheme.textScale
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "poster-artwork" }
    private val placeholder = label(11.5f).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        maxLines = 3
        setPadding(dp(10), 0, dp(10), dp(12))
        setTextColor(0xFFE1E6EE.toInt())
        tag = "poster-placeholder"
    }
    private val badge = if (artworkOnly) null else label(8f).apply {
        setPadding(dp(5), dp(3), dp(5), dp(3)); setTextColor(Color.WHITE); typeface = AppFont.face(true)
        setBackgroundColor(0xDB151C25.toInt())
    }
    // No background: the opaque placeholder covers this area until the poster does, so a fill here was drawn every
    // frame and never seen. On an onn 4K Pro each Library frame spent about 5 ms on fills like it.
    private val art = FrameLayout(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        addView(image, FrameLayout.LayoutParams(-1, -1))
        badge?.let { addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(4); marginStart = dp(4) }) }
    }
    private val title = if (artworkOnly) null else label(12f).apply { maxLines = 2 }
    private val detail = if (artworkOnly) null else label(9f)
    private val captions = if (artworkOnly) null else LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), 0)
        addView(title, LayoutParams(-1, -2))
        addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(3) })
    }
    private var layout: Layout? = null
    /** Grid tiles under a spotlight drop their text: the spotlight above names the title under the cursor, as on the rows. */
    private var captioned = true
    private var generation = 0
    private var identity = ""
    private var bound: Content? = null
    private var boundHttp = false
    private var boundDecode = 0
    private var appearanceKey: Any? = null
    private val hidePlaceholder = Runnable { placeholder.visibility = INVISIBLE }

    init {
        isFocusableInTouchMode = true; isClickable = true
        setPadding(dp(7), dp(7), dp(7), dp(7))
        addView(art)
        if (captions != null) addView(captions)
        if (artworkOnly) configure(Layout.ROW)
        appearance()
    }

    fun configure(next: Layout, captioned: Boolean = true) {
        val labelled = captioned || next != Layout.GRID
        if (next == layout && labelled == this.captioned) return
        layout = next
        if (labelled != this.captioned) {
            this.captioned = labelled
            // Without captions a title that has no artwork names itself on the placeholder.
            if (!artworkOnly) placeholder.text = if (labelled) "" else bound?.title.orEmpty()
            requestLayout()
        }
        captions?.visibility = if (labelled) VISIBLE else GONE
        orientation = if (next == Layout.LIST) HORIZONTAL else VERTICAL
        val height = when (next) { Layout.GRID -> -2; Layout.SHELF -> dp(158); Layout.LIST -> dp(55); Layout.ROW -> -2 }
        layoutParams = RecyclerView.LayoutParams(if (next == Layout.SHELF) dp(120) else -1, height).apply {
            marginEnd = dp(8); bottomMargin = dp(GuideTheme.rowGapDp + 5)
        }
        // Category rows show artwork only: the spotlight above names the title under the cursor.
        val padding = dp(if (next == Layout.ROW) 5 else 7); setPadding(padding, padding, padding, padding)
        art.layoutParams = if (next == Layout.LIST) LayoutParams(dp(70), -1).apply { marginEnd = dp(8) } else LayoutParams(-1, 0, 1f)
        captions?.layoutParams = if (next == Layout.LIST) LayoutParams(0, -1, 1f) else LayoutParams(-1, dp(43))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Grid tiles take their height from the column width so posters keep their 2:3 shape.
        if (layout == Layout.GRID && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            val height = gridHeight(MeasureSpec.getSize(widthMeasureSpec), resources.displayMetrics.density, captioned)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Rebinding during a scroll must not rebuild drawables or re-measure text unless the theme changed. */
    fun appearance() {
        val key = listOf(GuideTheme.signature, AppFont.face(true), AppFont.face(), resources.displayMetrics.density)
        if (key == appearanceKey) return
        appearanceKey = key
        // A bright ring drawn over the artwork: the accent background alone is mostly hidden behind the poster.
        foreground = FocusFade().apply { addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = GuideTheme.cornerRadiusDp * resources.displayMetrics.density; setStroke(dp(3), Color.WHITE)
            })
            addState(intArrayOf(), Color.TRANSPARENT.toDrawable())
        }
        background = GuideTheme.background(resources.displayMetrics.density, GuideTheme.current.background or 0x66000000)
        title?.typeface = AppFont.face(true); detail?.typeface = AppFont.face()
        badge?.typeface = AppFont.face(true); placeholder.typeface = AppFont.face(true)
        val base = GuideTheme.current.background or Color.BLACK
        val tint = GuideTheme.current.accent
        placeholder.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(ColorUtils.blendARGB(base, tint, .16f), ColorUtils.blendARGB(base, tint, .045f)))
        placeholder.textSize = 11.5f * GuideTheme.textScale
        title?.textSize = 12f * GuideTheme.textScale; detail?.textSize = 9f * GuideTheme.textScale
        tint()
    }

    /** [decode] is the size the artwork is decoded at (see [posterDecodeSize]); 0 leaves it to the loader. */
    fun bind(key: String, content: Content, images: LogoLoader, http: Boolean, requestPoster: (((String) -> Unit) -> Unit)? = null, decode: Int = 0) {
        val previous = bound
        art.visibility = if (layout == Layout.LIST && content.poster.isBlank()) GONE else VISIBLE
        if (key == identity && content == previous && http == boundHttp && decode == boundDecode) return
        if (content.title != previous?.title) {
            title?.text = content.title
            if (artworkOnly || !captioned) placeholder.text = content.title
        }
        if (content.subtitle != previous?.subtitle) detail?.apply {
            text = content.subtitle
            visibility = if (content.subtitle.isBlank()) GONE else VISIBLE
        }
        if (content.badge != previous?.badge) badge?.apply {
            text = content.badge
            visibility = if (content.badge.isBlank()) GONE else VISIBLE
        }
        identity = key; bound = content; boundHttp = http; boundDecode = decode
        val request = ++generation
        showPlaceholder(image.drawable == null)
        var requested = false
        images.bind(image, content.poster, http, decode) { bitmap ->
            if (generation == request) {
                showPlaceholder(bitmap == null)
                if (bitmap == null && !requested && requestPoster != null && (content.poster.isBlank() || images.unavailable(content.poster))) {
                    requested = true
                    requestPoster { fallback ->
                        if (generation == request && fallback.isNotBlank() && fallback != content.poster) {
                            images.bind(image, fallback, http, decode) { found ->
                                if (generation == request) showPlaceholder(found == null)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showPlaceholder(missing: Boolean) {
        image.removeCallbacks(hidePlaceholder)
        if (missing) {
            placeholder.visibility = VISIBLE
        } else if (image.alpha < 1f && placeholder.isVisible) {
            // Keep the title and tint under the image during its fade, then stop drawing them.
            image.postOnAnimationDelayed(hidePlaceholder, Motion.panel)
        } else {
            placeholder.visibility = INVISIBLE
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        tint()
        // Lift the focused poster so it stands out from its neighbours at TV viewing distance.
        val lift = gainFocus && layout != Layout.LIST
        animate().cancel()
        val scale = if (lift) 1.07f else 1f
        val elevation = if (lift) dp(8).toFloat() else 0f
        val duration = Motion.enter
        if (duration == 0) {
            scaleX = scale
            scaleY = scale
            translationZ = elevation
        } else {
            animate().scaleX(scale).scaleY(scale).translationZ(elevation).setDuration(duration.toLong()).start()
        }
    }

    private fun tint() {
        title?.setTextColor(if (hasFocus()) GuideTheme.focusText else Color.WHITE)
        detail?.setTextColor(if (hasFocus()) GuideTheme.focusText else Color.LTGRAY)
    }
}

/** Height of a grid tile whose poster area is [width] wide: 2:3 artwork, plus the caption block when it has one. */
internal fun gridHeight(width: Int, density: Float, captioned: Boolean = true): Int {
    val padding = (7 * density).toInt() * 2
    val art = ((width - padding).coerceAtLeast(0) * 3) / 2
    return art + (if (captioned) (43 * density).toInt() else 0) + padding
}

/**
 * Decode size for poster tiles, from the screen rather than a laid-out view so tiles, preloading and the
 * startup warm-up all ask for the same bitmap. Sized for the full-width rows, whose tiles are the largest
 * (about 190×285 px of artwork on a 1080p TV, so 320 px); grid tiles beside the category list reuse it.
 */
internal fun posterDecodeSize(metrics: android.util.DisplayMetrics): Int {
    val content = metrics.widthPixels - (CatalogShelves.CONTENT_INSET_DP * metrics.density).toInt()
    val art = content / CatalogShelves.COLUMNS - (10 * metrics.density).toInt()
    return posterDecodeSize(art * 3 / 2)
}
internal fun posterDecodeSize(artHeight: Int) = ((artHeight.coerceAtLeast(0) + 63) / 64 * 64).coerceIn(128, 480)

/** Library and Search share one encrypted, bounded poster cache, separate from guide logos. */
// Up to 2,500 posters within 64 MB: at 600, browsing a large catalog evicted (and re-downloaded) posters constantly.
internal fun posterFiles(context: Context) = GuideLogoFiles(context, "poster-art-", 2500, 64L * 1024 * 1024)

internal class PosterHolder(val box: LibraryPosterTile) : RecyclerView.ViewHolder(box)
