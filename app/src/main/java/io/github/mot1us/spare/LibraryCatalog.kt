package io.github.mot1us.spare

import org.json.JSONArray
import java.util.UUID

/**
 * Movies and Series catalogs live in [CatalogDb], one encrypted row per title, and are read a page or a row at a time.
 * Memory holds only a compact [Index] per catalog (search key, category, date and rating per title) so filtering,
 * search and the rows home never need the titles themselves until they are on screen. No catalog URLs reach plaintext storage.
 */
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
            /** One saved title as its list of text fields (see [json]); older catalogs have fewer of them. */
            fun from(values: List<String>): Title {
                require(values.size in 7..11 && values[0].matches(idPattern))
                fun at(index: Int) = values.getOrNull(index).orEmpty()
                return Title(values[0], values[1].take(256), values[2].take(128), values[3].take(16), values[4].take(16), values[5].take(4096), values[6].take(16384), at(7).take(128), at(8).take(256), at(9).toLongOrNull() ?: 0L, at(10).toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f)
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
        val scopeCount: Int? = null, val schema: Int = SCHEMA,
        /** The whole Movies or Series catalog, saved in [CatalogDb] with an [Index] in memory. */
        val whole: Boolean = false) {
        val shown get() = scopeCount ?: count
        fun label(group: String) = labels[group.removePrefix("Category ")] ?: group
        val categories: Map<String, String> get() = chunks.flatMap { it.groups }.distinct().associateWith(::label)
        val groups: List<String> get() = categories.values.distinct()
    }
    data class Page(val entries: List<OnDemand>, val cursor: Int, val more: Boolean)
    /** What browsing filters on, per title position, for a whole catalog. About 100 bytes a title instead of every field. */
    class Index(val keys: Array<String>, private val canonical: IntArray, val cats: Array<String>, val added: LongArray, val rating: FloatArray) {
        val size get() = keys.size
        val counts: Map<String, Int> = HashMap<String, Int>().also { counts -> cats.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        /** Positions whose matching key (see [Title.canonical]) has this hash; the caller checks the titles themselves. */
        fun canonical(key: String): List<Int> { val hash = key.hashCode(); return (0 until size).filter { canonical[it] == hash }.takeLast(64) }
        fun bytes(gen: String): ByteArray {
            val out = java.io.ByteArrayOutputStream(size * 64)
            java.io.DataOutputStream(out).use { data ->
                data.writeInt(2); data.writeUTF(gen); data.writeInt(size)
                for (pos in 0 until size) { data.writeUTF(keys[pos]); data.writeInt(canonical[pos]); data.writeUTF(cats[pos]); data.writeLong(added[pos]); data.writeFloat(rating[pos]) }
            }
            return out.toByteArray()
        }
        companion object {
            fun read(bytes: ByteArray, gen: String): Index? = runCatching {
                java.io.DataInputStream(bytes.inputStream()).use { data ->
                    require(data.readInt() == 2); if (data.readUTF() != gen) return@runCatching null
                    val size = data.readInt(); require(size in 0..500_000)
                    val builder = Builder(size)
                    repeat(size) { builder.add(data.readUTF().take(256), data.readInt(), data.readUTF().take(128), data.readLong(), data.readFloat().takeIf { it.isFinite() } ?: 0f) }
                    builder.build()
                }
            }.getOrNull()
        }
        class Builder(capacity: Int = 1024) {
            private val keys = ArrayList<String>(capacity); private val cats = ArrayList<String>(capacity)
            private var canonical = IntArray(capacity); private var added = LongArray(capacity); private var rating = FloatArray(capacity)
            private val shared = HashMap<String, String>()
            val size get() = keys.size
            fun add(title: Title) = add(title.key, title.canonical.hashCode(), title.category, title.added, title.rating)
            fun add(key: String, canonicalHash: Int, category: String, date: Long, score: Float) {
                val at = keys.size
                if (at == added.size) { val grown = maxOf(16, at * 2); canonical = canonical.copyOf(grown); added = added.copyOf(grown); rating = rating.copyOf(grown) }
                keys.add(key); cats.add(shared.getOrPut(category) { category }); canonical[at] = canonicalHash; added[at] = date; rating[at] = score
            }
            fun build() = Index(keys.toTypedArray(), canonical.copyOf(size), cats.toTypedArray(), added.copyOf(size), rating.copyOf(size))
        }
    }
    private fun indexKey(scope: String, gen: String) = "$scope\n$gen"
    /** Shared by every [LibraryCatalog] in the process (Library and Search each have one); rebuilt from the database if missing. */
    private fun indexOf(snapshot: Snapshot): Index? {
        if (!snapshot.whole || snapshot.dbScope == null || snapshot.dbGen == null) return null
        val key = indexKey(snapshot.dbScope, snapshot.dbGen)
        indexes[key]?.let { return it }
        return synchronized(indexes) { indexes[key] ?: runCatching { rebuildIndex(snapshot.dbScope, snapshot.dbGen) }.getOrNull()?.takeIf { it.size > 0 }?.also { indexes[key] = it } }
    }
    /** Whether a catalog has titles in provider category [scope]. */
    fun hasCategory(snapshot: Snapshot, scope: String) = hasGroup(snapshot, "Category $scope")
    fun hasGroup(snapshot: Snapshot, group: String): Boolean = indexOf(snapshot)?.let { (it.counts[group] ?: 0) > 0 } ?: snapshot.chunks.any { group in it.groups }
    private val memory = object : LinkedHashMap<String, List<OnDemand>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OnDemand>>?) = size > 16
    }
    private data class LoadKey(val source: Source, val mode: String, val scope: String)
    private val jobs = SharedJobs<LoadKey, Snapshot>(java.util.concurrent.Executors.newFixedThreadPool(2) { task -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "spare-catalog-import") })
    // A category someone is waiting on must never queue behind whole-catalog search warming.
    private val categoryJobs = SharedJobs<LoadKey, Snapshot>(java.util.concurrent.Executors.newFixedThreadPool(3) { task -> Thread(task, "spare-catalog-category") })
    private val restoreLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
    fun invalidate(sourceId: String) {
        runCatching { store.catalogDb.deleteSource(sourceId) }
        jobs.cancel { it.source.id == sourceId }; categoryJobs.cancel { it.source.id == sourceId }
        synchronized(publishLock) { writers.keys.removeAll { it.startsWith("catalog-$sourceId-") } }
        synchronized(snapshots) { snapshots.keys.removeAll { it.startsWith("catalog-$sourceId-") } }
        synchronized(memory) { memory.keys.removeAll { it.startsWith("catalog-$sourceId-") } }
        indexes.keys.removeAll { it.startsWith("$sourceId:") }; missing.keys.removeAll { it.startsWith("$sourceId:") }
    }
    fun close() { jobs.close(); categoryJobs.close() }
    private val publishLock = Any()
    private val writers = mutableMapOf<String, String>()
    private val snapshots = object : LinkedHashMap<String, Snapshot>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?) = size > 8
    }
    private val missing = java.util.concurrent.ConcurrentHashMap<String, Long>()
    var changed: (() -> Unit)? = null
    fun memory(source: Source, mode: String): Snapshot? = synchronized(snapshots) { snapshots[prefix(source, mode)]?.takeIf { it.source == source } }
    /** One category of the whole catalog: no provider request, and its titles are read from the database a page at a time. */
    fun category(source: Source, mode: String, scope: String, names: Map<String, String>): Snapshot? {
        val full = memory(source, mode)?.takeIf { it.complete && it.whole } ?: return null
        val group = "Category $scope"
        val size = indexOf(full)?.counts?.get(group) ?: 0
        return if (size == 0) null else full.copy(labels = names, scopeGroup = group, scopeCount = size)
    }
    class HomeRows(val recent: List<OnDemand>, val top: List<OnDemand>, val categories: Map<String, List<OnDemand>>, val counts: Map<String, Int>)
    private fun items(snapshot: Snapshot, titles: List<Title>, names: Map<String, String>): List<OnDemand> {
        val source = snapshot.source ?: return emptyList()
        return titles.filter { available(source, it.id) }.map { title -> title.item(source).let { it.copy(category = names[it.category.removePrefix("Category ")] ?: it.category) } }
    }
    /**
     * The rows home: newest, best rated, how many titles each category has, and the first titles of the first [eager]
     * categories. Every other category row reads its titles with [row] when it scrolls into view.
     */
    fun home(snapshot: Snapshot, names: Map<String, String>, perRow: Int = 20, special: Int = 40, eager: Int = 3): HomeRows {
        val index = indexOf(snapshot); val scope = snapshot.dbScope; val gen = snapshot.dbGen
        if (index == null || scope == null || gen == null) return HomeRows(emptyList(), emptyList(), emptyMap(), emptyMap())
        val counts = index.counts.mapKeys { it.key.removePrefix("Category ") }
        val first = names.keys.filter { (counts[it] ?: 0) > 0 }.take(eager)
        val wanted = first.associateWith { ArrayList<Int>(perRow) }
        for (pos in 0 until index.size) { val list = wanted[index.cats[pos].removePrefix("Category ")] ?: continue; if (list.size < perRow) list.add(pos) }
        val recent = (0 until index.size).filter { index.added[it] > 0 }.sortedByDescending { index.added[it] }.take(special)
        // A rating alone ranks obscure titles first on many providers; newer titles break ties.
        val top = (0 until index.size).filter { index.rating[it] > 0f }.sortedWith(compareByDescending<Int> { index.rating[it] }.thenByDescending { index.added[it] }).take(special)
        val positions = (recent + top + wanted.values.flatten()).distinct()
        val byId = store.catalogDb.titlesAt(scope, gen, positions)
        // titlesAt skips positions that are gone, so pair titles back to positions by reading them one list at a time.
        val byPos = if (byId.size == positions.size) positions.zip(byId).toMap() else positions.associateWith { store.catalogDb.titlesAt(scope, gen, listOf(it)).firstOrNull() }.filterValues { it != null }.mapValues { it.value!! }
        fun at(list: List<Int>) = items(snapshot, list.mapNotNull(byPos::get), names)
        return HomeRows(at(recent), at(top), wanted.mapValues { at(it.value) }, counts)
    }
    /** The first titles of one category row of the whole catalog. */
    fun row(snapshot: Snapshot, scope: String, names: Map<String, String>, perRow: Int = 20): List<OnDemand> {
        val index = indexOf(snapshot) ?: return emptyList(); val group = "Category $scope"
        val positions = ArrayList<Int>(perRow)
        for (pos in 0 until index.size) { if (index.cats[pos] == group) { positions.add(pos); if (positions.size == perRow) break } }
        return items(snapshot, store.catalogDb.titlesAt(snapshot.dbScope!!, snapshot.dbGen!!, positions), names)
    }
    /** Titles matching a guide programme (Watch from start): found through the in-memory index, read from the database. */
    fun matches(source: Source, request: VodStart.Request): List<OnDemand> {
        val kinds = if (request.episode == null) listOf("movie", "series") else listOf("series")
        val key = ChannelSearch.key(request.query)
        return VodStart.matches(request, kinds.flatMap { kind ->
            val snapshot = memory(source, kind)?.takeIf { it.whole } ?: return@flatMap emptyList()
            val positions = indexOf(snapshot)?.canonical(key)?.takeIf { it.isNotEmpty() } ?: return@flatMap emptyList()
            runCatching { store.catalogDb.titlesAt(snapshot.dbScope!!, snapshot.dbGen!!, positions) }.getOrDefault(emptyList()).filter { it.canonical == key }
        }.filter { available(source, it.id) }.map { it.item(source) })
    }
    fun markMissing(source: Source, item: OnDemand) { missing["${source.id}:${item.id}"] = System.currentTimeMillis() + 3600000; changed?.invoke() }
    private fun available(source: Source, id: String) = (missing["${source.id}:$id"] ?: 0) <= System.currentTimeMillis()
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
        val wholeKey = store.catalogDb.scopeKey(source, mode, "")
        store.catalogDb.meta(wholeKey)?.let { meta ->
            if (meta.provider != source.providerKey()) return null
            val index = store.read("${prefix}keys.enc")?.let { Index.read(it, meta.gen) } ?: rebuildIndex(wholeKey, meta.gen).also { runCatching { store.write("${prefix}keys.enc", it.bytes(meta.gen)) } }
            indexes[indexKey(wholeKey, meta.gen)] = index
            return Snapshot(meta.updated, emptyList(), index.size, meta.labels, complete = meta.complete, source = source, dbScope = wholeKey, dbGen = meta.gen, whole = true)
                .also { remember(prefix, it); Timings.since("Saved category", restoreBegan) }
        }
        // A catalog saved before the database (one large encrypted file): move it in once, reading it a title at a time.
        store.read("${prefix}index.enc")?.let { bytes -> return migrate(source, mode, prefix, bytes).also { Timings.since("Saved category", restoreBegan) } }
        // One-time migration runs on the repository worker, never on the UI thread.
        if (scope.isNotEmpty()) return null
        val old = store.catalog(source, mode) ?: return null
        val writer = Writer(source, mode, old.first)
        old.second.chunked(64).forEach(writer::append)
        return writer.finish(emptyMap()).also { store.delete("library-${source.id}-$mode.enc") }
    }
    private fun rebuildIndex(scope: String, gen: String): Index {
        val builder = Index.Builder(); var offset = 0
        while (true) { val page = store.catalogDb.titles(scope, gen, offset, 500); if (page.isEmpty()) break; page.forEach(builder::add); offset += page.size }
        return builder.build()
    }
    private fun migrate(source: Source, mode: String, prefix: String, bytes: ByteArray): Snapshot? {
        var schema = 0; var provider: String? = null; var updated = System.currentTimeMillis(); var complete = true
        val labels = LinkedHashMap<String, String>(); var writer: Writer? = null
        fun strings(reader: android.util.JsonReader, limit: Int): List<String> {
            val values = ArrayList<String>(); reader.beginArray()
            while (reader.hasNext()) { require(values.size < limit); if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); values.add("") } else values.add(reader.nextString()) }
            reader.endArray(); return values
        }
        android.util.JsonReader(bytes.inputStream().reader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "schema" -> schema = reader.nextInt()
                "provider" -> provider = reader.nextString()
                "updated" -> updated = reader.nextLong()
                "complete" -> complete = reader.nextBoolean()
                "labels" -> { reader.beginObject(); while (reader.hasNext()) { val name = reader.nextName(); labels[name] = reader.nextString() }; reader.endObject() }
                "pages" -> {
                    val target = Writer(source, mode, updated).also { writer = it }
                    reader.beginArray(); var pages = 0
                    while (reader.hasNext()) {
                        require(++pages <= 2000)
                        reader.beginObject()
                        while (reader.hasNext()) when (reader.nextName()) {
                            "titles" -> { val read = ArrayList<Title>(); reader.beginArray(); while (reader.hasNext()) { require(read.size < 512); read.add(Title.from(strings(reader, 11))) }; reader.endArray(); target.appendTitles(read) }
                            else -> reader.skipValue()
                        }
                        reader.endObject()
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
            reader.endObject()
        }
        require(schema in 1..SCHEMA)
        val done = writer ?: return null
        if (provider != null && provider != source.providerKey() || provider == null && !store.legacyProviderMatches(source)) return null
        // Older saved formats (no titles in the index) are re-downloaded instead.
        if (done.size == 0) return null
        return (if (complete) done.finish(labels) else done.checkpoint(labels)).copy(schema = schema)
            .also { store.delete("${prefix}index.enc"); runCatching { store.pruneCatalog(prefix, setOf("${prefix}keys.enc")) } }
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
            // Refreshing a complete catalog happens out of sight: Library keeps reading the complete copy in memory until
            // the new one is whole. Showing the half-downloaded copy pushed every screen onto its slow path for a minute.
            val quiet = scope.isEmpty() && latest?.complete == true && latest.schema >= SCHEMA && latest.count > 0
            val writer = Writer(source, mode, scope = scope, indexOnly = true, quiet = quiet)
            var published = 0L; var checkpoint = 0L
            VodCatalog.stream(source, mode, scope.takeIf { it.isNotEmpty() }, lean = true) { batch ->
                writer.append(batch)
                // A refresh nobody is waiting for goes slowly, so a playing channel keeps the processor (64 titles per pause).
                if (quiet) Thread.sleep(40)
                val now = android.os.SystemClock.elapsedRealtime()
                if (!quiet && (published == 0L || now - published >= 750)) { publish(writer.snapshot(labels)); published = now }
                if (latest?.complete != true && now - checkpoint >= 20000) { writer.checkpoint(labels); checkpoint = now }
            }
            writer.finish(labels).also(publish)
        }
    }
    inner class Writer(private val source: Source, private val mode: String, private val stamp: Long = System.currentTimeMillis(), private val scope: String = "", @Suppress("unused") private val indexOnly: Boolean = false, private val quiet: Boolean = false) {
        private val prefix = prefix(source, mode, scope)
        private val generation = UUID.randomUUID().toString()
        private val pending = ArrayList<Title>(512)
        private var count = 0
        private val whole = scope.isEmpty()
        private val dbKey = store.catalogDb.scopeKey(source, mode, scope)
        private val index = if (whole) Index.Builder() else null
        val size get() = count + pending.size
        init { synchronized(publishLock) { writers[prefix] = generation } }
        private fun requireCurrent() {
            if (Thread.currentThread().isInterrupted || writers[prefix] != generation) throw java.io.InterruptedIOException("Catalog import superseded")
        }
        fun append(entries: List<OnDemand>) {
            require(entries.size <= 64)
            appendTitles(entries.map { Title.from(it, source) })
        }
        fun appendTitles(titles: List<Title>) = synchronized(publishLock) {
            requireCurrent()
            if (titles.isEmpty()) return@synchronized
            pending.addAll(titles)
            // Show the first handful immediately, then amortize encryption and disk writes over larger pages.
            if (count == 0 || pending.size >= 512) flush()
        }
        private fun flush() {
            if (pending.isEmpty()) return
            val titles = pending.toList(); pending.clear()
            titles.chunked(512).forEach { page ->
                store.catalogDb.insert(dbKey, generation, count, page)
                index?.let { builder -> page.forEach(builder::add) }
                count += page.size
            }
        }
        private fun register(): Snapshot {
            val built = index?.build()
            if (built != null) indexes[indexKey(dbKey, generation)] = built
            return Snapshot(stamp, emptyList(), count, emptyMap(), complete = false, source = source, dbScope = dbKey, dbGen = generation, whole = whole)
        }
        fun snapshot(labels: Map<String, String>): Snapshot = synchronized(publishLock) {
            // Invalidation must cover partial publication as well as the saved copy.
            requireCurrent(); flush()
            register().copy(labels = labels).also { if (!quiet) remember(prefix, it) }
        }
        fun checkpoint(labels: Map<String, String>): Snapshot = save(labels, false)
        fun finish(labels: Map<String, String>): Snapshot = save(labels, true)
        private fun save(labels: Map<String, String>, complete: Boolean): Snapshot = synchronized(publishLock) {
            requireCurrent(); flush()
            val value = register().copy(labels = labels, complete = complete)
            store.catalogDb.publish(dbKey, generation, stamp, complete, count, source.providerKey(), labels)
            if (whole) indexes[indexKey(dbKey, generation)]?.let { built -> runCatching { store.write("${prefix}keys.enc", built.bytes(generation)) } }
            // Saving made this copy current and removed older copies' titles from the database: drop their indexes too.
            if (whole) indexes.keys.removeAll { it.startsWith(dbKey + "\n") && it != indexKey(dbKey, generation) }
            remember(prefix, value)
            value
        }
    }
    /** Sorted browsing materializes one category's matches once, then pages through that list. */
    // Compared by identity: a Snapshot holds every title, so structural equality would rescan the catalog.
    private class Sorted(val snapshot: Snapshot, val group: String?, val query: String, val sort: String, val items: List<OnDemand>) {
        fun matches(other: Snapshot, group: String?, query: String, sort: String) = snapshot === other && this.group == group && this.query == query && this.sort == sort
    }
    @Volatile private var sorted: Sorted? = null
    /** One saved category: read and decrypt only the rows a page needs. */
    /** Filter and search on the in-memory index, then read only the matching page of titles from the database. */
    private fun queryWhole(snapshot: Snapshot, group: String?, query: String, cursor: Int, limit: Int): Page {
        val index = indexOf(snapshot) ?: return Page(emptyList(), snapshot.count, false)
        val source = snapshot.source!!; val key = ChannelSearch.key(query)
        val selected = (group ?: snapshot.scopeGroup)?.let { wanted -> if (wanted.startsWith("Category ")) wanted else snapshot.labels.entries.firstOrNull { it.value == wanted }?.let { "Category ${it.key}" } ?: wanted }
        val end = minOf(snapshot.count, index.size); val picked = ArrayList<Int>(limit); var pos = cursor.coerceAtLeast(0)
        while (pos < end && picked.size < limit) {
            if ((selected == null || index.cats[pos] == selected) && (key.isEmpty() || index.keys[pos].contains(key))) picked.add(pos)
            pos++
            if (pos and 4095 == 0 && Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Catalog query cancelled")
        }
        val titles = store.catalogDb.titlesAt(snapshot.dbScope!!, snapshot.dbGen!!, picked)
        val entries = titles.filter { available(source, it.id) }.map { title -> title.item(source).let { it.copy(category = snapshot.label(it.category)) } }
        return Page(entries, pos, picked.size == limit && pos < end)
    }
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
        if (snapshot.whole && snapshot.dbScope != null && snapshot.dbGen != null && snapshot.source != null) return queryWhole(snapshot, group, query, cursor, limit)
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
        private val indexes = java.util.concurrent.ConcurrentHashMap<String, Index>()
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
