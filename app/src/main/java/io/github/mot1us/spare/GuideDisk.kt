package io.github.mot1us.spare

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import android.util.JsonWriter

/** Migrate old combined caches without allocating their complete EPG before playback. */
object GuideDisk {
    /** Avoid a second object tree for a large, already authenticated local guide. */
    fun programmes(bytes: ByteArray): Map<String, List<Programme>> {
        val entries = linkedMapOf<String, MutableList<Programme>>()
        var count = 0; var bounded: ProgrammeBudget? = null
        JsonReader(bytes.inputStream().reader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) if (reader.nextName() != "programmes") reader.skipValue() else {
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginArray()
                    val channel = reader.nextString(); val title = reader.nextString(); val start = reader.nextLong(); val end = reader.nextLong()
                    val description = if (reader.hasNext()) reader.nextString() else ""
                    val artwork = if (reader.hasNext()) reader.nextString() else ""
                    while (reader.hasNext()) reader.skipValue()
                    reader.endArray()
                    require(channel.length <= 512 && title.length <= 512 && description.length <= 2048 && artwork.length <= 4096)
                    val programme = Programme(channel, title, start, end, description, artwork)
                    if (count++ < ProgrammeBudget.MAX) entries.getOrPut(channel) { arrayListOf() }.add(programme) else {
                        if (bounded == null) { bounded = ProgrammeBudget(Importers.MAX_CHANNELS, System.currentTimeMillis()); entries.values.forEach { values -> values.forEach(bounded::add) }; entries.clear() }
                        bounded.add(programme)
                    }
                }
                reader.endArray()
            }
            reader.endObject()
        }
        return bounded?.result() ?: entries
    }
    fun writeProgrammes(writer: JsonWriter, programmes: Map<String, List<Programme>>) {
        writer.beginObject().name("channels").beginArray().endArray().name("programmes").beginArray()
        programmes.values.forEach { values -> values.forEach { programme ->
            writer.beginArray().value(programme.channel).value(programme.title).value(programme.start).value(programme.end).value(programme.description).value(programme.artwork).endArray()
        } }
        writer.endArray().name("categories").beginArray().endArray().endObject()
    }
    /** "SPL1": a saved lineup in the compact form below. Older files are JSON and start with '{'. */
    private const val LINEUP = 0x53504C31
    /**
     * The channel list as plain lengths and bytes. Reading 15,000 channels back from JSON built an object tree per
     * channel and re-derived each one's quality from its name on every start; this form is read straight into channels.
     */
    fun lineupBytes(guide: Guide): ByteArray {
        val bytes = java.io.ByteArrayOutputStream(guide.channels.size * 192 + 64); val out = java.io.DataOutputStream(bytes)
        fun text(value: String) { if (value.isEmpty()) out.writeInt(0) else { val encoded = value.toByteArray(); out.writeInt(encoded.size); out.write(encoded) } }
        out.writeInt(LINEUP); out.writeInt(guide.channels.size)
        for (c in guide.channels) {
            text(c.id); text(c.name); text(c.group); text(c.url); text(c.logo); text(c.guideId)
            out.writeByte(c.catchupDays.coerceIn(0, 30)); text(c.catchupMode); text(c.catchupTemplate)
            val quality = c.reportedQuality
            out.writeByte((if (c.media) 1 else 0) or (if (c.archiveMetadataKnown) 2 else 0) or (if (quality != null) 4 else 0))
            if (quality != null) {
                out.writeInt(quality.width); out.writeInt(quality.height); out.writeFloat(quality.fps); out.writeBoolean(quality.interlaced)
                out.writeFloat(quality.encodedFps); text(quality.evidence); out.writeInt(quality.detector)
            }
            text(c.playlistId); text(c.providerId); text(c.providerGuideId); text(c.xmltvId)
        }
        out.writeInt(guide.categoryOrder.size); guide.categoryOrder.forEach(::text)
        out.flush(); return bytes.toByteArray()
    }
    private fun compactLineup(bytes: ByteArray): Guide {
        val data = java.nio.ByteBuffer.wrap(bytes); data.int
        // Category, playlist and catch-up names repeat on thousands of channels: keep one copy of each.
        val shared = HashMap<String, String>()
        fun text(limit: Int = 8192): String {
            val length = data.int; if (length == 0) return ""
            require(length in 1..minOf(limit * 4, data.remaining()))
            return String(bytes, data.position(), length, Charsets.UTF_8).also { data.position(data.position() + length) }
        }
        fun repeated(limit: Int = 8192) = text(limit).let { shared.getOrPut(it) { it } }
        val count = data.int; require(count in 0..Importers.MAX_CHANNELS)
        val channels = ArrayList<Channel>(count)
        repeat(count) {
            val id = text(); val name = text(); val group = repeated(); val url = text(); val logo = text(); val guideId = text()
            val days = data.get().toInt().coerceIn(0, 30); val mode = repeated(); val template = text()
            val flags = data.get().toInt()
            val quality = if (flags and 4 == 0) null else {
                val width = data.int; val height = data.int; val fps = data.float; val interlaced = data.get().toInt() != 0
                VideoInfo(width, height, fps, interlaced, data.float, repeated(64), data.int)
            }
            channels.add(Channel(id, name, group, url, logo, guideId, days, mode, template, flags and 1 != 0, quality, flags and 2 != 0,
                repeated(), text(), text(), text(256).take(256)))
        }
        val categories = data.int; require(categories in 0..20000)
        return Guide(channels, emptyMap(), List(categories) { text(128).take(128) })
    }
    fun lineup(bytes: ByteArray): Guide {
        if (bytes.size >= 8 && java.nio.ByteBuffer.wrap(bytes).int == LINEUP) return compactLineup(bytes)
        val channels = mutableListOf<Channel>(); val categories = mutableListOf<String>()
        JsonReader(bytes.inputStream().reader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "channels" -> { reader.beginArray(); while (reader.hasNext()) { require(channels.size < Importers.MAX_CHANNELS); channels.add(Channel.from(obj(reader))) }; reader.endArray() }
                "categories" -> { reader.beginArray(); while (reader.hasNext()) { require(categories.size < 20000); categories.add(reader.nextString().take(128)) }; reader.endArray() }
                else -> reader.skipValue()
            }
            reader.endObject()
        }
        return Guide(channels, emptyMap(), categories)
    }
    private fun obj(reader: JsonReader, depth: Int = 0): JSONObject {
        require(depth < 4); val j = JSONObject(); reader.beginObject()
        while (reader.hasNext()) { val key = reader.nextName(); when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> j.put(key, obj(reader, depth + 1))
            JsonToken.BOOLEAN -> j.put(key, reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); j.put(key, JSONObject.NULL) }
            JsonToken.STRING, JsonToken.NUMBER -> j.put(key, reader.nextString())
            else -> reader.skipValue()
        } }; reader.endObject(); return j
    }
}
