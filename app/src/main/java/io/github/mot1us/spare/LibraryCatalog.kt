package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small encrypted index plus bounded encrypted pages. No catalog URLs reach plaintext storage. */
class LibraryCatalog(private val store: SecureStore) {
    /** Only searchable/display fields. Reconstruct standard URLs instead of repeating credentials per title. */
    data class Title(val id: String, val name: String, val category: String, val extension: String, val year: String, val poster: String, val direct: String, val externalId: String = "") {
        val key = ChannelSearch.key(name)
        val canonical = VodStart.titleKey(OnDemand(id, name, category, year = year))
        fun item(source: Source) = OnDemand(id, name, category, if (id.startsWith("series:")) "" else direct.ifBlank { VodCatalog.streamUrl(source, "movie", id.substringAfter(':'), extension) }, id.substringBefore(':'), poster = poster, year = year, externalId = externalId)
        fun json() = JSONArray(listOf(id, name, category, extension, year, poster, direct, externalId))
        companion object {
            fun from(item: OnDemand, source: Source): Title {
                val ext = item.url.substringBefore('?').substringAfterLast('.', "mp4").lowercase(java.util.Locale.ROOT)
                val standard = if (item.kind == "movie") VodCatalog.streamUrl(source, "movie", item.id.substringAfter(':'), ext) else ""
                return Title(item.id, item.title, item.category, ext, item.year, item.poster, item.url.takeUnless { it == standard }.orEmpty(), item.externalId)
            }
            fun from(json: JSONArray): Title {
                require(json.length() in 7..8 && json.getString(0).matches(Regex("(?:movie|series):[0-9]{1,20}")))
                return Title(json.getString(0), json.getString(1).take(256), json.getString(2).take(128), json.getString(3).take(16), json.getString(4).take(16), json.getString(5).take(4096), json.getString(6).take(16384), json.optString(7).take(128))
            }
        }
    }
    data class Chunk(val file: String, val count: Int, val groups: List<String>, val keys: List<String> = emptyList(), val titles: List<Title> = emptyList())
    data class Snapshot(val updated: Long, val chunks: List<Chunk>, val count: Int, val labels: Map<String, String>, val complete: Boolean = true, val scopeGroup: String? = null, val source: Source? = null) {
        fun label(group: String) = labels[group.removePrefix("Category ")] ?: group
        val categories: Map<String, String> get() = chunks.flatMap { it.groups }.distinct().associateWith(::label)
        val groups: List<String> get() = categories.values.distinct()
    }
    data class Page(val entries: List<OnDemand>, val cursor: Int, val more: Boolean)
    private val memory = object : LinkedHashMap<String, List<OnDemand>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OnDemand>>?) = size > 16
    }
    private val publishLock = Any()
    private val writers = mutableMapOf<String, String>()
    private val snapshots = object : LinkedHashMap<String, Snapshot>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?) = size > 8
    }
    private val lookup = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, List<Title>>>()
    private val missing = java.util.concurrent.ConcurrentHashMap<String, Long>()
    var changed: (() -> Unit)? = null
    fun memory(source: Source, mode: String): Snapshot? = synchronized(snapshots) { snapshots[prefix(source, mode)]?.takeIf { it.source == source } }
    fun matches(source: Source, request: VodStart.Request): List<OnDemand> {
        val kinds = if (request.episode == null) listOf("movie", "series") else listOf("series")
        val key = ChannelSearch.key(request.query)
        return VodStart.matches(request, kinds.flatMap { kind -> lookup["${source.id}:$kind"]?.get(key).orEmpty() }.filter { available(source, it.id) }.map { it.item(source) })
    }
    fun markMissing(source: Source, item: OnDemand) { missing["${source.id}:${item.id}"] = System.currentTimeMillis() + 3600000; changed?.invoke() }
    private fun available(source: Source, id: String) = (missing["${source.id}:$id"] ?: 0) <= System.currentTimeMillis()
    private fun index(source: Source, mode: String, titles: List<Title>) {
        val map = lookup.getOrPut("${source.id}:$mode") { java.util.concurrent.ConcurrentHashMap() }
        titles.forEach { title -> map.compute(title.canonical) { _, old -> (old.orEmpty().filter { it.id != title.id } + title).takeLast(64) } }
    }
    private fun remember(key: String, snapshot: Snapshot) {
        synchronized(snapshots) {
            val previous = snapshots[key]
            if (previous == null || previous.source != snapshot.source || snapshot.complete || !previous.complete || snapshot.count >= previous.count) snapshots[key] = snapshot
        }
        changed?.invoke()
    }
    private fun prefix(source: Source, mode: String, scope: String = ""): String { require(mode in setOf("movie", "series") && (scope.isEmpty() || scope.matches(Regex("[0-9]{1,20}")))); return "catalog-${source.id}-$mode-${if (scope.isEmpty()) "" else "category-$scope-"}" }
    fun cached(source: Source, mode: String, scope: String = ""): Snapshot? {
        val prefix = prefix(source, mode, scope)
        synchronized(snapshots) { snapshots[prefix]?.takeIf { it.source == source } }?.let { return it }
        val bytes = store.read("${prefix}index.enc")
        if (bytes != null) {
            val json = JSONObject(bytes.toString(Charsets.UTF_8)); require(json.getInt("schema") in 1..2)
            val pages = json.getJSONArray("pages"); require(pages.length() <= 2000)
            val chunks = List(pages.length()) { i ->
                val p = pages.getJSONObject(i); val groups = p.getJSONArray("groups"); val keys = p.optJSONArray("keys")
                val count = p.getInt("count"); require(count in 1..512 && (keys == null || keys.length() == count))
                val records = p.optJSONArray("titles")
                require(records == null || records.length() == count)
                val titles = records?.let { values -> List(values.length()) { Title.from(values.getJSONArray(it)) } }.orEmpty()
                Chunk(p.getString("file"), count, List(groups.length()) { groups.getString(it) }, if (titles.isNotEmpty()) titles.map { it.key } else keys?.let { values -> List(values.length()) { values.getString(it) } }.orEmpty(), titles)
            }
            val names = json.getJSONObject("labels"); val labels = names.keys().asSequence().associateWith { names.getString(it) }
            val snapshot = Snapshot(json.getLong("updated"), chunks, chunks.sumOf { it.count }, labels, complete = json.optBoolean("complete", true), source = source)
            index(source, mode, chunks.flatMap { it.titles }); remember(prefix, snapshot)
            if (scope.isNotEmpty()) store.pruneCatalog(prefix, chunks.map { it.file }.toSet() + "${prefix}index.enc")
            return snapshot
        }
        // One-time migration runs on the repository worker, never on the UI thread.
        if (scope.isNotEmpty()) return null
        val old = store.catalog(source, mode) ?: return null
        val writer = Writer(source, mode, old.first)
        old.second.chunked(64).forEach(writer::append)
        return writer.finish(emptyMap()).also { store.delete("library-${source.id}-$mode.enc") }
    }
    inner class Writer(private val source: Source, private val mode: String, private val stamp: Long = System.currentTimeMillis(), private val scope: String = "", private val indexOnly: Boolean = false) {
        private val prefix = prefix(source, mode, scope)
        private val generation = UUID.randomUUID().toString()
        private val chunks = mutableListOf<Chunk>()
        private val pending = ArrayList<OnDemand>(512)
        private var count = 0
        init { synchronized(publishLock) { writers[prefix] = generation } }
        fun append(entries: List<OnDemand>) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Catalog import cancelled")
            if (entries.isEmpty()) return
            require(entries.size <= 64)
            pending.addAll(entries)
            // Show the first handful immediately, then amortize encryption/fsync over larger pages.
            if (chunks.isEmpty() || pending.size >= 512) flush()
        }
        private fun flush() {
            if (pending.isEmpty()) return
            val pages = pending.toList().chunked(512); pending.clear()
            pages.forEach { entries ->
                val file = if (indexOnly) "" else "$prefix$generation-${chunks.size}.enc"
                if (!indexOnly) { store.write(file, JSONArray(entries.map { it.json() }).toString().toByteArray()); synchronized(memory) { memory[file] = entries } }
                val titles = entries.map { Title.from(it, source) }
                chunks.add(Chunk(file, entries.size, entries.map { it.category }.distinct(), titles.map { it.key }, titles))
                index(source, mode, titles)
                count += entries.size
            }
        }
        fun snapshot(labels: Map<String, String>): Snapshot { flush(); return Snapshot(stamp, chunks.toList(), count, labels, complete = false, source = source).also { remember(prefix, it) } }
        fun checkpoint(labels: Map<String, String>): Snapshot = save(labels, false)
        fun finish(labels: Map<String, String>): Snapshot = save(labels, true)
        private fun save(labels: Map<String, String>, complete: Boolean): Snapshot {
            val value = snapshot(labels).copy(complete = complete)
            val pages = JSONArray(chunks.map { JSONObject().put("file", it.file).put("count", it.count).put("groups", JSONArray(it.groups)).put("titles", JSONArray(it.titles.map(Title::json))) })
            synchronized(publishLock) {
                if (Thread.currentThread().isInterrupted || writers[prefix] != generation) throw java.io.InterruptedIOException("Catalog import superseded")
                store.write("${prefix}index.enc", JSONObject().put("schema", 2).put("updated", stamp).put("complete", complete).put("pages", pages).put("labels", JSONObject(labels)).toString().toByteArray())
            }
            if (complete && scope.isEmpty()) {
                lookup["${source.id}:$mode"] = java.util.concurrent.ConcurrentHashMap(chunks.flatMap { it.titles }.groupBy { it.canonical })
            }
            remember(prefix, value)
            return value
        }
    }
    fun query(snapshot: Snapshot, group: String?, query: String, cursor: Int = 0, limit: Int = 80): Page {
        require(limit in 1..160)
        val key = ChannelSearch.key(query); val result = mutableListOf<OnDemand>(); var offset = 0
        val selectedGroup = group ?: snapshot.scopeGroup
        for (chunk in snapshot.chunks) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Catalog query cancelled")
            if (offset + chunk.count <= cursor || (selectedGroup != null && chunk.groups.none { it == selectedGroup || snapshot.label(it) == selectedGroup }) ||
                (key.isNotEmpty() && chunk.keys.isNotEmpty() && chunk.keys.none { it.contains(key) })) { offset += chunk.count; continue }
            val items = if (chunk.titles.isNotEmpty() && snapshot.source != null) chunk.titles.map { it.item(snapshot.source) } else synchronized(memory) { memory[chunk.file] } ?: run {
                val bytes = store.read(chunk.file) ?: error("Catalog page missing")
                val array = JSONArray(bytes.toString(Charsets.UTF_8)); require(array.length() <= 512)
                List(array.length()) { OnDemand.from(array.getJSONObject(it)) }.also { synchronized(memory) { memory[chunk.file] = it } }
            }
            items.forEachIndexed { index, item ->
                if (offset + index >= cursor && (snapshot.source == null || available(snapshot.source, item.id)) && (selectedGroup == null || item.category == selectedGroup || snapshot.label(item.category) == selectedGroup) && (key.isEmpty() || (chunk.keys.getOrNull(index) ?: ChannelSearch.key(item.title)).contains(key))) {
                    result.add(item.copy(category = snapshot.label(item.category)))
                    if (result.size == limit) { val next = offset + index + 1; return Page(result, next, next < snapshot.count) }
                }
            }
            offset += chunk.count
        }
        return Page(result, snapshot.count, false)
    }
}
