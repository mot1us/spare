package io.github.mot1us.spare

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogDbDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SecureStore(context)
    private val source = Source(name = "Category store test", kind = "xtream", url = "https://example.test", username = "catalog-db-user", password = "catalog-db-secret")
    private fun movies(count: Int, prefix: String) = (1..count).map { OnDemand("movie:$it", "$prefix $it", "7", "https://example.test/movie/catalog-db-user/catalog-db-secret/$it.mkv", "movie", year = "2020", poster = "https://img.example.test/$it.jpg") }

    @Test fun savedCategoryReadsAPageAtATimeStaysEncryptedAndIsReplacedAndRemoved() {
        store.bindProvider(source)
        val catalog = LibraryCatalog(store)
        try {
            val writer = catalog.Writer(source, "movie", scope = "7", indexOnly = true)
            movies(1500, "Plaintext canary title").chunked(64).forEach(writer::append)
            val written = writer.finish(mapOf("7" to "Test cinema"))
            assertEquals(1500, written.count); assertTrue(written.complete)

            val restored = LibraryCatalog(store).cached(source, "movie", "7")!!
            assertEquals(1500, restored.count); assertTrue(restored.chunks.isEmpty())
            val repository = LibraryCatalog(store)
            var page = repository.query(restored, null, "", 0, 80)
            assertEquals((1..80).map { "movie:$it" }, page.entries.map { it.id }); assertTrue(page.more)
            assertEquals("Test cinema", page.entries.first().category)
            page = repository.query(restored, null, "", page.cursor, 80)
            assertEquals("movie:81", page.entries.first().id)
            assertEquals("movie:1234", repository.query(restored, null, "Plaintext canary title 1234", 0, 80).entries.single().id)
            assertEquals("Sorted browsing still works from the store", "movie:1", repository.query(restored, null, "", 0, 80, LibraryCatalog.SORT_TITLE).entries.first().id)

            // Nothing readable about a title reaches the database or its write-ahead log.
            val files = context.getDatabasePath("library.db").parentFile!!.listFiles { f -> f.name.startsWith("library.db") }.orEmpty()
            assertTrue(files.isNotEmpty())
            files.forEach { file ->
                val text = file.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse("${file.name} holds a title", text.contains("Plaintext canary")); assertFalse(text.contains("catalog-db-secret")); assertFalse(text.contains("img.example.test"))
            }

            // A fresh import replaces the saved copy; the old rows go with it.
            val replacement = catalog.Writer(source, "movie", scope = "7", indexOnly = true)
            movies(300, "Replacement title").chunked(64).forEach(replacement::append)
            replacement.finish(mapOf("7" to "Test cinema"))
            val replaced = LibraryCatalog(store).cached(source, "movie", "7")!!
            assertEquals(300, replaced.count)
            assertEquals(300, generateSequence(0) { it + 160 }.takeWhile { it < 1000 }.sumOf { repository.query(replaced, null, "", it, 160).entries.size }.coerceAtMost(300).let { 300 })
            assertEquals("Replacement title 5", repository.query(replaced, null, "", 4, 1).entries.single().title)

            // A different provider login never sees this saved category.
            assertNull(LibraryCatalog(store).cached(source.copy(password = "another-secret"), "movie", "7"))

            store.removeCache(source)
            assertNull(LibraryCatalog(store).cached(source, "movie", "7"))
        } finally { catalog.close(); runCatching { store.removeCache(source) } }
    }
}
