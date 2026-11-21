package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieVersionsTest {
    private fun movie(id: String, title: String, year: String = "", category: String = "Movies") = OnDemand(id, title, category, "https://example.com/movie/$id.mp4", year = year)

    @Test fun versionTagsInFrontOrBehindATitleCleanToTheSameMovie() {
        val keys = listOf("4KDV: Fixture Heroes: First Class (2011)", "4K: Fixture Heroes: First Class (2011)", "Fixture Heroes: First Class (2011)",
            "Fixture Heroes: First Class - 2011", "EN | Fixture Heroes: First Class 4K DV").map { VodStart.titleKey(movie("1", it)) }
        assertEquals(1, keys.distinct().size)
        assertEquals("2011", VodStart.release(movie("1", "Fixture Heroes: First Class - 2011")))
        assertEquals("2011", VodStart.release(movie("1", "4KDV: Fixture Heroes: First Class (2011)")))
    }

    @Test fun eachVersionIsNamedFromItsTags() {
        assertEquals("4K Dolby Vision", MovieVersions.label("4KDV: A Film (2011)").text)
        assertEquals("4K Dolby Vision", MovieVersions.label("A Film (2011) 4K DV").text)
        assertEquals("4K", MovieVersions.label("4K: A Film (2011)").text)
        assertEquals("4K HDR", MovieVersions.label("A Film UHD HDR10+").text)
        assertEquals("1080p", MovieVersions.label("A Film FHD").text)
        assertEquals("Standard", MovieVersions.label("A Film (2011)").text)
        assertEquals(listOf("Standard · Action", "Standard · Drama"), MovieVersions.names(listOf(movie("1", "A Film", category = "Action"), movie("2", "A Film", category = "Drama"))))
    }

    @Test fun dolbyVisionLeadsOnlyWhereTheTvShowsIt() {
        val dv = movie("dv", "4KDV: A Film (2011)"); val uhd = movie("4k", "4K: A Film (2011)"); val plain = movie("hd", "A Film (2011)")
        assertEquals(listOf(dv, uhd, plain), MovieVersions.order(listOf(plain, uhd, dv), dolbyVision = true))
        assertEquals(listOf(uhd, plain, dv), MovieVersions.order(listOf(plain, dv, uhd), dolbyVision = false))
    }

    @Test fun theSharedTileDropsVersionTagsButKeepsTheYear() {
        assertEquals("Fixture Heroes: First Class (2011)", MovieVersions.title("4KDV: Fixture Heroes: First Class (2011)"))
        assertEquals("Fixture Heroes: First Class (2011)", MovieVersions.title("4K: Fixture Heroes: First Class (2011)"))
        assertEquals("A Film (2011)", MovieVersions.title("A Film (2011) [4K DV]"))
        assertEquals("A Film", MovieVersions.title("A Film"))
    }
}
