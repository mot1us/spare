package io.github.mot1us.spare

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadTest {
    @get:Rule val files = TemporaryFolder()

    private val payload = ByteArray(150_000) { it.toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @Test fun exposesFileOnlyAfterHashAndPackageValidation() {
        val file = files.root.resolve("spare.apk")
        val result = streamVerifiedUpdate(payload.inputStream(), file, hash) { staged ->
            assertFalse(file.exists())
            assertArrayEquals(payload, staged.readBytes())
            true
        }
        assertEquals(file, result)
        assertArrayEquals(payload, file.readBytes())
        assertEquals(listOf(file), files.root.listFiles()!!.toList())
    }

    @Test fun rejectedDigestAndPackageLeaveNoDownload() {
        val file = files.root.resolve("spare.apk")
        assertThrows(IllegalArgumentException::class.java) {
            streamVerifiedUpdate(payload.copyOf(10).inputStream(), file, hash) { error("Invalid digest reached validation") }
        }
        assertTrue(files.root.listFiles()!!.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            streamVerifiedUpdate(payload.inputStream(), file, hash) { false }
        }
        assertTrue(files.root.listFiles()!!.isEmpty())
    }

    @Test fun removesPartialFileWhenReadFails() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("Disconnected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (sent) throw IOException("Disconnected")
                sent = true
                bytes[offset] = 0
                return 1
            }
            private var sent = false
        }
        assertThrows(IOException::class.java) {
            streamVerifiedUpdate(input, files.root.resolve("spare.apk"), hash) { error("Partial file reached validation") }
        }
        assertTrue(files.root.listFiles()!!.isEmpty())
    }
}
