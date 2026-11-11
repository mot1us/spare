package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class PlaylistLiveSearchTest {
    private val now = 10 * 3_600_000L

    @Test fun exactProgrammeTitlesRankBeforeChannelNamesAndCurrentBeforeUpcoming() {
        val channels = listOf("Named", "Later", "Current", "Partial", "Too far", "Expired").mapIndexed { i, name ->
            Channel("$i", if (i == 0) "Café channel" else name, "Channels", "https://example.com/$i")
        }
        val guide = Guide(channels, mapOf(
            "1" to listOf(Programme("1", "CAFÉ", now + 10_000, now + 20_000)),
            "2" to listOf(Programme("2", "Café", now - 10_000, now + 20_000)),
            "3" to listOf(Programme("3", "Café stories", now - 10_000, now + 20_000)),
            "4" to listOf(Programme("4", "Café", now + 4 * 3_600_000, now + 5 * 3_600_000)),
            "5" to listOf(Programme("5", "Café", now - 20_000, now)),
        ))
        val index = GuideProgrammeIndex.Builder().prepare(guide, now)
        val matches = PlaylistLiveSearch.find(guide, index, "cafe", now, "legacy")
        assertEquals(listOf("2", "1", "0", "3"), matches.map { it.channel.id })
        assertEquals("CAFÉ", matches[1].programme?.title)
        assertEquals(listOf("0"), PlaylistLiveSearch.find(guide.copy(programmes = emptyMap()), index, "cafe", now, "legacy").map { it.channel.id })
    }

    @Test fun resultCapsApplyAcrossRankingsAndIncludeGroupAndM3uMatches() {
        val channels = (0 until 200).map { i ->
            Channel("$i", "Feed $i", "Regional Classics", "https://example.com/$i", playlistId = "owner-${i / 50}", media = i == 1)
        }
        val guide = Guide(channels, emptyMap())
        val results = PlaylistLiveSearch.find(guide, GuideProgrammeIndex.Builder().prepare(guide, now), "classics", now, "legacy")
        assertEquals(120, results.size)
        assertTrue(results.any { it.channel.media })
        assertEquals(listOf(40, 40, 40), results.groupingBy { it.channel.playlistId }.eachCount().values.toList())
    }
}
