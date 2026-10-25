package io.github.mot1us.spare

object CategoryOrder {
    private val usa = Regex("(?i)(?:^|[^a-z0-9])(?:us|usa|u\\.s\\.(?:a\\.?)?|united[ _-]+states(?:[ _-]+of[ _-]+america)?)(?=$|[^a-z0-9])")
    fun isUsa(name: String) = "🇺🇸" in name || usa.containsMatchIn(name)
    fun provider(names: List<String>, preferred: List<String> = emptyList()): List<String> {
        val present = names.toSet()
        return (preferred.filter { it in present } + names).distinct()
    }
    fun usaFirst(names: List<String>): List<String> = names.distinct().let { ordered ->
        ordered.filter(::isUsa) + ordered.filterNot(::isUsa)
    }
}
