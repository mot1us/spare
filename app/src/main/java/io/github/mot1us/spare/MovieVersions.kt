package io.github.mot1us.spare

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * One movie that a provider lists more than once, once per version ("4KDV: A Film (2011)", "4K: A Film (2011)",
 * "A Film (2011)"). Library shows it once; its details page names each version and Play starts the best one for this TV.
 */
internal object MovieVersions {
    data class Label(val text: String, val rank: Int, val dolbyVision: Boolean)
    private val vision = Regex("\\b(?:Dolby\\s*Vision|DoVi|DV|4K\\s*-?\\s*DV|UHD\\s*-?\\s*DV)\\b", RegexOption.IGNORE_CASE)
    private val uhd = Regex("\\b(?:4K|UHD|2160p|4K\\s*-?\\s*DV|UHD\\s*-?\\s*DV)\\b", RegexOption.IGNORE_CASE)
    private val hdr = Regex("\\bHDR(?:10)?\\+?(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
    private val fhd = Regex("\\b(?:FHD|1080[pi])\\b", RegexOption.IGNORE_CASE)
    private val hd = Regex("\\b(?:HD|720p)\\b", RegexOption.IGNORE_CASE)
    private val sd = Regex("\\b(?:SD|480p|576p)\\b", RegexOption.IGNORE_CASE)

    /** What a version is, from the tags in its name: "4K Dolby Vision", "4K HDR", "1080p", "HD", "SD" or "Standard". */
    fun label(name: String): Label {
        val dolby = vision.containsMatchIn(name)
        val resolution = when {
            uhd.containsMatchIn(name) -> "4K"
            fhd.containsMatchIn(name) -> "1080p"
            hd.containsMatchIn(name) -> "HD"
            sd.containsMatchIn(name) -> "SD"
            else -> ""
        }
        val range = if (dolby) "Dolby Vision" else if (hdr.containsMatchIn(name)) "HDR" else ""
        val text = listOf(resolution, range).filter(String::isNotEmpty).joinToString(" ").ifEmpty { "Standard" }
        // An untagged version is usually the provider's ordinary HD copy.
        val rank = when (resolution) { "4K" -> 40; "1080p" -> 30; "" -> 25; "HD" -> 20; else -> 5 } + if (range == "HDR") 2 else 0
        return Label(text, rank, dolby)
    }

    /**
     * Best first. Dolby Vision leads only where this box and the TV both play it; elsewhere it goes last, because some
     * Dolby Vision files show a purple or green picture on a TV without it. Every version can still be chosen by hand.
     */
    fun order(items: List<OnDemand>, dolbyVision: Boolean): List<OnDemand> = items.sortedWith(
        compareByDescending<OnDemand> { label(it.title).let { l -> if (!l.dolbyVision) l.rank * 10 else if (dolbyVision) l.rank * 10 + 5 else l.rank - 1000 } }
    )

    /** Button names for these versions; two with the same tags add their category so they can be told apart. */
    fun names(items: List<OnDemand>): List<String> {
        val texts = items.map { label(it.title).text }
        return items.mapIndexed { i, item -> if (texts.count { it == texts[i] } > 1 && item.category.isNotBlank()) "${texts[i]} · ${item.category}" else texts[i] }
    }

    /** The title without its version tags ("4KDV: A Film (2011)" → "A Film (2011)"), for the one tile that stands for every version. */
    fun title(name: String): String =
        VodStart.quality.replace(name, " ").replace(Regex("\\s{2,}"), " ").trim(' ', '-', ':', '|', '[', ']').replace(Regex("\\(\\s*\\)|\\[\\s*]"), "").trim().ifEmpty { name }
}

/** Whether this box can decode Dolby Vision and the connected TV says it shows it. */
internal object DolbyVision {
    @Volatile private var decoder: Boolean? = null
    fun supported(context: Context): Boolean = runCatching {
        val codecs = decoder ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { !it.isEncoder && it.supportedTypes.any { type -> type.equals("video/dolby-vision", ignoreCase = true) } }.also { decoder = it }
        if (!codecs) return@runCatching false
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching false
        @Suppress("DEPRECATION") val types = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        val modes = if (Build.VERSION.SDK_INT >= 34) display.mode.supportedHdrTypes else IntArray(0)
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types || Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in modes
    }.getOrDefault(false)
}
