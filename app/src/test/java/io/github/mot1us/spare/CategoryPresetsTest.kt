package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class CategoryPresetsTest {
    @Test fun presetsUseWholeRegionLabelsAndPreserveOrder() {
        val groups = listOf("UK | News", "RUSSIA", "USA Sports", "Music", "CA | Hockey", "🇦🇺 Sports", "ENGLISH Movies", "FR News", "UKRAINE")
        assertEquals(listOf("UK | News", "USA Sports", "CA | Hockey", "🇦🇺 Sports", "ENGLISH Movies"), CategoryPresets.select(groups, 1).toList())
        assertEquals(setOf("USA Sports", "CA | Hockey"), CategoryPresets.select(groups, 2))
        assertEquals(setOf("UK | News"), CategoryPresets.select(groups, 3))
        assertTrue(CategoryPresets.select(listOf("FR | News"), 1).isEmpty())
    }
}
