package io.github.mot1us.spare

import java.net.URI

object StreamUrls {
    fun format(url: String, extension: String): String? = runCatching {
        val u = URI(url)
        if (!(u.rawPath.endsWith(".ts") || u.rawPath.endsWith(".m3u8"))) return null
        "${u.scheme}://${u.rawAuthority}${u.rawPath.substringBeforeLast('.')}.$extension" +
            (u.rawQuery?.let { "?$it" } ?: "") + (u.rawFragment?.let { "#$it" } ?: "")
    }.getOrNull()
    fun alternate(url: String) = when {
        URI(url).path.endsWith(".ts") -> format(url, "m3u8")
        URI(url).path.endsWith(".m3u8") -> format(url, "ts")
        else -> null
    }
}
