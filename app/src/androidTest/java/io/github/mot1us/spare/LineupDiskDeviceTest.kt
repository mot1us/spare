package io.github.mot1us.spare

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LineupDiskDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun channels(count: Int) = (1..count).map { n ->
        val name = when (n % 4) { 0 -> "Síntesis Deportes $n FHD"; 1 -> "Sports $n 720p 50fps"; 2 -> "News $n"; else -> "4K: Movies $n" }
        Channel("fixture:$n", name, "Group ${n / 200}", "https://example.test/live/$n.ts", if (n % 3 == 0) "" else "https://example.test/logo/$n.png", "guide.$n",
            if (n % 5 == 0) 7 else 0, if (n % 5 == 0) "xc" else "", if (n % 50 == 0) "https://example.test/catchup/{start}/$n" else "", n % 97 == 0,
            ProviderQuality.parse(emptyMap(), name), n % 2 == 0, if (n % 2 == 0) "playlist-a" else "", "$n", "guide.$n", if (n % 7 == 0) "xmltv.$n" else "")
    }

    @Test fun savedLineupReadsBackTheSameFromTheCompactFormAndFromOlderJson() {
        val guide = Guide(channels(15800), emptyMap(), (0..79).map { "Group $it" })
        val json = guide.json().toString().toByteArray(); val compact = GuideDisk.lineupBytes(guide)
        var began = System.nanoTime(); val fromJson = GuideDisk.lineup(json); val jsonMs = (System.nanoTime() - began) / 1_000_000
        began = System.nanoTime(); val fromCompact = GuideDisk.lineup(compact); val compactMs = (System.nanoTime() - began) / 1_000_000
        android.util.Log.i("SpareTest", "lineup 15800: json ${json.size} bytes $jsonMs ms, compact ${compact.size} bytes $compactMs ms")
        assertEquals(guide.channels, fromCompact.channels); assertEquals(guide.categoryOrder, fromCompact.categoryOrder)
        assertEquals("The compact form restores what older JSON did", fromJson.channels, fromCompact.channels)
        assertTrue("Compact lineup took $compactMs ms against $jsonMs ms for JSON", compactMs * 2 < jsonMs)
        // Damaged files are refused rather than read past their end.
        assertTrue(runCatching { GuideDisk.lineup(compact.copyOf(compact.size / 2)) }.isFailure)
        assertTrue(runCatching { GuideDisk.lineup(compact.copyOf(12).also { it[4] = 0x7f }) }.isFailure)
    }

    @Test fun aLineupSavedAsJsonByAnOlderVersionIsSavedAgainInTheCompactForm() {
        val store = SecureStore(context); val source = Source(name = "Older lineup", kind = "m3u", url = "https://example.test/playlist")
        store.saveSources(listOf(source)); store.active = source.id
        try {
            val guide = Guide(channels(40), emptyMap(), listOf("Group 0"))
            val name = "lineup-${source.id}.enc"
            store.write(name, guide.json().toString().toByteArray())
            assertEquals(guide.channels, store.cachedLineup(source)!!.channels)
            assertEquals('S'.code.toByte(), store.read(name)!!.first())
            assertEquals(guide.channels, store.cachedLineup(source)!!.channels); assertEquals(guide.categoryOrder, store.cachedLineup(source)!!.categoryOrder)
        } finally { store.saveSources(emptyList()); store.removeCache(source) }
    }
}
