package io.github.mot1us.spare

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject

/** Migrate old combined caches without allocating their complete EPG before playback. */
object GuideDisk {
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
