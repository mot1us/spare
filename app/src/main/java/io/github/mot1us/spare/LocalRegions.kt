package io.github.mot1us.spare

object LocalRegions {
    const val MAX_LENGTH = 656
    fun parse(value: String): List<String> = value.take(MAX_LENGTH).split(';', '\n').map { it.trim().take(80) }
        .filter { it.isNotBlank() }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(8)
    fun saved(value: String) = parse(value).joinToString("; ")
}
