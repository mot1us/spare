package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryVersionsTest {
    private fun title(id: Int, name: String, category: String = "Category 1", year: String = "", external: String = "") =
        LibraryCatalog.Title("movie:$id", name, category, "mp4", year, "", "", external)
    private fun index(vararg titles: LibraryCatalog.Title, versions: Boolean = true) =
        LibraryCatalog.Index.Builder(versions = versions).apply { titles.forEach(::add) }.build()

    @Test fun versionsOfOneMovieAreGroupedAndListedOnce() {
        val index = index(title(1, "4KDV: Fixture Heroes: First Class (2011)", "Category 7"), title(2, "Another Film (2020)"),
            title(3, "4K: Fixture Heroes: First Class (2011)", "Category 7"), title(4, "Fixture Heroes: First Class (2011)"),
            title(5, "Fixture Heroes: First Class", "Category 2", year = "2011"))
        assertEquals(listOf(0, 2, 3, 4), index.members(2))
        assertEquals(2, index.works); assertEquals(4, index.versions(4))
        // Category 7 holds two versions: it counts the movie once. Category 1 holds one version and another film.
        assertEquals(mapOf("Category 7" to 1, "Category 1" to 2, "Category 2" to 1), index.counts)
        val everything: (Int) -> Boolean = { true }
        assertEquals(listOf(0, 1), (0 until index.size).filter { !index.hidden(it, everything) })
        // A list of category 1 shows the version that category holds, though an earlier version exists elsewhere.
        val first: (Int) -> Boolean = { index.cats[it] == "Category 1" }
        assertEquals(listOf(1, 3), (0 until index.size).filter { first(it) && !index.hidden(it, first) })
        assertEquals(3, index.positionOf("movie:4"))
    }

    @Test fun differentMoviesNeverMerge() {
        // A remake (another year), a title with no year beside a dated one, and two films that share only a provider ID.
        val index = index(title(1, "The Fixture (1982)"), title(2, "The Fixture (2011)"), title(3, "The Fixture"),
            title(4, "Film One (2001)", external = "tmdb:77"), title(5, "Film Two (2004)", external = "tmdb:77"), title(6, "Film Three", external = "tmdb:0"), title(7, "Film Four", external = "tmdb:0"))
        assertEquals(7, index.works)
    }

    @Test fun theSameProviderIdAndYearGroupDifferentlyWrittenNames() {
        val index = index(title(1, "Fixture Heroes First Class", year = "2011", external = "tmdb:49538"), title(2, "Fixture-Heroes: 1st Class", year = "2011", external = "tmdb:49538"))
        assertEquals(1, index.works)
    }

    @Test fun seriesAndOldIndexFilesStayAsTheyAre() {
        assertEquals(2, index(title(1, "4K: A Show"), title(2, "A Show"), versions = false).works)
        val grouped = index(title(1, "4K: A Film (2011)"), title(2, "A Film (2011)"))
        val restored = LibraryCatalog.Index.read(grouped.bytes("gen"), "gen")
        assertNotNull(restored); assertTrue(restored!!.grouped); assertEquals(1, restored.works); assertEquals(1, restored.positionOf("movie:2"))
    }

    @Test fun anIndexSavedBeforeVersionsReadsUngrouped() {
        val old = LibraryCatalog.Index.Builder().apply { add(title(1, "4K: A Film (2011)")); add(title(2, "A Film (2011)")) }.build(grouped = false)
        val restored = LibraryCatalog.Index.read(old.bytes("gen"), "gen")!!
        assertFalse(restored.grouped); assertEquals(2, restored.works)
    }
}
