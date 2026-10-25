package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class CategoryOrderTest {
    private val groups = listOf("UK | News", "USA | News", "CA | News", "US | MLB", "Other")
    private val channels = groups.mapIndexed { i, group -> Channel("$i", "Channel $i", group, "https://example.com/$i") }
    @Test fun providerOrderComesFromCategoryArrayRatherThanChannelOrAlphabeticalOrder() {
        assertEquals(listOf("CA | News", "US | MLB", "USA | News", "UK | News", "Other"),
            CategoryOrder.provider(groups + groups, listOf("CA | News", "Empty group", "US | MLB", "USA | News", "CA | News")))
    }
    @Test fun usaFirstIsAStablePartitionOfProviderOrder() {
        val provider = listOf("UK | News", "USA | Zulu", "US | Alpha", "CA | News", "USA | News")
        assertEquals(listOf("USA | Zulu", "US | Alpha", "USA | News", "UK | News", "CA | News"), CategoryOrder.usaFirst(provider))
    }
    @Test fun categoryOrderSurvivesCacheAndOldCachesUseFirstAppearance() {
        val order = groups.reversed()
        val guide = Guide(channels, emptyMap(), order)
        assertEquals(order, Guide.from(guide.json()).categoryOrder)
        val old = guide.json().apply { remove("categories") }
        val restored = Guide.from(old)
        assertEquals(groups, CategoryOrder.provider(restored.channels.map { it.group }, restored.categoryOrder))
    }
    @Test fun m3uGroupsRetainTheirFirstAppearance() {
        val text = "#EXTM3U\n" + groups.reversed().joinToString("\n") { "#EXTINF:-1 group-title=\"$it\",$it\nhttps://example.com/${it.hashCode()}" }
        val lineup = Importers.m3u(text, false)
        assertEquals(groups.reversed(), CategoryOrder.provider(lineup.channels.map { it.group }, lineup.categoryOrder))
    }
}
