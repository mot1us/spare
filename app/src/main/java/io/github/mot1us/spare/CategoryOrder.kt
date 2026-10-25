package io.github.mot1us.spare

import java.util.Locale

object CategoryOrder {
    private val usa = Regex("(?i)(?:^|[^a-z0-9])(?:us|usa|u\\.s\\.(?:a\\.?)?|united[ _-]+states(?:[ _-]+of[ _-]+america)?)(?=$|[^a-z0-9])")
    fun isUsa(name: String) = "🇺🇸" in name || usa.containsMatchIn(name)
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    fun sorted(names: List<String>): List<String> = names.distinct().map { Triple(it, if (isUsa(it)) 0 else 1, it.lowercase(Locale.ROOT).replace(separators, " ").trim()) }
        .sortedWith(compareBy<Triple<String, Int, String>> { it.second }.thenBy { it.third }).map { it.first }
}
