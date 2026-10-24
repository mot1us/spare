package io.github.mot1us.spare

import org.json.JSONObject
import org.json.JSONArray

data class Release(val version: String, val code: Long, val url: String, val sha256: String) {
    companion object {
        fun testingManifest(releases: JSONArray): String? {
            data class Candidate(val parts: List<Long>, val url: String)
            val candidates = (0 until minOf(releases.length(), 20)).mapNotNull { index ->
                val item = releases.optJSONObject(index) ?: return@mapNotNull null
                if (item.optBoolean("draft")) return@mapNotNull null
                val tag = item.optString("tag_name")
                if (!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) return@mapNotNull null
                val parts = tag.drop(1).split('.').map { it.toLongOrNull() ?: return@mapNotNull null }
                val expected = "https://github.com/mot1us/spare/releases/download/$tag/update.json"
                val assets = item.optJSONArray("assets") ?: return@mapNotNull null
                if ((0 until assets.length()).none { assets.optJSONObject(it)?.optString("browser_download_url") == expected }) return@mapNotNull null
                Candidate(parts, expected)
            }
            return candidates.maxWithOrNull(compareBy<Candidate> { it.parts[0] }.thenBy { it.parts[1] }.thenBy { it.parts[2] })?.url
        }
        fun parse(j: JSONObject): Release {
            val version = j.getString("versionName"); val code = j.getLong("versionCode")
            val url = j.getString("apkUrl"); val hash = j.getString("sha256")
            require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            require(code in 1..2_100_000_000L)
            require(url == "https://github.com/mot1us/spare/releases/download/v$version/spare.apk")
            require(hash.matches(Regex("[a-f0-9]{64}")))
            return Release(version, code, url, hash)
        }
    }
}
