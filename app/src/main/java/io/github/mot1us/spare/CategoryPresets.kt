package io.github.mot1us.spare

import java.util.Locale

/** Provider-label presets, not a claim about the spoken language of a stream. */
object CategoryPresets {
    val names = listOf("All categories", "English-speaking regions", "USA & Canada", "UK & Ireland", "Choose categories…")
    private val english = setOf("US", "USA", "UK", "GB", "GBR", "CA", "CAN", "AU", "AUS", "NZ", "NZL", "IE", "IRL", "EN", "ENG", "ENGLISH")
    private val northAmerica = setOf("US", "USA", "CA", "CAN")
    private val british = setOf("UK", "GB", "GBR", "IE", "IRL")
    fun select(groups: List<String>, preset: Int): Set<String> = groups.filter { group ->
        val normalized = group.uppercase(Locale.ROOT)
        val tokens = normalized.split(Regex("[^A-Z]+")).filter { it.isNotEmpty() }.toSet()
        val region = when (preset) { 1 -> english; 2 -> northAmerica; 3 -> british; else -> emptySet() }
        tokens.any { it in region } || when (preset) {
            1 -> listOf("UNITED STATES", "UNITED KINGDOM", "CANADA", "AUSTRALIA", "NEW ZEALAND", "IRELAND", "BRITISH", "🇺🇸", "🇬🇧", "🇨🇦", "🇦🇺", "🇳🇿", "🇮🇪").any(normalized::contains)
            2 -> listOf("UNITED STATES", "CANADA", "🇺🇸", "🇨🇦").any(normalized::contains)
            3 -> listOf("UNITED KINGDOM", "IRELAND", "BRITISH", "🇬🇧", "🇮🇪").any(normalized::contains)
            else -> preset == 0
        }
    }.toSet()
}
