package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small encrypted index plus bounded encrypted pages. No catalog URLs reach plaintext storage. */
class LibraryCatalog(private val store: SecureStore) {
    /** Only searchable/display fields. Reconstruct standard URLs instead of repeating credentials per title. */
    data class Title(val id: String, val name: String, val category: String, val extension: String, val year: String, val poster: String, val direct: String, val externalId: String = "", val savedCanonical: String = "", val added: Long = 0, val rating: Float = 0f) {
        val key = ChannelSearch.key(name)
        /** Computed once at import and saved; restoring a large catalog must not repeat the title cleanup per title. */
        val canonical = savedCanonical.ifEmpty { VodStart.titleKey(OnDemand(id, name, category, year = year)) }
        fun item(source: Source) = OnDemand(id, name, category, if (id.startsWith("series:")) "" else direct.ifBlank { VodCatalog.streamUrl(source, "movie", id.substringAfter(':'), extension) }, id.substringBefore(':'), poster = poster, year = year, externalId = externalId, playlistId = source.id, added = added, rating = rating)
        fun json() = JSONArray(listOf(id, name, category, extension, year, poster, direct, externalId, canonical, added.toString(), rating.toString()))
        companion object {
            private val idPattern = Regex("(?:movie|series):[0-9]{1,20}")
            fun from(item: OnDemand, source: Source): Title {
                val ext = item.url.substringBefore('?').substringAfterLast('.', "mp4").lowercase(java.util.Locale.ROOT)
                val standard = if (item.kind == "movie") VodCatalog.streamUrl(source, "movie", item.id.substringAfter(':'), ext) else ""
                return Title(item.id, item.title, item.category, ext, item.year, item.poster, item.url.takeUnless { it == standard }.orEmpty(), item.externalId, added = item.added, rating = item.rating)
            }
            fun from(json: JSONArray): Title {
                require(json.length() in 7..11 && json.getString(0).matches(idPattern))
                return Title(json.getString(0), json.getString(1).take(256), json.getString(2).take(128), json.getString(3).take(16), json.getString(4).take(16), json.getString(5).take(4096), json.getString(6).take(16384), json.optString(7).take(128), json.optString(8).take(256), json.optString(9).toLongOrNull() ?: 0L, json.optString(10).toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f)
            }
        }
    }
    data class Chunk(val file: String, val count: Int, val groups: List<String>, val keys: List<String> = emptyList(), val titles: List<Title> = emptyList())
    data class Snapshot(val updated: Long, val chunks: List<Chunk>, val count: Int, val labels: Map<String, String>, val complete: Boolean = true, val scopeGroup: String? = null, val source: Source? = null,
        /** A saved category lives in [CatalogDb]: its titles are read a page at a time, never held in [chunks]. */
        val dbScope: String? = null, val dbGen: String? = null,
        /** Titles in [scopeGroup] when this is one category's view of the whole catalog; [count] stays the catalog total for paging. */
        val scopeCount: Int? = null, val schema: Int = SCHEMA) {
        val shown get() = scopeCount ?: count
        fun label(group: String) = labels[group.removePrefix("Category ")] ?: group
        val categories: Map<String, String> get() = chunks.flatMap { it.groups }.distinct().associateWith(::label)
        val groups: List<String> get() = categories.values.distinct()
    }
    data class Page(val entries: List<OnDemand>, val cursor: Int, val more: Boolean)
    private val memory = object : LinkedHashMap<String, List<OnDemand>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OnDemand>>?) = size > 16
    }
    private data class LoadKey(val source: Source, val mode: String, val scope: String)
    private val jobs = SharedJobs<LoadKey, Snapshot>(java.util.concurrent.Executors.newFixedThreadPool(2) { task -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "spare-catalog-import") })
    // A category someone is waiting on must never queue behind whole-catalog search warming.
    private val categoryJobs = SharedJobs<LoadKey, Snapshot>(java.util.concurrent.Executors.newFixedThreadPool(3) { task -> Thread(task, "spare-catalog-category") })
    private val restoreLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
    private fun lookupKey(source: Source, mode: String) = "${source.id}:${source.providerKey()}:$mode"
    fun invalidate(sourceId: String) {
        runCatching { store.catalogDb.deleteSource(sourceId) }
        jobs.cancel { it.source.id == sourceId }; categoryJobs.cancel { it.source.id == sourceId }
        synchronized(publishLock) { writers.keys.removeAll { it.startsWith("catalog-$sourceId-") } }
        synchronized(snapshots) { snapshots.keys.removeAll { it.startsWith("catalog-$sourceId-") } }
        synchronized(memory) { memory.keys.removeAll { it.startsWith("catalog-$sourceId-") } }
        lookup.keys.removeAll { it.startsWith("$sourceId:") }; missing.keys.removeAll { it.startsWith("$sourceId:") }
    }
    fun close() { jobs.close(); categoryJobs.close() }
    private val publishLock = Any()
    private val writers = mutableMapOf<String, String>()
    private val snapshots = object : LinkedHashMap<String, Snapshot>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?) = size > 8
    }
    private val lookup = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, List<Title>>>()
    private val missing = java.util.concurrent.ConcurrentHashMap<String, Long>()
    var changed: (() -> Unit)? = null
    fun memory(source: Source, mode: String): Snapshot? = synchronized(snapshots) { snapshots[prefix(source, mode)]?.takeIf { it.source == source } }
    /** One category read straight from the whole catalog already in memory: no provider request and nothing to decrypt. */
    fun category(source: Source, mode: String, scope: String, names: Map<String, String>): Snapshot? {
        val full = memory(source, mode)?.takeIf { it.complete && it.dbScope == null } ?: return null
        val group = "Category $scope"
        val size = full.chunks.sumOf { chunk -> if (group in chunk.groups) chunk.titles.count { it.category == group } else 0 }
        return if (size == 0) null else full.copy(labels = names, scopeGroup = group, scopeCount = size)
    }
    fun matches(source: Source, request: VodStart.Request): List<OnDemand> {
        val kinds = if (request.episode == null) listOf("movie", "series") else listOf("series")
        val key = ChannelSearch.key(request.query)
        return VodStart.matches(request, kinds.flatMap { kind -> lookup[lookupKey(source, kind)]?.get(key).orEmpty() }.filter { available(source, it.id) }.map { it.item(source) })
    }
    fun markMissing(source: Source, item: OnDemand) { missing["${source.id}:${item.id}"] = System.currentTimeMillis() + 3600000; changed?.invoke() }
    private fun available(source: Source, id: String) = (missing["${source.id}:$id"] ?: 0) <= System.currentTimeMillis()
    private fun index(source: Source, mode: String, titles: List<Title>) {
        val map = lookup.getOrPut(lookupKey(source, mode)) { java.util.concurrent.ConcurrentHashMap() }
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
        val lock = restoreLocks.getOrPut(prefix(source, mode, scope)) { java.util.concurrent.locks.ReentrantLock() }
        lock.lockInterruptibly()
        try { return restore(source, mode, scope) } finally { lock.unlock() }
    }
    private fun restore(source: Source, mode: String, scope: String): Snapshot? {
        val prefix = prefix(source, mode, scope)
        synchronized(snapshots) { snapshots[prefix]?.takeIf { it.source == source } }?.let { return it }
        val restoreBegan = Timings.now()
        if (scope.isNotEmpty()) {
            val key = store.catalogDb.scopeKey(source, mode, scope)
            val meta = store.catalogDb.meta(key) ?: return null
            if (meta.provider != source.providerKey()) return null
            return Snapshot(meta.updated, emptyList(), meta.count, meta.labels, complete = meta.complete, source = source, dbScope = key, dbGen = meta.gen)
                .also { remember(prefix, it); Timings.since("Saved category", restoreBegan) }
        }
        val bytes = store.read("${prefix}index.enc")
        if (bytes != null) {
            val json = JSONObject(bytes.toString(Charsets.UTF_8)); require(json.getInt("schema") in 1..SCHEMA)
            if (json.has("provider")) { if (json.getString("provider") != source.providerKey()) return null }
            else if (!store.legacyProviderMatches(source)) return null
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
            val snapshot = Snapshot(json.getLong("updated"), chunks, chunks.sumOf { it.count }, labels, complete = json.optBoolean("complete", true), source = source, schema = json.getInt("schema"))
            index(source, mode, chunks.flatMap { it.titles }); remember(prefix, snapshot)
            Timings.since("Saved category", restoreBegan)
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
    /** Shared by background warming, explicit search and Library; consumers receive useful partial pages. */
    fun load(source: Source, mode: String, scope: String = "", force: Boolean = false, labels: Map<String, String> = emptyMap(), progress: (Snapshot) -> Unit = {}): Snapshot {
        fun fresh(value: Snapshot?) = value?.complete == true && value.schema >= SCHEMA && value.chunks.all { it.titles.size == it.count } && System.currentTimeMillis() - value.updated in 0 until 6 * 3600000L
        val saved = cached(source, mode, scope)
        if (saved != null) progress(saved)
        if (!force && fresh(saved)) return saved!!
        return (if (scope.isEmpty()) jobs else categoryJobs).await(LoadKey(source.copy(name = ""), mode, scope), progress) { publish ->
            val latest = cached(source, mode, scope)
            if (!force && fresh(latest)) return@await latest!!
            val writer = Writer(source, mode, scope = scope, indexOnly = true)
            var published = 0L; var checkpoint = 0L
            VodCatalog.stream(source, mode, scope.takeIf { it.isNotEmpty() }, lean = true) { batch ->
                writer.append(batch)
                val now = android.os.SystemClock.elapsedRealtime()
                if (published == 0L || now - published >= 750) { publish(writer.snapshot(labels)); published = now }
                if (latest?.complete != true && now - checkpoint >= 20000) { writer.checkpoint(labels); checkpoint = now }
            }
            writer.finish(labels).also(publish)
        }
    }
    inner class Writer(private val source: Source, private val mode: String, private val stamp: Long = System.currentTimeMillis(), private val scope: String = "", private val indexOnly: Boolean = false) {
        private val prefix = prefix(source, mode, scope)
        private val generation = UUID.randomUUID().toString()
        private val chunks = mutableListOf<Chunk>()
        private val pending = ArrayList<OnDemand>(512)
        private var count = 0
        private val dbMode = scope.isNotEmpty()
        private val dbKey = if (dbMode) store.catalogDb.scopeKey(source, mode, scope) else ""
        init { synchronized(publishLock) { writers[prefix] = generation } }
        private fun requireCurrent() {
            if (Thread.currentThread().isInterrupted || writers[prefix] != generation) throw java.io.InterruptedIOException("Catalog import superseded")
        }
        fun append(entries: List<OnDemand>) = synchronized(publishLock) {
            requireCurrent()
            if (entries.isEmpty()) return@synchronized
            require(entries.size <= 64)
            pending.addAll(entries)
            // Show the first handful immediately, then amortize encryption/fsync over larger pages.
            if ((if (dbMode) count == 0 else chunks.isEmpty()) || pending.size >= 512) flush()
        }
        private fun flush() {
            if (pending.isEmpty()) return
            val pages = pending.toList().chunked(512); pending.clear()
            if (dbMode) {
                pages.forEach { entries ->
                    val titles = entries.map { Title.from(it, source) }
                    store.catalogDb.insert(dbKey, generation, count, titles)
                    index(source, mode, titles)
                    count += entries.size
                }
                return
            }
            pages.forEach { entries ->
                val file = if (indexOnly) "" else "$prefix$generation-${chunks.size}.enc"
                if (!indexOnly) { store.write(file, JSONArray(entries.map { it.json() }).toString().toByteArray()); synchronized(memory) { memory[file] = entries } }
                val titles = entries.map { Title.from(it, source) }
                chunks.add(Chunk(file, entries.size, entries.map { it.category }.distinct(), titles.map { it.key }, titles))
                index(source, mode, titles)
                count += entries.size
            }
        }
        fun snapshot(labels: Map<String, String>): Snapshot = synchronized(publishLock) {
            // Invalidation must cover partial memory publication as well as the final disk index.
            requireCurrent(); flush()
            (if (dbMode) Snapshot(stamp, emptyList(), count, labels, complete = false, source = source, dbScope = dbKey, dbGen = generation)
            else Snapshot(stamp, chunks.toList(), count, labels, complete = false, source = source)).also { remember(prefix, it) }
        }
        fun checkpoint(labels: Map<String, String>): Snapshot = save(labels, false)
        fun finish(labels: Map<String, String>): Snapshot = save(labels, true)
        private fun save(labels: Map<String, String>, complete: Boolean): Snapshot {
            val value = snapshot(labels).copy(complete = complete)
            if (dbMode) {
                synchronized(publishLock) {
                    requireCurrent()
                    store.catalogDb.publish(dbKey, generation, stamp, complete, count, source.providerKey(), labels)
                    remember(prefix, value)
                }
                return value
            }
            val pages = JSONArray(chunks.map { JSONObject().put("file", it.file).put("count", it.count).put("groups", JSONArray(it.groups)).put("titles", JSONArray(it.titles.map(Title::json))) })
            synchronized(publishLock) {
                requireCurrent()
                store.write("${prefix}index.enc", JSONObject().put("schema", SCHEMA).put("provider", source.providerKey()).put("updated", stamp).put("complete", complete).put("pages", pages).put("labels", JSONObject(labels)).toString().toByteArray())
                if (complete && scope.isEmpty()) {
                    lookup[lookupKey(source, mode)] = java.util.concurrent.ConcurrentHashMap(chunks.flatMap { it.titles }.groupBy { it.canonical })
                }
                remember(prefix, value)
            }
            return value
        }
    }
    /** Sorted browsing materializes one category's matches once, then pages through that list. */
    // Compared by identity: a Snapshot holds every title, so structural equality would rescan the catalog.
    private class Sorted(val snapshot: Snapshot, val group: String?, val query: String, val sort: String, val items: List<OnDemand>) {
        fun matches(other: Snapshot, group: String?, query: String, sort: String) = snapshot === other && this.group == group && this.query == query && this.sort == sort
    }
    @Volatile private var sorted: Sorted? = null
    /** One saved category: read and decrypt only the rows a page needs. */
    private fun queryStored(snapshot: Snapshot, query: String, cursor: Int, limit: Int): Page {
        val source = snapshot.source!!; val key = ChannelSearch.key(query); val result = mutableListOf<OnDemand>(); var offset = cursor
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Catalog query cancelled")
            val rows = store.catalogDb.titles(snapshot.dbScope!!, snapshot.dbGen!!, offset, 160)
            if (rows.isEmpty()) break
            rows.forEachIndexed { index, title ->
                if (available(source, title.id) && (key.isEmpty() || title.key.contains(key))) {
                    result.add(title.item(source).let { it.copy(category = snapshot.label(it.category)) })
                    if (result.size == limit) { val next = offset + index + 1; return Page(result, next, next < snapshot.count) }
                }
            }
            offset += rows.size
        }
        return Page(result, snapshot.count, false)
    }
    fun query(snapshot: Snapshot, group: String?, query: String, cursor: Int = 0, limit: Int = 80, sort: String = SORT_PROVIDER): Page {
        require(limit in 1..160 && sort in SORTS)
        if (sort != SORT_PROVIDER) {
            val all = sorted?.takeIf { it.matches(snapshot, group, query, sort) }?.items ?: run {
                val matches = ArrayList<OnDemand>(); var next = 0
                while (true) { val page = query(snapshot, group, query, next, 160); matches.addAll(page.entries); if (!page.more || page.entries.isEmpty()) break; next = page.cursor }
                sortTitles(matches, sort).also { sorted = Sorted(snapshot, group, query, sort, it) }
            }
            val end = minOf(all.size, cursor + limit)
            return Page(if (cursor < end) all.subList(cursor, end).toList() else emptyList(), end, end < all.size)
        }
        if (snapshot.dbScope != null && snapshot.dbGen != null && snapshot.source != null) return queryStored(snapshot, query, cursor, limit)
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
    companion object {
        /** 4 adds each title's provider "added" time and rating; older saved catalogs are refreshed once. */
        const val SCHEMA = 4
        const val SORT_PROVIDER = "Provider order"
        const val SORT_TITLE = "A–Z"
        const val SORT_NEWEST = "Newest"
        val SORTS = listOf(SORT_PROVIDER, SORT_TITLE, SORT_NEWEST)
        private val providerPrefix = Regex("^\\s*[A-Za-z0-9]{2,4}\\s*[|:]\\s*")
        private val article = Regex("^(?:the|a|an)\\s+", RegexOption.IGNORE_CASE)
        /** A–Z ignores provider tags ("EN | ") and leading articles ("The Batman" sorts under B). */
        internal fun sortKey(title: String) = ChannelSearch.key(article.replace(providerPrefix.replace(title, ""), ""))
        internal fun sortTitles(items: List<OnDemand>, sort: String): List<OnDemand> {
            if (sort != SORT_TITLE && sort != SORT_NEWEST) return items
            // sortedBy would recompute the key on every comparison (millions of regex runs for a big category).
            class Keyed(val item: OnDemand, val key: String, val year: Int)
            val keyed = items.map { Keyed(it, sortKey(it.title), if (sort == SORT_NEWEST) it.year.toIntOrNull() ?: 0 else 0) }
            return (if (sort == SORT_TITLE) keyed.sortedBy { it.key } else keyed.sortedWith(compareByDescending<Keyed> { it.year }.thenBy { it.key })).map { it.item }
        }
    }
}
