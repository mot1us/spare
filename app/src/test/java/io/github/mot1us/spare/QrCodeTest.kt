package io.github.mot1us.spare

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeTest {
    private fun decode(text: String): String {
        val code = QrCode.encode(text)
        val matrix = BitMatrix(code.size)
        for (y in 0 until code.size) for (x in 0 until code.size) if (code[x, y]) matrix.set(x, y)
        return Decoder().decode(matrix).text
    }

    @Test fun phoneSetupLinksDecode() {
        assertEquals("http://192.168.0.151:45678/p/AbCdEfGhIjKlMnOpQrStUv", decode("http://192.168.0.151:45678/p/AbCdEfGhIjKlMnOpQrStUv"))
        assertEquals("http://10.0.2.15:1/p/x", decode("http://10.0.2.15:1/p/x"))
    }

    @Test fun everySupportedVersionDecodes() {
        // Lengths chosen to land in each version from 1 to 10, including the 16-bit length field in version 10.
        listOf(1, 14, 26, 42, 62, 84, 106, 122, 152, 180, 200, QrCode.MAX_BYTES).forEach { length ->
            val text = (0 until length).map { "abcdefghijklmnopqrstuvwxyz0123456789:/.-_"[(it * 7) % 41] }.joinToString("")
            assertEquals("length $length", text, decode(text))
        }
    }

    @Test(expected = IllegalArgumentException::class) fun tooLongTextIsRejected() { QrCode.encode("x".repeat(QrCode.MAX_BYTES + 1)) }
}
