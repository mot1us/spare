package io.github.mot1us.spare

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsQualityPreferenceTest {
    private val now = 1_800_000_000_000L
    private val game = SportsGame(listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }, now - 60_000, now + 3_600_000, emptyList(), "quality", "in", networks = listOf("ESPN"))
    private fun channel(id: String, info: VideoInfo? = null) = Channel(id, "ESPN $id", "NFL", "https://example.test/live/user/pass/1.ts", reportedQuality = info)

    @Test fun automaticSelectionPrefersFhdSixtyAndFallsBackThroughEveryAvailableQuality() {
        val sixty = channel("sixty", VideoInfo(1920, 1080, 59.94f))
        val unknown = channel("unknown", VideoInfo(3840, 2160, 0f))
        val thirty = channel("thirty", VideoInfo(3840, 2160, 30f))
        val low = channel("low", VideoInfo(1280, 720, 25f))
        val noInfo = channel("unlabelled")
        val choices = listOf(thirty, noInfo, low, unknown, sixty)
        val listing = Programme("test", game.title, game.start, game.end)
        val covered = game.copy(broadcasts = choices.map { SportsBroadcast(it, listing) })
        val filters = GuideFilters(1080, FrameRateFilter.sixtyOrBetter)
        assertEquals(listOf(sixty, unknown, thirty, low, noInfo), SportsStreams.automaticChoices(covered, filters, emptyMap(), Guide.EMPTY, now))
        assertEquals(low, SportsStreams.automaticChoices(covered, filters, emptyMap(), Guide.EMPTY, now) { it.id == low.id }.single())
        assertEquals(noInfo, SportsStreams.automaticChoices(covered, filters, emptyMap(), Guide.EMPTY, now) { it.id == noInfo.id }.single())
        assertTrue(SportsStreams.rank(covered, filters.copy(groups = setOf("News")), emptyMap()).isEmpty())
    }

    @Test fun confirmedCoverageComesBeforeAHighQualityUnconfirmedCandidate() {
        val low = channel("low", VideoInfo(1280, 720, 30f))
        val possible = channel("possible", VideoInfo(3840, 2160, 60f))
        val covered = game.copy(broadcasts = listOf(SportsBroadcast(low, Programme(low.id, game.title, game.start, game.end))), candidates = listOf(possible))
        assertEquals(listOf(low, possible), SportsStreams.rank(covered, GuideFilters(), emptyMap()))
        assertEquals(low, SportsStreams.automaticChoices(covered, GuideFilters(1080, FrameRateFilter.sixtyOrBetter), emptyMap(), Guide.EMPTY, now).first())
    }

    @Test fun qualityLabelsDistinguishMeasuredAndReportedComponents() {
        val provider = channel("reported", VideoInfo(1920, 1080, 60f, evidence = "provider"))
        assertTrue(provider.qualityLabel(null).contains("Provider reported"))
        val stream = provider.qualityLabel(VideoInfo(1920, 1080, 30f, evidence = "stream"))
        assertTrue(stream.contains("30 fps")); assertTrue(stream.contains("Stream reported")); assertFalse(stream.contains("Measured"))
        val measured = provider.qualityLabel(VideoInfo(0, 0, 59.94f, evidence = "input timestamps"))
        assertTrue(measured.contains("Measured FPS")); assertTrue(measured.contains("Provider resolution"))
        val fallback = provider.qualityLabel(VideoInfo(1920, 1080, 0f))
        assertTrue(fallback.contains("Provider reported")); assertTrue(fallback.contains("Playback resolution"))
        assertEquals(0f, provider.quality(VideoInfo(1280, 720, 0f))!!.fps)
        assertTrue(VideoInfo(0, 0, Float.NaN).label().contains("Resolution unknown"))
        assertFalse(VideoInfo(0, 0, Float.NaN).label().contains("NaN"))
    }

    @Test fun providerLabelsDoNotInventWidthOrDoubleAnInterlacedRate() {
        val labelled = ProviderQuality.parse(JSONObject(), "ESPN 1080i30")!!
        assertEquals(0, labelled.width); assertEquals(1080, labelled.height); assertTrue(labelled.interlaced)
        assertEquals(30f, labelled.fps, 0f)
        val fields = ProviderQuality.parse(JSONObject(), "ESPN 1080i59.94")!!
        assertEquals(59.94f, fields.fps, 0f)
        val encoded = ProviderQuality.parse(JSONObject("""{"height":1080,"field_order":"tt","r_frame_rate":"30000/1001"}"""))!!
        assertEquals(59.94f, encoded.fps, .001f); assertEquals(29.97f, encoded.encodedFps, .001f)
        val progressive = ProviderQuality.parse(JSONObject("""{"height":1080,"field_order":"progressive","fps":"30"}"""), "1080i60")!!
        assertFalse(progressive.interlaced); assertEquals(30f, progressive.fps, 0f)
        assertNull(ProviderQuality.parse(JSONObject("""{"fps":"60/1/2"}""")))
    }
}
