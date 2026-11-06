package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class GuidePrefetchTest {
    @Test fun lookAheadIsBoundedIncludesNeighborsAndFavoritesAndNeverOpensVodEntries() {
        val channels = (1..12000).map { Channel("$it", "Channel $it", "Group ${it / 100}", "https://example.test/$it") }
        val planner = GuidePrefetch(); planner.prepare(channels)
        val anchor = channels[5500]
        val category = channels.filter { it.group == anchor.group }
        val result = planner.targets(channels, category, anchor, setOf("11999"))
        assertEquals(anchor.id, result.first().id); assertTrue(result.any { it.id == "11999" })
        assertTrue(result.any { it.group == "Group 54" }); assertTrue(result.any { it.group == "Group 56" })
        assertTrue(result.size <= 96); assertEquals(result.size, result.map { it.id }.distinct().size)
        assertTrue(planner.targets(emptyList(), emptyList(), null, emptySet()).isEmpty())
    }
    @Test fun providerGroupsStaySeparateAndAnUnpreparedNewLineupNeverUsesOldNeighbors() {
        val a = (1..16).map { Channel("a:$it", "A $it", "Sports", "https://a.test/$it", playlistId = "a") }
        val b = (1..16).map { Channel("b:$it", "B $it", "Sports", "https://b.test/$it", playlistId = "b") }
        val vod = Channel("vod", "Movie", "Sports", "https://b.test/movie", media = true)
        val channels = a + b + vod; val planner = GuidePrefetch(); planner.prepare(channels)
        assertTrue(planner.targets(channels, a, a.first(), emptySet()).any { it.playlistId == "b" })
        assertFalse(planner.targets(channels, a, a.first(), emptySet()).any { it.media })
        assertTrue(planner.targets(b, b, b.first(), emptySet()).all { it.playlistId == "b" })
    }
}
