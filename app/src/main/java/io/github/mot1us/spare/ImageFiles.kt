package io.github.mot1us.spare

interface ImageFiles {
    fun bytes(url: String, fetch: () -> ByteArray): ByteArray
    /** The saved copy, without going to the network; null when there is none. */
    fun saved(url: String): ByteArray? = null
}
