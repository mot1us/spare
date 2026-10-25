package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URI
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object Importers {
    const val MAX_CHANNELS = 100000
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private fun attrs(line: String) = attribute.findAll(line).associate { it.groupValues[1].lowercase(Locale.ROOT) to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
    fun m3u(text: String, http: Boolean): Lineup {
        if (!text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")) throw SourceFailure(SourceProblem.PLAYLIST)
        val result = ArrayList<Channel>()
        var metadata: Map<String, String> = emptyMap()
        var title = ""
        var group = ""
        var epg = ""
        text.lineSequence().forEach { raw ->
            require(raw.length <= 32768) { "Playlist line too long" }
            val line = raw.trim().trimStart('\uFEFF')
            when {
                line.startsWith("#EXTM3U") -> {
                    val a = attrs(line)
                    epg = (a["url-tvg"] ?: a["x-tvg-url"] ?: "").split(',').first().trim()
                }
                line.startsWith("#EXTINF:") -> {
                    metadata = attrs(line)
                    var quote = '\u0000'
                    var comma = -1
                    for ((i, c) in line.withIndex()) {
                        if (c == quote) quote = '\u0000' else if (quote == '\u0000' && (c == '\"' || c == '\'')) quote = c
                        else if (quote == '\u0000' && c == ',') { comma = i; break }
                    }
                    title = (if (comma >= 0) line.substring(comma + 1) else metadata["tvg-name"].orEmpty()).take(256)
                    group = metadata["group-title"].orEmpty().take(128)
                }
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').take(128)
                line.isNotEmpty() && !line.startsWith('#') -> {
                    if (Network.allowed(line, http)) {
                        val name = title.ifBlank { metadata["tvg-name"] ?: "Channel ${result.size + 1}" }
                        val guideId = metadata["tvg-id"].orEmpty().take(256)
                        val stable = "$guideId|$name|${URI(line).let { uri ->
                            val secretKeys = setOf("username", "user", "password", "pass", "token", "auth", "signature", "expires")
                            val query = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=').lowercase(Locale.ROOT) !in secretKeys }.sorted().joinToString("&")
                            "${uri.host}${uri.rawPath}?$query"
                        }}"
                        result.add(Channel(Channel.stableId(stable), name, group.ifBlank { "Other" }, line,
                            metadata["tvg-logo"].orEmpty().takeIf { Network.allowed(it, http) }.orEmpty(), guideId))
                        if (result.size > MAX_CHANNELS) throw SourceFailure(SourceProblem.CHANNEL_LIMIT)
                    }
                    metadata = emptyMap(); title = ""; group = ""
                }
            }
        }
        if (result.isEmpty()) throw SourceFailure(SourceProblem.NO_CHANNELS)
        return Lineup(result.distinctBy { it.id }, epg.takeIf { Network.allowed(it, http) }.orEmpty())
    }

    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun serverBase(value: String): String {
        val base = value.trim().trimEnd('/')
        val uri = URI(base)
        require(uri.rawQuery == null && uri.fragment == null) { "Use the server base address" }
        val leaf = uri.path.substringAfterLast('/')
        return if (leaf in setOf("player_api.php", "get.php", "xmltv.php")) base.substringBeforeLast('/') else base
    }
    fun endpoint(source: Source, file: String, action: String = ""): String {
        val base = serverBase(source.url)
        require(Network.allowed(base, source.allowHttp))
        require(URI(base).rawQuery == null && URI(base).fragment == null) { "Use the server base address" }
        return "$base/$file?username=${encoded(source.username)}&password=${encoded(source.password)}" + if (action.isEmpty()) "" else "&action=$action"
    }
    fun xtreamArray(text: String): JSONArray {
        val value = try { JSONTokener(text.trimStart('\uFEFF')).nextValue() }
        catch (_: org.json.JSONException) { throw SourceFailure(SourceProblem.RESPONSE) }
        if (value is JSONArray) return value
        if (value is JSONObject) {
            val account = value.optJSONObject("user_info") ?: value
            if (account.has("auth") && account.optString("auth") in setOf("0", "false")) throw SourceFailure(SourceProblem.AUTH)
            val status = account.optString("status")
            if (status.equals("Expired", true) || status.equals("Disabled", true) || status.equals("Banned", true)) throw SourceFailure(SourceProblem.INACTIVE)
        }
        throw SourceFailure(SourceProblem.RESPONSE)
    }
    fun xtream(source: Source): Lineup {
        val streams = xtreamArray(Network.get(endpoint(source, "player_api.php", "get_live_streams"), source.allowHttp).toString(Charsets.UTF_8))
        // Categories are optional metadata. A provider may serve live streams while
        // rejecting or omitting this separate endpoint.
        val names = runCatching {
            val categories = xtreamArray(Network.get(endpoint(source, "player_api.php", "get_live_categories"), source.allowHttp).toString(Charsets.UTF_8))
            require(categories.length() <= 20000)
            (0 until categories.length()).mapNotNull { i -> categories.optJSONObject(i)?.let { it.optString("category_id") to it.optString("category_name").take(128) } }.toMap()
        }.getOrDefault(emptyMap())
        return Lineup(xtreamChannels(streams, source, names), endpoint(source, "xmltv.php"))
    }

    fun xtreamChannels(streams: JSONArray, source: Source, names: Map<String, String>): List<Channel> {
        if (streams.length() > MAX_CHANNELS) throw SourceFailure(SourceProblem.CHANNEL_LIMIT)
        val base = serverBase(source.url)
        val channels = (0 until streams.length()).mapNotNull { index ->
            val j = streams.optJSONObject(index) ?: return@mapNotNull null
            val id = j.optString("stream_id")
            if (!id.matches(Regex("[0-9]{1,20}"))) return@mapNotNull null
            val extension = j.optString("container_extension", "ts").takeIf { it in listOf("ts", "m3u8", "mp4") } ?: "ts"
            Channel("xc:$id", j.optString("name", "Channel $id").take(256), names[j.optString("category_id")] ?: "Other",
                "$base/live/${encoded(source.username)}/${encoded(source.password)}/$id.$extension",
                j.optString("stream_icon").takeIf { Network.allowed(it, source.allowHttp) }.orEmpty(), j.optString("epg_channel_id").take(256))
        }
        if (channels.isEmpty()) throw SourceFailure(SourceProblem.NO_CHANNELS)
        return channels.distinctBy { it.id }
    }

    fun xmltv(bytes: ByteArray, channelIds: Set<String>, now: Long = System.currentTimeMillis()): Map<String, List<Programme>> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        catch (_: org.xmlpull.v1.XmlPullParserException) { /* Some parsers only expose the disabled default. */ }
        require(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) { "DTD processing must be disabled" }
        parser.setInput(Network.xmlPayload(bytes).inputStream(), null)
        val result = ArrayList<Programme>()
        var channel = ""; var title = ""; var description = ""; var start = 0L; var end = 0L
        var text = StringBuilder(); var field = ""; var inside = false; var elements = 0
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL -> throw IllegalArgumentException("DTDs are not accepted")
                XmlPullParser.ENTITY_REF -> {
                    require(parser.name in setOf("amp", "lt", "gt", "quot", "apos") || parser.name.matches(Regex("#(?:x[0-9a-fA-F]+|[0-9]+)"))) { "Custom XML entities are not accepted" }
                    if (field.isNotEmpty()) { require(text.length < 16384); text.append(parser.text.orEmpty()) }
                }
                XmlPullParser.START_TAG -> {
                    require(++elements <= 2_000_000 && parser.depth <= 32) { "Guide exceeds safety limits" }
                    when (parser.name) {
                        "programme" -> {
                            channel = parser.getAttributeValue(null, "channel").orEmpty().take(256)
                            start = timestamp(parser.getAttributeValue(null, "start").orEmpty())
                            end = timestamp(parser.getAttributeValue(null, "stop").orEmpty())
                            title = ""; description = ""; inside = true
                        }
                        "title", "desc" -> if (inside) { field = parser.name; text = StringBuilder() }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (field.isNotEmpty()) {
                    require(text.length + parser.text.length <= 16384) { "Guide field too long" }; text.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> if (inside) { if (title.isEmpty()) title = text.toString().take(512); field = "" }
                    "desc" -> if (inside) { if (description.isEmpty()) description = text.toString().take(2048); field = "" }
                    "programme" -> {
                        inside = false; field = ""
                        if (channel in channelIds && start > 0 && end > start && end > now - 3600000 && start < now + 3 * 86400000L) {
                            require(result.size < 200000) { "Guide has too many programmes" }
                            result.add(Programme(channel, title.ifBlank { "Untitled" }, start, end, description))
                        }
                    }
                }
            }
        }
        return result.groupBy { it.channel }.mapValues { (_, list) -> list.distinctBy { it.start }.sortedBy { it.start } }
    }
    fun timestamp(value: String): Long = runCatching {
        val parts = value.trim().split(Regex("\\s+"))
        val digits = parts[0]
        if (digits.length != 14) return 0L
        val zone = if (parts.size > 1) ZoneOffset.of(parts[1]) else ZoneOffset.UTC
        java.time.LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("uuuuMMddHHmmss", Locale.ROOT).withResolverStyle(java.time.format.ResolverStyle.STRICT)).toInstant(zone).toEpochMilli()
    }.getOrDefault(0L)
}
