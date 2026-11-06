package io.github.mot1us.spare

import org.json.JSONObject
import org.json.JSONArray

data class Release(val version: String, val code: Long, val url: String, val sha256: String) {
    companion object {
        fun testingFeed(bytes: ByteArray): String? {
            require(bytes.size <= 256 * 1024)
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser()
            try { parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: org.xmlpull.v1.XmlPullParserException) { }
            require(!parser.getFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL))
            parser.setInput(bytes.inputStream(), null)
            val releases = JSONArray(); var entry = -1; var elements = 0
            while (true) when (parser.nextToken()) {
                org.xmlpull.v1.XmlPullParser.END_DOCUMENT -> break
                org.xmlpull.v1.XmlPullParser.DOCDECL -> error("DTDs are not accepted")
                org.xmlpull.v1.XmlPullParser.ENTITY_REF -> require(parser.name in setOf("amp", "lt", "gt", "quot", "apos") || parser.name.matches(Regex("#(?:x[0-9a-fA-F]+|[0-9]+)")))
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    require(++elements <= 10000 && parser.depth <= 32)
                    if (parser.namespace == "http://www.w3.org/2005/Atom") when (parser.name) {
                        "entry" -> entry = parser.depth
                        "link" -> if (entry > 0 && parser.depth == entry + 1 && parser.getAttributeValue(null, "rel") == "alternate" && releases.length() < 20) {
                            val url = parser.getAttributeValue(null, "href").orEmpty()
                            val tag = Regex("https://github\\.com/mot1us/spare/releases/tag/(v[0-9]+\\.[0-9]+\\.[0-9]+)").matchEntire(url)?.groupValues?.get(1)
                            if (tag != null) releases.put(JSONObject().put("tag_name", tag).put("assets", JSONArray().put(JSONObject().put("browser_download_url", "https://github.com/mot1us/spare/releases/download/$tag/update.json"))))
                        }
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.depth == entry && parser.name == "entry") entry = -1
            }
            return testingManifest(releases)
        }
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
