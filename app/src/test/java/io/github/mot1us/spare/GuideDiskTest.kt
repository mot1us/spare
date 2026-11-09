package io.github.mot1us.spare

import java.io.StringWriter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideDiskTest {
    @Test fun streamingLineupMatchesTheExistingJsonFormat() {
        val news = Channel("one", "News \"quoted\" •", "News", "https://fixture.invalid/live/a?token=example",
            guideId = "station", reportedQuality = VideoInfo(1920, 1080, 59.94f), archiveMetadataKnown = true)
        val other = Channel("two", "Other\nline", "Other", "https://fixture.invalid/live/b")
        val guide = Guide(listOf(news, other), mapOf("station" to listOf(Programme("station", "Listing", 100, 200))),
            listOf("Other", "News"))
        for (value in listOf(Guide.EMPTY, guide)) {
            val written = StringWriter().also { GuideDisk.writeLineup(it, value) }
            assertEquals(Guide.from(value.copy(programmes = emptyMap()).json()), Guide.from(JSONObject(written.toString())))
        }
    }
}
