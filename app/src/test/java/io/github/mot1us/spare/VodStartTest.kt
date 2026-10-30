package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class VodStartTest {
    @org.junit.Test fun compactTitleRetainsPlaybackAndMetadataWithoutRepeatingStandardCredentials() {
        val source = Source(name = "Fixture", kind = "xtream", url = "https://example.com", username = "user", password = "secret")
        val item = OnDemand("movie:42", "EN | A Movie (2025) 4K DV", "Category 1", VodCatalog.streamUrl(source, "movie", "42", "mkv"), poster = "https://example.com/poster.jpg", year = "2025", externalId = "tmdb:42")
        val title = LibraryCatalog.Title.from(item, source)
        assertFalse(title.json().toString().contains("secret"))
        val restored = LibraryCatalog.Title.from(title.json()).item(source)
        assertEquals(item, restored)
        assertEquals("amovie", title.canonical)
        assertEquals("https://example.com/asset.mp4", LibraryCatalog.Title.from(item.copy(url = "https://example.com/asset.mp4"), source).item(source).url)
    }

    private fun programme(title: String, description: String = "") = Programme("live", title, 100, 200, description)
    private fun movie(id: String, title: String, year: String = "") = OnDemand(id, title, "Movies", "https://example.com/movie/$id.mp4", year = year)
    @Test fun movieReleaseDisambiguatesRemakesAndQualityVariants() {
        val request = VodStart.request(programme("Movie: A Film (2025)"))!!
        val old = movie("old", "A Film", "1990"); val hd = movie("hd", "US | A Film FHD (2025)", "2025"); val uhd = movie("4k", "A Film 4K (2025)", "2025")
        assertEquals(uhd, VodStart.best(request, listOf(old, hd, uhd)))
        assertNull(VodStart.best(VodStart.request(programme("A Film"))!!, listOf(old, hd)))
        assertTrue(VodStart.matches(request, listOf(movie("other", "A Different Film", "2025"))).isEmpty())
    }
    @Test fun numberTitlesRemainTitlesAndGenericProgrammeNamesAreRejected() {
        val request = VodStart.request(programme("1917"))!!
        assertEquals("1917", request.query); assertEquals(movie("war", "1917", "2019"), VodStart.best(request, listOf(movie("war", "1917", "2019"))))
        assertNull(VodStart.request(programme("Untitled"))); assertNull(VodStart.request(programme("Movie")))
    }
    @Test fun formatTagsAndMissingVariantYearsDoNotPreventAnExactMovieFromPlaying() {
        val request = VodStart.request(programme("Movie: The Film (2025)"))!!
        val dolby = movie("dv", "EN | The Film (2025) 4K DV")
        val standard = movie("standard", "The Film [Dolby Vision] FHD", "2025")
        val undated = movie("undated", "The Film HDR10+ HEVC")
        assertEquals(dolby, VodStart.best(request, listOf(dolby)))
        assertEquals(standard, VodStart.best(request, listOf(standard, undated)))
        assertEquals(undated, VodStart.best(request, listOf(undated)))
        assertEquals(standard, VodStart.best(VodStart.Request("The Film"), listOf(standard, undated)))
        assertNull(VodStart.best(VodStart.Request("The Film"), listOf(standard, movie("remake", "The Film", "1990"))))
        assertNull(VodStart.best(request, listOf(movie("wrong", "The Film", "1990"))))
    }
    @Test fun episodeNumberingSelectsSeriesAndNeverAnUnrelatedMovie() {
        val request = VodStart.request(programme("Example Show S02E03 - An episode"))!!
        assertEquals("Example Show", request.query); assertEquals(2, request.season); assertEquals(3, request.episode)
        val series = OnDemand("series:1", "Example Show", "Series", kind = "series")
        assertEquals(series, VodStart.best(request, listOf(series, movie("film", "Example Show"))))
        assertEquals(1, VodStart.request(programme("Example Show", "Tonight's episode is S01E05."))!!.season)
        val unnumbered = VodStart.request(programme("Example Show"))!!
        assertNull(unnumbered.season); assertNull(unnumbered.episode)
        assertNull(VodStart.best(unnumbered, listOf(series, movie("film", "Example Show"))))
    }
}
