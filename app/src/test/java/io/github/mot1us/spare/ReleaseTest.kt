package io.github.mot1us.spare

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseTest {
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
