package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Test

class PosterUrlsTest {
    @Test fun largerTmdbRenditionsBecomeTheTileSizeAndEverythingElseIsUntouched() {
        assertEquals("https://image.tmdb.org/t/p/w342/abc123.jpg", PosterUrls.sized("https://image.tmdb.org/t/p/w500/abc123.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w342/abc123.jpg", PosterUrls.sized("https://image.tmdb.org/t/p/original/abc123.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w342/abc123.jpg", PosterUrls.sized("https://image.tmdb.org/t/p/w780/abc123.jpg"))
        assertEquals("http://image.tmdb.org/t/p/w342/abc123.png", PosterUrls.sized("http://image.tmdb.org/t/p/w1280/abc123.png"))
        // Already small enough: a smaller rendition is never enlarged.
        for (same in listOf("https://image.tmdb.org/t/p/w342/abc123.jpg", "https://image.tmdb.org/t/p/w185/abc123.jpg", "https://image.tmdb.org/t/p/w92/abc123.jpg"))
            assertEquals(same, PosterUrls.sized(same))
        // Other hosts, look-alike hosts, other size schemes and addresses with a query are left exactly as supplied.
        for (other in listOf("", "https://images.example/t/p/w780/abc123.jpg", "https://image.tmdb.org.example/t/p/w780/abc123.jpg", "https://example.test/?u=https://image.tmdb.org/t/p/w780/abc123.jpg",
            "https://image.tmdb.org/t/p/h632/abc123.jpg", "https://image.tmdb.org/t/p/w780/abc123.jpg?token=1", "https://image.tmdb.org/t/p/w780/", "https://image.tmdb.org/other/w780/abc123.jpg"))
            assertEquals(other, PosterUrls.sized(other))
        assertEquals("https://image.tmdb.org/t/p/w780/abc123.jpg", PosterUrls.sized("https://image.tmdb.org/t/p/original/abc123.jpg", 780))
    }

    @Test fun tilesDecodeAtTheirOwnSizeRoundedUpAndBounded() {
        assertEquals(256, posterDecodeSize(252)); assertEquals(256, posterDecodeSize(246)); assertEquals(256, posterDecodeSize(256))
        assertEquals(192, posterDecodeSize(166)); assertEquals(128, posterDecodeSize(0)); assertEquals(128, posterDecodeSize(-5)); assertEquals(480, posterDecodeSize(4000))
    }
}
