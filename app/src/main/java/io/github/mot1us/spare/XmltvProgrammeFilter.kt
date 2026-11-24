package io.github.mot1us.spare

import java.io.InputStream

/**
 * Drops whole `<programme>` elements that the guide reader would throw away, before the XML parser reads them. A full
 * third-party guide lists about 680,000 programmes, mostly for channels a playlist does not have, and tokenizing them took
 * most of a guide read on an onn 4K Pro. [wanted] gets a programme's channel and returns false to drop it, or null while the
 * reader cannot decide yet (everything then passes); [window] then gets a wanted programme's start and stop as written. Anything this filter cannot read with
 * certainty (an attribute with an entity, an overlong tag, an encoding other than an ASCII superset) passes to the parser,
 * which still applies every check; dropped bytes never reach it.
 */
internal class XmltvProgrammeFilter(private val input: InputStream, private val wanted: (channel: String) -> Boolean?,
    private val window: (channel: String, start: String, stop: String) -> Boolean = { _, _, _ -> true }) : InputStream() {
    private var buf = ByteArray(256 * 1024)
    private var pos = 0
    private var end = 0
    private var eof = false
    private var skipping = false
    /** The end of a comment or CDATA section being passed whole. */
    private var passing: ByteArray? = null
    /** Programmes dropped, for tests and timing. */
    var dropped = 0L
        private set

    /** Makes at least [n] bytes available from [pos] unless the stream ends first; returns how many are available. */
    private fun fill(n: Int): Int {
        if (end - pos >= n || eof) return end - pos
        if (pos > 0) { System.arraycopy(buf, pos, buf, 0, end - pos); end -= pos; pos = 0 }
        if (n > buf.size) buf = buf.copyOf(n)
        while (end - pos < n && !eof) { val read = input.read(buf, end, buf.size - end); if (read < 0) eof = true else end += read }
        return end - pos
    }

    private fun matches(at: Int, text: ByteArray): Boolean {
        if (end - at < text.size) return false
        for (i in text.indices) if (buf[at + i] != text[i]) return false
        return true
    }

    private fun named(at: Int, length: Int, name: ByteArray): Boolean {
        if (length != name.size) return false
        for (k in 0 until length) if (buf[at + k] != name[k]) return false
        return true
    }

    private fun space(value: Byte) = value == SPACE || value == TAB || value == NEWLINE || value == RETURN

    private fun programmeAt(at: Int) = matches(at, PROGRAMME) && end - at > PROGRAMME.size &&
        buf[at + PROGRAMME.size].let { space(it) || it == GT || it == SLASH }

    override fun read(): Int {
        val one = ByteArray(1)
        while (true) { val read = read(one, 0, 1); if (read < 0) return -1; if (read == 1) return one[0].toInt() and 0xff }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            if (skipping) { if (!skipElement()) return -1; continue }
            if (pos >= end && fill(1) == 0) return -1
            // Fields are read once per run: this loop sees every byte of a guide that unpacks to about 600 MB.
            val data = buf; val last = end; var marker = passing
            var i = pos
            val limit = minOf(last, pos + len)
            while (i < limit) {
                if (marker == null) {
                    while (i < limit && data[i] != LT) i++
                    if (i >= limit) break
                    // Decide at a '<' only with enough bytes to see "<programme" and the character after it.
                    if (last - i < LOOKAHEAD && !eof) break
                    if (matches(i, COMMENT)) marker = END_COMMENT
                    else if (matches(i, CDATA)) marker = END_CDATA
                    else if (programmeAt(i)) break
                } else {
                    // Inside a comment or CDATA nothing is a tag: pass it whole.
                    val first = marker[0]
                    while (i < limit && data[i] != first) i++
                    if (i >= limit) break
                    if (last - i < marker.size && !eof) break
                    if (matches(i, marker)) marker = null
                }
                i++
            }
            passing = marker
            if (i > pos) {
                val count = i - pos
                System.arraycopy(buf, pos, b, off, count); pos = i
                return count
            }
            if (end - pos < LOOKAHEAD && !eof) { fill(LOOKAHEAD); continue }
            // Only a programme start stops a run at its first byte.
            if (decide()) continue
            // Kept: pass its '<' on; the rest of the tag holds no '<' and passes with the next run.
            b[off] = LT; pos++
            return 1
        }
    }

    /** Reads the programme tag at [pos]; returns true when it was dropped. */
    private fun decide(): Boolean {
        fill(MAX_TAG)
        var i = pos + PROGRAMME.size
        var quote: Byte = 0
        var close = -1
        while (i < end && i - pos < MAX_TAG) {
            val c = buf[i]
            if (quote != 0.toByte()) { if (c == quote) quote = 0 }
            else if (c == QUOTE || c == APOSTROPHE) quote = c
            else if (c == GT) { close = i; break }
            else if (c == LT) return false
            i++
        }
        if (close < 0) return false
        var channelAt = -1; var channelLength = 0; var startAt = -1; var startLength = 0; var stopAt = -1; var stopLength = 0
        i = pos + PROGRAMME.size
        while (i < close) {
            while (i < close && (space(buf[i]) || buf[i] == SLASH)) i++
            val nameStart = i
            while (i < close && buf[i] != EQUALS && !space(buf[i])) i++
            val nameLength = i - nameStart
            while (i < close && space(buf[i])) i++
            if (i >= close || buf[i] != EQUALS) { if (nameLength == 0) break else return false }
            i++
            while (i < close && space(buf[i])) i++
            if (i >= close || buf[i] != QUOTE && buf[i] != APOSTROPHE) return false
            val mark = buf[i++]
            val valueStart = i
            while (i < close && buf[i] != mark) { if (buf[i] == AMPERSAND) return false; i++ }
            if (i >= close) return false
            when {
                named(nameStart, nameLength, CHANNEL) -> { channelAt = valueStart; channelLength = i - valueStart }
                named(nameStart, nameLength, START) -> { startAt = valueStart; startLength = i - valueStart }
                named(nameStart, nameLength, STOP) -> { stopAt = valueStart; stopLength = i - valueStart }
            }
            i++
        }
        fun text(at: Int, length: Int) = if (at < 0) "" else String(buf, at, length, Charsets.UTF_8)
        val channel = text(channelAt, channelLength).trim().take(256)
        // Times are read only for a channel the reader keeps: most programmes are for other channels.
        when (wanted(channel)) {
            null -> return false
            true -> if (window(channel, text(startAt, startLength), text(stopAt, stopLength))) return false
            false -> {}
        }
        dropped++
        val selfClosing = buf[close - 1] == SLASH
        pos = close + 1
        skipping = !selfClosing
        return true
    }

    /** Skips to just past `</programme>`, stepping over comments and CDATA; false when the stream ends first. */
    private fun skipElement(): Boolean {
        while (true) {
            val data = buf; val last = end
            var i = pos
            while (i < last && data[i] != LT) i++
            pos = i
            if (fill(MAX_MARK) == 0) return false
            if (buf[pos] != LT) continue
            when {
                matches(pos, END_PROGRAMME) -> {
                    var j = pos + END_PROGRAMME.size
                    while (j < end && j - pos < MAX_MARK && space(buf[j])) j++
                    if (j < end && buf[j] == GT) { pos = j + 1; skipping = false; return true }
                    pos++
                }
                matches(pos, COMMENT) -> { pos += COMMENT.size; if (!skipPast(END_COMMENT)) return false }
                matches(pos, CDATA) -> { pos += CDATA.size; if (!skipPast(END_CDATA)) return false }
                else -> pos++
            }
        }
    }

    private fun skipPast(marker: ByteArray): Boolean {
        while (true) {
            if (fill(marker.size) < marker.size) { pos = end; return false }
            var i = pos
            val last = end - marker.size
            while (i <= last && !matches(i, marker)) i++
            if (i <= last) { pos = i + marker.size; return true }
            pos = last + 1
        }
    }

    override fun close() = input.close()

    private companion object {
        const val LT = '<'.code.toByte(); const val GT = '>'.code.toByte(); const val SLASH = '/'.code.toByte()
        const val QUOTE = '"'.code.toByte(); const val APOSTROPHE = '\''.code.toByte(); const val EQUALS = '='.code.toByte()
        const val AMPERSAND = '&'.code.toByte(); const val SPACE = ' '.code.toByte(); const val TAB = '\t'.code.toByte()
        const val NEWLINE = '\n'.code.toByte(); const val RETURN = '\r'.code.toByte()
        const val MAX_TAG = 4096
        const val MAX_MARK = 64
        const val LOOKAHEAD = 11
        val CHANNEL = "channel".toByteArray(Charsets.US_ASCII)
        val START = "start".toByteArray(Charsets.US_ASCII)
        val STOP = "stop".toByteArray(Charsets.US_ASCII)
        val PROGRAMME = "<programme".toByteArray(Charsets.US_ASCII)
        val END_PROGRAMME = "</programme".toByteArray(Charsets.US_ASCII)
        val COMMENT = "<!--".toByteArray(Charsets.US_ASCII)
        val END_COMMENT = "-->".toByteArray(Charsets.US_ASCII)
        val CDATA = "<![CDATA[".toByteArray(Charsets.US_ASCII)
        val END_CDATA = "]]>".toByteArray(Charsets.US_ASCII)
    }
}
