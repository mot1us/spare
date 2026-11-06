package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject

/** Release feed first, with the REST API as a fallback; neither path opens a player. */
object UpdateLookup {
    const val FEED = "https://github.com/mot1us/spare/releases.atom"
    const val API = "https://api.github.com/repos/mot1us/spare/releases?per_page=10"
    const val STABLE = "https://github.com/mot1us/spare/releases/latest/download/update.json"
    fun latest(testing: Boolean, read: (String, Int) -> ByteArray = { url, limit -> Network.get(url, limit = limit) }): Release? {
        fun metadata(url: String): Release {
            val release = Release.parse(JSONObject(read(url, 16384).toString(Charsets.UTF_8)))
            if (url != STABLE) require(url == "https://github.com/mot1us/spare/releases/download/v${release.version}/update.json")
            return release
        }
        if (!testing) return metadata(STABLE)
        val feed = runCatching { metadata(requireNotNull(Release.testingFeed(read(FEED, 256 * 1024)))) }
        if (feed.isSuccess) return feed.getOrThrow()
        val manifest = Release.testingManifest(JSONArray(read(API, 256 * 1024).toString(Charsets.UTF_8))) ?: throw feed.exceptionOrNull()!!
        return metadata(manifest)
    }
    fun error(error: Throwable): String = when (error) {
        is HttpFailure -> "GitHub refused the connection (HTTP ${error.status}). Try another VPN server or check again later."
        is java.io.IOException -> "Could not reach GitHub. Check the network or try another VPN server, then retry."
        else -> "The update information could not be verified. Please try again later."
    }
}
