package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class GuideChannelIndexTest {
    @Test fun repeatedCategoryQueriesDoNotReadTheWholeLineupAgain() {
        var reads = 0
        val source = (1..26000).map { Channel("$it", "Channel $it", "Group ${it / 100}", "https://example.test/$it") }
        val channels = object : AbstractList<Channel>() { override val size get() = source.size; override fun get(index: Int): Channel { reads++; return source[index] } }
        val index = GuideChannelIndex(); index.prepare(channels, "main"); reads = 0
        repeat(100) { assertEquals(100, index.candidates(channels, null, "Group 100").size); assertTrue(index.contains(channels, "26000")) }
        assertEquals("Hot browsing uses indexes, not the original playlist", 0, reads)
    }
    @Test fun playlistScopesAndUnpreparedLineupsHaveSafeFallbacks() {
        val first = Channel("one", "One", "News", "https://a.test/live")
        val second = first.copy(id = "playlist:b:one", playlistId = "b")
        val channels = listOf(first, second); val index = GuideChannelIndex(); index.prepare(channels, "a")
        assertEquals(listOf(first), index.candidates(channels, "a", null))
        assertEquals(listOf(second), index.candidates(channels, "b", null))
        assertEquals(channels, index.candidates(channels, null, "News"))
        assertTrue(index.candidates(channels, null, "Unknown").isEmpty())
        val newer = listOf(second); assertSame(newer, index.candidates(newer, "b", "News"))
        assertFalse(index.contains(newer, "one"))
    }
}
