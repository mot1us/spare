package io.github.mot1us.spare

/** Private cache identity, excluding display name and transport consent. Never log this value. */
fun Source.providerKey(): String {
    val value = listOf(kind, if (kind == "xtream") url.trimEnd('/') else url, username, password).joinToString("") { "${it.length}:$it" }
    return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
