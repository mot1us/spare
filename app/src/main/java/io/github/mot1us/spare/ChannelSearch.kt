package io.github.mot1us.spare

import java.text.Normalizer
import java.util.Locale

/** Compare the text users see, ignoring provider spacing, punctuation and styled letters. */
object ChannelSearch {
    private val ignored = Regex("[^\\p{L}\\p{N}]+")
    fun key(value: String): String {
        // Plain ASCII needs no Unicode decomposition: lowercase letters and digits are the whole key.
        var ascii = true
        for (c in value) if (c.code >= 128) { ascii = false; break }
        if (ascii) {
            val out = StringBuilder(value.length)
            for (c in value) if (c in 'a'..'z' || c in '0'..'9') out.append(c) else if (c in 'A'..'Z') out.append(c + 32)
            return out.toString()
        }
        return ignored.replace(Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase(Locale.ROOT), "")
    }
    fun matches(name: String, query: String) = key(name).contains(key(query))
}
