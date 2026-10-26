package io.github.mot1us.spare

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QualityEvidenceTest {
    private fun sps(interlaced: Boolean): ByteArray {
        val bits = StringBuilder()
        fun b(value: Long, n: Int) { bits.append(value.toString(2).padStart(n, '0')) }
        fun ue(value: Int) { val code = (value + 1).toString(2); bits.append("0".repeat(code.length - 1)).append(code) }
        b(66, 8); b(0, 8); b(40, 8); ue(0); ue(0); ue(0); ue(0); ue(1); b(0, 1); ue(119); ue(if (interlaced) 33 else 67)
        b(if (interlaced) 0 else 1, 1); if (interlaced) b(1, 1)
        b(1, 1); b(1, 1); ue(0); ue(0); ue(0); ue(if (interlaced) 2 else 4)
        b(1, 1); b(0, 4); b(1, 1); b(1001, 32); b(60000, 32); b(1, 1)
        b(0, 1); b(0, 1); b(1, 1); b(0, 1); b(1, 1)
        while (bits.length % 8 != 0) b(0, 1)
        val output = mutableListOf(0x67.toByte()); var zeros = 0
        bits.toString().chunked(8).forEach { chunk ->
            val byte = chunk.toInt(2)
            if (zeros >= 2 && byte <= 3) { output.add(3); zeros = 0 }
            output.add(byte.toByte()); zeros = if (byte == 0) zeros + 1 else 0
        }
        return output.toByteArray()
    }
    @Test fun avcTimingAndScanAreReadFromRawAnnexBAndConfigurationHeaders() {
        for (interlaced in listOf(false, true)) {
            val raw = sps(interlaced)
            val avcc = byteArrayOf(1, 66, 0, 40, -1, -31, (raw.size shr 8).toByte(), raw.size.toByte()) + raw
            listOf(raw, byteArrayOf(0, 0, 1) + raw, byteArrayOf(0, 0, 0, 1) + raw, avcc).forEach { data ->
                val header = AvcHeader.read(listOf(data))
                assertTrue(header.known); assertEquals(interlaced, header.interlaced); assertEquals(29.97f, header.fps, .001f)
                val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080).build()
                val quality = VideoRate.observation(format, 29.97f, 30f, header)
                assertEquals(if (interlaced) 59.94f else 29.97f, quality.fps, .001f)
                assertEquals(interlaced, GuideFilters(1080, FrameRateFilter.onlySixty).matches(Channel("1", "One", "USA", "https://example.com"), quality))
                assertEquals(quality, VideoInfo.from(quality.json()))
            }
        }
    }
    @Test fun oldDetectorReadingsCannotRemainVerifiedAndMalformedHeadersStayUnknown() {
        val old = VideoInfo.from(JSONObject("""{"width":1920,"height":1080,"fps":30.303,"interlaced":false}"""))
        assertEquals(1080, old.height); assertEquals(0f, old.fps, 0f)
        assertFalse(AvcHeader.read(listOf(byteArrayOf(0, 0, 1, 0x67, 0))).known)
        assertEquals(29.97f, VideoRate.resolve(-1f, 29.97f, 30f), .001f)
        assertEquals(60f, VideoRate.resolve(60f, 30f, 30f), .001f)
    }
}
