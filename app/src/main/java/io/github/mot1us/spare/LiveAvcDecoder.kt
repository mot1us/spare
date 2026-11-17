package io.github.mot1us.spare

/** Google TV Streamer can freeze on live AVC transport streams (AndroidX Media issue 2972). */
internal class LiveAvcDecoder(device: String) {
    private val googleTvStreamer = device.equals("kirkwood", ignoreCase = true)

    fun prefersSoftware(live: Boolean, path: String?) =
        googleTvStreamer && live && path?.endsWith(".ts", ignoreCase = true) == true

    fun <T> preferSoftware(decoders: List<T>, isSoftware: (T) -> Boolean): List<T> {
        if (decoders.indexOfFirst(isSoftware) <= 0) return decoders
        val (software, hardware) = decoders.partition(isSoftware)
        return software + hardware
    }
}
