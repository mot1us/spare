package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {
    private fun movie(title: String, year: String = "") = OnDemand("movie:${title.hashCode().toUInt()}", title, "1", year = year)

    @Test fun alphabeticalIgnoresProviderTagsAndLeadingArticles() {
        val titles = listOf(movie("The Batman"), movie("EN | Zodiac"), movie("Alien"), movie("A Quiet Place"))
        assertEquals(listOf("Alien", "The Batman", "A Quiet Place", "EN | Zodiac"), LibraryCatalog.sortTitles(titles, LibraryCatalog.SORT_TITLE).map { it.title })
    }

    @Test fun newestFirstThenAlphabeticalWithUnknownYearsLast() {
        val titles = listOf(movie("Old", "1999"), movie("Unknown"), movie("Brand new", "2026"), movie("Also new", "2026"))
        assertEquals(listOf("Also new", "Brand new", "Old", "Unknown"), LibraryCatalog.sortTitles(titles, LibraryCatalog.SORT_NEWEST).map { it.title })
    }

    @Test fun providerOrderIsUnchanged() {
        val titles = listOf(movie("b"), movie("a"))
        assertEquals(titles, LibraryCatalog.sortTitles(titles, LibraryCatalog.SORT_PROVIDER))
    }
}
