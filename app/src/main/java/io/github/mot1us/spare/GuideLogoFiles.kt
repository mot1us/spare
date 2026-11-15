package io.github.mot1us.spare

import android.content.Context
import java.io.File
import java.nio.ByteBuffer

/** Provider images may contain private account URLs: cache only encrypted bytes and hashed names. */
class GuideLogoFiles(context: Context, private val prefix: String = "guide-logo-", private val maxFiles: Int = 1024,
    private val maxBytes: Long = 32L * 1024 * 1024) : ImageFiles {
    init { require(prefix.matches(Regex("[a-z-]+-"))) }
    private val store = SecureStore(context.applicationContext)
    private val directory = context.applicationContext.filesDir
    override fun bytes(url: String, fetch: () -> ByteArray): ByteArray {
        val name = "$prefix${Channel.stableId(url)}.enc"
        return synchronized(locks[(name.hashCode() and Int.MAX_VALUE) % locks.size]) {
            val now = System.currentTimeMillis(); val file = File(directory, name)
            if (file.length() in 38..2097189) runCatching { store.read(name) }.getOrNull()?.let { saved ->
                if (saved.size > 8 && now - ByteBuffer.wrap(saved, 0, 8).long in 0 until 7 * 86400000L) {
                    file.setLastModified(now)
                    return@synchronized saved.copyOfRange(8, saved.size)
                }
            }
            val bytes = fetch(); require(bytes.size in 1..2097152)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
            store.write(name, ByteBuffer.allocate(8 + bytes.size).putLong(now).put(bytes).array())
            // Trimming lists the whole app folder: do it every few dozen new images, not after each one.
            if (++written >= 32 || now - trimmedAt !in 0 until 120_000L) trim(now)
            bytes
        }
    }
    @Volatile private var written = 0
    @Volatile private var trimmedAt = 0L
    private class Saved(val file: File, val modified: Long, val length: Long)
    private fun trim(now: Long) = synchronized(maintenance) {
        written = 0; trimmedAt = now
        // Each file's date and size are read once; sorting used to read them again on every comparison.
        val files = directory.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".enc") }.orEmpty()
            .map { Saved(it, it.lastModified(), it.length()) }.sortedByDescending { it.modified }
        var size = 0L
        files.forEachIndexed { index, saved -> size += saved.length; if (index >= maxFiles || size > maxBytes || now - saved.modified > 7 * 86400000L) saved.file.delete() }
    }
    companion object { private val locks = Array(32) { Any() }; private val maintenance = Any() }
}
