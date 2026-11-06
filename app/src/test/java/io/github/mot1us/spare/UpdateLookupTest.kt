package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateLookupTest {
    private val manifest = "https://github.com/mot1us/spare/releases/download/v1.2.3/update.json"
    private fun metadata(version: String = "1.2.3") = JSONObject().put("versionName", version).put("versionCode", 100)
        .put("apkUrl", "https://github.com/mot1us/spare/releases/download/v$version/spare.apk").put("sha256", "a".repeat(64)).toString().toByteArray()
    private fun feed(url: String = "https://github.com/mot1us/spare/releases/tag/v1.2.3") =
        """<feed xmlns="http://www.w3.org/2005/Atom"><entry><link rel="alternate" href="$url"/></entry></feed>""".toByteArray()
    @Test fun releaseFeedChecksUpdatesWithoutSpendingAnApiRequest() {
        val urls = mutableListOf<String>()
        val result = UpdateLookup.latest(true) { url, _ -> urls.add(url); when (url) { UpdateLookup.FEED -> feed(); manifest -> metadata(); else -> error("API must not be needed") } }
        assertEquals("1.2.3", result!!.version); assertEquals(listOf(UpdateLookup.FEED, manifest), urls)
    }
    @Test fun anUnavailableFeedFallsBackToTheVerifiedApiManifest() {
        val api = JSONArray().put(JSONObject().put("tag_name", "v1.2.3").put("assets", JSONArray().put(JSONObject().put("browser_download_url", manifest)))).toString().toByteArray()
        val result = UpdateLookup.latest(true) { url, _ -> when (url) { UpdateLookup.FEED -> throw HttpFailure(403); UpdateLookup.API -> api; manifest -> metadata(); else -> error("Unexpected URL") } }
        assertEquals("1.2.3", result!!.version)
    }
    @Test fun feedCannotSelectForeignRepositoriesOrProcessXmlDeclarations() {
        assertNull(Release.testingFeed(feed("https://github.com/other/app/releases/tag/v9.9.9")))
        assertThrows(Exception::class.java) { Release.testingFeed("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///private/data'>]>${feed().toString(Charsets.UTF_8)}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { Release.testingFeed(ByteArray(256 * 1024 + 1)) }
    }
    @Test fun mismatchedFeedAndManifestVersionsCannotBeInstalled() {
        assertThrows(IllegalArgumentException::class.java) { UpdateLookup.latest(true) { url, _ -> when (url) { UpdateLookup.FEED -> feed(); UpdateLookup.API -> "[]".toByteArray(); else -> metadata("1.2.2") } } }
    }
    @Test fun stableChannelDoesNotReadPrereleaseDiscovery() {
        assertEquals("1.2.3", UpdateLookup.latest(false) { url, _ -> assertEquals(UpdateLookup.STABLE, url); metadata() }!!.version)
    }
}
