package io.github.mot1us.spare

import java.text.Normalizer
import java.util.Locale

/** Compare the text users see, ignoring provider spacing, punctuation and styled letters. */
object ChannelSearch {
    private val ignored = Regex("[^\\p{L}\\p{N}]+")
    fun key(value: String): String = ignored.replace(Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase(Locale.ROOT), "")
    fun matches(name: String, query: String) = key(name).contains(key(query))
}
