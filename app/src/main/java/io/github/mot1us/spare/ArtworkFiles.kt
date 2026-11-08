package io.github.mot1us.spare

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File

/** Public Sports artwork only. Raw compressed files are shared by hero and thumbnail decoders. */
class ArtworkFiles(context: Context) : ImageFiles {
    private val directory = File(context.cacheDir, "sports-images").apply { mkdirs() }
    override fun bytes(url: String, fetch: () -> ByteArray): ByteArray {
        require(Network.allowed(url, false))
        val key = Channel.stableId(url); val file = File(directory, "$key.img")
        return synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            val now = System.currentTimeMillis()
            if (file.exists() && file.length() in 1..2097152 && now - file.lastModified() in 0..14 * 86400000L) {
                runCatching { file.readBytes() }.getOrNull()?.takeIf(::valid)?.let { file.setLastModified(now); return@synchronized it }
            }
            val atomic = AtomicFile(file); atomic.delete()
            val bytes = fetch(); require(valid(bytes))
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
            trim(now); bytes
        }
    }
    private fun valid(bytes: ByteArray): Boolean {
        if (bytes.size !in 1..2097152) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return false
        // A plausible header alone can still be a truncated/corrupt CDN response.
        // Validate with a tiny decode rather than allocating the full hero image.
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while ((maxOf(bounds.outWidth, bounds.outHeight) + inSampleSize - 1) / inSampleSize > 64) inSampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { it.recycle(); true } ?: false
    }
    private fun trim(now: Long) = synchronized(maintenance) {
        val files = directory.listFiles()?.filter { it.extension == "img" }?.sortedByDescending { it.lastModified() }.orEmpty()
        var size = 0L
        files.forEachIndexed { index, file -> size += file.length(); if (index >= 256 || size > 48L * 1024 * 1024 || now - file.lastModified() > 14 * 86400000L) file.delete() }
    }
    companion object { private val locks = Array(32) { Any() }; private val maintenance = Any() }
}
