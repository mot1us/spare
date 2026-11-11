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
            trim(now); bytes
        }
    }
    private fun trim(now: Long) = synchronized(maintenance) {
        val files = directory.listFiles()?.filter { it.name.startsWith(prefix) && it.extension == "enc" }?.sortedByDescending { it.lastModified() }.orEmpty()
        var size = 0L
        files.forEachIndexed { index, file -> size += file.length(); if (index >= maxFiles || size > maxBytes || now - file.lastModified() > 7 * 86400000L) file.delete() }
    }
    companion object { private val locks = Array(32) { Any() }; private val maintenance = Any() }
}
