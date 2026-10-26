package io.github.mot1us.spare

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Public Sports artwork only. Raw compressed files are shared by hero and thumbnail decoders. */
class ArtworkFiles(context: Context) {
    private val directory = File(context.cacheDir, "sports-images").apply { mkdirs() }
    fun bytes(url: String, fetch: () -> ByteArray): ByteArray {
        require(Network.allowed(url, false))
        val key = Channel.stableId(url); val file = File(directory, "$key.img")
        return synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            val now = System.currentTimeMillis()
            if (file.exists() && file.length() in 1..2097152 && now - file.lastModified() in 0..14 * 86400000L) {
                runCatching { file.readBytes() }.getOrNull()?.let { file.setLastModified(now); return@synchronized it }
            }
            val bytes = fetch(); val atomic = AtomicFile(file); val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
            trim(now); bytes
        }
    }
    private fun trim(now: Long) = synchronized(maintenance) {
        val files = directory.listFiles()?.filter { it.extension == "img" }?.sortedByDescending { it.lastModified() }.orEmpty()
        var size = 0L
        files.forEachIndexed { index, file -> size += file.length(); if (index >= 256 || size > 48L * 1024 * 1024 || now - file.lastModified() > 14 * 86400000L) file.delete() }
    }
    companion object { private val locks = Array(32) { Any() }; private val maintenance = Any() }
}
