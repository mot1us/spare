package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class XmltvProgrammeFilterTest {
    private val now = System.currentTimeMillis() / 60000L * 60000L
    private val format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
    private fun stamp(at: Long) = format.format(Instant.ofEpochMilli(at))

    /** A guide with the shapes that could trip a byte-level filter, around ordinary programmes. */
    private fun guide(): ByteArray {
        val random = java.util.Random(7)
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"&lt;programme&gt; test\">\n")
        (0 until 40).forEach { out.append("<channel id=\"c$it\"><display-name>Channel $it &amp; Co</display-name></channel>\n") }
        out.append("<!-- <programme channel=\"c1\" start=\"x\"> a commented programme </programme> -->\n")
        (0 until 3000).forEach { n ->
            val channel = "c${random.nextInt(45)}"
            val start = now + (random.nextInt(24 * 9) - 24 * 2) * 3600000L
            val quote = if (n % 7 == 0) "'" else "\""
            val attributes = when (n % 11) {
                0 -> "start=$quote${stamp(start)}$quote stop=$quote${stamp(start + 3600000L)}$quote channel=$quote$channel$quote"
                1 -> "channel=\"$channel\" start=\"${stamp(start)}\" stop=\"${stamp(start + 3600000L)}\" title-hint=\"a > b\""
                2 -> "channel=\"c1&amp;\" start=\"${stamp(start)}\" stop=\"${stamp(start + 3600000L)}\""
                3 -> "\n   channel = \"$channel\"\n   start=\"${stamp(start)}\" stop=\"${stamp(start + 3600000L)}\""
                else -> "start=\"${stamp(start)}\" stop=\"${stamp(start + 3600000L)}\" channel=\"$channel\""
            }
            when (n % 13) {
                0 -> out.append("<programme $attributes/>\n")
                1 -> out.append("<programme $attributes><title>CDATA $n</title><desc><![CDATA[Not a tag: </programme> <programme channel=\"c2\"> ]]></desc></programme >\n")
                2 -> out.append("<programme $attributes><title>Comment $n</title><!-- </programme> --><desc>Still inside</desc></programme\n>\n")
                3 -> out.append("<programmes-note>not a programme</programmes-note>\n")
                else -> out.append("<programme $attributes><title>Show $n é</title><desc>Line &lt;one&gt; &#233;</desc><icon src=\"https://example.test/$n.png\"/></programme>\n")
            }
        }
        out.append("</tv>\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Returns the bytes in small, uneven pieces, as a slow network or decompressor does. */
    private class Trickle(private val bytes: ByteArray, seed: Long) : InputStream() {
        private val random = java.util.Random(seed); private var at = 0
        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= bytes.size) return -1
            val count = minOf(len, bytes.size - at, 1 + random.nextInt(23)); System.arraycopy(bytes, at, b, off, count); at += count; return count
        }
    }

    @Test fun droppingProgrammesBeforeTheParserGivesExactlyTheSameGuide() {
        val bytes = guide()
        val history = mapOf("c3" to 7)
        for ((index, wanted) in listOf(setOf("c1", "c2", "c3", "c1&"), (0 until 40).map { "c$it" }.toSet(), emptySet(), setOf("c44")).withIndex()) {
            val plain = Importers.xmltv(Trickle(bytes, index.toLong()), wanted, now, history, prefilter = false)
            val filtered = Importers.xmltv(Trickle(bytes, 100L + index), wanted, now, history)
            assertEquals("wanted $wanted", plain, filtered)
            if (wanted.isNotEmpty() && wanted != setOf("c44")) assertTrue(plain.values.sumOf { it.size } > 0)
        }
        // With channels chosen by name once the channel list ends.
        val choose: (Map<String, List<String>>) -> Set<String> = { names -> names.keys.filter { it.endsWith("5") }.toSet() }
        assertEquals(Importers.xmltv(Trickle(bytes, 1), setOf("c2"), now, listed = choose, prefilter = false),
            Importers.xmltv(Trickle(bytes, 2), setOf("c2"), now, listed = choose))
    }

    @Test fun theFilterDropsOnlyWhatItCanReadAndPassesEverythingElse() {
        val xml = """<tv><programme channel="a" start="1" stop="2"><title>keep</title></programme><programme channel="b"><desc><![CDATA[</programme>]]></desc></programme>""" +
            """<programme channel="c&amp;d"><title>entity</title></programme><programme channel='b'/><x>after</x></tv>"""
        val filter = XmltvProgrammeFilter(Trickle(xml.toByteArray(), 3), wanted = { channel -> channel != "b" })
        val passed = filter.readBytes().toString(Charsets.UTF_8)
        assertEquals("""<tv><programme channel="a" start="1" stop="2"><title>keep</title></programme>""" +
            """<programme channel="c&amp;d"><title>entity</title></programme><x>after</x></tv>""", passed)
        assertEquals(2, filter.dropped)
        val undecided = XmltvProgrammeFilter(xml.byteInputStream(), wanted = { null })
        assertEquals("Nothing is dropped before the reader can decide", xml, undecided.readBytes().toString(Charsets.UTF_8))
    }
}
