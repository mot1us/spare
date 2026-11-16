package io.github.mot1us.spare

import java.util.Locale

/** The short facts line and cast text shared by the Library banner and details page. */
object LibraryFacts {
    /** "★ 7.8  ·  2024  ·  Drama  ·  1h 52m  ·  Category". The rating is the provider's (or Cinemeta's) 0–10 score. */
    fun meta(item: OnDemand): String = listOf(rating(item.rating), item.year, item.genre.substringBefore(',').trim(), item.runtime, item.category)
        .filter { it.isNotBlank() }.distinct().joinToString("  ·  ")

    fun rating(value: Float): String = if (value.isFinite() && value > 0f) "★ " + String.format(Locale.US, "%.1f", value.coerceAtMost(10f)) else ""

    /** A provider rating field: plain 0–10, or a 0–5 score. */
    fun parseRating(ten: String, five: String = ""): Float =
        (ten.trim().toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: five.trim().toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.times(2f) ?: 0f).coerceIn(0f, 10f)

    /** "Starring  A · B · C": the first [limit] distinct names from a provider or Cinemeta cast list. */
    fun cast(raw: String, limit: Int = 6): String {
        val names = raw.split(',', ';', '|', '\n').map { it.trim().trim('.').replace(Regex("\\s+"), " ") }
            .filter { it.length in 2..60 && !it.equals("n/a", true) }.distinctBy { it.lowercase(Locale.ROOT) }
        if (names.isEmpty()) return ""
        return "Starring  " + names.take(limit).joinToString("  ·  ") + if (names.size > limit) "  ·  +${names.size - limit} more" else ""
    }
}
