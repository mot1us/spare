package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.net.URI
import java.net.URLEncoder
import java.time.ZoneOffset
import java.util.Locale

object Importers {
    const val MAX_CHANNELS = 100000
    private const val MAX_PLAYLIST_LINE = 32768
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private fun attrs(line: String) = attribute.findAll(line).associate { it.groupValues[1].lowercase(Locale.ROOT) to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
    fun m3u(text: String, http: Boolean): Lineup = m3u(text.lineSequence(), http)

    fun m3u(reader: Reader, http: Boolean): Lineup = m3u(playlistLines(reader), http)

    private fun playlistLines(reader: Reader): Sequence<String> = sequence {
        val buffer = CharArray(8192)
        val line = StringBuilder()
        var afterCarriageReturn = false
        fun append(from: Int, to: Int) {
            require(line.length + to - from <= MAX_PLAYLIST_LINE) { "Playlist line too long" }
            line.append(buffer, from, to - from)
        }
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            var start = 0
            for (index in 0 until count) {
                val char = buffer[index]
                if (char == '\r' || char == '\n') {
                    if (char != '\n' || !afterCarriageReturn) {
                        append(start, index)
                        yield(line.toString())
                        line.setLength(0)
                    }
                    start = index + 1
                }
                afterCarriageReturn = char == '\r'
            }
            append(start, count)
        }
        if (line.isNotEmpty()) yield(line.toString())
    }

    private fun m3u(lines: Sequence<String>, http: Boolean): Lineup {
        val result = ArrayList<Channel>()
        var headerSeen = false
        var metadata: Map<String, String> = emptyMap()
        var defaults: Map<String, String> = emptyMap()
        var title = ""
        var group = ""
        var epg = ""
        for (raw in lines) {
            require(raw.length <= MAX_PLAYLIST_LINE) { "Playlist line too long" }
            if (!headerSeen) {
                val start = raw.trimStart('\uFEFF', ' ')
                if (start.isEmpty()) continue
                if (!start.startsWith("#EXTM3U")) throw SourceFailure(SourceProblem.PLAYLIST)
                headerSeen = true
            }
            val line = raw.trim().trimStart('\uFEFF')
            when {
                line.startsWith("#EXTM3U") -> {
                    val a = attrs(line); defaults = a.filterKeys { it.startsWith("catchup") || it == "timeshift" }
                    epg = (a["url-tvg"] ?: a["x-tvg-url"] ?: "").split(',').first().trim()
                }
                line.startsWith("#EXTINF:") -> {
                    metadata = defaults + attrs(line)
                    var quote = '\u0000'
                    var comma = -1
                    for ((i, c) in line.withIndex()) {
                        if (c == quote) quote = '\u0000' else if (quote == '\u0000' && (c == '\"' || c == '\'')) quote = c
                        else if (quote == '\u0000' && c == ',') { comma = i; break }
                    }
                    title = (if (comma >= 0) line.substring(comma + 1) else metadata["tvg-name"].orEmpty()).take(256)
                    group = metadata["group-title"].orEmpty().take(128)
                }
                line == "#EXT-X-PLAYLIST-TYPE:VOD" -> metadata = metadata + ("media" to "true")
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').take(128)
                line.isNotEmpty() && !line.startsWith('#') -> {
                    if (Network.allowed(line, http)) {
                        val name = title.ifBlank { metadata["tvg-name"] ?: "Channel ${result.size + 1}" }
                        val guideId = metadata["tvg-id"].orEmpty().trim().take(256)
                        val stable = "$guideId|$name|${URI(line).let { uri ->
                            val secretKeys = setOf("username", "user", "password", "pass", "token", "auth", "signature", "expires")
                            val query = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=').lowercase(Locale.ROOT) !in secretKeys }.sorted().joinToString("&")
                            "${uri.host}${uri.rawPath}?$query"
                        }}"
                        result.add(Channel(Channel.stableId(stable), name, group.ifBlank { "Other" }, line,
                            metadata["tvg-logo"].orEmpty().takeIf { Network.allowed(it, http) }.orEmpty(), guideId,
                            (metadata["catchup-days"] ?: metadata["timeshift"])?.toIntOrNull()?.coerceIn(0, 30) ?: 0,
                            metadata["catchup"].orEmpty().ifBlank { if (metadata.containsKey("timeshift")) "shift" else "" }, metadata["catchup-source"].orEmpty().take(8192),
                            metadata["media"] == "true" || metadata.containsKey("media-dir"), reportedQuality = ProviderQuality.parse(metadata, name)))
                        if (result.size > MAX_CHANNELS) throw SourceFailure(SourceProblem.CHANNEL_LIMIT)
                    }
                    metadata = emptyMap(); title = ""; group = ""
                }
            }
        }
        if (!headerSeen) throw SourceFailure(SourceProblem.PLAYLIST)
        if (result.isEmpty()) throw SourceFailure(SourceProblem.NO_CHANNELS)
        return Lineup(result.distinctBy { it.id }, epg.takeIf { Network.allowed(it, http) }.orEmpty())
    }

    fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
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
    /**
     * Runs a provider request against the main Xtream server, then the playlist's backup server when the main one
     * cannot be reached (no connection, a timeout or a server error). Wrong credentials and cancellation never fail over.
     */
    fun <T> withBackup(source: Source, request: (Source) -> T): T = try { request(source) } catch (error: java.io.IOException) {
        val unreachable = error !is java.io.InterruptedIOException && (error !is HttpFailure || error.status >= 500)
        if (source.kind != "xtream" || source.backupUrl.isBlank() || !unreachable || Thread.currentThread().isInterrupted) throw error
        request(source.copy(url = source.backupUrl))
    }
    fun xtream(source: Source, progress: (String, Long, Long) -> Unit = { _, _, _ -> }): Lineup {
        val payload = Network.get(endpoint(source, "player_api.php", "get_live_streams"), source.allowHttp, progress = { bytes, total -> progress("Downloading channels", bytes, total) }, large = true)
        progress("Reading channel list", 0, -1)
        val streams = xtreamArray(payload.toString(Charsets.UTF_8))
        // Categories are optional metadata. A provider may serve live streams while
        // rejecting or omitting this separate endpoint.
        val names = runCatching {
            val categories = xtreamArray(Network.get(endpoint(source, "player_api.php", "get_live_categories"), source.allowHttp, progress = { bytes, total -> progress("Loading provider categories", bytes, total) }).toString(Charsets.UTF_8))
            require(categories.length() <= 20000)
            (0 until categories.length()).mapNotNull { i -> categories.optJSONObject(i)?.let { it.optString("category_id") to it.optString("category_name").take(128) } }.toMap()
        }.getOrDefault(emptyMap())
        progress("Organizing ${streams.length()} channels", 0, -1)
        val channels = xtreamChannels(streams, source, names)
        return Lineup(channels, endpoint(source, "xmltv.php"), CategoryOrder.provider(channels.map { it.group }, names.values.toList()))
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
                j.optString("stream_icon").takeIf { Network.allowed(it, source.allowHttp) }.orEmpty(), if (source.epgUrl.isBlank()) "xc:$id" else j.optString("epg_channel_id").trim().take(256).ifBlank { "xc:$id" },
                if (j.optString("tv_archive").lowercase(Locale.ROOT) in setOf("1", "true")) j.optInt("tv_archive_duration").coerceIn(0, 30) else 0, "xc", reportedQuality = ProviderQuality.parse(j, j.optString("name")), xmltvId = j.optString("epg_channel_id").trim().take(256))
        }
        if (channels.isEmpty()) throw SourceFailure(SourceProblem.NO_CHANNELS)
        return channels.distinctBy { it.id }
    }

    fun xmltv(bytes: ByteArray, channelIds: Set<String>, now: Long = System.currentTimeMillis(), historyDays: Map<String, Int> = emptyMap()): Map<String, List<Programme>> =
        xmltv(bytes.inputStream(), channelIds, now, historyDays)

    /**
     * [listed], when given, receives every channel the guide lists with its display names once the channel list ends, and returns
     * more channel ids to keep: a caller matches channels the guide names differently (a stream number, a name) there. A full
     * guide (24,000 channels, 680,000 programmes) unpacks to several hundred MB: the stream is bounded at [XMLTV_MAX_BYTES]
     * unpacked and [XMLTV_MAX_ELEMENTS] elements, and only wanted channels are kept.
     */
    fun xmltv(input: java.io.InputStream, channelIds: Set<String>, now: Long = System.currentTimeMillis(), historyDays: Map<String, Int> = emptyMap(),
        listed: ((Map<String, List<String>>) -> Set<String>)? = null, prefilter: Boolean = true): Map<String, List<Programme>> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        catch (_: org.xmlpull.v1.XmlPullParserException) { /* Some parsers only expose the disabled default. */ }
        require(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) { "DTD processing must be disabled" }
        val raw = input.buffered(); raw.mark(2); val gzip = raw.read() == 0x1f && raw.read() == 0x8b; raw.reset()
        val decoded = if (gzip) java.util.zip.GZIPInputStream(raw) else raw
        val bounded = object : java.io.FilterInputStream(decoded) {
            private var consumed = 0L
            private fun count(size: Int) { if (size > 0) consumed += size; require(consumed <= XMLTV_MAX_BYTES) { GUIDE_TOO_LARGE }; if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Guide cancelled") }
            override fun read(): Int = `in`.read().also { count(if (it < 0) 0 else 1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { count(it) }
        }
        return bounded.use { stream ->
            var wanted = channelIds
            // Display names of every listed channel, only while a caller still has to choose more channels.
            val guideNames = if (listed != null) LinkedHashMap<String, MutableList<String>>() else null
            var chosen = listed == null
            fun choose() { if (!chosen) { chosen = true; wanted = wanted + listed!!(guideNames!!); guideNames.clear() } }
            fun kept(channel: String, start: Long, end: Long) = channel in wanted && start > 0 && end > start &&
                end > now - maxOf(3600000L, (historyDays[channel] ?: 0).coerceIn(0, 30) * 86400000L) && start < now + 3 * 86400000L
            // Programmes Spare would discard skip the XML parser once the channels to keep are known.
            parser.setInput(if (!prefilter) stream else XmltvProgrammeFilter(stream, wanted = { channel -> if (!chosen) null else channel in wanted },
                window = { channel, start, stop -> kept(channel, timestamp(start), timestamp(stop)) }), null)
            var result: ProgrammeBudget? = null
            var channel = ""; var title = ""; var description = ""; var artwork = ""; var start = 0L; var end = 0L
            var text = StringBuilder(); var field = ""; var inside = false; var keep = false; var programmeDepth = 0; var elements = 0; var listing = ""
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> throw IllegalArgumentException("DTDs are not accepted")
                    XmlPullParser.ENTITY_REF -> {
                        require(parser.name in XML_ENTITIES || XML_CHARACTER.matches(parser.name)) { "Custom XML entities are not accepted" }
                        if (field.isNotEmpty() && text.length < XMLTV_FIELD_MAX) text.append(parser.text.orEmpty())
                    }
                    XmlPullParser.START_TAG -> {
                        require(++elements <= XMLTV_MAX_ELEMENTS && parser.depth <= 32) { GUIDE_TOO_LARGE }
                        when (parser.name) {
                            "channel" -> if (!inside) listing = parser.getAttributeValue(null, "id").orEmpty().trim().take(256)
                            "display-name" -> if (!inside && !chosen && listing.isNotEmpty() && (guideNames!!.size < XMLTV_LISTED_MAX || listing in guideNames) &&
                                guideNames.getOrPut(listing) { ArrayList(2) }.size < 8) { field = "display-name"; text = StringBuilder() }
                            "programme" -> {
                                choose()
                                channel = parser.getAttributeValue(null, "channel").orEmpty().trim().take(256)
                                // Most of a full guide is other channels or other days: their titles and images are never read.
                                keep = channel in wanted
                                start = if (keep) timestamp(parser.getAttributeValue(null, "start").orEmpty()) else 0L
                                end = if (keep) timestamp(parser.getAttributeValue(null, "stop").orEmpty()) else 0L
                                keep = keep && kept(channel, start, end)
                                title = ""; description = ""; artwork = ""; inside = true; programmeDepth = parser.depth
                            }
                            "icon" -> if (inside && keep && parser.depth == programmeDepth + 1 && artwork.isEmpty()) { val image = parser.getAttributeValue(null, "src").orEmpty().take(4096); if (Network.allowed(image, true)) artwork = image }
                            "image" -> if (inside && keep && parser.depth == programmeDepth + 1) { field = "image"; text = StringBuilder() }
                            "title", "desc" -> if (inside && keep) { field = parser.name; text = StringBuilder() }
                        }
                    }
                    // An overlong title or description keeps its first part; one such field threw the whole guide away.
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (field.isNotEmpty() && text.length < XMLTV_FIELD_MAX) {
                        val part = parser.text.orEmpty(); text.append(part, 0, minOf(part.length, XMLTV_FIELD_MAX - text.length))
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "display-name" -> if (field == "display-name") { text.toString().trim().take(256).takeIf { it.isNotEmpty() }?.let { guideNames?.get(listing)?.add(it) }; field = "" }
                        "channel" -> if (!inside) listing = ""
                        "title" -> if (inside) { if (title.isEmpty()) title = text.toString().take(512); field = "" }
                        "desc" -> if (inside) { if (description.isEmpty()) description = text.toString().take(2048); field = "" }
                        "image" -> if (inside && parser.depth == programmeDepth + 1 && field == "image") { val image = text.toString().trim().take(4096); if (Network.allowed(image, true)) artwork = image; field = "" }
                        "programme" -> {
                            inside = false; field = ""
                            if (keep) {
                                (result ?: ProgrammeBudget(wanted.size.coerceAtMost(ProgrammeBudget.MAX), now).also { result = it })
                                    .add(Programme(channel, title.ifBlank { "Untitled" }, start, end, description, artwork))
                            }
                        }
                    }
                }
            }
            choose()
            result?.result().orEmpty()
        }
    }
    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
    private val XML_CHARACTER = Regex("#(?:x[0-9a-fA-F]+|[0-9]+)")
    /** Unpacked bytes and XML elements a guide may have: a 680,000-programme guide is about 600 MB and 4 million elements. */
    const val XMLTV_MAX_BYTES = 1024L * 1024 * 1024
    const val XMLTV_MAX_ELEMENTS = 20_000_000
    /** Channels whose display names a caller may compare: a full third-party guide lists about 24,000. */
    const val XMLTV_LISTED_MAX = 100_000
    const val XMLTV_FIELD_MAX = 16384
    const val GUIDE_TOO_LARGE = "Guide exceeds safety limits"
    /**
     * An XMLTV time ("20261122183000 +0000"): 12 or 14 digits, then optionally a zone (Z, UTC, GMT, +hhmm or +hh:mm); 0 when it
     * is not a real time. Read digit by digit: a full guide has over a million of these, and building a pattern and a date
     * formatter for each took most of a 5-minute guide read on an onn 4K Pro.
     */
    fun timestamp(value: String): Long {
        val text = value.trim()
        var digits = 0
        while (digits < text.length && digits < 15 && text[digits] in '0'..'9') digits++
        if (digits != 12 && digits != 14) return 0L
        fun number(from: Int, length: Int): Int { var n = 0; for (i in from until from + length) n = n * 10 + (text[i] - '0'); return n }
        var at = digits
        while (at < text.length && text[at].isWhitespace()) at++
        val zone = text.substring(at)
        val offset = when {
            zone.isEmpty() || zone.equals("Z", true) || zone.equals("UTC", true) || zone.equals("GMT", true) -> 0
            (zone.length == 5 || zone.length == 6 && zone[3] == ':') && (zone[0] == '+' || zone[0] == '-') -> {
                val hours = zone.substring(1, 3); val minutes = zone.substring(zone.length - 2)
                if (!hours.all { it in '0'..'9' } || !minutes.all { it in '0'..'9' }) return 0L
                val h = hours.toInt(); val m = minutes.toInt()
                if (m > 59 || h > 18 || h == 18 && m > 0) return 0L
                (h * 3600 + m * 60) * (if (zone[0] == '-') -1 else 1)
            }
            else -> return 0L
        }
        return try {
            java.time.LocalDateTime.of(number(0, 4), number(4, 2), number(6, 2), number(8, 2), number(10, 2), if (digits == 14) number(12, 2) else 0)
                .toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) * 1000L
        } catch (_: java.time.DateTimeException) { 0L }
    }
}
