package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFactsTest {
    @Test fun ratingLeadsTheFactsLineWhenKnown() {
        val item = OnDemand("movie:1", "Fixture", "Action", "", year = "2024", genre = "Drama, Thriller", runtime = "1h 52m", rating = 7.84f)
        assertEquals("★ 7.8  ·  2024  ·  Drama  ·  1h 52m  ·  Action", LibraryFacts.meta(item))
        assertEquals("No rating, no star", "2024  ·  Drama  ·  1h 52m  ·  Action", LibraryFacts.meta(item.copy(rating = 0f)))
    }
    @Test fun providerRatingsOnEitherScale() {
        assertEquals(8.2f, LibraryFacts.parseRating("8.2"), 0.001f)
        assertEquals("A 0-5 score is doubled", 7f, LibraryFacts.parseRating("", "3.5"), 0.001f)
        assertEquals(0f, LibraryFacts.parseRating("N/A", "0"), 0.001f)
        assertEquals(10f, LibraryFacts.parseRating("97"), 0.001f)
    }
    @Test fun castShowsTheFirstNamesTidily() {
        assertEquals("Starring  Ana Lane  ·  Bo Reed  ·  Cy Moss", LibraryFacts.cast(" Ana Lane, Bo  Reed,Cy Moss, cy moss, N/A"))
        assertEquals("Starring  A1  ·  B2  ·  +1 more", LibraryFacts.cast("A1, B2, C3", limit = 2))
        assertEquals("", LibraryFacts.cast(" , "))
    }
}
