package io.github.mot1us.spare

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
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
                        if (bounded == null) { bounded = ProgrammeBudget(Importers.MAX_CHANNELS, System.currentTimeMillis()); entries.values.forEach { values -> values.forEach(bounded!!::add) }; entries.clear() }
                        bounded!!.add(programme)
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
    /** Keep the existing JSON layout and serialize only one channel at a time. */
    internal fun writeLineup(writer: Writer, guide: Guide) {
        writer.write("{\"channels\":[")
        guide.channels.forEachIndexed { index, channel ->
            if (index != 0) writer.write(",")
            writer.write(channel.json().toString())
        }
        writer.write("],\"programmes\":[],\"categories\":")
        writer.write(JSONArray(guide.categoryOrder).toString())
        writer.write("}")
    }

    fun lineup(bytes: ByteArray): Guide {
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
