package io.github.mot1us.spare

/** TMDB serves every image at fixed widths; a poster tile never needs more than the 342 px rendition. */
internal object PosterUrls {
    private val tmdb = Regex("^(https?://image\\.tmdb\\.org/t/p/)(original|w[0-9]{2,4})(/[^?#]+)$", RegexOption.IGNORE_CASE)
    const val TILE_WIDTH = 342

    /** The same image at [width] when the address asks TMDB for a larger one; every other address is returned unchanged. */
    fun sized(url: String, width: Int): String {
        val match = tmdb.matchEntire(url) ?: return url
        val size = match.groupValues[2].lowercase()
        val current = if (size == "original") Int.MAX_VALUE else size.drop(1).toInt()
        return if (current > width) match.groupValues[1] + "w$width" + match.groupValues[3] else url
    }
    fun sized(url: String) = sized(url, TILE_WIDTH)
}
