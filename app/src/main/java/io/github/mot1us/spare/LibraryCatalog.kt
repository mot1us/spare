package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small encrypted index plus bounded encrypted pages. No catalog URLs reach plaintext storage. */
class LibraryCatalog(private val store: SecureStore) {
    data class Chunk(val file: String, val count: Int, val groups: List<String>)
    data class Snapshot(val updated: Long, val chunks: List<Chunk>, val count: Int, val labels: Map<String, String>, val complete: Boolean = true) {
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
    private fun prefix(source: Source, mode: String): String { require(mode in setOf("movie", "series")); return "catalog-${source.id}-$mode-" }
    fun cached(source: Source, mode: String): Snapshot? {
        val prefix = prefix(source, mode)
        val bytes = store.read("${prefix}index.enc")
        if (bytes != null) {
            val json = JSONObject(bytes.toString(Charsets.UTF_8)); require(json.getInt("schema") == 1)
            val pages = json.getJSONArray("pages"); require(pages.length() <= 2000)
            val chunks = List(pages.length()) { i -> val p = pages.getJSONObject(i); val groups = p.getJSONArray("groups"); Chunk(p.getString("file"), p.getInt("count"), List(groups.length()) { groups.getString(it) }) }
            val names = json.getJSONObject("labels"); val labels = names.keys().asSequence().associateWith { names.getString(it) }
            val snapshot = Snapshot(json.getLong("updated"), chunks, chunks.sumOf { it.count }, labels)
            store.pruneCatalog(prefix, chunks.map { it.file }.toSet() + "${prefix}index.enc")
            return snapshot
        }
        // One-time migration runs on the repository worker, never on the UI thread.
        val old = store.catalog(source, mode) ?: return null
        val writer = Writer(source, mode, old.first)
        old.second.chunked(64).forEach(writer::append)
        return writer.finish(emptyMap()).also { store.delete("library-${source.id}-$mode.enc") }
    }
    inner class Writer(source: Source, mode: String, private val stamp: Long = System.currentTimeMillis()) {
        private val prefix = prefix(source, mode)
        private val generation = UUID.randomUUID().toString()
        private val chunks = mutableListOf<Chunk>()
        init { synchronized(publishLock) { writers[prefix] = generation } }
        fun append(entries: List<OnDemand>) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Catalog import cancelled")
            if (entries.isEmpty()) return
            require(entries.size <= 64)
            val file = "$prefix$generation-${chunks.size}.enc"
            store.write(file, JSONArray(entries.map { it.json() }).toString().toByteArray())
            synchronized(memory) { memory[file] = entries }
            chunks.add(Chunk(file, entries.size, entries.map { it.category }.distinct()))
        }
        fun snapshot(labels: Map<String, String>) = Snapshot(stamp, chunks.toList(), chunks.sumOf { it.count }, labels, complete = false)
        fun finish(labels: Map<String, String>): Snapshot {
            val value = snapshot(labels).copy(complete = true)
            val pages = JSONArray(chunks.map { JSONObject().put("file", it.file).put("count", it.count).put("groups", JSONArray(it.groups)) })
            synchronized(publishLock) {
                if (Thread.currentThread().isInterrupted || writers[prefix] != generation) throw java.io.InterruptedIOException("Catalog import superseded")
                store.write("${prefix}index.enc", JSONObject().put("schema", 1).put("updated", stamp).put("pages", pages).put("labels", JSONObject(labels)).toString().toByteArray())
            }
            return value
        }
    }
    fun query(snapshot: Snapshot, group: String?, query: String, cursor: Int = 0, limit: Int = 80): Page {
        require(limit in 1..160)
        val key = ChannelSearch.key(query); val result = mutableListOf<OnDemand>(); var offset = 0
        for (chunk in snapshot.chunks) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Catalog query cancelled")
            if (offset + chunk.count <= cursor || (group != null && chunk.groups.none { it == group || snapshot.label(it) == group })) { offset += chunk.count; continue }
            val items = synchronized(memory) { memory[chunk.file] } ?: run {
                val bytes = store.read(chunk.file) ?: error("Catalog page missing")
                val array = JSONArray(bytes.toString(Charsets.UTF_8)); require(array.length() <= 64)
                List(array.length()) { OnDemand.from(array.getJSONObject(it)) }.also { synchronized(memory) { memory[chunk.file] = it } }
            }
            items.forEachIndexed { index, item ->
                if (offset + index >= cursor && (group == null || item.category == group || snapshot.label(item.category) == group) && (key.isEmpty() || ChannelSearch.matches(item.title, key))) {
                    result.add(item.copy(category = snapshot.label(item.category)))
                    if (result.size == limit) { val next = offset + index + 1; return Page(result, next, next < snapshot.count) }
                }
            }
            offset += chunk.count
        }
        return Page(result, snapshot.count, false)
    }
}
