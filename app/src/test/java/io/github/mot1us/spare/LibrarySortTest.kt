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

    @Test fun boundedHomeRowsMatchStableCatalogSortsWithTiesAndMissingValues() {
        val size = 32493
        val index = LibraryCatalog.Index.Builder(size).apply {
            repeat(size) { pos -> add("title", pos, "Category ${pos % 22}", (pos % 521).toLong(), (pos % 11).toFloat()) }
        }.build()
        val recent = (0 until size).filter { index.added[it] > 0 }.sortedByDescending { index.added[it] }.take(40)
        val top = (0 until size).filter { index.rating[it] > 0 }.sortedWith(compareByDescending<Int> { index.rating[it] }.thenByDescending { index.added[it] }).take(40)
        assertEquals(recent to top, index.homeRanks(40))
        assertEquals(emptyList<Int>() to emptyList<Int>(), index.homeRanks(0))
    }

}
