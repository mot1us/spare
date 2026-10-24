package io.github.mot1us.spare

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ReleaseTest {
    private fun release(version: String, draft: Boolean = false, url: String = "https://github.com/mot1us/spare/releases/download/v$version/update.json") =
        JSONObject().put("tag_name", "v$version").put("draft", draft).put("prerelease", true)
            .put("assets", JSONArray().put(JSONObject().put("browser_download_url", url)))
    @Test fun testingChannelFindsNewestPublishedManifestIncludingPrereleases() {
        assertEquals("https://github.com/mot1us/spare/releases/download/v0.1.10/update.json", Release.testingManifest(JSONArray().put(release("0.1.2")).put(release("0.1.10"))))
    }
    @Test fun testingChannelRejectsDraftsAndForeignManifestUrls() {
        assertNull(Release.testingManifest(JSONArray().put(release("0.2.0", draft = true)).put(release("0.3.0", url = "https://evil.example/update.json"))))
        assertNull(Release.testingManifest(JSONArray()))
    }
    private fun metadata() = JSONObject().put("versionName", "0.1.1").put("versionCode", 2)
        .put("apkUrl", "https://github.com/mot1us/spare/releases/download/v0.1.1/spare.apk").put("sha256", "a".repeat(64))
    @Test fun acceptsVersionPinnedOfficialRelease() { assertEquals(2, Release.parse(metadata()).code) }
    @Test fun rejectsForeignHostsAndRepositoryPaths() {
        listOf("http://github.com/mot1us/spare/releases/download/v0.1.1/spare.apk", "https://evil.example/spare.apk", "https://github.com/other/spare/releases/download/v0.1.1/spare.apk", "https://github.com/mot1us/spare/releases/latest/download/spare.apk").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { Release.parse(metadata().put("apkUrl", url)) }
        }
    }
    @Test fun rejectsMissingHashAndInvalidVersionCodes() {
        assertThrows(IllegalArgumentException::class.java) { Release.parse(metadata().put("sha256", "bad")) }
        assertThrows(IllegalArgumentException::class.java) { Release.parse(metadata().put("versionCode", -1)) }
        assertThrows(IllegalArgumentException::class.java) { Release.parse(metadata().put("versionCode", Long.MAX_VALUE)) }
    }
    @Test fun rejectsMismatchBetweenTagAndApk() {
        assertThrows(IllegalArgumentException::class.java) { Release.parse(metadata().put("versionName", "0.1.2")) }
    }
}
