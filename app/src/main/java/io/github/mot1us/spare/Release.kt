package io.github.mot1us.spare

import org.json.JSONObject

data class Release(val version: String, val code: Long, val url: String, val sha256: String) {
    companion object {
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
