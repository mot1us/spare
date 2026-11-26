package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.app.AlertDialog
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.speech.RecognizerIntent
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.EditText
import android.widget.TextView
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SpareDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val store = SecureStore(context)

    @Before fun resetGuideStyle() { store.onboarded = true; store.navigationAnimation = "Instant"; store.navigationSpeed = "Normal"; Motion.use("Instant", "Normal"); store.resetScoreDelays(); store.guideAutoCategories = true; store.guideOpenFullscreen = true; store.guideLoopChannels = true; store.scoreOverlayPinned = false; store.scoreOverlayPosition = "Bottom"; store.sportsAutoFailover = false; store.sportsFailoverSeconds = 15; store.localLocation = ""; store.guideOpacity = -1; store.hideCompletedScores = false; store.sportsFollowedOnly = false; store.sportsAutoPlay = false; store.sportsLeagues = SportsLeagues.byId.keys; store.sportsLastLeague = "NFL"; store.frameRateMatching = true; store.smallPreview = false; store.hiddenDock = false; store.theme = "Monochrome"; store.themeStyle = "Glass"; store.customAccent = 0; store.customBackground = -1; store.cornerRadius = -1; store.borderWidth = -1; store.guideTextScale = 100; store.guideRowHeight = 32; store.guideRowGap = 3; store.subtitleSize = "Small"; store.subtitleStyle = "Outline"; store.captions = false; store.followedTeams = emptySet(); store.sportsTeam = ""; store.clock24 = false; store.font = "Default"; store.librarySort = LibraryCatalog.SORT_PROVIDER; SportsDestinations.redZoneDay = { true } }
    // RedZone is offered only on Sundays; these tests treat every day as one unless they say otherwise.
    @org.junit.After fun restoreRedZoneDay() { SportsDestinations.redZoneDay = SportsDestinations::sunday }

    @Test fun encryptedStorageAuthenticatesDataAndNeverStoresPlaintext() {
        val secret = "spare-test-secret-credentials"
        try {
            store.write("test-security.enc", secret.toByteArray())
            val file = File(context.filesDir, "test-security.enc")
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(secret))
            assertEquals(secret, store.read("test-security.enc")!!.toString(Charsets.UTF_8))
            // Files written before 0.41.0 used the Keystore key directly; one is read, then re-saved with the fast data key.
            val legacyKey = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("spare-v1", null) as javax.crypto.SecretKey
            val legacyPlain = ByteArray(40 * 1024) { (it % 251).toByte() }
            val legacyCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, legacyKey) }
            val legacyFile = File(context.filesDir, "test-legacy.enc")
            legacyFile.writeBytes(byteArrayOf(1) + legacyCipher.iv + legacyCipher.doFinal(legacyPlain))
            assertArrayEquals(legacyPlain, store.read("test-legacy.enc"))
            assertEquals("A legacy file moves to the data key", 2.toByte(), legacyFile.readBytes()[0])
            assertArrayEquals(legacyPlain, SecureStore(context).read("test-legacy.enc"))
            store.delete("test-legacy.enc")
            val corrupt = file.readBytes(); corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte(); file.writeBytes(corrupt)
            try { store.read("test-security.enc"); fail("Tampered ciphertext accepted") } catch (_: java.security.GeneralSecurityException) { }
            assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
            // AtomicFile is not thread-safe across separate SecureStore instances.
            val originalSources = store.sources(); val concurrentSource = Source(name = "Concurrent cache test", kind = "m3u", url = "https://example.com/playlist.m3u")
            val concurrentStore = SecureStore(context); val pool = Executors.newFixedThreadPool(2); val start = java.util.concurrent.CountDownLatch(1)
            try {
                store.saveSources(listOf(concurrentSource))
                val writer = pool.submit { start.await(); repeat(160) { store.saveSources(listOf(concurrentSource.copy(name = "Write $it"))) } }
                val reader = pool.submit { start.await(); repeat(160) { assertEquals("Concurrent reads retain the last committed source", 1, concurrentStore.sources().size) } }
                start.countDown(); writer.get(15, java.util.concurrent.TimeUnit.SECONDS); reader.get(15, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("Write 159", concurrentStore.sources().single().name)
            } finally { pool.shutdownNow(); store.saveSources(originalSources) }
            val cacheSource = Source(name = "Provider cache isolation", kind = "xtream", url = "https://example.com", username = "old-account", password = "old-secret")
            val replacement = cacheSource.copy(username = "new-account", password = "new-secret")
            val channel = Channel("xc:1", "Private channel", "News", "https://example.com/live/old-account/old-secret/1.ts", guideId = "xc:1")
            val programme = Programme("xc:1", "Private schedule", 1, Long.MAX_VALUE)
            val cachedGuide = Guide(listOf(channel), mapOf("xc:1" to listOf(programme)))
            val movie = OnDemand("movie:1", "Private movie", "Films", "https://example.com/movie/old-account/old-secret/1.mp4")
            try {
                store.bindProvider(cacheSource)
                store.cache(cacheSource, cachedGuide)
                store.saveLineup(cacheSource, cachedGuide, "https://example.com/old-guide.xml")
                store.cacheCatalog(cacheSource, "movie", listOf(movie))
                assertTrue(store.bindProvider(replacement))
                // This is the interval before MainActivity's worker can invalidate the files.
                assertNull(concurrentStore.cachedLineup(replacement))
                assertTrue(concurrentStore.cachedProgrammes(replacement).isEmpty())
                assertEquals("", concurrentStore.guideUrl(replacement))
                assertNull(concurrentStore.catalog(replacement, "movie"))
                assertTrue("A recreated store must retry unfinished cleanup", concurrentStore.bindProvider(replacement))
                concurrentStore.saveLineup(replacement, cachedGuide)
                assertNull(concurrentStore.cachedLineup(replacement))
                val lineupName = "lineup-${replacement.id}.enc"
                store.delete(lineupName)
                for (suffix in listOf("", ".new", ".bak")) {
                    // A nonempty directory makes AtomicFile.delete fail without changing permissions.
                    val blocked = File(context.filesDir, lineupName + suffix)
                    assertTrue(blocked.mkdir())
                    val child = File(blocked, "fixture").apply { writeText("synthetic") }
                    try {
                        try { store.invalidateProviderFiles(replacement); fail("Incomplete cache cleanup was accepted: $suffix") } catch (_: java.io.IOException) { }
                        assertTrue("Failed cleanup must remain pending", concurrentStore.bindProvider(replacement))
                        assertNull(concurrentStore.cachedLineup(replacement))
                        assertTrue(concurrentStore.cachedProgrammes(replacement).isEmpty())
                    } finally { assertTrue(child.delete()); assertTrue(blocked.delete()) }
                }
                val orphan = File(context.filesDir, "catalog-${replacement.id}-movie-orphan.enc.bak").apply { writeText("synthetic") }
                store.invalidateProviderFiles(replacement)
                assertFalse("Cleanup includes orphaned AtomicFile sidecars", orphan.exists())
                assertFalse(store.bindProvider(replacement))
                val newGuide = cachedGuide.copy(channels = listOf(channel.copy(url = "https://example.com/new-stream.ts")))
                store.cache(replacement, newGuide)
                store.cacheCatalog(replacement, "movie", listOf(movie.copy(title = "Replacement movie")))
                store.invalidateProviderFiles(cacheSource)
                store.invalidateProviderFiles(replacement)
                assertEquals("Obsolete and duplicate cleanup retain fresh files", newGuide, concurrentStore.cached(replacement))
                store.cache(cacheSource, cachedGuide)
                assertEquals(newGuide, concurrentStore.cached(replacement))
                store.removeCache(replacement)
                concurrentStore.cache(replacement, newGuide)
                concurrentStore.cacheCatalog(replacement, "movie", listOf(movie))
                concurrentStore.saveVideoInfo(replacement, mapOf(channel.id to VideoInfo(1920, 1080, 60f)))
                assertNull(concurrentStore.cachedLineup(replacement))
                assertNull(concurrentStore.catalog(replacement, "movie"))
                assertTrue(concurrentStore.videoInfo(replacement).isEmpty())
                assertFalse(File(context.filesDir, "lineup-${replacement.id}.enc").exists())
                assertFalse(File(context.filesDir, "epg-${replacement.id}.enc").exists())
                assertFalse(File(context.filesDir, "library-${replacement.id}-movie.enc").exists())
            } finally { store.removeCache(cacheSource) }
            val files = ArtworkFiles(context); val url = "https://images.example/synthetic-${System.nanoTime()}.png"
            val bytes = ByteArrayOutputStream().also { output -> Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, output); recycle() } }.toByteArray()
            var downloads = 0
            assertArrayEquals(bytes, files.bytes(url) { downloads++; bytes }); assertArrayEquals(bytes, files.bytes(url) { downloads++; bytes }); assertEquals(1, downloads)
            val first = LogoLoader(16, 1024 * 1024, files); val second = LogoLoader(32, 1024 * 1024, files)
            first.prefetch(url); second.prefetch(url)
            val deadline = System.currentTimeMillis() + 5000
            while ((first.get(url, false) {} == null || second.get(url, false) {} == null) && System.currentTimeMillis() < deadline) Thread.sleep(30)
            assertNotNull(first.get(url, false) {}); assertNotNull(second.get(url, false) {}); first.close(); second.close()
            val source = Source(name = "Page test", kind = "xtream", url = "https://example.com")
            try {
                val catalog = LibraryCatalog(store); val writer = catalog.Writer(source, "movie")
                val entries = (1..2190).map { OnDemand("movie:$it", "Movie $it", if (it % 2 == 0) "Category 1" else "Category 2", "https://example.com/private/$it.mp4") }
                entries.chunked(64).forEach(writer::append)
                val checkpoint = writer.checkpoint(mapOf("1" to "Science fiction", "2" to "Drama"))
                val partial = LibraryCatalog(store).cached(source, "movie")!!
                assertFalse(partial.complete); assertEquals(checkpoint.count, partial.count)
                assertEquals("movie:2188", catalog.query(partial, null, "Movie 2188").entries.single().id)
                val snapshot = writer.finish(mapOf("1" to "Science fiction", "2" to "Drama"))
                val first = catalog.query(snapshot, "Science fiction", "", limit = 40)
                val second = catalog.query(snapshot, "Science fiction", "", first.cursor, 40)
                assertEquals(40, first.entries.size); assertTrue(first.more)
                assertTrue(first.entries.none { a -> second.entries.any { b -> a.id == b.id } })
                assertEquals("movie:188", catalog.query(snapshot, null, "Movie 188").entries.first().id)
                val restored = LibraryCatalog(store).cached(source, "movie")!!
                assertEquals(2190, restored.count)
                assertTrue("The whole catalog is in the database, not in memory", restored.whole && restored.chunks.isEmpty())
                assertTrue(catalog.hasCategory(restored, "1") && !catalog.hasCategory(restored, "3"))
                val home = catalog.home(restored, mapOf("1" to "Science fiction", "2" to "Drama"), perRow = 5)
                assertEquals(mapOf("1" to 1095, "2" to 1095), home.counts); assertEquals(listOf("movie:2", "movie:4", "movie:6", "movie:8", "movie:10"), home.categories.getValue("1").map { it.id })
                assertEquals(listOf("movie:1", "movie:3"), catalog.row(restored, "2", emptyMap(), perRow = 2).map { it.id })
                val cold = LibraryCatalog(store); cold.cached(source, "movie")
                assertEquals("movie:2188", cold.matches(source, VodStart.Request("Movie 2188")).single().id)
                assertEquals("movie:2188", LibraryCatalog(store).query(restored, null, "Movie 2188").entries.single().id)
                assertTrue(catalog.query(restored.copy(scopeGroup = "Category 1"), null, "", limit = 40).entries.all { it.category == "Science fiction" })
                assertTrue(context.filesDir.listFiles()!!.filter { it.name.startsWith("catalog-${source.id}-") }.none { it.readBytes().toString(Charsets.UTF_8).contains("https://example.com/private/") })
                listOf("library.db", "library.db-wal").map(context::getDatabasePath).filter { it.exists() }.forEach { file ->
                    val text = file.readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse("Titles stay sealed in the database", text.contains("https://example.com/private/") || text.contains("Movie 2188"))
                }
                // A catalog saved by an earlier version (one encrypted file) moves into the database the first time it is read.
                store.removeCache(source)
                val legacy = org.json.JSONObject().put("schema", LibraryCatalog.SCHEMA).put("provider", source.providerKey()).put("updated", System.currentTimeMillis()).put("complete", true)
                    .put("pages", org.json.JSONArray().put(org.json.JSONObject().put("file", "").put("count", 2).put("groups", org.json.JSONArray(listOf("Category 1")))
                        .put("titles", org.json.JSONArray(listOf(LibraryCatalog.Title("movie:7", "Old saved film", "Category 1", "mp4", "2020", "", "").json(), LibraryCatalog.Title("movie:8", "Second film", "Category 1", "mp4", "2021", "", "").json())))))
                    .put("labels", org.json.JSONObject().put("1", "Science fiction"))
                store.write("catalog-${source.id}-movie-index.enc", legacy.toString().toByteArray())
                val migrated = LibraryCatalog(store).cached(source, "movie")!!
                assertTrue(migrated.whole && migrated.complete); assertEquals(2, migrated.count)
                assertEquals("movie:7", LibraryCatalog(store).query(migrated, null, "Old saved").entries.single().id)
                assertNull("The old file is removed once moved", store.read("catalog-${source.id}-movie-index.enc"))
            } finally { store.removeCache(source) }

        FixtureServer(slowVod = true, parallelClients = true).use { server ->
            val source = Source(name = "Shared import", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val repository = LibraryCatalog(store); val consumers = Executors.newFixedThreadPool(2)
            val partial = java.util.concurrent.CountDownLatch(1); val second = java.util.concurrent.CountDownLatch(1)
            try {
                val search = consumers.submit<LibraryCatalog.Snapshot> { repository.load(source, "movie") { if (!it.complete) partial.countDown() } }
                assertTrue(partial.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val library = consumers.submit<LibraryCatalog.Snapshot> { repository.load(source, "movie") { if (!it.complete) second.countDown() } }
                assertTrue(second.await(5, java.util.concurrent.TimeUnit.SECONDS)); server.finishVodCatalog.countDown()
                assertEquals(16, search.get(10, java.util.concurrent.TimeUnit.SECONDS).count)
                assertEquals(16, library.get(10, java.util.concurrent.TimeUnit.SECONDS).count)
                assertEquals("Search and Library must share the provider download", 1, server.catalogTargets.count { "get_vod_streams" in it })
                val otherAccount = source.copy(password = "different-account")
                assertNull(repository.memory(otherAccount, "movie")); assertNull(repository.cached(otherAccount, "movie"))
                assertTrue(repository.matches(otherAccount, VodStart.Request("Other movie 16")).isEmpty())
                assertEquals(1, repository.matches(source, VodStart.Request("Other movie 16")).size)
            } finally { server.finishVodCatalog.countDown(); repository.close(); consumers.shutdownNow(); store.removeCache(source) }
        }
        } finally { context.deleteFile("test-security.enc") }
    }

    @Test fun legacyFpsSettingsMigrateAndNewExclusionsPersist() {
        val source = Source(name = "Migration", kind = "xtream", url = "https://example.com")
        val prefs = context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("sixty-${source.id}", true).putInt("min-height-${source.id}", 1080).putStringSet("groups-${source.id}", setOf("US | Sports")).commit()
        try {
            assertEquals(GuideFilters(1080, FrameRateFilter.onlySixty), store.sportsFilters(source))
            assertEquals(GuideFilters(groups = setOf("US | Sports")), store.filters(source))
            val profile = GuideFilters(2160, setOf(25, 50))
            store.setSportsFilters(source, profile)
            assertEquals(profile, SecureStore(context).sportsFilters(source))
            assertEquals(setOf("US | Sports"), store.filters(source).groups)
            store.setFilters(source, GuideFilters(groups = setOf("US | News")))
            assertEquals(profile, SecureStore(context).sportsFilters(source))
            assertEquals(GuideFilters(groups = setOf("US | News")), store.filters(source))
            assertFalse(prefs.contains("sixty-${source.id}"))
        } finally { store.removeCache(source) }
    }

    @Test fun editedSourceRefreshDoesNotJoinThePreviousAccount() {
        FixtureServer(heldPlaylist = true).use { originalServer -> FixtureServer().use { replacementServer ->
            val source = Source(name = "Original refresh", kind = "m3u", url = "${originalServer.base}/playlist.m3u", allowHttp = true)
            val replacement = source.copy(url = "${replacementServer.base}/playlist.m3u")
            val oldGuide = Guide(listOf(Channel("old", "Original cached channel", "News", "${originalServer.base}/channel1.mp4")), emptyMap())
            val originalSources = store.sources()
            val finished = java.util.concurrent.CountDownLatch(2)
            val result = java.util.concurrent.atomic.AtomicReference<PlaylistRefresh.Result>()
            try {
                store.saveSources(listOf(source)); store.bindProvider(source); store.cache(source, oldGuide)
                assertTrue(PlaylistRefresh.request(context, source, true, false) { finished.countDown() })
                assertTrue("The old request must already own its cached guide", originalServer.playlistStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                store.saveSources(listOf(replacement))
                assertTrue(store.bindProvider(replacement)); store.invalidateProviderFiles(replacement)
                // Sharing just the saved source ID would attach this callback to the old request.
                assertTrue("An edited source needs its own refresh", PlaylistRefresh.request(context, replacement, true, false) { result.set(it); finished.countDown() })
                originalServer.finishPlaylist.countDown()
                assertTrue(finished.await(15, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("", result.get().error)
                assertEquals(8, result.get().guide!!.channels.size)
                assertTrue(result.get().guide!!.channels.all { it.url.startsWith(replacementServer.base + "/") })
                assertEquals(1, replacementServer.requests["/playlist.m3u"])
            } finally {
                originalServer.finishPlaylist.countDown()
                finished.await(15, java.util.concurrent.TimeUnit.SECONDS)
                store.saveSources(originalSources); store.removeCache(source)
            }
        } }
    }

    @Test fun savedStartupBackgroundRefreshAndScheduledJobKeepPlaybackAndFocus() {
        FixtureServer().use { server ->
            val source = Source(name = "Refresh fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val savedText = "#EXTM3U\n" + (1..8).joinToString("\n") { "#EXTINF:-1 tvg-id=\"test$it\" group-title=\"${if (it <= 4) "News" else "Culture"}\",Test Channel $it\n${server.base}/channel$it.mp4" }
            val channels = Importers.m3u(savedText, true).channels; val now = System.currentTimeMillis()
            val other = source.copy(id = "restore-other-${java.util.UUID.randomUUID()}", name = "Other cached playlist", url = "${server.base}/other-playlist.m3u")
            val otherChannel = channels.first().copy(id = "other-channel", guideId = "other-guide")
            store.saveSources(listOf(source, other)); store.active = source.id
            store.cache(source, Guide(channels, mapOf("test1" to listOf(Programme("test1", "Saved programme", now - 60000, now + 3600000)))))
            store.cache(other, Guide(listOf(otherChannel), mapOf("other-guide" to listOf(Programme("other-guide", "Other saved programme", now - 60000, now + 3600000)))))
            store.setRefreshPolicy(other, RefreshPolicy()); store.markFullEpgAttempted(other); store.markEpgRefreshed(other); store.markPlaylistRefreshed(other)
            store.toggleFavorite(source, channels[1]); store.setLast(source, channels[0]); store.setRefreshPolicy(source, RefreshPolicy())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                assertEquals("Saved launch must not request the playlist", 0, server.requests["/playlist.m3u"] ?: 0)
                var samePlayer: Player? = null; var item = ""
                scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player; item = samePlayer!!.currentMediaItem!!.mediaId }
                try { waitFor(scenario) { activity ->
                    val saved = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    saved.programmes.values.flatten().map { it.title }.containsAll(listOf("Saved programme", "Other saved programme"))
                } } catch (failure: AssertionError) { var titles = ""; scenario.onActivity { activity -> val g = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide; titles = "channels=${g.channels.map { it.id }} programmes=${g.programmes.mapValues { e -> e.value.map { it.title } }} disk=${store.cachedProgrammes(source).keys}/${store.cachedProgrammes(other).keys}" }; throw AssertionError(titles, failure) }
                // Reproduce a lifecycle flush while just one owner's EPG remains
                // unrestored: another owner's listings must not erase its disk cache.
                val originalProgrammes = store.cachedProgrammes(source)
                val flushed = java.util.concurrent.CountDownLatch(1); var completeGuide: Guide? = null
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    completeGuide = field.get(activity) as Guide
                    field.set(activity, completeGuide!!.copy(programmes = completeGuide!!.programmes.filterValues { entries -> entries.any { it.title == "Other saved programme" } }))
                    @Suppress("UNCHECKED_CAST")
                    val restoring = MainActivity::class.java.getDeclaredField("restoringProgrammes").apply { isAccessible = true }.get(activity) as MutableSet<String>
                    restoring.add(source.id)
                    (MainActivity::class.java.getDeclaredField("cacheEpg").apply { isAccessible = true }.get(activity) as Runnable).run()
                    (MainActivity::class.java.getDeclaredField("cacheWrites").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService).execute { flushed.countDown() }
                }
                try {
                    assertTrue(flushed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals("A partial multi-playlist restore must retain the owner's encrypted EPG", originalProgrammes, SecureStore(context).cachedProgrammes(source))
                } finally { scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, completeGuide)
                    @Suppress("UNCHECKED_CAST")
                    val restoring = MainActivity::class.java.getDeclaredField("restoringProgrammes").apply { isAccessible = true }.get(activity) as MutableSet<String>
                    restoring.remove(source.id)
                } }
                scenario.onActivity { activity ->
                    store.markFullEpgAttempted(source); store.markEpgRefreshed(source)
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity) as PlaybackWarmup
                    warmup.ready(token, android.os.SystemClock.elapsedRealtime() - 61000, true, 6000)
                    MainActivity::class.java.getDeclaredMethod("autoRefresh", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, source, false)
                    assertFalse("Missing Xtream archive flags must not repeatedly refresh an M3U playlist", PlaylistRefresh.running(source.id))
                }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressDPadDown(); waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == channels[1].id }
                val refreshDone = java.util.concurrent.atomic.AtomicBoolean(false)
                scenario.onActivity { activity ->
                    val method = MainActivity::class.java.getDeclaredMethod("refreshCurrent", Source::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java)
                    method.isAccessible = true; method.invoke(activity, source, true, true, { refreshDone.set(true); Unit })
                }
                waitFor(scenario) { refreshDone.get() && it.window.decorView.findViewWithTag<View>("background-refresh")?.isShown != true }
                scenario.onActivity { activity ->
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player); assertEquals(item, samePlayer!!.currentMediaItem!!.mediaId)
                    assertEquals(channels[1].id, (channelList(activity)?.findFocus() as? GuideAdapter.Row)?.channel?.id)
                }
                assertEquals(setOf(channels[1].id), store.favorites(source)); assertEquals(1, server.requests["/playlist.m3u"])
                assertTrue(store.epgRefreshed(source) > 0)
                // Force an actual Android job after making only the playlist schedule due.
                store.setRefreshPolicy(source, RefreshPolicy(playlistHours = 1))
                context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().putLong("playlist-refreshed-${source.id}", 0).commit()
                scenario.onActivity { RefreshJobs.configure(it, listOf(source)) }
                val jobs = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs
                val job = jobs.single { it.extras.getString("source") == source.id }
                assertTrue(job.isPersisted); assertFalse(job.extras.toString().contains(server.base))
                device.executeShellCommand("cmd jobscheduler run -f ${context.packageName} ${job.id}")
                waitFor(scenario) { (server.requests["/playlist.m3u"] ?: 0) == 2 && !PlaylistRefresh.running(source.id) }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(item, samePlayer!!.currentMediaItem!!.mediaId); assertTrue(samePlayer!!.isPlaying) }
                store.setRefreshPolicy(source, RefreshPolicy()); scenario.onActivity { RefreshJobs.configure(it, listOf(source)) }
                // An edited provider address cannot borrow the old provider's cache, which remains intact.
                val bad = source.copy(url = "${server.base}/missing-playlist.m3u")
                store.saveSources(listOf(bad)); val failed = java.util.concurrent.atomic.AtomicReference<PlaylistRefresh.Result>()
                PlaylistRefresh.request(context, bad, true, false) { failed.set(it) }
                waitFor(scenario) { failed.get() != null }
                assertTrue(failed.get().error.isNotBlank()); assertNull(store.cachedLineup(bad)); assertEquals(8, store.cachedLineup(source)!!.channels.size)
            }
            store.saveSources(emptyList()); RefreshJobs.configure(context, emptyList()); store.removeCache(source); store.removeCache(other)
        }
        FixtureServer(channelCount = 48, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Quiet cached launch", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channels = (1..48).map { Channel("xc:$it", "Channel $it", "News", "${server.base}/live/test/test-secret/$it.mp4", guideId = "xc:$it") }
            val stamp = System.currentTimeMillis()
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(channels, mapOf("xc:1" to listOf(Programme("xc:1", "Saved programme", stamp - 60000, stamp + 3600000)))))
            store.setLast(source, channels.first()); store.markPlaylistRefreshed(source)
            store.setRefreshPolicy(source, RefreshPolicy(playlistOnLaunch = true, epgOnLaunch = true))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var item = ""
                scenario.onActivity { item = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                val playingAt = android.os.SystemClock.elapsedRealtime()
                waitFor(scenario) { android.os.SystemClock.elapsedRealtime() - playingAt >= 2500 }
                assertEquals("Cached startup must not immediately redownload catalogs or launch-refresh metadata", 0, server.requests["/player_api.php"] ?: 0)
                assertTrue("Fullscreen playback must not query neighboring or favorite channel guides", server.epgTargets.isEmpty())
                scenario.onActivity { activity ->
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity) as PlaybackWarmup
                    warmup.ready(token, android.os.SystemClock.elapsedRealtime() - 61000, true, 6000)
                    MainActivity::class.java.getDeclaredMethod("autoRefresh", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, source, true)
                }
                waitFor(scenario) { store.playlistRefreshed(source) > stamp }
                assertTrue("Scheduled live refresh must not import VOD", server.catalogTargets.isEmpty())
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertEquals("Background refresh must not replace the resumed stream", item, player.currentMediaItem!!.mediaId)
                    assertTrue(player.isPlaying)
                }
            }
            store.setRefreshPolicy(source, RefreshPolicy()); store.saveSources(emptyList()); store.removeCache(source)
        }

    }

    @Test fun googleTvStreamerStartsOnARestartFrameAndRequestsFullDisplayModes() {
        StreamerQuirks.override = true
        try {
            FixtureServer(longVideo = true).use { server ->
                val source = Source(name = "Streamer fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
                store.saveSources(listOf(source)); store.active = source.id
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { activity ->
                        val player = find<PlayerView>(activity.window.decorView)?.player
                        player?.isPlaying == true && player.currentMediaItem?.localConfiguration?.uri?.path == "/channel1.ts" &&
                            MainActivity::class.java.getDeclaredField("firstFrame").apply { isAccessible = true }.getBoolean(activity)
                    }
                    scenario.onActivity { activity ->
                        assertEquals("The Streamer uses Media3's own TS join policy", 0,
                            MainActivity::class.java.getDeclaredField("tsFlags").apply { isAccessible = true }.getInt(activity))
                        val matching = MainActivity::class.java.getDeclaredField("frameRates").apply { isAccessible = true }.get(activity) as FrameRates
                        val attempt = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                        matching.request(30f); assertEquals(60f, matching.requestedRate, .001f)
                        matching.request(29.97f); assertEquals(59.94f, matching.requestedRate, .001f)
                        matching.request(25f); assertEquals(50f, matching.requestedRate, .001f)
                        matching.request(23.976f); assertEquals(23.976f, matching.requestedRate, .001f)
                        val diagnostic = MainActivity::class.java.getDeclaredMethod("playbackDiagnostics").apply { isAccessible = true }.invoke(activity) as String
                        assertTrue("The report says the Streamer settings are in use: $diagnostic", diagnostic.contains("Google TV Streamer · video starts on a full restart frame"))
                        assertEquals("Frame-rate requests keep the playing connection", attempt, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    }
                }
            }
        } finally { StreamerQuirks.override = null }
    }

    @Test fun networkImportPreviewRemoteFavoritesAndResume() {
        FixtureServer().use { server ->
            val source = Source(name = "Spare Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY } == true
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.videoSize?.width?.let { width -> width > 0 } == true }
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.pressBack()
                waitFor(scenario) { channelList(it)?.let { list -> list.isShown && list.hasFocus() } == true }
                assertTrue(device.wait(Until.hasObject(By.textContains("World News")), 5000))
                scenario.onActivity { activity ->
                    val video = find<PlayerView>(activity.window.decorView)!!
                    assertEquals("Live video stays full screen behind the translucent guide", activity.window.decorView.width, video.width)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "guide-all.png"))
                device.pressDPadDown(); instrumentation.waitForIdleSync()
                assertEquals("Browsing must not change streams", "/channel1.mp4", playingPath(scenario))
                device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == (channelList(it)!!.adapter as GuideAdapter).channels[1].id }
                scenario.onActivity { assertFalse("Selecting a new channel opens full screen", channelList(it)!!.isShown) }
                assertEquals("/channel2.mp4", playingPath(scenario))
                device.pressDPadUp(); instrumentation.waitForIdleSync()
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.pressBack()
                waitFor(scenario) { activity ->
                    channelList(activity)?.let { list ->
                        list.isShown && list.findFocus() is GuideAdapter.Row
                    } == true
                }
                // Hold beyond the threshold: --longpress releases at that exact threshold,
                // which can race View's queued long-click callback on a busy CI emulator.
                val holdMillis = ViewConfiguration.getLongPressTimeout() + 500
                device.executeShellCommand("input dpad keyevent --duration $holdMillis 23")
                instrumentation.waitForIdleSync()
                waitFor(scenario) { channelList(it)?.isShown == true && store.favorites(source).size == 1 }
                assertEquals(1, store.favorites(source).size)
                device.pressDPadLeft(); device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 1 }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "guide.png"))
                holdBack()
                waitFor(scenario) { channelList(it)?.isShown == false }
                scenario.recreate()
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY } == true
                }
                assertEquals("/channel1.mp4", playingPath(scenario))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun xtreamLoadsLargeLiveLineupCategoriesAndVideo() {
        FixtureServer(channelCount = 25001, regionGroups = true).use { server ->
            val source = Source(name = "Xtream Test", kind = "xtream", url = "${server.base}/", username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY && it.videoSize.width > 0 } == true
                }
                assertEquals("/live/test/test-secret/1.mp4", playingPath(scenario))
                device.pressBack()
                waitFor(scenario) { channelList(it)?.isShown == true }
                waitFor(scenario) { device.hasObject(By.textContains("World News")) }
                assertNull("A provider without XMLTV IDs keeps its per-channel fallback", server.requests["/xmltv.php"])
                scenario.onActivity { activity -> assertEquals(25001, channelList(activity)!!.adapter!!.itemCount) }
                val expected = listOf("USA | News", "CA | News", "UK | News", "US | MLB")
                waitFor(scenario) { File(context.filesDir, "lineup-${source.id}.enc").exists() }
                assertEquals(expected, store.cached(source)?.categoryOrder)
                scenario.onActivity { activity ->
                    val categories = activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories")
                    expected.forEachIndexed { index, name -> assertEquals(name, (categories.findViewHolderForAdapterPosition(index + 2)!!.itemView as android.widget.TextView).text.toString()) }
                }
                scenario.recreate()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 25001 }
                assertEquals(expected, store.cached(source)?.categoryOrder)
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun largeEncryptedProgrammeCacheStreamsAndReadsLegacyJson() {
        val source = Source(name = "Streaming cache", kind = "m3u", url = "https://example.test/playlist")
        store.saveSources(listOf(source)); store.active = source.id
        try {
            val channels = (1..6000).map { Channel("cache:$it", "Cached station", "Group ${it / 100}", "https://example.test/$it") }
            store.saveLineup(source, Guide(channels, emptyMap()))
            val legacy = mapOf(channels.first().id to listOf(Programme(channels.first().id, "Legacy", 1, 2, "Description", "https://example.test/art")))
            store.write("epg-${source.id}.enc", Guide(emptyList(), legacy).json().toString().toByteArray())
            assertEquals(legacy, store.cachedProgrammes(source))
            store.cacheProgrammes(source, legacy, System.currentTimeMillis())
            assertEquals(legacy, Guide.from(org.json.JSONObject(store.read("epg-${source.id}.enc")!!.toString(Charsets.UTF_8))).programmes)
            val now = System.currentTimeMillis()
            val programmes = channels.associate { channel -> channel.id to (0..11).map { slot -> Programme(channel.id, "Slot $slot", now + slot * 3600000L, now + (slot + 1) * 3600000L, "Cached description") } }
            store.cacheProgrammes(source, programmes, now + 1)
            val restored = store.cachedProgrammes(source)
            assertEquals(72000, restored.values.sumOf { it.size }); assertEquals(programmes["cache:6000"], restored["cache:6000"])
            val lineup = store.cachedLineup(source)!!.channels
            assertEquals(channels.size, lineup.size)
            channels.zip(lineup).forEach { (expected, actual) -> assertEquals(expected.id, expected, actual) }
            val file = File(context.filesDir, "epg-${source.id}.enc"); val bytes = file.readBytes()
            assertEquals(2, bytes.first().toInt()); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertTrue("Streaming writes keep authenticated encryption", runCatching { store.cachedProgrammes(source) }.isFailure)
        } finally { store.saveSources(emptyList()); store.removeCache(source) }
    }

    @Test fun providerLogosAreEncryptedPersistBetweenLoadersAndRespectHttpConsent() {
        FixtureServer().use { server ->
            val url = "${server.base}/logo.png?synthetic-account=${System.nanoTime()}"
            val files = GuideLogoFiles(context)
            val first = LogoLoader(96, 1024 * 1024, files)
            val second = LogoLoader(96, 1024 * 1024, GuideLogoFiles(context))
            val name = "guide-logo-${Channel.stableId(url)}.enc"
            try {
                first.prefetch(url, true)
                val deadline = System.currentTimeMillis() + 5000
                while (first.get(url, true) {} == null && System.currentTimeMillis() < deadline) Thread.sleep(30)
                assertNotNull(first.get(url, true) {}); assertNull("Cached HTTP still requires consent", first.get(url, false) {})
                first.close(); second.prefetch(url, true)
                while (second.get(url, true) {} == null && System.currentTimeMillis() < deadline) Thread.sleep(30)
                assertNotNull(second.get(url, true) {}); assertEquals("Second loader uses encrypted disk bytes", 1, server.requests["/logo.png"])
                val disk = File(context.filesDir, name).readBytes()
                assertFalse(disk.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
                assertFalse(disk.toString(Charsets.ISO_8859_1).contains("synthetic-account"))
                assertNotNull(store.read(name))
            } finally { first.close(); second.close(); store.delete(name) }
        }
    }

    @Test fun cachedStartupDoesNotDownloadVodOrSweepUnvisitedChannels() {
        FixtureServer(channelCount = 20, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Quiet startup", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val lineup = Importers.xtream(source); val now = System.currentTimeMillis()
            store.cache(source, Guide(lineup.channels, mapOf("xc:1" to listOf(Programme("xc:1", "Cached programme", now - 60000, now + 7200000)))))
            store.markPlaylistRefreshed(source); store.markEpgRefreshed(source); store.markFullEpgAttempted(source); store.setRefreshPolicy(source, RefreshPolicy())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var player: Player? = null; var media = ""
                scenario.onActivity { activity ->
                    player = find<PlayerView>(activity.window.decorView)!!.player; media = player!!.currentMediaItem!!.mediaId
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity) as PlaybackWarmup
                    warmup.reset(); warmup.ready(token, android.os.SystemClock.elapsedRealtime() - 61000, true, 6000)
                }
                Thread.sleep(3500)
                scenario.onActivity { activity ->
                    assertSame(player, find<PlayerView>(activity.window.decorView)!!.player); assertEquals(media, player!!.currentMediaItem!!.mediaId)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("background-refresh").isShown)
                }
                assertTrue("Live startup must not import VOD catalogs", server.catalogTargets.isEmpty())
                assertTrue("Watching a cached channel must not sweep other channels", server.requestedEpgTargets.isEmpty())
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:2"]?.isNotEmpty() == true
                }
                // The open guide reads ahead in the background, so rows below the screen are ready before
                // the remote reaches them, and each channel's guide is fetched only once.
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:20"]?.isNotEmpty() == true
                }
                assertEquals("Read-ahead never repeats a channel", server.requestedEpgTargets.size, server.requestedEpgTargets.toSet().size)
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("focusChannelAt", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, 19) }
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:20"]?.isNotEmpty() == true
                }
                scenario.onActivity { assertEquals(media, player!!.currentMediaItem!!.mediaId) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun aCatalogPastTheTitleLimitKeepsTheTitlesThatFitAndIsNotDownloadedAgain() {
        FixtureServer(libraryCategories = true, slowVod = true, parallelClients = true).use { server ->
            val source = Source(name = "Large catalog", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.bindProvider(source)
            val catalog = LibraryCatalog(store)
            // The fixture lists 16 movies (sent without its usual pause); with a limit of 10 it stands in for a provider larger than Spare keeps.
            server.finishVodCatalog.countDown()
            VodCatalog.maxTitles = 10
            try {
                val saved = catalog.load(source, "movie")
                assertTrue("The titles that fit are saved as a finished catalog", saved.complete && saved.whole)
                assertEquals("Every title read before the limit is kept", 10, saved.count)
                assertEquals("The copy notes the limit it stopped at", 10, saved.capped)
                catalog.load(source, "movie")
                assertEquals("A saved catalog that reached the limit is not downloaded again", 1, server.catalogTargets.count { "get_vod_streams" in it })
                assertTrue(Diagnostics.report(), "Movies list is larger than Spare keeps · first 10 titles saved" in Diagnostics.report())
                // A newer Spare keeps more titles: the copy cut short by the old limit downloads again at once, though saved today.
                VodCatalog.maxTitles = VodCatalog.MAX_TITLES
                val restored = LibraryCatalog(store).let { cold -> try { cold.cached(source, "movie") } finally { cold.close() } }
                assertTrue("A restored copy keeps its limit", restored != null && restored.capped == 10 && LibraryCatalog.outgrown(restored))
                val grown = catalog.load(source, "movie")
                assertEquals("The whole list is saved under the higher limit", 16, grown.count)
                assertEquals(0, grown.capped)
                catalog.load(source, "movie")
                assertEquals("Then it is current again", 2, server.catalogTargets.count { "get_vod_streams" in it })
            } finally { VodCatalog.maxTitles = VodCatalog.MAX_TITLES; catalog.close(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun theMoviesListTheLibraryWaitsForDownloadsAtNormalPriority() {
        // Busy with a 4K channel, an onn 4K Plus gave a background-priority download about 25 KB a second: a first Movies
        // list never finished. Only a download a screen waits for moves up; search warming and refreshes stay in the background.
        FixtureServer(parallelClients = true, hugeCatalog = 40_000).use { server ->
            val source = Source(name = "Large fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.bindProvider(source)
            val catalog = LibraryCatalog(store)
            fun importPriorities(): List<Int> = java.io.File("/proc/self/task").listFiles().orEmpty()
                .filter { runCatching { java.io.File(it, "comm").readText().trim() }.getOrNull() == "spare-catalog-i" }
                .mapNotNull { it.name.toIntOrNull()?.let { tid -> runCatching { android.os.Process.getThreadPriority(tid) }.getOrNull() } }
            fun watch(waiting: Boolean, force: Boolean): Set<Int> {
                val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>(); val done = AtomicBoolean()
                val load = Thread { try { catalog.load(source, "movie", force = force, waiting = waiting) } finally { done.set(true) } }.apply { start() }
                // While the list streams in, note the priority of the import thread that is busy with it.
                while (!done.get()) { val before = server.catalogTargets.size; importPriorities().forEach(seen::add); if (before > 0) Thread.sleep(20) else Thread.sleep(5) }
                load.join(60000); return seen
            }
            try {
                val waited = watch(waiting = true, force = false)
                assertTrue("The Library's download runs at normal priority: $waited", android.os.Process.THREAD_PRIORITY_DEFAULT in waited)
                assertEquals("Afterwards the pool thread is back in the background", setOf(android.os.Process.THREAD_PRIORITY_BACKGROUND), importPriorities().toSet())
                val unwaited = watch(waiting = false, force = true)
                assertEquals("A download nobody waits for stays in the background", setOf(android.os.Process.THREAD_PRIORITY_BACKGROUND), unwaited)
                assertEquals(2, server.catalogTargets.count { "get_vod_streams" in it })
                assertEquals(40_000, catalog.cached(source, "movie")?.count)
            } finally { catalog.close(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aWholeListThatTakesLongOpensLibraryACategoryAtATimeAndKeepsDownloading() {
        // Purple's 130,000 movies took minutes on an onn 4K Plus, and Library showed only its loading steps all that time.
        FixtureServer(heldWholeVod = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            LibraryController.wholeWaitMs = 2000
            fun wholeRequests() = server.catalogTargets.count { "get_vod_streams" in it && "category_id" !in it }
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                // After the wait, the first category's titles show while the whole list is still arriving.
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                assertTrue(Diagnostics.report(), "Whole list still downloading after 2 s" in Diagnostics.report())
                assertEquals(1, wholeRequests())
                // The rest arrives in the background and is saved: the download was kept, not cancelled and started again.
                server.finishVodCatalog.countDown()
                val deadline = System.currentTimeMillis() + 15000; var saved: LibraryCatalog.Snapshot? = null
                while (saved?.complete != true && System.currentTimeMillis() < deadline) {
                    Thread.sleep(300); saved = LibraryCatalog(store).let { fresh -> try { fresh.cached(source, "movie") } finally { fresh.close() } }
                }
                assertTrue("The whole list is saved", saved?.complete == true && saved.whole)
                assertEquals(1, wholeRequests())
            } } finally { LibraryController.wholeWaitMs = LibraryController.WHOLE_WAIT_MS; server.finishVodCatalog.countDown(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun holdingLeftWhileWatchingListsTheChannelsProgrammesWithCatchUpMarked() {
        FixtureServer(historyListings = true, parallelClients = true).use { server ->
            val source = Source(name = "History fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            fun texts(view: View): List<String> = if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                else listOfNotNull((view as? TextView)?.takeIf { it.isShown }?.text?.toString())
            /** Each programme row on screen as its texts, in list order. */
            fun rows(activity: MainActivity): List<Pair<View, String>> = activity.window.decorView.findViewWithTag<RecyclerView>("library-items")?.let { list ->
                (0 until list.childCount).map(list::getChildAt).sortedBy(list::getChildAdapterPosition).map { it to texts(it).joinToString(" | ") }
            }.orEmpty()
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true && channelList(it)?.isShown != true }
                var watching: String? = null
                scenario.onActivity { watching = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                // Hold Left: the list opens, and the release does not also go to the previous channel.
                val down = android.os.SystemClock.uptimeMillis()
                instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0))
                Thread.sleep(ViewConfiguration.getLongPressTimeout() + 400L)
                instrumentation.sendKeySync(KeyEvent(down, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0))
                // Oldest first: the archived programme, the one the provider does not keep, what is on now, what is later.
                waitFor(scenario) { a -> rows(a).map { it.second }.let { r -> r.size >= 4 && r.any { "Archived fixture" in it } } }
                scenario.onActivity { activity ->
                    val shown = rows(activity).map { it.second }
                    val archived = shown.indexOfFirst { it.startsWith("Archived fixture") }; val missing = shown.indexOfFirst { it.startsWith("Not archived fixture") }
                    val now = shown.indexOfFirst { "| Now · " in it }; val later = shown.indexOfFirst { it.startsWith("Later fixture") }
                    assertTrue("$shown", archived in 0 until missing && missing < now && now < later)
                    assertTrue(shown[archived], shown[archived].endsWith("· Catch-up")); assertTrue(shown[missing], shown[missing].endsWith("· No catch-up"))
                    assertEquals("Holding Left keeps the channel", watching, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    assertTrue(Diagnostics.report(), "Programme list opened" in Diagnostics.report())
                    // The cursor starts on what is on now.
                    val focused = activity.currentFocus?.let { view -> generateSequence(view) { it.parent as? View }.firstOrNull { (it.tag as? String)?.startsWith("library-item:history:") == true } }
                    assertEquals("The cursor starts on the programme on now", rows(activity)[now].first, focused)
                    // OK on a programme marked Catch-up plays it from the archive.
                    rows(activity)[archived].first.performClick()
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/timeshift/test/test-secret/60/") == true }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun libraryRowsBelowTheScreenReadTheirTitlesAndPostersAhead() {
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true, shelfCategories = 12).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            // Twelve categories of 30 titles. The first 8 of each are its oldest and lowest rated, so only its own row shows them.
            val seeded = LibraryCatalog(store)
            seeded.Writer(source, "movie", stamp = System.currentTimeMillis() - 3600000L).apply {
                (1..12).flatMap { c -> (1..30).map { j -> OnDemand("movie:${c * 100 + j}", "Shelf $c title $j", "Category $c", "${server.base}/movie/test/test-secret/${c * 100 + j}.mp4", "movie",
                    poster = "${server.base}/art-c$c-$j.png", year = "2024", added = if (j <= 8) 1_000L + j else 1_700_000_000L + c * 100 + j, rating = if (j <= 8) 0f else 5f + j % 5) } }
                    .chunked(64).forEach(::append)
                finish((1..12).associate { "$it" to "Shelf $it" })
            }
            seeded.close()
            fun firstPosters(id: String) = (1..CatalogShelves.COLUMNS).map { "/art-c$id-$it.png" }
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { (it.currentFocus?.tag as? String)?.startsWith("library-item:") == true }
                // The remote rests on the first row: the two rows just below the screen get their titles and first posters.
                var ahead = emptyList<String>(); var beyond = emptyList<String>()
                waitFor(scenario) { a ->
                    val rows = a.window.decorView.findViewWithTag<CatalogShelves>("library-shelves")
                    @Suppress("UNCHECKED_CAST") val order = (CatalogShelves::class.java.getDeclaredField("shelves").apply { isAccessible = true }.get(rows) as List<CatalogShelves.Shelf>).map { it.id }
                    val last = (0 until rows.childCount).maxOf { rows.getChildAdapterPosition(rows.getChildAt(it)) }
                    ahead = order.drop(last + 1).take(CatalogShelves.READ_AHEAD_ROWS); beyond = order.drop(last + 1 + CatalogShelves.READ_AHEAD_ROWS)
                    ahead.size == CatalogShelves.READ_AHEAD_ROWS && beyond.isNotEmpty() && ahead.all { id -> firstPosters(id).all { (server.requests[it] ?: 0) > 0 } }
                }
                Thread.sleep(1500)
                assertTrue("Rows further down wait until the remote comes nearer: ${server.requests.keys.filter { it.startsWith("/art-") }}", beyond.none { id -> firstPosters(id).any(server.requests::containsKey) })
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aListCutAtAnOlderLimitGetsTheRestWhileTheRowsStayOpen() {
        // The provider sends 8 of its 16 movies and waits; the saved copy, made today, stopped at an older limit of 10 titles.
        FixtureServer(libraryCategories = true, slowVod = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            val seeded = LibraryCatalog(store)
            seeded.Writer(source, "movie").run {
                (1..10).map { n -> OnDemand("movie:${7000 + n}", "Saved title $n", "Category 1", "${server.base}/movie/test/test-secret/${7000 + n}.mp4", "movie", added = 1_700_000_000L + n) }.forEach { append(listOf(it)) }
                finish(mapOf("1" to "Fixture cinema", "2" to "Action", "3" to "Comedy"), capped = 10)
            }
            seeded.close()
            fun status(activity: MainActivity) = activity.window.decorView.findViewWithTag<TextView>("library-status").text.toString()
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-shelves").isShown && status(it) == "10 titles · Getting the rest of your titles…" }
                server.finishVodCatalog.countDown()
                // When the rest arrives the rows just say how many titles there are now: no instructions.
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-shelves").isShown && status(it) == "16 titles · Saved today" }
                assertTrue(Diagnostics.report(), "Saved catalog stopped at the old limit of 10 titles" in Diagnostics.report())
                assertEquals(1, server.catalogTargets.count { "get_vod_streams" in it })
            } } finally { server.finishVodCatalog.countDown(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aMovieListedInSeveralVersionsShowsOnceAndItsDetailsOfferEachVersion() {
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            fun movie(id: Int, name: String, category: Int) = OnDemand("movie:$id", name, "Category $category", "${server.base}/movie/test/test-secret/$id.mp4", "movie", poster = "${server.base}/logo.png", added = 1_700_000_000L + id, rating = 5f)
            // One movie in three versions (two in the same category), and two other films.
            val seeded = LibraryCatalog(store)
            seeded.Writer(source, "movie").run {
                append(listOf(movie(9001, "4KDV: Fixture Heroes (2011)", 1), movie(9002, "Another fixture film (2020)", 1), movie(9003, "4K: Fixture Heroes (2011)", 2),
                    movie(9004, "Fixture Heroes (2011)", 1), movie(9005, "Fixture comedy (2019)", 3)))
                finish(mapOf("1" to "Fixture cinema", "2" to "Action", "3" to "Comedy"))
            }
            seeded.close()
            fun text(scenario: ActivityScenario<MainActivity>, tag: String): String { var value = ""; scenario.onActivity { value = it.window.decorView.findViewWithTag<TextView>(tag)?.text?.toString().orEmpty() }; return value }
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                // Five entries, three movies.
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-shelves").isShown && text(scenario, "library-status") == "3 titles · Saved today" }
                // Searching finds the movie once, under its name without version tags.
                scenario.onActivity { it.window.decorView.findViewWithTag<android.widget.EditText>("library-search").setText("Fixture Heroes") }
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<RecyclerView>("library-items").let { it.isShown && it.adapter?.itemCount == 1 } && a.window.decorView.findViewWithTag<View>("library-item:movie:9001")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:movie:9001").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-versions")?.isShown == true }
                scenario.onActivity { a -> val view = a.window.decorView
                    assertEquals("Fixture Heroes (2011)", view.findViewWithTag<TextView>("library-spot-title").text.toString())
                    // The emulator has no Dolby Vision display, so the 4K copy leads and Dolby Vision is last, still one press away.
                    assertEquals(listOf("4K", "Standard", "4K Dolby Vision"), (0..2).map { view.findViewWithTag<TextView>("library-version:$it").text.toString() })
                    assertEquals("▶  Play · 4K", view.findViewWithTag<TextView>("library-play").text.toString())
                }
                assertTrue(Diagnostics.report(), "3 versions · Play starts 4K" in Diagnostics.report())
                device.takeScreenshot(File(context.getExternalFilesDir(null), "library-versions.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-version:2").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/movie/test/test-secret/9001.mp4" }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun searchShowsAMovieOnceAcrossVersionsWatchHistoryAndPlaylists() {
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            // Two playlists. The main one lists the movie three times (4K Dolby Vision, 4K, plain) and its 4K copy was
            // part-watched; the second lists it once, with the year after a dash.
            val main = Source(name = "Main fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val second = Source(name = "Second fixture", kind = "xtream", url = server.base, username = "other", password = "other-secret", allowHttp = true)
            store.saveSources(listOf(main, second)); store.active = main.id; store.bindProvider(main); store.bindProvider(second)
            fun movie(source: Source, id: Int, name: String) = OnDemand("movie:$id", name, "Category 1", "${server.base}/movie/${source.username}/${source.password}/$id.mp4", "movie", poster = "${server.base}/logo.png", added = 1_700_000_000L + id)
            val seeded = LibraryCatalog(store)
            seeded.Writer(main, "movie").run { append(listOf(movie(main, 9001, "4KDV: Fixture Heroes (2011)"), movie(main, 9002, "4K: Fixture Heroes (2011)"), movie(main, 9003, "Fixture Heroes (2011)"), movie(main, 9004, "Other fixture film (2020)"))); finish(mapOf("1" to "Fixture cinema")) }
            seeded.Writer(second, "movie").run { append(listOf(movie(second, 7001, "Fixture Heroes - 2011"))); finish(mapOf("1" to "Fixture cinema")) }
            seeded.close()
            store.saveWatchProgress(main, movie(main, 9002, "4K: Fixture Heroes (2011)").copy(playlistId = main.id), 60_000, 600_000)
            fun grid(activity: MainActivity) = activity.window.decorView.findViewWithTag<RecyclerView>("search-poster-grid")
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity {
                    it.window.decorView.findViewWithTag<View>("search").performClick()
                    it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Fixture Heroes")
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:vod").performClick() }
                // Five copies, one card, named without version tags; it stays one card once every source has answered.
                waitFor(scenario) { a -> grid(a)?.let { it.isShown && it.adapter?.itemCount == 1 } == true && a.window.decorView.findViewWithTag<TextView>("search-spot-title").text.toString() == "Fixture Heroes (2011)" }
                Thread.sleep(3000)
                scenario.onActivity { a -> assertEquals("Still one card after every source answered", 1, grid(a).adapter?.itemCount) }
                scenario.onActivity { a -> grid(a).getChildAt(0).performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-versions")?.isShown == true }
                scenario.onActivity { a -> val view = a.window.decorView
                    val names = (0..3).map { view.findViewWithTag<TextView>("library-version:$it")?.text?.toString() }
                    // The part-watched 4K copy is the one Play resumes; the second playlist's copy is one more version.
                    assertEquals(listOf("4K", "Standard", "Standard · Second fixture", "4K Dolby Vision"), names)
                    assertEquals("▶  Play · 4K", view.findViewWithTag<TextView>("library-play").text.toString())
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-version:2").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/movie/other/other-secret/7001.mp4" }
            } } finally { store.saveSources(emptyList()); store.removeCache(main); store.removeCache(second) }
        }
    }

    @Test fun backFromASearchResultReturnsToTheResults() {
        store.performanceOverlay = false
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Search fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channels = listOf(Channel("xc:1", "Fixture live", "Other", "${server.base}/live/test/test-secret/1.mp4"), Channel("xc:2", "Second live", "Other", "${server.base}/live/test/test-secret/2.mp4"))
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            fun search(activity: MainActivity) = activity.window.decorView.findViewWithTag<View>("playlist-search")
            fun focusedTag(activity: MainActivity) = activity.currentFocus?.tag as? String
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity {
                    it.window.decorView.findViewWithTag<View>("search").performClick()
                    it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Second")
                }
                // A live channel from the results plays full screen; Back returns to the results, on that channel.
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:xc:2")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-result:live:xc:2").performClick() }
                waitFor(scenario) { a -> !search(a).isShown && find<PlayerView>(a.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/live/test/test-secret/2.mp4" && channelList(a)?.isShown != true }
                device.pressBack()
                waitFor(scenario) { a -> search(a).isShown && a.window.decorView.findViewWithTag<EditText>("guide-search-input").text.toString() == "Second" && focusedTag(a) == "search-result:live:xc:2" }
                // A movie opens its details page; Back returns to the results on that movie, not to Library.
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Fixture") }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:vod").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:movie:101")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-result:movie:101").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-play")?.isShown == true }
                device.pressBack()
                waitFor(scenario) { a -> search(a).isShown && !a.window.decorView.findViewWithTag<View>("library-details").isShown && focusedTag(a) == "search-result:movie:101" }
                // Picking it again and playing it: Back from the movie returns to its details, then to the results.
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-result:movie:101").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-play")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-play").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/movie/test/test-secret/101.") == true }
                // OK shows the movie's controls; Back closes them and the movie keeps playing.
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("on-demand-controls")?.isShown == true }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("on-demand-controls")?.isShown != true }
                Thread.sleep(500)
                scenario.onActivity { activity ->
                    assertTrue("The movie keeps playing", find<PlayerView>(activity.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/movie/test/test-secret/101.") == true)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("library-play")?.isShown == true)
                }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-play")?.isShown == true }
                device.pressBack()
                waitFor(scenario) { a -> search(a).isShown && focusedTag(a) == "search-result:movie:101" }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun leavingASearchForAMovieAfterWatchingItLeavesTheWholeGuideReachable() {
        store.performanceOverlay = false
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Search fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channels = listOf(Channel("xc:1", "Fixture live", "Other", "${server.base}/live/test/test-secret/1.mp4"), Channel("xc:2", "Second live", "Other", "${server.base}/live/test/test-secret/2.mp4"))
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            fun search(activity: MainActivity) = activity.window.decorView.findViewWithTag<View>("playlist-search")
            fun rows(activity: MainActivity) = (channelList(activity)!!.adapter as GuideAdapter).channels.size
            fun filter(activity: MainActivity) = MainActivity::class.java.getDeclaredField("query").apply { isAccessible = true }.get(activity) as String
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                // A search for a movie, watched and stopped: nothing plays afterwards, as on the owner's onn.
                scenario.onActivity {
                    it.window.decorView.findViewWithTag<View>("search").performClick()
                    it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Fixture movie")
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:vod").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:movie:101")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-result:movie:101").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-play")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-play").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/movie/test/test-secret/101.") == true }
                closeDemandControls(scenario)
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-play")?.isShown == true }
                device.pressBack(); waitFor(scenario) { a -> search(a).isShown && (a.currentFocus?.tag as? String) == "search-result:movie:101" }
                // Closing Search: its words match no channel, so they do not stay behind as a filter that empties the guide.
                device.pressBack()
                waitFor(scenario) { a -> !search(a).isShown && rows(a) == 2 && filter(a).isEmpty() && channelList(a)?.hasFocus() == true }
                device.pressBack(); waitFor(scenario) { a -> a.window.decorView.findViewWithTag<View>("category-column")?.hasFocus() == true }
                // A search that does match channels still filters the guide when Search closes.
                scenario.onActivity {
                    it.window.decorView.findViewWithTag<View>("search").performClick()
                    it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Second")
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:xc:2")?.isShown == true }
                device.pressBack()
                waitFor(scenario) { a -> !search(a).isShown && rows(a) == 1 && filter(a) == "Second" }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun theCursorMovesWithASoftTickInsteadOfTheSystemClickAndCanBeSilent() {
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source); store.navigationSound = true
            val seeded = LibraryCatalog(store)
            seeded.Writer(source, "movie").run { (1..30).map { n -> OnDemand("movie:${6000 + n}", "Tick title $n", "Category 1", "${server.base}/movie/test/test-secret/${6000 + n}.mp4", "movie", poster = "${server.base}/logo.png", added = 1_700_000_000L + n) }.chunked(30).forEach(::append); finish(mapOf("1" to "Fixture cinema")) }
            seeded.close()
            fun focusTag(scenario: ActivityScenario<MainActivity>): String { var tag = ""; scenario.onActivity { tag = it.currentFocus?.tag as? String ?: "" }; return tag }
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:6030" }
                // Right along the newest row: the cursor moves poster by poster, each with one soft tick.
                val before = NavigationSound.played
                device.pressDPadRight(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:6029" }
                device.pressDPadRight(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:6028" }
                assertEquals("One tick per move", before + 2, NavigationSound.played)
                // Off: the cursor still moves, silently.
                store.navigationSound = false
                device.pressDPadLeft(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:6029" }
                assertEquals(before + 2, NavigationSound.played)
                assertTrue(focusTag(scenario).startsWith("library-item:"))
            } } finally { store.navigationSound = true; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aMovieWithDtsSoundIsDecodedBySpareOnABoxWithoutADtsDecoder() {
        // The emulator, like an onn 4K Pro whose DTS decoder plays silence, has no DTS decoder Spare will use.
        // The movie is Media3's DTS-HD Master Audio 7.1 sample, the sound on the owner's silent copies.
        FixtureServer(libraryCategories = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source); store.decodeAudioOnBox = false
            val movie = OnDemand("movie:8801", "Fixture DTS film (2011)", "Category 1", "${server.base}/movie/test/test-secret/8801.mkv", "movie", poster = "${server.base}/logo.png", added = 1_700_000_000L)
            LibraryCatalog(store).run { Writer(source, "movie").run { append(listOf(movie)); finish(mapOf("1" to "Fixture cinema")) }; close() }
            assertTrue("Spare's DTS decoder is in the app", androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable())
            assertTrue(androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(androidx.media3.common.MimeTypes.AUDIO_DTS))
            fun report(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("playbackDiagnostics").apply { isAccessible = true }.invoke(activity) as String
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player != null }
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("playOnDemand", OnDemand::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, movie.copy(playlistId = source.id), 0L) }
                // The DTS track plays, decoded in Spare rather than left silent or unplayable.
                waitFor(scenario) { a -> (find<PlayerView>(a.window.decorView)!!.player as androidx.media3.exoplayer.ExoPlayer).audioFormat?.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_DTS_HD && "decoded by Spare" in report(a) }
                scenario.onActivity { a ->
                    assertTrue(report(a), Regex("Audio · DTS-HD · \\d+ channels · decoded by Spare \\(ffmpeg").containsMatchIn(report(a)))
                    assertTrue(Diagnostics.report(), Regex("Last sound: Fixture DTS film \\(2011\\) · .*DTS-HD.*\\(playing\\) · decoded by Spare").containsMatchIn(Diagnostics.report()))
                    assertFalse("No \"No sound\" note", Diagnostics.report().contains("No sound ·"))
                }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aMovieWhoseConnectionDropsKeepsPlayingFromItsBufferWhileSpareReconnects() {
        // 0.57.4 on the owner's main onn: a 4K film buffered for a few seconds every 2 to 5 minutes while TiviMate played it
        // smoothly. A dropped connection whose reconnect was refused made Spare stop the player and start the film again.
        FixtureServer(libraryCategories = true, parallelClients = true, longVideo = true, droppingMovie = true).use { server ->
            val source = Source(name = "Dropping fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            val movie = OnDemand("movie:5501", "Dropping film (2025)", "Category 1", "${server.base}/movie/test/test-secret/5501.ts", "movie", poster = "${server.base}/logo.png", added = 1_700_000_000L)
            LibraryCatalog(store).run { Writer(source, "movie").run { append(listOf(movie)); finish(mapOf("1" to "Fixture cinema")) }; close() }
            fun reconnects(activity: MainActivity) = MainActivity::class.java.getDeclaredField("playbackReconnects").apply { isAccessible = true }.getInt(activity)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player != null }
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("playOnDemand", OnDemand::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, movie.copy(playlistId = source.id), 0L) }
                waitFor(scenario) { server.refusedMovie.get() >= 2 }
                // Past the point where the first connection ended, without a restart: the buffer carried the film across.
                val until = System.currentTimeMillis() + 60_000; var position = 0L
                while (System.currentTimeMillis() < until) {
                    scenario.onActivity { position = find<PlayerView>(it.window.decorView)!!.player!!.currentPosition }
                    if (position > 20_000) break
                    Thread.sleep(500)
                }
                scenario.onActivity { activity ->
                    assertTrue("position $position", position > 20_000)
                    assertEquals("The film was never restarted", 0, reconnects(activity))
                    assertTrue("The refused reconnect was retried, not restarted", server.refusedMovie.get() >= 2)
                }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aLibraryDatabaseFromAnEarlierVersionMarksAListCutAtTheOldLimit() {
        // The tables as versions up to 0.51.45 made them, which kept the first 100,000 titles of a longer list without noting it.
        val db = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE meta(scope TEXT PRIMARY KEY, gen TEXT NOT NULL, updated INTEGER NOT NULL, complete INTEGER NOT NULL, count INTEGER NOT NULL, provider TEXT NOT NULL, labels BLOB NOT NULL)")
            for ((scope, count) in listOf("cut" to 100_000, "whole" to 32_553)) db.execSQL("INSERT INTO meta VALUES (?, 'g', 0, 1, ?, 'p', x'00')", arrayOf<Any>(scope, count))
            CatalogDb.upgrade(db, 1)
            fun capped(scope: String) = db.rawQuery("SELECT capped FROM meta WHERE scope = ?", arrayOf(scope)).use { it.moveToFirst(); it.getInt(0) }
            assertEquals("A list of exactly the old limit stopped there", 100_000, capped("cut"))
            assertEquals("A shorter list is the provider's whole list", 0, capped("whole"))
        } finally { db.close() }
    }

    @Test fun aBackgroundCatalogCheckCutOffByTheProviderTriesAgainAtOnce() {
        FixtureServer(libraryCategories = true, slowVod = true, cutVodOnce = true, parallelClients = true).use { server ->
            val source = Source(name = "Cut catalog", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.bindProvider(source)
            // A saved copy three days old, so loading it is a background check for new titles.
            val seeded = LibraryCatalog(store)
            seeded.Writer(source, "movie", System.currentTimeMillis() - 3 * 86400000L).run {
                (1..40).map { n -> OnDemand("movie:${7000 + n}", "Saved title $n", "Test", "${server.base}/movie/test/test-secret/${7000 + n}.mp4", "movie") }.chunked(20).forEach(::append)
                finish(mapOf("1" to "Fixture cinema"))
            }
            seeded.close()
            server.finishVodCatalog.countDown()
            val catalog = LibraryCatalog(store)
            try {
                val saved = catalog.load(source, "movie")
                assertEquals("The second try saves the provider's whole list", 16, saved.count)
                assertTrue(saved.complete)
                assertEquals("One cut-off download, then one more straight away", 2, server.catalogTargets.count { "get_vod_streams" in it })
            } finally { catalog.close(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aFirstLibraryVisitShowsCalmStepsUntilTheRowsAreReady() {
        // The provider sends 8 of its 16 movies and waits, like a large list arriving over a minute.
        FixtureServer(libraryCategories = true, slowVod = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            fun loadingView(activity: MainActivity) = activity.window.decorView.findViewWithTag<View>("library-loading-view")
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { a -> loadingView(a)?.isShown == true && a.window.decorView.findViewWithTag<TextView>("library-loading-headline").text.toString() == "Saving your movies to this TV" }
                Thread.sleep(1600); device.takeScreenshot(File(context.getExternalFilesDir(null), "library-loading.png"))
                // Titles keep arriving, but nothing grows or redraws: no partial grid, no count, no title under a cursor.
                repeat(6) {
                    scenario.onActivity { a -> val root = a.window.decorView
                        assertTrue("The steps stay up while the list is saved", loadingView(a).isShown)
                        assertFalse("No partial grid while loading", root.findViewWithTag<View>("library-items").isShown)
                        assertFalse("No count while loading: ${root.findViewWithTag<TextView>("library-status").text}", root.findViewWithTag<TextView>("library-status").text.any(Char::isDigit))
                        assertTrue("No title under a cursor while loading", root.findViewWithTag<TextView>("library-spot-title").text.isBlank())
                        assertTrue("The remote rests on the steps, not the menu: ${a.currentFocus?.tag}", loadingView(a).hasFocus())
                        assertFalse("The menu stays closed over the steps", root.findViewWithTag<NavigationRail>("app-navigation").wide)
                    }
                    Thread.sleep(400)
                }
                server.finishVodCatalog.countDown()
                // The whole list is saved: the rows replace the steps, and the count shows once.
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<View>("library-shelves").isShown && loadingView(a)?.isShown == false &&
                    a.window.decorView.findViewWithTag<TextView>("library-status").text.toString() == "16 titles · Saved today" }
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<View>("library-shelves").hasFocus() && (a.currentFocus?.tag as? String)?.startsWith("library-item:") == true }
            } } finally { server.finishVodCatalog.countDown(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun idleBrowsingWarmsOnlyArtworkFromSavedCatalogs() {
        FixtureServer(libraryCategories = true, parallelClients = true).use { server ->
            val source = Source(name = "Idle Library", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.bindProvider(source)
            // A unique address, so a poster another test left on disk never stands in for this one.
            val poster = "${server.base}/logo.png?idle=${System.nanoTime()}"
            val reader = LibraryCatalog(store)
            val worker = Executors.newSingleThreadExecutor()
            var warmer: IdleBrowse? = null
            try {
                val entries = (1..24).map { OnDemand("movie:$it", "Saved title $it", "Test", "", "movie", poster = if (it <= 8) poster else "${server.base}/art-$it.png") }
                reader.Writer(source, "movie").apply { append(entries); finish(emptyMap()) }
                instrumentation.runOnMainSync { warmer = IdleBrowse(context, worker, reader) {} }
                val until = android.os.SystemClock.uptimeMillis() + 10000
                var ready = false
                val files = posterFiles(context)
                while (!ready && android.os.SystemClock.uptimeMillis() < until) {
                    instrumentation.runOnMainSync { warmer?.step(source, emptyList()) }
                    ready = files.saved(poster) != null
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("The poster is cached on disk before Library opens", ready)
                assertEquals(1, server.requests["/logo.png"])
                assertTrue("A cold idle pass reads only the first saved titles", server.requests.keys.none { it.startsWith("/art-") })
                assertTrue("No provider catalog import", server.vodImportTargets.isEmpty())
            } finally {
                instrumentation.runOnMainSync { warmer?.pause() }
                worker.shutdownNow(); reader.close(); store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun nostalgiaSweepStopsWhenTheWaitIsHidden() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val accent = Color.GREEN
            lateinit var bar: TuningBar
            lateinit var sweep: android.graphics.drawable.Animatable
            lateinit var standard: android.graphics.drawable.Drawable
            scenario.onActivity { activity ->
                bar = TuningBar(activity).apply { isIndeterminate = true; visibility = View.GONE }
                standard = bar.indeterminateDrawable
                bar.useNostalgia(true, accent)
                sweep = bar.indeterminateDrawable as android.graphics.drawable.Animatable
                assertNotSame(standard, sweep)
                assertFalse(sweep.isRunning)
                activity.addContentView(bar, android.widget.FrameLayout.LayoutParams(320, 14))
                bar.visibility = View.VISIBLE
            }
            waitFor(scenario) { sweep.isRunning }
            scenario.onActivity {
                val bitmap = Bitmap.createBitmap(320, 14, Bitmap.Config.ARGB_8888)
                try {
                    bar.draw(Canvas(bitmap))
                    assertTrue("The CRT sweep draws accent-colored pixels", (0 until 320).any { x -> Color.green(bitmap.getPixel(x, 7)) > 0 })
                } finally { bitmap.recycle() }
                bar.visibility = View.GONE
            }
            waitFor(scenario) { !sweep.isRunning }
            scenario.onActivity {
                bar.useNostalgia(false, accent)
                assertSame(standard, bar.indeterminateDrawable)
            }
        }
    }

    @Test fun backgroundGuideFillCoversUnvisitedChannelsOnceAndSavesThem() {
        FixtureServer(channelCount = 40, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Background guide", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val lineup = Importers.xtream(source)
            store.cache(source, Guide(lineup.channels, emptyMap()))
            store.markPlaylistRefreshed(source); store.markEpgRefreshed(source); store.markFullEpgAttempted(source); store.setRefreshPolicy(source, RefreshPolicy())
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var media = ""
                scenario.onActivity { activity ->
                    media = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                    // Skip the 45 s startup grace period instead of sleeping through it.
                    MainActivity::class.java.getDeclaredField("epgBackgroundNotBefore").apply { isAccessible = true }.setLong(activity, 0L)
                    MainActivity::class.java.getDeclaredMethod("pumpEpg").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:1"]?.isNotEmpty() == true
                }
                assertTrue("Fullscreen only requests the playing channel after the grace period", server.requestedEpgTargets.all { it.contains("stream_id=1&") || it.endsWith("stream_id=1") })
                device.pressBack()
                waitFor(scenario) { channelList(it)?.hasFocus() == true }
                // Once you're browsing, the guide fills out and saves its listings for the next visit.
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.channels.all { channel -> guide.programmes[guide.key(channel)].orEmpty().isNotEmpty() }
                }
                assertEquals("Background fill never repeats a channel", server.requestedEpgTargets.size, server.requestedEpgTargets.toSet().size)
                scenario.onActivity { activity ->
                    assertEquals("Background fill keeps the playing connection", media, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    (MainActivity::class.java.getDeclaredField("cacheEpg").apply { isAccessible = true }.get(activity) as Runnable).run()
                }
                val last = lineup.channels.last()
                waitFor(scenario) { store.cachedProgrammes(source)[last.guideId.ifBlank { last.id }].orEmpty().isNotEmpty() }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    /** 0.51.32 filled game channels here and the onn skipped frames while it ran: full screen reads only the playing channel. */
    @Test fun steadyFullScreenPlaybackReadsOnlyThePlayingChannelEvenWithLiveGames() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Game listings", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val now = System.currentTimeMillis()
            val playing = Channel("xc:1", "Xtream Test 1", "News", "${server.base}/channel1.mp4")
            val network = Channel("xc:2", "US: ESPN FHD", "US | Sports", "${server.base}/channel2.mp4")
            val others = (3..12).map { Channel("xc:$it", "Xtream Test $it", "News", "${server.base}/channel$it.mp4") }
            val scores = LiveSportsSnapshot(listOf(LiveEvent("listings", setOf("nfl:SEA", "nfl:SF"), now - 1200000, networks = listOf("ESPN"))), now, setOf("NFL"))
            store.recentFeeds = emptyList(); store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(playing, network) + others, emptyMap()))
            store.markPlaylistRefreshed(source); store.markEpgRefreshed(source); store.markFullEpgAttempted(source); store.setRefreshPolicy(source, RefreshPolicy())
            fun requested(id: Int) = server.requestedEpgTargets.any { it.contains("stream_id=$id&") || it.endsWith("stream_id=$id") }
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var media = ""
                scenario.onActivity { activity ->
                    media = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                    activity.liveSportsLoader = { scores.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    // Skip the 45 s startup grace period instead of sleeping through it.
                    MainActivity::class.java.getDeclaredField("epgBackgroundNotBefore").apply { isAccessible = true }.setLong(activity, 0L)
                }
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:1"]?.isNotEmpty() == true
                }
                Thread.sleep(3000)
                assertFalse("Until playback has run smoothly for a while, full screen reads only the playing channel", (2..12).any(::requested))
                scenario.onActivity { activity ->
                    // Playback has been steady for more than a minute (instead of waiting 30-60 s in the test).
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity)
                    PlaybackWarmup::class.java.getDeclaredField("token").apply { isAccessible = true }.setInt(warmup, MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity))
                    PlaybackWarmup::class.java.getDeclaredField("readySince").apply { isAccessible = true }.setLong(warmup, 1L)
                }
                // The refresh tick runs every second; give a playback-time fill several chances to start.
                Thread.sleep(8000)
                assertFalse("Steady full-screen playback does not read the live game's channel", requested(2))
                assertFalse("Steady full-screen playback does not fill the lineup", (3..12).any(::requested))
                scenario.onActivity { activity ->
                    assertEquals("Playback keeps its connection", media, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    assertTrue(find<PlayerView>(activity.window.decorView)!!.player!!.isPlaying)
                }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun fullProviderEpgWarmsUnvisitedChannelsAndSurvivesRestart() {
        FixtureServer(channelCount = 96, fullGuide = true).use { server ->
            val source = Source(name = "Full guide fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val lineup = Importers.xtream(source)
            store.cache(source, Guide(lineup.channels.map { it.copy(xmltvId = "") }, emptyMap())); store.markPlaylistRefreshed(source)
            // Simulate an upgrade: the saved lineup predates preservation of XMLTV IDs.
            context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().remove("epg-mapping-${source.id}").apply()
            assertEquals(6, store.refreshPolicy(source).epgHours)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null; var media = ""
                waitFor(scenario) { player = find<PlayerView>(it.window.decorView)?.player; player?.repeatMode = Player.REPEAT_MODE_ONE; player?.isPlaying == true }
                scenario.onActivity { activity ->
                    media = player!!.currentMediaItem!!.mediaId
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity) as PlaybackWarmup
                    warmup.reset(); warmup.ready(token, android.os.SystemClock.elapsedRealtime() - 61000, true, 6000)
                    MainActivity::class.java.getDeclaredMethod("autoRefresh", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, source, false)
                }
                try { waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:96"]?.isNotEmpty() == true && store.fullEpgAttempted(source)
                } } catch (failure: AssertionError) { throw AssertionError("Full guide: requests=${server.requests}, mapped=${store.epgMappingReady(source)}, attempted=${store.fullEpgAttempted(source)}, error=${store.refreshError(source)}", failure) }
                assertEquals("One full download fills channels never visited", 1, server.requests["/xmltv.php"])
                assertEquals("The old lineup recovered the provider XMLTV mapping", "test96", store.cachedLineup(source)!!.channels.last().xmltvId)
                assertFalse(server.requestedEpgTargets.any { "stream_id=96&" in it || it.endsWith("stream_id=96") })
                scenario.onActivity { assertSame(player, find<PlayerView>(it.window.decorView)!!.player); assertEquals(media, player!!.currentMediaItem!!.mediaId) }
                assertTrue(store.cachedProgrammes(source)["xc:96"].orEmpty().isNotEmpty())
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("focusChannelAt", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 95)
                }
                waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.nativeId == "xc:96" }
                scenario.onActivity { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    assertNotNull(guide.at(guide.channels.first { it.nativeId == "xc:96" }, System.currentTimeMillis()))
                }
                scenario.recreate()
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:96"].orEmpty().isNotEmpty()
                }
                assertEquals("Saved guide is reused across launches", 1, server.requests["/xmltv.php"])
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun xtreamCategoryFailureStillLoadsLiveVideo() {
        FixtureServer(failCategories = true).use { server ->
            val source = Source(name = "Category Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY && it.videoSize.width > 0 } == true }
                assertEquals("/live/test/test-secret/1.mp4", playingPath(scenario))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun dialogRemoteFocusIsVisibleAndHttpConsentPreservesHttps() {
        FixtureServer().use { server ->
            val seed = Source(name = "UI Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(seed)); store.active = seed.id
            val https = Source(name = "HTTPS Test", kind = "xtream", url = "https://provider.example:8443/panel/", username = "test", password = "test-secret")
            var dialog: AlertDialog? = null
            var saved: Source? = null
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.playbackState == Player.STATE_READY }
                scenario.onActivity { activity ->
                    dialog = SourceDialog.show(activity, https, { saved = it }, {})
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                }
                waitFor(scenario) { dialog?.window?.decorView?.hasWindowFocus() == true }
                assertTrue(device.wait(Until.hasObject(By.text("Save").focused(true)), 5000))
                device.waitForIdle()
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val save = dialog!!.getButton(AlertDialog.BUTTON_POSITIVE)
                    assertTrue(save.hasFocus())
                    assertEquals(Color.BLACK, save.currentTextColor)
                    val bitmap = Bitmap.createBitmap(save.width, save.height, Bitmap.Config.ARGB_8888)
                    save.background.draw(Canvas(bitmap))
                    assertEquals(Color.WHITE, bitmap.getPixel(save.width / 2, save.height / 2))
                    bitmap.recycle()
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "source-save.png"))
                device.pressDPadRight(); instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val cancel = dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE)
                    assertTrue("D-pad right from Save must reach the adjacent Cancel action", cancel.hasFocus())
                    assertEquals(Color.BLACK, cancel.currentTextColor)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "source-cancel.png"))
                device.pressDPadCenter(); instrumentation.waitForIdleSync()
                assertNull("Cancel must not save", saved)
                scenario.onActivity { activity ->
                    dialog = SourceDialog.show(activity, https, { saved = it }, {})
                    val root = dialog!!.window!!.decorView
                    val username = root.findViewWithTag<EditText>("Xtream username")
                    val password = root.findViewWithTag<EditText>("Xtream password")
                    val epg = root.findViewWithTag<EditText>("Optional XMLTV guide link (provider guide fills gaps)")
                    assertTrue(password.transformationMethod is android.text.method.PasswordTransformationMethod)
                    username.requestFocus()
                    username.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                    username.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                    assertTrue("D-pad Down must not skip the password", password.hasFocus())
                    password.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
                    assertTrue("Keyboard Next reaches the guide URL", epg.hasFocus())
                    username.requestFocus(); username.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
                    assertTrue("Keyboard Next from username reaches password", password.hasFocus())
                    root.findViewWithTag<CheckBox>("playlist-show-password").performClick()
                    assertNull(password.transformationMethod)
                    root.findViewWithTag<CheckBox>("playlist-show-password").performClick()
                    assertTrue(password.transformationMethod is android.text.method.PasswordTransformationMethod)
                    fun httpConsent(view: View): CheckBox? {
                        if (view is CheckBox && view.tag != "playlist-show-password" && view.tag != "playlist-advanced") return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) httpConsent(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val allow = httpConsent(root)!!
                    allow.performClick()
                    assertEquals(https.url, root.findViewWithTag<EditText>("Playlist URL").text.toString())
                    // Advanced options start folded away; opened, Xtream offers a backup server, then an optional User-Agent.
                    val backup = root.findViewWithTag<EditText>("Optional backup server URL (used when the main server can't be reached)")
                    val agent = root.findViewWithTag<EditText>("Optional User-Agent (only if your provider asks for one)")
                    assertFalse(backup.isShown || agent.isShown)
                    root.findViewWithTag<CheckBox>("playlist-advanced").performClick()
                    assertTrue(backup.isShown && agent.isShown)
                    epg.requestFocus(); epg.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
                    assertTrue("Keyboard Next from the guide URL reaches the backup server", backup.hasFocus())
                    backup.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
                    assertTrue("Keyboard Next from the backup server reaches the User-Agent", agent.hasFocus())
                    backup.setText("https://backup.example:8443"); agent.setText("FixturePlayer/2.0")
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                }
                waitFor(scenario) { dialog?.window?.decorView?.hasWindowFocus() == true }
                assertTrue(device.wait(Until.hasObject(By.text("Save").focused(true)), 5000))
                device.pressDPadCenter(); instrumentation.waitForIdleSync()
                assertNotNull(saved)
                assertEquals(https.url, saved!!.url)
                assertTrue(saved!!.allowHttp)
                assertEquals("https://backup.example:8443", saved!!.backupUrl); assertEquals("FixturePlayer/2.0", saved!!.userAgent)
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("playOnDemand", OnDemand::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
                        .invoke(activity, OnDemand("movie:remove", "Removal fixture", "", "${server.base}/channel1.mp4"), 0L)
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/channel1.mp4" }
                lateinit var removedCatalog: LibraryCatalog
                lateinit var abandonedImport: LibraryCatalog.Writer
                scenario.onActivity { activity ->
                    removedCatalog = (MainActivity::class.java.getDeclaredField("library").apply { isAccessible = true }.get(activity) as LibraryController).repository
                    abandonedImport = removedCatalog.Writer(seed, "movie", indexOnly = true).apply {
                        append(listOf(OnDemand("movie:909", "Abandoned import", "Films", "${server.base}/channel1.mp4")))
                    }
                    val removal = MainActivity::class.java.getDeclaredMethod("editSource", Source::class.java, PhoneSetupResult::class.java).apply { isAccessible = true }.invoke(activity, seed, null) as AlertDialog
                    removal.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                }
                waitFor(scenario) { store.sources().isEmpty() }
                try { abandonedImport.finish(emptyMap()); fail("A removed source's catalog producer was still current") } catch (_: java.io.InterruptedIOException) { }
                try { abandonedImport.append(listOf(OnDemand("movie:910", "Late page", "Films"))); fail("A removed source accepted another catalog page") } catch (_: java.io.InterruptedIOException) { }
                assertNull("Rejected catalog completion must not republish a partial snapshot", removedCatalog.memory(seed, "movie"))
                assertTrue(removedCatalog.matches(seed, VodStart.Request("Abandoned import")).isEmpty())
                scenario.onActivity { activity ->
                    assertNull(MainActivity::class.java.getDeclaredField("onDemand").apply { isAccessible = true }.get(activity))
                    assertEquals("", MainActivity::class.java.getDeclaredField("playbackUrl").apply { isAccessible = true }.get(activity))
                    assertEquals(0, find<PlayerView>(activity.window.decorView)!!.player!!.mediaItemCount)
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED).moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertEquals("Removed VOD must not reopen after returning to the app", 0, player.mediaItemCount)
                    assertFalse(player.isPlaying)
                }
                assertTrue(store.sources().isEmpty())
            }
            store.saveSources(emptyList()); store.removeCache(seed)
        }
    }

    @Test fun transportWithoutAccessUnitDelimitersAndCategorySidebar() {
        FixtureServer(tsMode = true, slowPlaylist = true).use { server ->
            val source = Source(name = "TS Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("import-progress")?.isShown == true }
                val preparing = java.util.concurrent.CountDownLatch(2); val releaseApply = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val workers = MainActivity::class.java.getDeclaredField("imports").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    repeat(2) { workers.execute { preparing.countDown(); releaseApply.await(15, java.util.concurrent.TimeUnit.SECONDS) } }
                }
                try {
                    assertTrue(preparing.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    waitFor(scenario) { (server.requests["/playlist.m3u"] ?: 0) > 0 && !PlaylistRefresh.running(source.id) }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertTrue("Progress remains animated while the downloaded lineup is being prepared", activity.window.decorView.findViewWithTag<View>("import-progress").isShown)
                        val empty = MainActivity::class.java.getDeclaredField("emptyInfo").apply { isAccessible = true }.get(activity) as View
                        assertFalse("An unapplied lineup must not show an empty-channel error", empty.isShown)
                    }
                    device.pressMenu()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown == true }
                } finally { releaseApply.countDown() }
                waitFor(scenario) { activity ->
                    (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.isNotEmpty()
                }
                scenario.onActivity { activity ->
                    assertTrue("Late startup data must not close a screen opened by the user", activity.window.decorView.findViewWithTag<View>("settings-panel").isShown)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("import-progress").isShown)
                }
                leaveSettings(scenario)
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.videoSize.width > 0 && it.playbackState == Player.STATE_READY } == true }
                waitFor(scenario) { store.videoInfo(source).isNotEmpty() }
                assertEquals("/channel1.ts", playingPath(scenario))
                waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.pressDPadLeft(); instrumentation.waitForIdleSync()
                assertTrue(device.wait(Until.hasObject(By.text("All channels").focused(true)), 5000))
                repeat(2) { device.pressDPadDown() }; device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 4 }
                assertEquals("Category browsing must keep the current video", "/channel1.ts", playingPath(scenario))
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertEquals(activity.window.decorView.width, find<PlayerView>(activity.window.decorView)!!.width)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun xtreamRetriesHlsWhenTsEndpointFails() {
        listOf(503, 404, 410).forEach { segmentStatus -> FixtureServer(fallbackHls = true, transientSegmentErrors = 2, transientSegmentStatus = segmentStatus).use { server ->
            val source = Source(name = "Fallback Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.videoSize.width > 0 && it.playbackState == Player.STATE_READY } == true }
                assertEquals("/live/test/test-secret/1.m3u8", playingPath(scenario))
                assertEquals("Transient segment failures must not reopen the channel manifest", 1, server.requests["/live/test/test-secret/1.m3u8"])
                assertTrue("Playback must recover after both failed segment reads", (server.requests["/segment.ts"] ?: 0) >= 3)
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertEquals("Provider live timing must not be replaced with a fixed offset", androidx.media3.common.C.TIME_UNSET, player.currentMediaItem!!.liveConfiguration!!.targetOffsetMs)
                }
                waitFor(scenario) { store.videoInfo(source).isNotEmpty() }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
        }
    }

    @Test fun sportsQualityDoesNotHideGuideChannelsOrInterruptPlayback() {
        FixtureServer(longVideo = true).use { server ->
            val source = Source(name = "Sports quality fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val profile = GuideFilters(1080, FrameRateFilter.onlySixty)
            val categories = GuideFilters(groups = setOf("News"))
            store.setSportsFilters(source, profile); store.setFilters(source, categories)
            val legacy = VideoInfo(3840, 2160, 29.97f).json().put("detector", 3)
            context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().putString("video-info-${source.id}", org.json.JSONObject().put("old", legacy).toString()).commit()
            assertEquals("Old display-only FPS must be remeasured", 0f, store.videoInfo(source).getValue("old").fps, 0f)
            store.saveVideoInfo(source, emptyMap())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true && channelList(it)?.adapter?.itemCount == 4 }
                var mediaId = ""
                scenario.onActivity { activity ->
                    mediaId = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                    activity.window.decorView.findViewWithTag<View>("settings").requestFocus()
                }
                device.pressDPadCenter()
                scenario.onActivity { assertNull(it.window.decorView.findViewWithTag<View>("settings-nav:Guide filters")) }
                activateSetting(scenario, "Sports"); activateSetting(scenario, "Stream quality")
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-manager")?.isShown == true }
                scenario.onActivity { activity ->
                    val manager = activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager")
                    assertNull("Resolution is always ranked first", manager.findViewWithTag<View>("settings-option:HD · 720p or better"))
                    manager.findViewWithTag<View>("settings-option:30 fps · includes 29.97").performClick()
                    assertTrue("Changing quality keeps Sports settings open", manager.opened)
                    assertEquals(0, store.sportsFilters(source).minHeight)
                    assertFalse(30 in store.sportsFilters(source).excludedFps)
                    assertTrue(50 in store.sportsFilters(source).excludedFps)
                    assertEquals(categories, store.filters(source))
                    assertEquals("Sports quality does not retune playback", mediaId, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
                device.pressBack(); device.pressBack(); leaveSettings(scenario)
                waitFor(scenario) { channelList(it)?.isShown == true && channelList(it)?.adapter?.itemCount == 4 }
                val saved = store.sportsFilters(source)
                store.saveVideoInfo(source, emptyMap()); scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.adapter?.itemCount == 4 && channelList(it)?.isShown == true }
                assertEquals(saved, SecureStore(context).sportsFilters(source)); assertEquals(categories, store.filters(source))
                scenario.onActivity { activity ->
                    val channels = listOf(Channel("local:king", "KING5 FHD", "News", "${server.base}/channel1.ts"),
                        Channel("local:kiro", "KIRO HD", "News", "${server.base}/channel2.ts"), Channel("other:abc", "WABC New York", "News", "${server.base}/channel3.ts"),
                        Channel("show:king", "King of Queens", "News", "${server.base}/channel4.ts"), Channel("show:24", "24/7 KING TV", "News", "${server.base}/channel5.ts"),
                        Channel("show:fox", "FOX Seattle 24x7 Shows", "News", "${server.base}/channel6.ts"),
                        Channel("local:kgw", "KGW Portland OR", "News", "${server.base}/channel7.ts"),
                        Channel("other:wgme", "WGME Portland ME", "News", "${server.base}/channel8.ts"))
                    val localGuide = Guide(channels, emptyMap()); store.cache(source, localGuide); store.markPlaylistRefreshed(source)
                    MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, localGuide)
                    activity.window.decorView.findViewWithTag<View>("settings").performClick()
                    val panel = activity.window.decorView.findViewWithTag<SettingsPanel>("settings-panel")
                    panel.selectSetting("Local channels")
                    val location = panel.findViewWithTag<EditText>("local-location-input")
                    location.requestFocus(); location.setText("Seatt")
                    assertNull(panel.findViewWithTag<View>("settings-option:Save location"))
                    assertTrue(location.hasFocus())
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("local-location-suggestion:Seattle, WA") != null }
                scenario.onActivity { assertEquals("Typing a draft must not overwrite saved regions", "", SecureStore(context).localLocation) }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("local-location-input").requestFocus() }
                device.pressDPadDown() // Suggestions sit right under the box, so Down lands on the first one.
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("local-location-suggestion:Seattle, WA")?.hasFocus() == true }
                device.pressDPadCenter()
                waitFor(scenario) { activity ->
                    SecureStore(context).localLocation == "Seattle, WA" &&
                        activity.window.decorView.findViewWithTag<EditText>("local-location-input").hasFocus() &&
                        activity.window.decorView.findViewWithTag<TextView>("local-location-matches").text.toString() == "2 local channels matched"
                }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("local-add-region").performClick()
                    val input = activity.window.decorView.findViewWithTag<EditText>("local-location-input")
                    input.setText("Portland Oregon") // TV/phone IMEs may replace the entire draft.
                    activity.window.decorView.findViewWithTag<View>("local-location-suggestion:Portland, OR").performClick()
                    assertEquals("Seattle, WA; Portland, OR", SecureStore(context).localLocation)
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("local-saved-regions").text.contains("Seattle, WA"))
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("local-location-matches").text.toString() == "3 local channels matched" }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("local-edit:0").performClick()
                    activity.window.decorView.findViewWithTag<EditText>("local-location-input").setText("Seattle Washington")
                    activity.window.decorView.findViewWithTag<View>("local-location-suggestion:Seattle, WA").performClick()
                    activity.window.decorView.findViewWithTag<View>("local-remove:1").performClick()
                    assertEquals("Seattle, WA", store.localLocation)
                    activity.window.decorView.findViewWithTag<EditText>("local-location-input").setText("Portland Oregon")
                    activity.window.decorView.findViewWithTag<View>("local-location-suggestion:Portland, OR").performClick()
                    assertEquals("Seattle, WA; Portland, OR", store.localLocation)
                }
                setThemeControl(scenario, "Guide", "Opacity", "80%")
                assertEquals(80, SecureStore(context).guideOpacity)
                leaveSettings(scenario)
                val releaseLocal = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val worker = MainActivity::class.java.getDeclaredField("localWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    worker.submit { releaseLocal.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    MainActivity::class.java.getDeclaredField("localLineup").apply { isAccessible = true }.set(activity, null)
                    activity.window.decorView.findViewWithTag<View>("guide-local").performClick()
                    assertTrue("Local matching has an animated loading indicator", activity.window.decorView.findViewWithTag<View>("local-matching-progress").isShown)
                }
                releaseLocal.countDown()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 3 && !it.window.decorView.findViewWithTag<View>("local-matching-progress").isShown }
                assertEquals("Seattle, WA; Portland, OR", SecureStore(context).localLocation)
                scenario.onActivity { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    val fingerprint = LocalMatchCache.fingerprint(guide.channels, store.localLocation)
                    assertEquals(setOf("local:king", "local:kiro", "local:kgw"), LocalMatchCache.decode(store.read("local-matches-${source.id}.enc"), fingerprint))
                    activity.window.decorView.findViewWithTag<View>("guide-all").performClick()
                }
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 8 }
                scenario.recreate()
                waitFor(scenario) { activity ->
                    @Suppress("UNCHECKED_CAST") val ids = MainActivity::class.java.getDeclaredField("localIds").apply { isAccessible = true }.get(activity) as Set<String>
                    ids == setOf("local:king", "local:kiro", "local:kgw") && !MainActivity::class.java.getDeclaredField("localLoading").apply { isAccessible = true }.getBoolean(activity)
                }
                scenario.onActivity { activity ->
                    assertFalse("Local is warmed before opening its tab", activity.window.decorView.findViewWithTag<View>("guide-local").isSelected)
                    activity.window.decorView.findViewWithTag<View>("guide-local").performClick()
                    assertEquals(3, channelList(activity)!!.adapter!!.itemCount)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("local-matching-progress").isShown)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }


    @Test fun gameReminderOverlayKeepsPlaybackAndDismissesWithBack() {
        val previousReminders = store.read("game-reminders.enc")
        var writes: java.util.concurrent.ExecutorService? = null
        FixtureServer(missingStatus = 404).use { server ->
            val source = Source(name = "Reminder Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis(); val teams = listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }
            val game = SportsGame(teams, now + 86400000L, now + 90000000L, emptyList(), "synthetic-reminder", "pre", competition = "NFL")
            val reminders = GameReminders(); reminders.toggle(game); store.write("game-reminders.enc", reminders.json().toString().toByteArray())
            store.saveSources(listOf(source)); store.active = source.id
            // Install the fixture before onCreate can start a real ESPN request.
            // A late live reply must never compete with this synthetic matchup.
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val isolated = AtomicBoolean()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { LiveSportsSnapshot.EMPTY }; isolated.set(true)
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    assertTrue("The reminder test must never depend on a real scoreboard", isolated.get())
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { player -> player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying } == true }
                    var playing: Player? = null
                    scenario.onActivity { activity ->
                        playing = find<PlayerView>(activity.window.decorView)!!.player
                        writes = MainActivity::class.java.getDeclaredField("cacheWrites").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                        MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                        val due = GameReminders().apply { toggle(game.copy(start = System.currentTimeMillis() - 1000)) }
                        MainActivity::class.java.getDeclaredField("reminders").apply { isAccessible = true }.set(activity, due)
                        MainActivity::class.java.getDeclaredField("reminderChecking").apply { isAccessible = true }.setBoolean(activity, true)
                        MainActivity::class.java.getDeclaredMethod("checkGameReminders").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("game-reminder").isShown }
                    scenario.onActivity { activity ->
                        assertEquals("reminder-watch", activity.currentFocus?.tag)
                        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                        assertTrue("A Back release from the underlying page cannot dismiss a new reminder", activity.window.decorView.findViewWithTag<View>("game-reminder").isShown)
                        assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playing!!.isPlaying)
                    }
                    device.pressBack()
                    waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("game-reminder").isShown }
                    scenario.onActivity { assertTrue(playing!!.isPlaying); assertSame(playing, find<PlayerView>(it.window.decorView)!!.player) }
                    val releaseReminderSearch = java.util.concurrent.CountDownLatch(1)
                    try {
                        scenario.onActivity { activity ->
                            MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                            val worker = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                            worker.submit { releaseReminderSearch.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                            val due = GameReminders().apply { toggle(game.copy(start = System.currentTimeMillis() - 1000)) }
                            MainActivity::class.java.getDeclaredField("reminders").apply { isAccessible = true }.set(activity, due)
                            MainActivity::class.java.getDeclaredMethod("checkGameReminders").apply { isAccessible = true }.invoke(activity)
                        }
                        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("game-reminder").isShown }
                        device.pressDPadCenter()
                        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("game-watch-panel").isShown }
                        scenario.onActivity { activity ->
                            assertEquals("game-watch-cancel", activity.currentFocus?.tag)
                            assertFalse("Watch game must not navigate to the guide while matching", channelList(activity)?.isShown == true)
                            assertFalse(activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                            assertTrue("The previous programme keeps playing during lookup", playing!!.isPlaying)
                        }
                        device.pressBack()
                        waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("game-watch-panel").isShown }
                        scenario.onActivity { activity ->
                            assertFalse(channelList(activity)?.isShown == true)
                            assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player)
                        }
                    } finally { releaseReminderSearch.countDown() }
                    assertFalse(SecureStore(context).sportsAutoPlay)
                    // Settings › Sports opens the same Sports settings screen as Sports › Leagues and teams.
                    device.pressMenu(); activateSetting(scenario, "Sports"); activateSetting(scenario, "Preferences")
                    waitFor(scenario) { activity ->
                        activity.window.decorView.findViewWithTag<View>("sports-manager")?.takeIf { it.isShown }
                            ?.findViewWithTag<View>("settings-option:Play best stream on selection")?.apply { requestFocus(); performClick() } != null
                    }
                    assertTrue("Automatic playback persists from main Settings", SecureStore(context).sportsAutoPlay)
                    // Back leaves the option for the section list, then closes the Sports settings.
                    repeat(3) { var open = false; scenario.onActivity { open = it.window.decorView.findViewWithTag<View>("sports-manager")?.isShown == true }; if (open) { device.pressBack(); instrumentation.waitForIdleSync() } }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-manager")?.isShown != true }
                    leaveSettings(scenario)
                    val releaseWarmWorker = java.util.concurrent.CountDownLatch(1)
                    val largeLineup = (1..25000).map { Channel("filler:$it", "Local news $it", "News", "${server.base}/unused.mp4") }
                    try {
                        scenario.onActivity { activity ->
                            val worker = MainActivity::class.java.getDeclaredField("sportsWarmWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                            worker.submit { releaseWarmWorker.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                            val live = game.copy(state = "in", start = System.currentTimeMillis() - 60000, end = System.currentTimeMillis() + 3600000)
                            val reported = Channel("provider-game", "Seattle Seahawks vs San Francisco 49ers 4K 60fps", "NFL", "${server.base}/channel2.mp4", reportedQuality = VideoInfo(3840, 2160, 59.94f))
                            val broken = reported.copy(id = "broken-game", name = "Seattle Seahawks vs San Francisco 49ers 4K 120fps", url = "${server.base}/live/test/test-secret/2.ts", reportedQuality = VideoInfo(3840, 2160, 120f))
                            val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                            val snapshot = guideField.get(activity) as Guide
                            guideField.set(activity, snapshot.copy(channels = snapshot.channels + largeLineup + broken + reported))
                            val scores = LiveSportsSnapshot(listOf(LiveEvent(live.eventId, teams.map { it.id }.toSet(), live.start, league = "NFL")), System.currentTimeMillis(), setOf("NFL"))
                            activity.liveSportsLoader = { scores }
                            MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                            MainActivity::class.java.getDeclaredMethod("switchMode", java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, true)
                        }
                        // The executor is deliberately blocked: its task cannot post
                        // "Finding stream" until this gate opens. Observe the queued
                        // task itself so cancellation does not race the gate timeout.
                        waitFor(scenario) { activity ->
                            // Prefetch tracks a batch of games even while its coordinator is queued.
                            val active = MainActivity::class.java.getDeclaredField("sportsWarmActive").apply { isAccessible = true }.get(activity) as Set<*>
                            MainActivity::class.java.getDeclaredField("sportsWarmTask").apply { isAccessible = true }.get(activity) != null &&
                                game.key in active &&
                                activity.window.decorView.findViewWithTag<View>("sports-coverage:${game.key}")?.isShown == true
                        }
                        scenario.onActivity { activity ->
                            MainActivity::class.java.getDeclaredMethod("stopSportsPrefetch", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                            assertEquals("Cancelled work leaves an actionable card", "Select to find stream", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-coverage:${game.key}").text.toString())
                        }
                    } finally { releaseWarmWorker.countDown() }
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod("queueSportsPrefetch", List::class.java).apply { isAccessible = true }.invoke(activity, null)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-coverage:${game.key}")?.text?.contains("Seattle Seahawks") == true }
                    scenario.onActivity { activity ->
                        val record = MainActivity::class.java.getDeclaredMethod("recordVideo", VideoInfo::class.java).apply { isAccessible = true }
                        record.invoke(activity, VideoInfo(3840, 2160, 60f)); record.invoke(activity, VideoInfo(1920, 1080, 30f))
                        assertTrue("Quality changes must retain completed coverage", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-coverage:${game.key}").text.contains("Seattle Seahawks"))
                        MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                        MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                        MainActivity::class.java.getDeclaredMethod("queueSportsPrefetch", List::class.java).apply { isAccessible = true }.invoke(activity, null)
                        assertTrue("Returning to Sports retains the discovered channel", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-coverage:${game.key}").text.contains("Seattle Seahawks"))
                    }
                    assertNull("Prefetch never opens the candidate video", server.requests["/live/test/test-secret/2.ts"])
                    assertNull(server.requests["/channel2.mp4"])
                    scenario.onActivity { activity ->
                        val badge = activity.window.decorView.findViewWithTag<TextView>("sports-badge:${game.key}")
                        assertEquals("● LIVE", badge.text.toString()); assertEquals(0xFF07572E.toInt(), badge.currentTextColor)
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                    }
                    waitFor(scenario) { playing?.currentMediaItem?.localConfiguration?.uri?.path == "/channel2.mp4" && playing?.isPlaying == true }
                    assertEquals("A failed cached pick falls through once to the next game stream", 1, server.requests["/live/test/test-secret/2.ts"])
                    scenario.onActivity { assertSame("Watch Best uses explicit provider FHD+/60 data before the first playback measurement", playing, find<PlayerView>(it.window.decorView)!!.player) }
                    device.pressDPadCenter()
                    focusDetailsAction(scenario, "channel-game-streams")
                    device.pressDPadCenter()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:provider-game")?.isShown == true }
                    scenario.onActivity { activity ->
                        assertTrue("Opening game alternatives must preserve stall monitoring", MainActivity::class.java.getDeclaredField("monitoringPlayback").apply { isAccessible = true }.getBoolean(activity))
                        activity.window.decorView.findViewWithTag<View>("sports-stream:provider-game").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-panel")?.isShown == false }
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod("showPlaybackFailure", String::class.java).apply { isAccessible = true }.invoke(activity, "Synthetic stream failure")
                        assertTrue("Manual Sports selections keep game alternatives after errors", activity.window.decorView.findViewWithTag<View>("sports-other-streams").isShown)
                        activity.window.decorView.findViewWithTag<View>("sports-other-streams").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:provider-game")?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:provider-game").performClick() }
                    waitFor(scenario) { playing?.isPlaying == true && it.window.decorView.findViewWithTag<View>("sports-panel")?.isShown == false }
                    assertEquals("Opening cached alternatives must not retry a missing provider endpoint", 1, server.requests["/live/test/test-secret/2.ts"])
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod("switchMode", java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, true)
                        val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                        panel.openGameActions(game)
                        assertTrue("Upcoming games still offer reminders", panel.findViewWithTag<View>("sports-reminder").isShown)
                        panel.openGameActions(game.copy(state = "post"))
                        assertEquals("Find replay", panel.findViewWithTag<android.widget.Button>("sports-watch").text.toString())
                        assertTrue("Completed games still show replay actions", panel.findViewWithTag<View>("sports-actions").isShown)
                        assertEquals("/channel2.mp4", playing!!.currentMediaItem!!.localConfiguration!!.uri.path)
                    }
                    scenario.onActivity { activity ->
                        val network = Channel("broadcaster", "USA ESPN 4K", "NFL", "${server.base}/channel3.mp4")
                        val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                        val saved = guideField.get(activity) as Guide
                        guideField.set(activity, saved.copy(channels = saved.channels.filter { it.id !in setOf("provider-game", "broken-game") } + network,
                            programmes = saved.programmes + (network.id to listOf(Programme(network.id, game.title, System.currentTimeMillis() - 60000, System.currentTimeMillis() + 3600000)))))
                        @Suppress("UNCHECKED_CAST") val readings = MainActivity::class.java.getDeclaredField("videoInfo").apply { isAccessible = true }.get(activity) as MutableMap<String, VideoInfo>
                        readings[network.id] = VideoInfo(3840, 2160, 59.94f)
                        val live = game.copy(state = "in", start = System.currentTimeMillis() - 60000, end = System.currentTimeMillis() + 3600000, networks = listOf("ESPN"))
                        MainActivity::class.java.getDeclaredMethod("switchMode", java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, true)
                        MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, live, true)
                    }
                    waitFor(scenario) { playing?.currentMediaItem?.localConfiguration?.uri?.path == "/channel3.mp4" && playing?.isPlaying == true }
                    scenario.onActivity { activity ->
                        assertFalse(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-match-notice").isShown)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                    }
                    scenario.onActivity { activity ->
                        val nba = game.copy(teams = SportsCatalog.teams.filter { it.league == "NBA" }.take(2), state = "in", start = System.currentTimeMillis() - 60000, end = System.currentTimeMillis() + 3600000, competition = "NBA", eventId = "nba-unknown")
                        val unknown = Channel("unknown-fps", nba.title + " 4K", "NBA", "${server.base}/channel4.mp4", reportedQuality = VideoInfo(3840, 2160, 0f))
                        val low = unknown.copy(id = "known-50", name = nba.title + " 4K 50FPS", url = "${server.base}/channel5.mp4", reportedQuality = VideoInfo(3840, 2160, 50f))
                        val fhd = unknown.copy(id = "fhd-sixty", name = nba.title + " FHD 60FPS", url = "${server.base}/channel6.mp4", reportedQuality = VideoInfo(1920, 1080, 60f))
                        val scoreboard = LiveSportsSnapshot(listOf(LiveEvent(nba.eventId, nba.teams.map { it.id }.toSet(), nba.start, league = "NBA")), System.currentTimeMillis(), setOf("NBA"))
                        activity.liveSportsLoader = { scoreboard }
                        MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scoreboard)
                        MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, Guide(listOf(unknown, low, fhd), emptyMap()))
                        MainActivity::class.java.getDeclaredField("sportsFilters").apply { isAccessible = true }.set(activity, GuideFilters(1080, FrameRateFilter.sixtyOrBetter))
                        MainActivity::class.java.getDeclaredMethod("switchMode", java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, true)
                        MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, nba, true)
                    }
                    waitFor(scenario) { playing?.currentMediaItem?.localConfiguration?.uri?.path == "/channel4.mp4" && playing?.isPlaying == true }
                    assertNull("The other 4K candidate is not probed", server.requests["/channel5.mp4"])
                    assertNull("Known FHD/60 cannot displace an unplayed 4K match or open an extra connection", server.requests["/channel6.mp4"])
                    waitFor(scenario) { activity ->
                        @Suppress("UNCHECKED_CAST") val readings = MainActivity::class.java.getDeclaredField("videoInfo").apply { isAccessible = true }.get(activity) as Map<String, VideoInfo>
                        (readings["unknown-fps"]?.fps ?: 0f) > 0f
                    }
                    scenario.onActivity { activity ->
                        assertSame("Unknown-FPS choices reuse the primary player", playing, find<PlayerView>(activity.window.decorView)!!.player)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                        val hockey = game.copy(teams = listOf("nhl:TOR", "nhl:SEA").map { SportsCatalog.byId.getValue(it) }, state = "in", start = System.currentTimeMillis() - 60000, end = System.currentTimeMillis() + 3600000, competition = "NHL", eventId = "nhl-hd-only", networks = listOf("ESPN"))
                        val hd = Channel("hd-only", "NHL 07 HD", "NHL", "${server.base}/channel5.mp4", reportedQuality = VideoInfo(1280, 720, 30f))
                        val guess = Channel("stale-4k", "ESPN 4K", "Sports", "${server.base}/channel6.mp4", reportedQuality = VideoInfo(3840, 2160, 60f))
                        val scoreboard = LiveSportsSnapshot(listOf(LiveEvent(hockey.eventId, hockey.teams.map { it.id }.toSet(), hockey.start, league = "NHL")), System.currentTimeMillis(), setOf("NHL"))
                        activity.liveSportsLoader = { scoreboard }
                        MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scoreboard)
                        val guide = Guide(listOf(hd, guess), emptyMap())
                        val cache = MainActivity::class.java.getDeclaredField("sportsStreamCache").apply { isAccessible = true }.get(activity) as SportsStreamCache
                        val generation = MainActivity::class.java.getDeclaredField("generation").apply { isAccessible = true }.getInt(activity)
                        @Suppress("UNCHECKED_CAST") val favorites = MainActivity::class.java.getDeclaredField("favorites").apply { isAccessible = true }.get(activity) as Set<String>
                        val token = cache.use(source.id, generation, guide, favorites)
                        cache.put(token, SportsStreamCache.Match(hockey.copy(candidates = listOf(guess))), System.currentTimeMillis())
                        MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, guide.copy(programmes = mapOf(hd.id to listOf(Programme(hd.id, "Maple Leafs at Kraken", hockey.start, hockey.end)))))
                        MainActivity::class.java.getDeclaredField("sportsFilters").apply { isAccessible = true }.set(activity, GuideFilters(1080, FrameRateFilter.sixtyOrBetter))
                        MainActivity::class.java.getDeclaredField("reminderGame").apply { isAccessible = true }.set(activity, hockey)
                        MainActivity::class.java.getDeclaredMethod("watchGameReminder").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { playing?.currentMediaItem?.localConfiguration?.uri?.path == "/channel5.mp4" && playing?.isPlaying == true }
                    assertNull("An unconfirmed 4K broadcaster must not displace the reminder's exact 720p game", server.requests["/channel6.mp4"])
                    scenario.onActivity { activity ->
                        assertFalse("Lower measured quality must not interrupt a playable game", activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                        assertFalse("Reminder playback finishes full screen", channelList(activity)?.isShown == true)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("game-watch-panel").isShown)
                        assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player)
                    }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                writes?.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                if (previousReminders == null) store.delete("game-reminders.enc") else store.write("game-reminders.enc", previousReminders)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    private fun channelList(activity: MainActivity): RecyclerView? = activity.window.decorView.findViewWithTag("channel-guide")
    @Test fun remoteNumberChannelAndLastKeysTuneFromFullScreen() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Remote keys fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = List(3) { id -> Channel("remote:$id", "Remote ${id + 1}", "News", "${server.base}/channel${id % 2 + 1}.mp4") }
            store.recentFeeds = emptyList(); store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            fun currentId(activity: MainActivity) = (MainActivity::class.java.getDeclaredField("current").apply { isAccessible = true }.get(activity) as Channel?)?.id
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                waitFor(scenario) { currentId(it) == "remote:0" && channelList(it)?.isShown != true }
                device.pressKeyCode(KeyEvent.KEYCODE_3)
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("channel-number-entry")?.text?.contains("3  ·  Remote 3") == true }
                waitFor(scenario) { currentId(it) == "remote:2" && it.window.decorView.findViewWithTag<View>("channel-number-entry")?.isShown == false }
                device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
                waitFor(scenario) { currentId(it) == "remote:0" }
                device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN)
                waitFor(scenario) { currentId(it) == "remote:2" }
                device.pressKeyCode(KeyEvent.KEYCODE_LAST_CHANNEL)
                waitFor(scenario) { currentId(it) == "remote:0" }
                device.pressKeyCode(KeyEvent.KEYCODE_9)
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("channel-number-entry")?.text?.contains("No channel 9") == true }
                scenario.onActivity { assertEquals("A number outside the list keeps the current channel", "remote:0", currentId(it)) }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun largeSavedGuideStartsPlaybackAndOpensTheGuideQuickly() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Startup fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis()
            val channels = List(20_000) { id -> Channel("start:$id", "Channel ${id + 1}", listOf("News", "Sports", "Movies", "Kids")[id % 4], "${server.base}/channel1.mp4") }
            val programmes = channels.associate { channel ->
                channel.id to List(8) { slot -> Programme(channel.id, "${channel.name} show $slot", now - 3_600_000 + slot * 1_800_000L, now - 1_800_000 + slot * 1_800_000L) }
            }
            store.recentFeeds = emptyList(); store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            try {
                val launched = android.os.SystemClock.elapsedRealtime()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    val playing = android.os.SystemClock.elapsedRealtime() - launched
                    device.pressBack()
                    waitFor(scenario) { activity ->
                        val list = channelList(activity)
                        list?.hasFocus() == true && (list.adapter as GuideAdapter).channels.size == 20_000
                    }
                    val guide = android.os.SystemClock.elapsedRealtime() - launched
                    println("Startup 20000 channels, 160000 programmes: first frame ${playing}ms, guide ready ${guide}ms")
                    assertTrue("Saved playback starts within 15 s even with a large guide, took ${playing}ms", playing < 15_000)
                }
            } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sleepTimerStopsPlaybackReleasesTheScreenAndOkResumesLive() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Sleep fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = List(2) { id -> Channel("sleep:$id", "Sleep ${id + 1}", "News", "${server.base}/channel${id + 1}.mp4") }
            store.recentFeeds = emptyList(); store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            fun screenKeptOn(activity: MainActivity) = activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { activity ->
                    assertTrue(screenKeptOn(activity))
                    MainActivity::class.java.getDeclaredMethod("setSleepTimer", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 30)
                    // Fire the timer now instead of waiting half an hour.
                    MainActivity::class.java.getDeclaredMethod("endSleepTimer").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    player.playbackState == Player.STATE_IDLE && !screenKeptOn(activity) &&
                        activity.window.decorView.findViewWithTag<TextView>("channel-number-entry")?.text?.contains("Sleep timer ended") == true
                }
                device.pressDPadCenter()
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.isPlaying == true && screenKeptOn(activity) &&
                        activity.window.decorView.findViewWithTag<View>("channel-number-entry")?.isShown == false
                }
                scenario.onActivity { activity ->
                    assertEquals("OK resumes the same channel", "sleep:0", (MainActivity::class.java.getDeclaredField("current").apply { isAccessible = true }.get(activity) as Channel?)?.id)
                    assertNull("Resuming does not also open details", activity.window.decorView.findViewWithTag<View>("details-panel")?.takeIf { it.isShown })
                }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aStartupChannelWhoseEventEndedFallsBackToTheLastChannelThatPlayed() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Ended slot fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            // Spare reopens the last channel watched: an event slot whose game has ended. Before it, a regular channel played,
            // and before that another slot.
            val ended = Channel("ended:1", "LP 07 | Kings @ Jazz (2026-11-15 21:00:00)", "League Pass", "${server.base}/ended.m3u8")
            val olderSlot = Channel("ended:2", "LP 08 | Hawks @ Heat (2026-11-15 19:00:00)", "League Pass", "${server.base}/channel2.mp4")
            val working = Channel("ended:3", "Harbour Sports HD", "Sports", "${server.base}/channel1.mp4")
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(ended, olderSlot, working), emptyMap())); store.markPlaylistRefreshed(source)
            store.catalogRoot = source.id; store.recentFeeds = listOf(ended.id, olderSlot.id, working.id)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val opened = android.os.SystemClock.elapsedRealtime()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true && playingPath(scenario) == "/channel1.mp4" }
                val waited = android.os.SystemClock.elapsedRealtime() - opened
                assertTrue("The regular channel plays without the 30 s of reconnects (took $waited ms)", waited < 15000)
                scenario.onActivity { activity ->
                    val retry = MainActivity::class.java.getDeclaredField("retryPanel").apply { isAccessible = true }.get(activity) as View
                    assertFalse("No error greets the viewer", retry.isShown)
                    assertTrue("The fallback is recorded", "Startup channel had no stream" in Diagnostics.report())
                }
                assertEquals("The channel that played is now the latest", working.id, store.recentFeeds.first())
                // A channel the viewer picks that fails still shows the error and Retry.
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("tune", Channel::class.java).apply { isAccessible = true }.invoke(activity, ended)
                }
                waitFor(scenario) { activity -> (MainActivity::class.java.getDeclaredField("retryPanel").apply { isAccessible = true }.get(activity) as View).isShown }
            } } finally { store.recentFeeds = emptyList(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun retryReturnsTheRemoteToTheGuideRowItWasOn() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Retry fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val working = Channel("retry:1", "Harbour Sports HD", "Sports", "${server.base}/channel1.mp4")
            val ended = Channel("retry:2", "Event slot 04", "Sports", "${server.base}/ended.m3u8")
            val other = Channel("retry:3", "Harbour Sports 2", "Sports", "${server.base}/channel2.mp4")
            store.saveSources(listOf(source)); store.active = source.id; store.hiddenDock = true
            store.cache(source, Guide(listOf(working, ended, other), emptyMap())); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                // The guide is open on the event slot while it plays behind, as when Spare opens on it; it has no stream, so Retry takes the remote.
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressDPadDown()
                waitFor(scenario) { activity -> channelList(activity)?.let { list -> activity.currentFocus?.let(list::findContainingItemView)?.let(list::getChildAdapterPosition) } == 1 }
                scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("tune", Channel::class.java).apply { isAccessible = true }.invoke(activity, ended) }
                waitFor(scenario) { activity ->
                    val retry = MainActivity::class.java.getDeclaredField("retryPanel").apply { isAccessible = true }.get(activity) as View
                    retry.isShown && retry.hasFocus()
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity { activity ->
                    val focus = activity.currentFocus
                    assertNotEquals("Retry never leaves the remote on the menu handle", "navigation-handle", focus?.tag)
                    val list = channelList(activity)!!
                    assertEquals("Retry returns the remote to the row it was on", 1, focus?.let(list::findContainingItemView)?.let(list::getChildAdapterPosition))
                }
            } } finally { store.hiddenDock = false; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aReopenedXtreamSlotThatNeverAnswersFallsBackWithoutTryingTheOtherFormat() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Silent slot fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val silent = Channel("xc:91", "LP 05 | Nets @ Bulls (2026-11-15 20:00:00)", "League Pass", "${server.base}/hang.ts")
            val working = Channel("xc:92", "Harbour Sports HD", "Sports", "${server.base}/channel1.mp4")
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(silent, working), emptyMap()))
            store.markPlaylistRefreshed(source); store.markEpgRefreshed(source); store.markFullEpgAttempted(source); store.setRefreshPolicy(source, RefreshPolicy())
            store.catalogRoot = source.id; store.recentFeeds = listOf(silent.id, working.id)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val opened = android.os.SystemClock.elapsedRealtime()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true && playingPath(scenario) == "/channel1.mp4" }
                val waited = android.os.SystemClock.elapsedRealtime() - opened
                // 10 s for a channel that played before, then the fallback; trying the other format first took 29 s on an onn.
                assertTrue("The fallback plays after the shorter wait (took $waited ms)", waited < 17000)
                assertNull("The other stream format is not tried for a reopened channel", server.requests["/hang.m3u8"])
            } } finally { store.recentFeeds = emptyList(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun searchRemembersChosenQueriesAndReplaysThemFromAChip() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Recent search fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = listOf(Channel("recent:0", "Harbour News", "News", "${server.base}/channel1.mp4"), Channel("recent:1", "Mountain Sports", "Sports", "${server.base}/channel2.mp4"))
            store.recentFeeds = emptyList(); store.recentSearches = emptyList(); store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity {
                    it.window.decorView.findViewWithTag<View>("search").performClick()
                    it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Mountain")
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:recent:1")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-result:live:recent:1").performClick() }
                waitFor(scenario) { store.recentSearches == listOf("Mountain") }
                scenario.onActivity {
                    it.window.decorView.findViewWithTag<View>("search").performClick()
                    it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("")
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-recent:Mountain")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-recent:Mountain").performClick() }
                waitFor(scenario) { activity ->
                    activity.window.decorView.findViewWithTag<EditText>("guide-search-input").text.toString() == "Mountain" &&
                        activity.window.decorView.findViewWithTag<View>("search-result:live:recent:1")?.isShown == true &&
                        activity.window.decorView.findViewWithTag<View>("search-recent")?.isShown == false
                }
            } } finally { store.recentSearches = emptyList(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun guideScrollsALargeLogoPlaylistAndDrawsEveryVisibleLogo() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Guide scroll fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis()
            val channels = List(3000) { id -> Channel("scroll:$id", "Channel ${id + 1}", if (id % 2 == 0) "News" else "Sports", "${server.base}/channel1.mp4", logo = "${server.base}/logo.png") }
            val programmes = channels.associate { channel ->
                channel.id to List(12) { slot -> Programme(channel.id, "${channel.name} show $slot", now - 3_600_000 + slot * 1_800_000L, now - 1_800_000 + slot * 1_800_000L) }
            }
            val measure = InstrumentationRegistry.getArguments().getString("guide_benchmark") == "true"
            store.performanceOverlay = measure; store.recentFeeds = emptyList()
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                var frames: PerformanceFrames? = null
                if (measure) scenario.onActivity { activity ->
                    val overlay = MainActivity::class.java.getDeclaredField("performanceOverlay").apply { isAccessible = true }.get(activity) as PerformanceOverlay
                    frames = PerformanceOverlay::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(overlay) as PerformanceFrames
                    frames!!.summary(android.os.SystemClock.elapsedRealtime() + 10_000L)
                }
                // A held Down key: repeated presses faster than a person can read rows.
                repeat(60) { step ->
                    scenario.onActivity { activity ->
                        val tick = android.os.SystemClock.uptimeMillis()
                        activity.dispatchKeyEvent(KeyEvent(tick, tick, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, if (step == 0) 0 else 1))
                    }
                    Thread.sleep(45)
                }
                scenario.onActivity { activity ->
                    val tick = android.os.SystemClock.uptimeMillis()
                    activity.dispatchKeyEvent(KeyEvent(tick, tick, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0))
                }
                frames?.let { recorded ->
                    Thread.sleep(200)
                    val result = recorded.summary(android.os.SystemClock.elapsedRealtime())!!
                    println("Guide scroll 3000 channels: ${result.jankyPercent}% jank, ${result.count} frames")
                    val threads = Thread.getAllStackTraces().keys.groupingBy { it.name.replace(Regex("[-:]?\\d+$"), "") }.eachCount()
                    println("Guide scroll threads: ${threads.values.sum()} · " + threads.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
                    val runtime = Runtime.getRuntime()
                    println("Guide scroll heap: ${(runtime.totalMemory() - runtime.freeMemory()) / 1048576} MB used of ${runtime.maxMemory() / 1048576} MB")
                }
                waitFor(scenario) { activity ->
                    val list = channelList(activity) ?: return@waitFor false
                    val focused = list.focusedChild ?: return@waitFor false
                    list.getChildAdapterPosition(focused) >= 30
                }
                // Logos for the rows now on screen arrive from the shared cache, not row by row later.
                waitFor(scenario) { activity ->
                    val list = channelList(activity)!!
                    val adapter = list.adapter as GuideAdapter
                    (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isShown }.all { row ->
                        val bitmap = GuideAdapter.Row::class.java.getDeclaredField("logoBitmap").apply { isAccessible = true }.get(row)
                        bitmap != null || adapter.channels.isEmpty()
                    }
                }
            } } finally {
                store.performanceOverlay = false; store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun missingChannelIsHiddenWithoutRetryLoopAndAuthIsNotHidden() {
        listOf(404, 401).forEach { status -> FixtureServer(channelCount = 2, missingStatus = status).use { server ->
            val source = Source(name = "Missing Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.videoSize.width > 0 && it.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressDPadDown(); device.pressDPadCenter()
                if (status == 404) {
                    waitFor(scenario) { store.unavailable(source).contains("xc:2", System.currentTimeMillis()) && channelList(it)?.isShown == true }
                    waitFor(scenario) { playingPath(scenario) == "/live/test/test-secret/1.ts" }
                    scenario.onActivity { assertEquals(1, channelList(it)!!.adapter!!.itemCount) }
                    assertEquals(1, server.requests["/live/test/test-secret/2.ts"])
                    assertEquals(1, server.requests["/live/test/test-secret/2.m3u8"])
                    scenario.recreate(); waitFor(scenario) { channelList(it)?.adapter?.itemCount == 1 }
                    assertTrue(store.unavailable(source).contains("xc:2", System.currentTimeMillis()))
                } else {
                    assertTrue(device.wait(Until.hasObject(By.textContains("HTTP 401")), 10000))
                    assertTrue(store.unavailable(source).snapshot().isEmpty())
                    assertEquals(1, server.requests["/live/test/test-secret/2.ts"])
                    assertNull(server.requests["/live/test/test-secret/2.m3u8"])
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        } }
    }

    @Test fun topControlsUsaOrderingAndBothGuideVideoModesKeepPlayback() {
        FixtureServer(regionGroups = true).use { server ->
            val source = Source(name = "Guide Modes", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.setUsaFirst(source, true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val categories = activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories")
                    assertEquals("USA | News", (categories.findViewHolderForAdapterPosition(2)!!.itemView as android.widget.TextView).text.toString())
                    assertEquals("US | MLB", (categories.findViewHolderForAdapterPosition(3)!!.itemView as android.widget.TextView).text.toString())
                    val rail = activity.window.decorView.findViewWithTag<View>("app-navigation")
                    val a = IntArray(2); val b = IntArray(2); rail.getLocationOnScreen(a); categories.getLocationOnScreen(b)
                    assertTrue(a[0] + rail.width <= b[0])
                    val controls = listOf("search", "guide-mode", "sports-mode", "library", "settings").map { activity.window.decorView.findViewWithTag<View>(it) }
                    assertEquals(1, controls.map { it.width to it.height }.distinct().size)
                    assertTrue(controls.zipWithNext().all { (first, next) -> val x = IntArray(2); val y = IntArray(2); first.getLocationOnScreen(x); next.getLocationOnScreen(y); x[1] + first.height < y[1] })
                    assertEquals(activity.window.decorView.width, find<PlayerView>(activity.window.decorView)!!.width)
                    activity.window.decorView.findViewWithTag<View>("settings").requestFocus()
                }
                device.pressDPadCenter(); assertTrue(device.wait(Until.hasObject(By.text("Theme")), 5000))
                assertEquals("Highlights move at once unless a fade is chosen", 0, Motion.enter); assertEquals(0L, Motion.panel)
                setThemeControl(scenario, "Guide", "Navigation animation", "Cross-fade"); assertEquals(110, Motion.enter); assertEquals(110, Motion.exit); assertTrue(Motion.panel > 0)
                setThemeControl(scenario, "Guide", "Animation speed", "Fast"); assertEquals(60, Motion.enter); assertEquals(60, Motion.exit)
                setThemeControl(scenario, "Guide", "Navigation animation", "Fade in"); assertEquals(60, Motion.enter); assertEquals("The old highlight clears at once", 0, Motion.exit)
                setThemeControl(scenario, "Guide", "Navigation animation", "Instant"); assertEquals(0, Motion.enter); assertEquals("Instant", store.navigationAnimation)
                setThemeControl(scenario, "Guide", "Guide video", "Small preview"); leaveSettings(scenario)
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.let { it.width < activity.window.decorView.width / 2 && it.player?.isPlaying == true } == true }
                assertTrue(store.smallPreview)
                scenario.onActivity { assertTrue(find<PlayerView>(it.window.decorView)!!.width >= it.window.decorView.width / 3) }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                // This 1.024-second MP4 naturally opens another request on repeat.
                // Preserve player/attempt identity rather than counting loop requests,
                // which depend on the emulator's speed during remote/UI operations.
                var beforePlayer: Player? = null
                var beforeMediaId = ""
                scenario.onActivity { activity ->
                    beforePlayer = find<PlayerView>(activity.window.decorView)!!.player!!
                    beforeMediaId = beforePlayer!!.currentMediaItem!!.mediaId
                    assertTrue(beforeMediaId.isNotBlank())
                }
                fun assertPlaybackContinues() = waitFor(scenario) { activity ->
                    val playing = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertSame("Guide changes must keep the same player", beforePlayer, playing)
                    assertEquals("Expanding must not start a new playback attempt", beforeMediaId, playing.currentMediaItem!!.mediaId)
                    playing.isPlaying
                }
                device.pressDPadCenter(); waitFor(scenario) { channelList(it)?.isShown == false }
                assertPlaybackContinues()
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                scenario.onActivity { activity ->
                    val rect = android.graphics.Rect(); find<PlayerView>(activity.window.decorView)!!.getGlobalVisibleRect(rect)
                    val colors = mutableSetOf<Int>()
                    for (x in rect.left + rect.width()/4 until rect.right - rect.width()/4 step 8) for (y in rect.top + 10 until rect.bottom - 45 step 8) colors.add(bitmap.getPixel(x, y))
                    assertTrue("Preview must contain rendered video, not an empty black surface", colors.size > 10)
                }; bitmap.recycle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "guide-preview.png"))
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                assertPlaybackContinues()
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun previouslyPlayingLiveEndpointRecoversFromTemporaryProvider404WithoutRetuning() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Temporary live outage", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true && MainActivity::class.java.getDeclaredField("hasPlayedChannel").apply { isAccessible = true }.getBoolean(it) }
                var original: Player? = null
                val requestsBefore = server.requests["/channel1.ts"] ?: 0
                server.transientLiveFailures.set(2)
                scenario.onActivity { activity ->
                    original = find<PlayerView>(activity.window.decorView)!!.player!!
                    // Exercise real HTTP errors after proven playback, without a channel change.
                    original!!.setMediaItem(original!!.currentMediaItem!!); original!!.prepare(); original!!.play()
                }
                waitFor(scenario) { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    (server.requests["/channel1.ts"] ?: 0) >= requestsBefore + 3 && player.isPlaying &&
                        MainActivity::class.java.getDeclaredField("firstFrame").apply { isAccessible = true }.getBoolean(activity)
                }
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertSame(original, player); assertEquals("/channel1.ts", player.currentMediaItem!!.localConfiguration!!.uri.path)
                    assertEquals(2, MainActivity::class.java.getDeclaredField("playbackReconnects").apply { isAccessible = true }.getInt(activity))
                    assertFalse((MainActivity::class.java.getDeclaredField("retryPanel").apply { isAccessible = true }.get(activity) as View).isShown)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun actualStreamStallUsesBufferingAndResumesWithoutReconnect() {
        FixtureServer(stalled = true).use { server ->
            Diagnostics.reset()
            val source = Source(name = "Stall Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.playbackState == Player.STATE_BUFFERING }
                // The stall says how fast the stream was arriving, when its last data came, and what else was downloading.
                val stallDeadline = android.os.SystemClock.elapsedRealtime() + 5000
                while ("Stream stalled" !in Diagnostics.report() && android.os.SystemClock.elapsedRealtime() < stallDeadline) Thread.sleep(100)
                val stall = Diagnostics.report().lines().firstOrNull { "Stream stalled" in it }.orEmpty()
                assertTrue(stall, Regex("Stream stalled · buffer .* · stream \\d+\\.\\d Mbit/s last \\d+ s \\(avg \\d+\\.\\d Mbit/s\\) · last data \\d+\\.\\d s ago · other downloads: ").containsMatchIn(stall))
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val guide = field.get(activity) as Guide; val channel = guide.channels.first(); val now = System.currentTimeMillis()
                    field.set(activity, guide.copy(programmes = guide.programmes + (guide.key(channel) to listOf(Programme(guide.key(channel), "NFL: Seahawks vs 49ers", now - 60000, now + 3600000)))))
                }
                device.pressDPadCenter()
                focusDetailsAction(scenario, "channel-game-streams")
                device.pressBack()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED).moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    assertTrue("Pause/resume must restore live stall monitoring", MainActivity::class.java.getDeclaredField("monitoringPlayback").apply { isAccessible = true }.getBoolean(activity))
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playback-status")?.let { bar -> bar.isShown && bar.contentDescription.toString() == "Buffering…" } == true }
                assertEquals(1, server.requests["/channel1.ts"])
                server.resumeStream.countDown()
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.isPlaying == true && activity.window.decorView.findViewWithTag<View>("playback-status")?.isShown == false }
                assertEquals("A recoverable buffer underrun must keep the existing stream", 1, server.requests["/channel1.ts"])
                var samePlayer: Player? = null; var before = ""
                scenario.onActivity { activity ->
                    samePlayer = find<PlayerView>(activity.window.decorView)!!.player
                    before = samePlayer!!.currentMediaItem!!.mediaId
                    val progress = MainActivity::class.java.getDeclaredField("playbackProgress").apply { isAccessible = true }.get(activity) as PlaybackProgress
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    progress.reset(); progress.stalled(token, android.os.SystemClock.elapsedRealtime() - 21000, samePlayer!!.currentPosition, null, false, true)
                    (MainActivity::class.java.getDeclaredField("monitorPlayback").apply { isAccessible = true }.get(activity) as Runnable).run()
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.currentMediaItem?.mediaId != before && p.isPlaying } == true }
                assertEquals("A frozen READY stream is reopened without replacing the app", 2, server.requests["/channel1.ts"])
                scenario.onActivity { activity ->
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player)
                    before = samePlayer!!.currentMediaItem!!.mediaId
                    MainActivity::class.java.getDeclaredMethod("showDetails").apply { isAccessible = true }.invoke(activity)
                    assertNull("Reconnect is no longer a playback action", activity.window.decorView.findViewWithTag<View>("channel-reconnect"))
                    assertEquals("Opening details preserves playback", before, samePlayer!!.currentMediaItem!!.mediaId)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun inlineSearchUsesNativeRecognitionAndKeepsFailedVoiceEditable() {
        assertTrue("Reset isolated voice fixture", device.executeShellCommand("run-as ${instrumentation.context.packageName} rm -f files/voice-first-result").isBlank())
        val previous = device.executeShellCommand("settings get secure voice_recognition_service").trim()
        device.executeShellCommand("settings put secure voice_recognition_service io.github.mot1us.spare.debug.test/io.github.mot1us.spare.FakeRecognitionService")
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        try {
        FixtureServer().use { server ->
            val source = Source(name = "Search Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                val monitor = instrumentation.addMonitor(IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
                try {
                    var attempt = ""
                    scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId; assertTrue(it.window.decorView.findViewWithTag<View>("search").requestFocus()) }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { assertTrue("Search icon must retain remote focus", it.window.decorView.findViewWithTag<View>("search").hasFocus()) }
                    // OK on the menu's Search icon starts native voice without a keyboard step.
                    device.pressDPadCenter()
                    waitFor(scenario) { activity -> activity.window.decorView.findViewWithTag<EditText>("guide-search-input")?.text.toString() == "Channel 2" && channelList(activity)?.adapter?.itemCount == 1 && activity.window.decorView.findViewWithTag<RecyclerView>("playlist-search-results")?.hasFocus() == true }
                    assertEquals("Native speech must not launch a chooser/activity", 0, monitor.hits)
                    scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId); assertEquals(1f, find<PlayerView>(it.window.decorView)!!.player!!.volume, 0f) }
                    assertEquals("/channel1.mp4", playingPath(scenario))
                    assertTrue("Reset isolated voice fixture for a second voice result", device.executeShellCommand("run-as ${instrumentation.context.packageName} rm -f files/voice-first-result").isBlank())
                    scenario.onActivity {
                        it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Old search")
                        it.window.decorView.findViewWithTag<View>("guide-search-voice").apply { requestFocus(); performClick() }
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<EditText>("guide-search-input").text.toString() == "Channel 2" }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-search-voice").requestFocus() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { assertTrue("Pending channel navigation must not steal Voice focus", it.window.decorView.findViewWithTag<View>("guide-search-voice").hasFocus()) }
                    device.pressDPadCenter()
                    waitFor(scenario) { device.hasObject(By.textStartsWith("No speech heard.")) }
                } finally { instrumentation.removeMonitor(monitor) }
                val keyboardPackage = android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD))!!.packageName
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.pkg(keyboardPackage)), 5000))
                scenario.onActivity {
                    val field = it.window.decorView.findViewWithTag<EditText>("guide-search-input")
                    assertEquals("The TV keyboard starts with an empty query for typing or dictation", "", field.text.toString())
                }
                device.findObject(By.desc("Search playlist")).text = "Channel 7"
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("playlist-search-results").getChildAt(0)?.contentDescription?.contains("Test Channel 7") == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("playlist-search-results")?.hasFocus() == true }
                assertTrue(device.wait(Until.gone(By.pkg(keyboardPackage)), 5000))
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search.png"))
                var selectedId = ""
                scenario.onActivity { selectedId = (channelList(it)!!.adapter as GuideAdapter).channels.single().id }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("playlist-search-results").getChildAt(0).requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == selectedId && channelList(it)?.isShown == false }
                assertEquals("/channel7.mp4", playingPath(scenario))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
        } finally {
            if (previous.isBlank() || previous == "null") device.executeShellCommand("settings delete secure voice_recognition_service")
            else device.executeShellCommand("settings put secure voice_recognition_service $previous")
        }
    }

    @Test fun okShowsDetailsAndLeftTogglesWatchedChannelsAcrossFailuresAndRestart() {
        // Observe two continuing streams, with independent provider connections.
        FixtureServer(channelCount = 3, missingStatus = 404, longVideo = true, liveCycles = 4, parallelClients = true).use { server ->
            val source = Source(name = "History Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying && store.last(source) == "xc:1" } == true }
                var attempt = ""
                scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playback-details")?.isShown == true && device.hasObject(By.text("Guide")) }
                scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId); assertFalse(channelList(it)!!.isShown) }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "details.png"))
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playback-details")?.isShown == false && channelList(it)?.isShown == false }
                device.pressDPadUp(); waitFor(scenario) { store.last(source) == "xc:3" }
                assertEquals("xc:1", store.previous(source))
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == "xc:1" }
                assertEquals("xc:3", store.previous(source))
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == "xc:3" }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                // Dispatch rapid Up/OK in one input batch: focus cannot wait for a later posted task.
                scenario.onActivity { activity ->
                    for (key in listOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER)) {
                        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
                        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
                    }
                }
                waitFor(scenario) { store.unavailable(source).contains("xc:2", System.currentTimeMillis()) && store.last(source) == "xc:3" && playingPath(scenario) == "/live/test/test-secret/3.ts" }
                assertEquals("Failed channels must not replace the previously watched channel", "xc:1", store.previous(source))
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == "xc:1" }
                scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying && store.last(source) == "xc:1" } == true }
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == "xc:3" }
                assertEquals("xc:1", store.previous(source))
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("recent-watch:xc:1")?.isShown == true }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "details-multiview.png"))
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("recent-watch:xc:1").isFocusable)
                    activity.window.decorView.findViewWithTag<View>("recent-watch:xc:1").performLongClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<PlayerView>("multiview-second")?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<View>("sports-panel").isShown) }
                device.pressBack(); holdBack()
                waitFor(scenario) { channelList(it)?.isShown == false }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-multiview")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-multiview").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-controls")?.isShown == true }
                scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<View>("sports-panel").isShown) }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "recent-multiview.png"))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun guideRepeatsSettleHeaderWithoutDelayingTheFirstPress() {
        FixtureServer().use { server ->
            val source = Source(name = "Guide repeat fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..6).map { Channel("repeat-$it", "Repeat channel $it", "Guide", "${server.base}/channel1.mp4") }
            val now = System.currentTimeMillis()
            val programmes = channels.mapIndexed { index, channel ->
                channel.id to listOf(Programme(channel.id, "Repeat listing ${index + 1}", now - 120_000, now + 1_800_000))
            }.toMap()
            val info = MainActivity::class.java.getDeclaredField("channelInfo").apply { isAccessible = true }
            fun header(activity: MainActivity) = (info.get(activity) as TextView).text.toString()
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source); store.markFullEpgAttempted(source)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { player ->
                        player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying
                    } == true }
                    device.pressBack()
                    waitFor(scenario) { channelList(it)?.findFocus() is GuideAdapter.Row && header(it) == "Repeat listing 1" }
                    scenario.onActivity { activity ->
                        val first = channelList(activity)!!.findFocus() as GuideAdapter.Row
                        val down = android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        first.onKeyDown(down, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, down))
                        assertEquals("Repeat listing 2", header(activity))
                        val second = channelList(activity)!!.findFocus() as GuideAdapter.Row
                        val time = android.os.SystemClock.uptimeMillis()
                        second.onKeyDown(down, android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, down, 1))
                        assertEquals("Focus moves immediately while the repeated header waits", "repeat-3", (channelList(activity)!!.findFocus() as GuideAdapter.Row).channel?.id)
                        assertEquals("Repeat listing 2", header(activity))
                    }
                    waitFor(scenario) { header(it) == "Repeat listing 3" }
                    scenario.onActivity { activity ->
                        assertTrue(channelList(activity)!!.findFocus().contentDescription.contains("Repeat listing 3"))
                    }
                }
            } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun guideScrollingThemesAndCategorySelectionKeepThePlayer() {
        FixtureServer(regionGroups = true).use { server ->
            val source = Source(name = "Navigation Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.smallPreview = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                repeat(7) { device.pressDPadDown(); instrumentation.waitForIdleSync() }
                waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.name == "Test Channel 8" }
                device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == (channelList(it)!!.adapter as GuideAdapter).channels.last().id }
                scenario.onActivity { assertFalse(channelList(it)!!.isShown) }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                var attempt = ""
                scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId; it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Theme")), 5000)); activateSetting(scenario, "Theme")
                setThemeControl(scenario, "Look", "Palette", "Ocean"); leaveSettings(scenario)
                waitFor(scenario) { store.theme == "Ocean" && GuideTheme.current.name == "Ocean" }
                scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId); it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Categories")), 5000)); activateSetting(scenario, "Categories")
                assertTrue(device.wait(Until.hasObject(By.text("USA & Canada")), 5000)); activateSetting(scenario, "USA & Canada"); leaveSettings(scenario)
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 6 }
                assertEquals(setOf("USA | News", "US | MLB", "CA | News"), SecureStore(context).filters(source).groups)
                scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun vodSharesCaptionAppearanceAndShowsMeasuredVideoQuality() {
        FixtureServer(captionsMode = true).use { server ->
            val source = Source(name = "VOD captions", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.captions = true
            store.subtitleSize = "Medium"; store.subtitleStyle = "Shadow"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                fun verifyAppearance(activity: MainActivity) {
                    val view = find<PlayerView>(activity.window.decorView)!!.subtitleView!!
                    val size = view.javaClass.getDeclaredField("defaultTextSize").apply { isAccessible = true }.getFloat(view)
                    val style = view.javaClass.getDeclaredField("style").apply { isAccessible = true }.get(view) as androidx.media3.ui.CaptionStyleCompat
                    assertEquals(SubtitleAppearance.sizes.getValue("Medium"), size, .001f)
                    assertEquals(androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, style.edgeType)
                }
                scenario.onActivity { activity ->
                    verifyAppearance(activity)
                    MainActivity::class.java.getDeclaredMethod("playOnDemand", OnDemand::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(activity, OnDemand("movie:captions", "VOD caption fixture", "", "${server.base}/channel1.m3u8", playlistId = source.id), 0L)
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.currentMediaItem?.mediaId?.startsWith("movie:captions:") == true && p.isPlaying && p.currentCues.cues.isNotEmpty() } == true }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("on-demand-quality")?.text?.contains("fps") == true }
                scenario.onActivity { activity ->
                    verifyAppearance(activity)
                    // Image subtitles share size too, without mutating/reallocating the decoded image.
                    val bitmap = Bitmap.createBitmap(100, 20, Bitmap.Config.ARGB_8888)
                    try {
                        val cue = androidx.media3.common.text.Cue.Builder().setBitmap(bitmap).setSize(.5f).setBitmapHeight(.1f).build()
                        val playerView = PlayerView(activity)
                        SubtitleAppearance.render(playerView, listOf(cue), "Small")
                        @Suppress("UNCHECKED_CAST")
                        val rendered = playerView.subtitleView!!.javaClass.getDeclaredField("cues").apply { isAccessible = true }.get(playerView.subtitleView) as List<androidx.media3.common.text.Cue>
                        assertSame(bitmap, rendered.single().bitmap); assertTrue(rendered.single().size < cue.size)
                        assertEquals(5f, rendered.single().size / rendered.single().bitmapHeight, .001f)
                        playerView.subtitleView!!.setCues(emptyList())
                    } finally { bitmap.recycle() }
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun automaticSubtitlesRenderAndFpsBecomesKnown() {
        FixtureServer(captionsMode = true).use { server ->
            val source = Source(name = "Captions Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Subtitles")
                assertTrue(device.wait(Until.hasObject(By.text("Automatic")), 5000)); activateSetting(scenario, "Automatic"); leaveSettings(scenario)
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.any { cue -> cue.text.toString().contains("Spare test captions") } == true }
                assertEquals(CaptionTracks.AUTO, store.channelCaptions(source, store.cached(source)!!.channels.first()))
                waitFor(scenario) { store.videoInfo(source).values.any { it.fps > 0 } }
                scenario.onActivity { assertFalse(store.videoInfo(source).values.first().label().contains("unavailable")) }
                // Automatic above is a channel override; enable the app default separately for the next channel.
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Subtitles")
                assertTrue(device.wait(Until.hasObject(By.text("App default")), 5000)); activateSetting(scenario, "App default")
                assertTrue(device.wait(Until.hasObject(By.text("Default subtitles · all channels")), 5000)); activateSetting(scenario, "Automatic"); leaveSettings(scenario)
                waitFor(scenario) { store.captions }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == (channelList(it)!!.adapter as GuideAdapter).channels[1].id }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isNotEmpty() == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Subtitles")
                assertTrue(device.wait(Until.hasObject(By.text("Off")), 5000)); activateSetting(scenario, "Off"); leaveSettings(scenario)
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isEmpty() == true }
                assertEquals(CaptionTracks.OFF, SecureStore(context).channelCaptions(source, store.cached(source)!!.channels[1]))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun guideBackHierarchyAndBrowsingPreferencesKeepFocusAndPlayback() {
        FixtureServer(channelCount = 8).use { server ->
            val source = Source(name = "Guide browsing", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.hiddenDock = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var liveId = ""
                scenario.onActivity { liveId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").hasFocus() }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-mode").hasFocus() }
                device.pressDPadRight(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").hasFocus() }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(2)!!.itemView.requestFocus()
                }
                waitFor(scenario) { (channelList(it)?.adapter as? GuideAdapter)?.channels?.let { c -> c.size == 4 && c.all { channel -> channel.group == "News" } } == true }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories").hasFocus())
                    assertEquals(liveId, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(3)!!.itemView.requestFocus()
                }
                // Right before the debounce expires must enter the highlighted category, not the old rows.
                device.pressDPadRight()
                waitFor(scenario) { channelList(it)?.hasFocus() == true && (channelList(it)?.adapter as? GuideAdapter)?.channels?.all { c -> c.group == "Culture" } == true }
                device.pressBack(); device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-mode").hasFocus() }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                device.pressMenu(); activateSetting(scenario, "Switch categories on highlight"); activateSetting(scenario, "Open channels full screen"); activateSetting(scenario, "Loop guide channels")
                scenario.onActivity { assertFalse(store.guideAutoCategories); assertFalse(store.guideOpenFullscreen); assertFalse(store.guideLoopChannels) }
                leaveSettings(scenario); device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").hasFocus() }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(2)!!.itemView.requestFocus() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertTrue((channelList(it)!!.adapter as GuideAdapter).channels.all { c -> c.group == "Culture" }) }
                device.pressDPadCenter(); waitFor(scenario) { channelList(it)?.hasFocus() == true && (channelList(it)?.adapter as? GuideAdapter)?.channels?.all { c -> c.group == "News" } == true }
                device.pressDPadUp(); scenario.onActivity { assertEquals(0, channelList(it)!!.getChildAdapterPosition(channelList(it)!!.focusedChild)) }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.mediaId != liveId && channelList(it)?.hasFocus() == true }
                var previewId = ""; scenario.onActivity { previewId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true && MainActivity::class.java.getDeclaredField("hasPlayedChannel").apply { isAccessible = true }.getBoolean(it) }
                device.pressDPadCenter(); waitFor(scenario) { channelList(it)?.isShown == false }
                scenario.onActivity { assertEquals(previewId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun backFromChannelsReturnsToTheCategoryOnScreenNotOneBrowsedEarlier() {
        FixtureServer().use { server ->
            val source = Source(name = "Category return", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..12).map { Channel("back:$it", "Channel $it", "Category ${(it - 1) / 3 + 1}", "${server.base}/channel1.mp4") }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }; device.pressDPadLeft()
                // Browse to a category by moving the cursor onto it, then switch to All channels another way.
                repeat(10) { if (!device.hasObject(By.text("Category 3").focused(true))) { device.pressDPadDown(); instrumentation.waitForIdleSync() } }
                waitFor(scenario) { device.hasObject(By.text("Category 3").focused(true)) && channelList(it)?.adapter?.itemCount == 3 }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-all").performClick() }
                waitFor(scenario) { channelList(it)?.let { list -> list.adapter?.itemCount == 12 && list.hasFocus() } == true }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").hasFocus() }
                Thread.sleep(700); instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals("Back changed the category to one browsed earlier", 12, channelList(it)!!.adapter!!.itemCount) }
                assertTrue("Back lands on the category whose channels are shown", device.hasObject(By.text("All channels").focused(true)))
            }
        }
    }

    @Test fun navigationAnimationsLeaveNoHighlightBehindOnRowsTheCursorLeft() {
        FixtureServer().use { server ->
            val source = Source(name = "Ghost check", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..40).map { Channel("ghost:$it", "Channel $it", "Category ${(it - 1) / 5 + 1}", "${server.base}/channel1.mp4") }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            for (style in listOf("Fade in", "Cross-fade")) for (speed in listOf("Slow", "Fast")) {
                store.navigationAnimation = style; store.navigationSpeed = speed
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                    device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                    // Presses arrive faster than a fade lasts, as when a remote button is held or tapped quickly.
                    fun keys(vararg codes: Int) = codes.forEach { code ->
                        val at = android.os.SystemClock.uptimeMillis()
                        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                            instrumentation.uiAutomation.injectInputEvent(KeyEvent(at, at, action, code, 0).apply { setSource(android.view.InputDevice.SOURCE_DPAD) }, false)
                        }
                        Thread.sleep(25)
                    }
                    val d = KeyEvent.KEYCODE_DPAD_DOWN; val u = KeyEvent.KEYCODE_DPAD_UP; val l = KeyEvent.KEYCODE_DPAD_LEFT; val r = KeyEvent.KEYCODE_DPAD_RIGHT
                    keys(d, d, d, d, d, d, d, d, d, d, d, d, d, d, u, u, u, u, u, u, l, d, d, r, d)
                    Thread.sleep(1500); instrumentation.waitForIdleSync()
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "ghost-${style.replace(' ', '-')}-$speed.png"))
                    scenario.onActivity { activity ->
                        fun pixels(drawable: android.graphics.drawable.Drawable, view: View): IntArray {
                            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                            drawable.draw(android.graphics.Canvas(bitmap))
                            return IntArray(view.width * view.height).also { bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height); bitmap.recycle() }
                        }
                        val stuck = mutableListOf<String>()
                        fun check(view: View) {
                            listOfNotNull(view.background, view.foreground).filterIsInstance<android.graphics.drawable.StateListDrawable>().forEach { drawable ->
                                if (view.width > 0 && view.height > 0) {
                                    val shown = pixels(drawable, view); drawable.jumpToCurrentState(); val settled = pixels(drawable, view)
                                    if (!shown.contentEquals(settled) || drawable.current.alpha != 255) stuck += "${(view as? TextView)?.text ?: view.contentDescription ?: view.tag} focused=${view.isFocused} alpha=${drawable.current.alpha}"
                                }
                            }
                            (view as? ViewGroup)?.let { group -> (0 until group.childCount).forEach { check(group.getChildAt(it)) } }
                        }
                        check(activity.window.decorView)
                        assertTrue("$style $speed left a highlight on: $stuck", stuck.isEmpty())
                        // A row refreshed or re-attached in the middle of its fade (Android then jumps its drawables to the end) must end fully drawn, not frozen part-way.
                        val list = channelList(activity)!!
                        val from = list.focusedChild; val to = (0 until list.childCount).map(list::getChildAt).first { it !== from && it.isFocusable }
                        assertTrue(to.requestFocus())
                        listOf(from, to).forEach { it.jumpDrawablesToCurrentState() }
                        check(activity.window.decorView)
                        assertTrue("$style $speed froze a fade part-way on: $stuck", stuck.isEmpty())
                    }
                }
            }
        }
    }

    @Test fun deepCategoriesReachToolbarAndReturnWithoutScrollingOrRetuning() {
        FixtureServer().use { server ->
            val source = Source(name = "Deep sidebar", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..80).map { Channel("deep:$it", "Channel $it", "Category $it", "${server.base}/channel1.mp4") }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var attempt = ""
                scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }; device.pressDPadLeft()
                repeat(30) { device.pressDPadDown(); instrumentation.waitForIdleSync() }
                waitFor(scenario) { device.hasObject(By.text("Category 29").focused(true)) }
                var first = -1
                scenario.onActivity { first = (it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition() }
                device.pressDPadLeft()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-mode").hasFocus() }
                device.pressDPadUp(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search").hasFocus() }
                device.pressDPadDown(); device.pressDPadRight(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressDPadLeft(); waitFor(scenario) { device.hasObject(By.text("Category 29").focused(true)) }
                scenario.onActivity {
                    assertTrue(kotlin.math.abs(first - (it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()) <= 1)
                    assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
                device.pressDPadLeft(); repeat(3) { device.pressDPadDown() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Playlists")), 5000))
                activateSetting(scenario, "Navigation dock"); activateSetting(scenario, "Hidden dock"); leaveSettings(scenario)
                waitFor(scenario) { channelList(it)?.hasFocus() == true && it.window.decorView.findViewWithTag<View>("navigation-handle").isShown && it.window.decorView.findViewWithTag<View>("app-navigation").alpha < .01f }
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                val dockFlashed = AtomicBoolean(false)
                lateinit var dockDraw: android.view.ViewTreeObserver.OnPreDrawListener
                scenario.onActivity { activity ->
                    val dock = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                    dockDraw = android.view.ViewTreeObserver.OnPreDrawListener {
                        if (dock.isShown && (dock.expanded || dock.moving || dock.alpha > .01f)) dockFlashed.set(true)
                        true
                    }
                    dock.viewTreeObserver.addOnPreDrawListener(dockDraw)
                }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation").viewTreeObserver.removeOnPreDrawListener(dockDraw)
                    assertFalse("Returning from fullscreen must never draw an expanded/closing hidden dock", dockFlashed.get())
                    assertEquals(attempt, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
                device.pressDPadLeft(); device.pressDPadLeft(); repeat(3) { device.pressDPadDown() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings").hasFocus() && it.window.decorView.findViewWithTag<View>("app-navigation").alpha > .99f }
                scenario.onActivity { activity ->
                    val dock = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                    channelList(activity)!!.getChildAt(0).requestFocus()
                    dock.postOnAnimation { dock.focusCurrent() }
                }
                waitFor(scenario) { activity -> activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation").let { it.expanded && !it.moving && it.alpha == 1f && it.hasFocus() } }
                device.pressDPadRight(); waitFor(scenario) { channelList(it)?.hasFocus() == true && it.window.decorView.findViewWithTag<View>("settings-panel").isShown == false }
                device.pressDPadLeft(); device.pressDPadLeft(); repeat(2) { device.pressDPadDown() }; device.pressDPadRight()
                waitFor(scenario) { channelList(it)?.hasFocus() == true && !it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                device.pressMenu(); activateSetting(scenario, "Navigation dock"); activateSetting(scenario, "Always open"); leaveSettings(scenario)
                waitFor(scenario) { !store.hiddenDock && it.window.decorView.findViewWithTag<View>("app-navigation").alpha == 1f && !it.window.decorView.findViewWithTag<View>("navigation-handle").isShown }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun undeclaredEmbeddedCaptionsRenderAndChannelChoicesPersist() {
        listOf(false, true).forEach { digital -> FixtureServer(embeddedCaptions = if (digital) 708 else 608, embeddedHls = digital).use { server ->
            val source = Source(name = "Embedded captions", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.captions = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.any { cue -> cue.text.toString().contains("Spare captions") } == true }
                val first = store.cached(source)!!.channels.first()
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    val selected = CaptionTracks.choices(player.currentTracks).first { it.group.isTrackSelected(it.index) }
                    assertEquals(if (digital) androidx.media3.common.MimeTypes.APPLICATION_CEA708 else androidx.media3.common.MimeTypes.APPLICATION_CEA608, selected.format.sampleMimeType)
                }
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-subtitles")?.isShown == true }; scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-subtitles").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Off"); leaveSettings(scenario)
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isEmpty() == true }
                assertEquals(CaptionTracks.OFF, SecureStore(context).channelCaptions(source, first)); assertTrue(store.captions)
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("settings-panel").isShown && channelList(it)?.isShown == false }
                device.pressDPadDown()
                waitFor(scenario) { store.last(source) != first.id && find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isNotEmpty() == true }
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == first.id }
                scenario.onActivity { assertTrue(find<PlayerView>(it.window.decorView)!!.player!!.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)) }
                scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { assertTrue(find<PlayerView>(it.window.decorView)!!.player!!.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        } }
    }

    @Test fun compactSportsCardsKeepBothTeamNamesAndScoresInsideTheirBounds() {
        FixtureServer().use { server ->
            val source = Source(name = "Compact Sports fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("compact-feed", "News", "News", "${server.base}/channel1.mp4")
            val teams = SportsCatalog.teams.filter { "NCAAM" in it.competitions }.sortedByDescending { it.name.length }.take(2).sortedBy { it.id }
            val now = System.currentTimeMillis(); val key = "NCAAM:compact-card"
            val snapshot = LiveSportsSnapshot(listOf(LiveEvent("compact-card", teams.map { it.id }.toSet(), now - 60000,
                scores = mapOf(teams[0].id to "103", teams[1].id to "102"), status = "12:34 - 2nd half", league = "NCAAM",
                records = teams.associate { it.id to "11-11" })), now, setOf("NCAAM"))
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NCAAM")
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { snapshot }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { player -> player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-card-team-name:$key:1")?.let { view -> view.layout != null && view.height > 0 } == true }
                scenario.onActivity { activity ->
                    val card = activity.window.decorView.findViewWithTag<ViewGroup>(key)
                    card.requestFocus()
                    val banner = card.findViewWithTag<ViewGroup>("sports-card-banner:$key")
                    val checked = mutableListOf<TextView>()
                    assertTrue("The compact card reserves at least 52 dp for both crests", banner.height >= (52 * activity.resources.displayMetrics.density).toInt())
                    repeat(2) { index ->
                        val name = card.findViewWithTag<TextView>("sports-card-team-name:$key:$index")
                        val record = card.findViewWithTag<TextView>("sports-card-team-record:$key:$index")
                        val score = card.findViewWithTag<TextView>("sports-card-score:$key:$index")
                        assertEquals(teams[index].name, name.text.toString()); assertEquals("(11-11)", record.text.toString())
                        assertEquals(if (index == 0) "103" else "102", score.text.toString())
                        assertEquals("Focused bottom-line scores stay legible", GuideTheme.focusText, score.currentTextColor)
                        assertTrue("Scores belong in the bottom details line", generateSequence(score as View?) { it.parent as? View }.any { it.tag == "sports-card-footer" })
                        assertFalse("The artwork remains free of score widgets", generateSequence(score as View?) { it.parent as? View }.any { it === banner })
                        checked.addAll(listOf(name, record, score))
                    }
                    checked.add(card.findViewWithTag("sports-card-date:$key")); checked.add(card.findViewWithTag("sports-card-live-detail:$key"))
                    checked.forEach { view ->
                        val layout = view.layout!!
                        assertTrue("Text must retain a complete line height: ${view.tag}", layout.getLineBottom(layout.lineCount - 1) + view.totalPaddingTop <= view.height - view.totalPaddingBottom + 1)
                    }
                    val first = card.findViewWithTag<View>("sports-card-team-name:$key:0"); val second = card.findViewWithTag<View>("sports-card-team-name:$key:1")
                    val a = IntArray(2); val b = IntArray(2); first.getLocationOnScreen(a); second.getLocationOnScreen(b)
                    assertTrue("The second team has its own row", a[1] + first.height <= b[1])
                    assertTrue("Fixed body fits within the smaller card", (0 until card.childCount).all { index -> card.getChildAt(index).bottom <= card.height - card.paddingBottom })
                }
                instrumentation.waitForIdleSync(); device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-compact-card.png"))
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.sportsLeagues = SportsLeagues.byId.keys }
        }
    }

    @Test fun largeSportsSlateKeepsNavigationAndScoresResponsiveDuringPendingDiffs() {
        FixtureServer().use { server ->
            val source = Source(name = "Large Sports fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("large-slate-feed", "News", "News", "${server.base}/channel1.mp4")
            val bytes = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, out); recycle() } }.toByteArray()
            val urls = listOf("https://images.example/large-home-${System.nanoTime()}.png", "https://images.example/large-away-${System.nanoTime()}.png")
            val files = ArtworkFiles(context); urls.forEach { files.bytes(it) { bytes } }
            val now = System.currentTimeMillis()
            var latest = LiveSportsSnapshot((1..240).map { LiveEvent("large-slate-$it", setOf("nfl:SEA", "nfl:SF"), now - 60000,
                scores = mapOf("nfl:SEA" to "14", "nfl:SF" to "7"), logos = mapOf("nfl:SEA" to urls[0], "nfl:SF" to urls[1]),
                status = "8:24 - 2nd", league = "NFL", records = mapOf("nfl:SEA" to "6-2", "nfl:SF" to "4-4"), situation = "3rd & 7 at SEA 28", possession = "nfl:SEA") }, now, setOf("NFL"))
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NFL"); store.hiddenDock = true
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { latest }
            }
            lifecycle.addLifecycleCallback(fixture)
            val release = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var playing: Player? = null
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> playing = p; p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.let { grid -> grid.adapter?.itemCount == 241 && grid.hasFocus() } == true }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-hero-title")?.text?.contains("(6-2)") == true }
                scenario.onActivity { activity ->
                    val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                    grid.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                    val card = grid.findViewHolderForAdapterPosition(0)!!.itemView
                    assertEquals("Seattle Seahawks", card.findViewWithTag<TextView>("sports-card-team-name:NFL:large-slate-1:0").text.toString())
                    assertEquals("(6-2)", card.findViewWithTag<TextView>("sports-card-team-record:NFL:large-slate-1:0").text.toString())
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-hero-detail").text.contains("3rd & 7"))
                }
                val entered = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                    val worker = SportsPanel::class.java.getDeclaredField("cardDiffWorker").apply { isAccessible = true }.get(panel) as java.util.concurrent.ExecutorService
                    worker.submit { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    latest = latest.copy(events = latest.events.map { it.copy(scores = mapOf("nfl:SEA" to "21", "nfl:SF" to "7")) })
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, latest)
                    MainActivity::class.java.getDeclaredMethod("refreshSports").apply { isAccessible = true }.invoke(activity)
                }
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                try {
                    device.pressDPadRight()
                    waitFor(scenario) { activity ->
                        val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                        grid.findFocus()?.let { grid.findContainingViewHolder(it)?.bindingAdapterPosition == 1 } == true
                    }
                } finally { release.countDown() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-hero-score")?.text?.contains("21") == true }
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                    repeat(12) {
                        val tick = android.os.SystemClock.uptimeMillis()
                        panel.dispatchKeyEvent(KeyEvent(tick, tick, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0))
                        panel.dispatchKeyEvent(KeyEvent(tick, tick, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0))
                    }
                }
                waitFor(scenario) { activity ->
                    val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                    grid.findFocus()?.let { (grid.findContainingViewHolder(it)?.bindingAdapterPosition ?: -1) >= 30 } == true
                }
                scenario.onActivity { activity ->
                    val rail = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                    assertFalse("Rapid card navigation must not open the main menu", rail.expanded)
                    assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playing!!.isPlaying)
                    assertEquals(241, activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter!!.itemCount)
                }
                instrumentation.waitForIdleSync()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-large-slate.png"))
            } } finally { release.countDown(); lifecycle.removeLifecycleCallback(fixture); store.sportsLeagues = SportsLeagues.byId.keys }
        }
    }

    @Test fun sportsCompactBrowseKeepsScopeFocusAndTwoRowsVisible() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Sports layout fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val preview = Channel("layout-preview", "Preview", "News", "${server.base}/channel1.mp4")
            val feed = Channel("layout-feed", "Fixture Sports", "Sports", "${server.base}/channel2.mp4")
            val now = System.currentTimeMillis()
            val football = setOf("nfl:SEA", "nfl:SF")
            val basketball = setOf("nba:POR", "nba:LAL")
            val logoLabels = mapOf("nfl:SEA" to ("SEA" to 0xFF314F71.toInt()), "nfl:SF" to ("SF" to 0xFF732B36.toInt()),
                "nba:POR" to ("POR" to 0xFF762A37.toInt()), "nba:LAL" to ("LAL" to 0xFF6B4B8D.toInt()))
            val artwork = ArtworkFiles(context)
            val logos = logoLabels.mapValues { (id, nameAndColor) ->
                val (name, tint) = nameAndColor
                val url = "https://images.example/spare-layout-${id.replace(':', '-')}.png"
                artwork.bytes(url) {
                    val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
                    canvas.drawCircle(80f, 80f, 74f, paint)
                    paint.color = Color.WHITE; paint.textSize = 43f; paint.textAlign = Paint.Align.CENTER
                    paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    canvas.drawText(name, 80f, 94f, paint)
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
                }
                url
            }
            val snapshot = LiveSportsSnapshot(listOf("NFL" to football, "NBA" to basketball).flatMap { (league, teams) ->
                List(30) { i ->
                    LiveEvent("layout-${league.lowercase()}-$i", teams, now - 600_000 + i * 1000, league = league,
                        networks = listOf("Fixture Sports"), scores = teams.associateWith { if (it.endsWith("SEA")) "21" else "14" },
                        logos = logos.filterKeys { it in teams }, status = "Q3 · 08:24",
                        records = teams.associateWith { "6-2" }, situation = "3rd & 7 at SEA 28")
                }
            }, now, setOf("NFL", "NBA"))
            val guide = Guide(listOf(preview, feed), mapOf(feed.id to listOf(Programme(feed.id, "NFL: Seahawks at 49ers", now - 900_000, now + 3_600_000))))
            val measure = InstrumentationRegistry.getArguments().getString("sports_benchmark") == "true"
            store.sportsLeagues = setOf("NFL", "NBA"); store.sportsLastLeague = "NFL"; store.performanceOverlay = measure
            store.recentFeeds = emptyList(); store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, guide); store.markPlaylistRefreshed(source)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { snapshot }
            }
            lifecycle.addLifecycleCallback(callback)
            val gridTag = "sports-games"
            val firstKey = "NFL:layout-nfl-0"
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                fun sports() {
                    scenario.onActivity {
                        // This layout check browses one league's whole day (Today), not Live now.
                        MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.set(it, "today")
                        MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, true)
                        it.window.decorView.findViewWithTag<SportsPanel>("sports-panel").home()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>(firstKey)?.isShown == true }
                }
                fun focusFirst() {
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>(firstKey).requestFocus() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>(firstKey)?.hasFocus() == true }
                }
                fun screenshot(name: String) {
                    instrumentation.waitForIdleSync(); device.waitForIdle()
                    device.takeScreenshot(File(context.getExternalFilesDir(null), name))
                }
                sports()
                focusFirst()
                var compact = false
                scenario.onActivity { compact = it.window.decorView.findViewWithTag<View>("sports-context") != null }

                if (measure) {
                    val overlayField = MainActivity::class.java.getDeclaredField("performanceOverlay").apply { isAccessible = true }
                    lateinit var overlay: PerformanceOverlay
                    scenario.onActivity { overlay = overlayField.get(it) as PerformanceOverlay }
                    val frames = PerformanceOverlay::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(overlay) as PerformanceFrames
                    fun resetFrames() { frames.summary(android.os.SystemClock.elapsedRealtime() + 10_000L) }
                    fun reportFrames(label: String) {
                        Thread.sleep(200)
                        val result = frames.summary(android.os.SystemClock.elapsedRealtime())!!
                        println("Sports layout ${if (compact) "after" else "before"} $label: ${result.jankyPercent}% jank, ${result.count} frames")
                    }
                    resetFrames()
                    repeat(36) { i ->
                        scenario.onActivity { activity ->
                            val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                            val tick = android.os.SystemClock.uptimeMillis()
                            val key = if ((i / 9) % 2 == 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                            panel.dispatchKeyEvent(KeyEvent(tick, tick, KeyEvent.ACTION_DOWN, key, 0))
                            panel.dispatchKeyEvent(KeyEvent(tick, tick, KeyEvent.ACTION_UP, key, 0))
                        }
                        Thread.sleep(65)
                    }
                    reportFrames("grid")
                    resetFrames()
                    repeat(10) { i ->
                        val league = if (i % 2 == 0) "NBA" else "NFL"
                        val key = "$league:layout-${league.lowercase()}-0"
                        scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:$league").performClick() }
                        waitFor(scenario) { activity ->
                            activity.window.decorView.findViewWithTag<RecyclerView>(gridTag).findViewHolderForAdapterPosition(0)?.itemView?.tag == key
                        }
                    }
                    reportFrames("league")
                    store.sportsAutoPlay = true
                    repeat(20) {
                        sports()
                        val previous = overlay.sports.forGame(firstKey)
                        scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>(firstKey).performClick()) }
                        waitFor(scenario) { overlay.sports.forGame(firstKey)?.let { it !== previous && it.snapshot().firstFrameMs != null } == true }
                    }
                    println("Sports layout ${if (compact) "after" else "before"} " + overlay.sports.report().lineSequence().last())
                    store.sportsAutoPlay = false; store.performanceOverlay = false
                    scenario.onActivity {
                        MainActivity::class.java.getDeclaredMethod("syncPerformanceOverlay").apply { isAccessible = true }.invoke(it)
                    }
                    sports(); focusFirst()
                }

                screenshot(if (compact) "sports-browse.png" else "sports-before.png")
                if (compact) {
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        val grid = root.findViewWithTag<RecyclerView>(gridTag)
                        val second = grid.findViewHolderForAdapterPosition(3)!!.itemView
                        val third = grid.findViewHolderForAdapterPosition(6)!!.itemView
                        assertTrue("Two rows and a peek at 1080p, grid=${grid.height}, row 2 bottom=${second.bottom}, row 3=${third.top}..${third.bottom}",
                            second.bottom <= grid.height && third.top < grid.height && third.bottom > grid.height)
                        assertTrue(root.findViewWithTag<TextView>("sports-context").text.contains("30 games"))
                        assertNull(root.findViewWithTag<View>("sports-league-filter"))
                    }
                    // One filter row: Up from the games reaches the selected league, Left/Right walk the row.
                    device.pressDPadUp()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:NFL").hasFocus() }
                    device.pressDPadLeft()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-date").hasFocus() }
                    device.pressDPadLeft()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-my-teams").hasFocus() }
                    device.pressDPadLeft()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-today").hasFocus() }
                    screenshot("sports-filter-bar.png")
                    device.pressDPadDown()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>(firstKey).hasFocus() }
                    device.pressDPadLeft()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:NBA").requestFocus() }
                    device.pressDPadRight()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-teams").hasFocus() }
                    device.pressDPadDown()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>(gridTag).hasFocus() }
                    focusFirst()
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>(firstKey).performLongClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-watch").hasFocus() }
                    screenshot("sports-hero-actions.png")
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:layout-feed")?.hasFocus() == true }
                    assertTrue("Back returns from stream results", device.pressBack())
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>(gridTag).hasFocus() }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>(firstKey).performLongClick() }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:layout-feed")?.hasFocus() == true }
                    device.pressDPadUp()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-filters")?.hasFocus() == true }
                    screenshot("sports-streams.png")
                }
            } } finally {
                lifecycle.removeLifecycleCallback(callback)
                store.performanceOverlay = false; store.sportsAutoPlay = false; store.recentFeeds = emptyList()
                store.saveSources(emptyList()); store.removeCache(source); store.sportsLeagues = SportsLeagues.byId.keys
            }
        }
    }

    @Test fun sportsSchedulePickerBackAndLeftRestoreOpenerFocus() {
        FixtureServer().use { server ->
            val source = Source(name = "Schedule picker fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL")
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { LiveSportsSnapshot(emptyList(), System.currentTimeMillis(), setOf("NFL")) }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) {
                        find<PlayerView>(it.window.decorView)?.player?.let { player ->
                            player.repeatMode = Player.REPEAT_MODE_ONE
                            player.isPlaying
                        } == true
                    }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-live")?.isShown == true }
                    scenario.onActivity { assertNull("The schedule is a row of choices, not a popup", it.window.decorView.findViewWithTag<View>("sports-schedule-menu")) }
                    // Let the first load place focus before moving it, or it can land after our request.
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-games")?.hasFocus() == true || it.currentFocus?.tag == "sports-league:NFL" }
                    scenario.onActivity { activity ->
                        // A remote key cancels the pending "enter the games once loaded" step; a programmatic focus does not.
                        val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                        SportsPanel::class.java.getDeclaredField("pendingEnter").apply { isAccessible = true }.setBoolean(panel, false)
                        activity.window.decorView.findViewWithTag<View>("sports-today").requestFocus()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-today").hasFocus() }
                    device.pressDPadLeft()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-live").hasFocus() }
                    device.pressDPadLeft()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
            }
        }
    }

    @Test fun sportsShowsGamesWhileScoreboardRepliesAreStillArriving() {
        FixtureServer().use { server ->
            val source = Source(name = "Sports arriving fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            // A lineup the size of a real provider's, so preparing the cards takes real time.
            val start = System.currentTimeMillis() - 1800000L
            val channels = (1..15800).map { Channel("arriving:$it", if (it % 40 == 0) "US| NFL Game Pass $it HD" else "US| Channel $it HD", "Category ${it / 200}", "${server.base}/channel1.mp4") }
            val programmes = channels.filter { it.id.substringAfter(':').toInt() % 4 == 0 }.associate { channel ->
                channel.id to (0 until 8).map { slot -> Programme(channel.id, if (slot % 3 == 0) "NFL Football: Buffalo Bills at Kansas City Chiefs" else "Programme $slot", start + slot * 3600000L, start + (slot + 1) * 3600000L) }
            }
            store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            store.sportsLeagues = setOf("NFL")
            val finished = java.util.concurrent.atomic.AtomicBoolean()
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = {
                        // Many leagues answer one after another for several seconds, each reply publishing what is known so far.
                        val now = System.currentTimeMillis()
                        fun known() = LiveSportsSnapshot(listOf(LiveEvent("arriving-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL")), now, setOf("NFL"))
                        val until = android.os.SystemClock.elapsedRealtime() + 15000
                        while (android.os.SystemClock.elapsedRealtime() < until && !Thread.currentThread().isInterrupted) { activity.publishSportsPartial(known()); Thread.sleep(2) }
                        finished.set(true); known()
                    }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    val opened = android.os.SystemClock.elapsedRealtime()
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.adapter?.itemCount == 2 }
                    val waited = android.os.SystemClock.elapsedRealtime() - opened
                    assertTrue("The game shows while later replies are still arriving, not after the last one (took $waited ms)", !finished.get() && waited < 4000)
                    // The performance report says what arrived when, so a slow open on a real box can be traced.
                    val report = Diagnostics.report()
                    assertTrue("Sports opening is traced: $report", "Last Sports opening" in report && "Sports chosen" in report && "Scoreboard reply" in report && Regex("Prepared 1 games in \\d+ ms").containsMatchIn(report))
                    // The slower guide read for other live sports waits until the scoreboard replies finish, then is traced too.
                    val deadline = android.os.SystemClock.elapsedRealtime() + 40000
                    while ("Guide read for other live sports" !in Diagnostics.report() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
                    assertTrue("The guide read waited for the scoreboard", finished.get())
                    assertTrue("The guide read is traced: ${Diagnostics.report()}", "Guide read for other live sports" in Diagnostics.report())
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun sportsStartsAtTheTopWhenGamesArriveAfterRedZoneTookTheCursor() {
        FixtureServer().use { server ->
            val source = Source(name = "Sports top fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL", "NBA")
            val release = java.util.concurrent.CountDownLatch(1)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = {
                        val now = System.currentTimeMillis()
                        // The NFL answers first with no live game, so RedZone is the only card for a moment.
                        activity.publishSportsPartial(LiveSportsSnapshot(emptyList(), now, setOf("NFL")))
                        release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                        LiveSportsSnapshot(listOf(LiveEvent("top-1", setOf("nba:BOS", "nba:NY"), now - 300000, league = "NBA"),
                            LiveEvent("top-2", setOf("nba:LAL", "nba:GS"), now - 200000, league = "NBA")), now, setOf("NFL", "NBA"))
                    }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.hasFocus() == true }
                    release.countDown()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.adapter?.itemCount == 3 }
                    waitFor(scenario) { activity ->
                        val list = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                        list.findViewHolderForAdapterPosition(0)?.itemView?.hasFocus() == true
                    }
                    scenario.onActivity { activity ->
                        assertFalse("The cursor left RedZone, now the last card", activity.window.decorView.findViewWithTag<View>("sports-redzone").hasFocus())
                        assertNotEquals("The hero follows the focused game", "NFL COVERAGE", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.toString())
                    }
                    // A card the viewer moves to is still where Sports resumes.
                    device.pressDPadRight(); device.pressDPadRight()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.hasFocus() == true }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun sportsLeavesRedZoneOutOnOtherDays() {
        FixtureServer().use { server ->
            val source = Source(name = "Sports weekday fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL")
            SportsDestinations.redZoneDay = { false }
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(LiveEvent("weekday-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL")), now, setOf("NFL")) }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.adapter?.itemCount == 1 }
                    Thread.sleep(500)
                    scenario.onActivity { activity ->
                        assertEquals("Only the game: no RedZone card outside Sunday", 1, activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter!!.itemCount)
                        assertNull(activity.window.decorView.findViewWithTag<View>("sports-redzone"))
                    }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun sportsRetriesSoonWhenTheFirstScoreboardLoadMissesItsDeadline() {
        FixtureServer().use { server ->
            val source = Source(name = "Sports retry fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL")
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = {
                        val now = System.currentTimeMillis()
                        // The first load runs out of time on a busy TV and comes back with no league finished.
                        if (calls.incrementAndGet() == 1) LiveSportsSnapshot(emptyList(), now, emptySet())
                        else LiveSportsSnapshot(listOf(LiveEvent("retry-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL")), now, setOf("NFL"))
                    }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    val opened = android.os.SystemClock.elapsedRealtime()
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.adapter?.itemCount == 2 }
                    val waited = android.os.SystemClock.elapsedRealtime() - opened
                    assertTrue("The game appears after a quick retry, not the minute-long poll (took $waited ms, ${calls.get()} loads)", waited < 20000 && calls.get() >= 2)
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun myTeamsListsAFollowedTeamsWeekAndALeagueLeadsWithTheTeamOnADayItDoesNotPlay() {
        FixtureServer().use { server ->
            val source = Source(name = "My teams tab fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL"); store.followedTeams = setOf("nfl:SEA")
            val nextStart = java.util.concurrent.atomic.AtomicLong()
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = {
                        val now = System.currentTimeMillis(); val day = 86400000L; nextStart.compareAndSet(0, now + 4 * day)
                        // Seattle played three days ago and plays again in four; only Buffalo and Kansas City play today.
                        LiveSportsSnapshot(listOf(LiveEvent("other-live", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL"),
                            LiveEvent("sea-final", setOf("nfl:SEA", "nfl:LAR"), now - 3 * day, "post", scores = mapOf("nfl:SEA" to "27", "nfl:LAR" to "20"), scoreboard = false, league = "NFL"),
                            LiveEvent("sea-next", setOf("nfl:SEA", "nfl:DAL"), nextStart.get(), "pre", scoreboard = false, league = "NFL")), now, setOf("NFL"))
                    }
                }
            }
            fun cards(activity: MainActivity): List<String?> {
                val rows = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games") ?: return emptyList()
                return (0 until rows.childCount).map(rows::getChildAt).sortedBy(rows::getChildAdapterPosition).map { it.tag as? String }
            }
            fun context(activity: MainActivity) = activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-context")?.text?.toString()
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-my-teams")?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-my-teams").performClick() }
                    // My teams: the team's next game, then its result this week. No other team's game and no RedZone card.
                    // The banner names the upcoming game's date once.
                    waitFor(scenario) { cards(it) == listOf("NFL:sea-next", "NFL:sea-final") && context(it) == "My teams · 2 games · 0 live" &&
                        it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-detail")?.text?.toString()?.lines()?.first() == GuideClock.sportsDate(nextStart.get()) }
                    scenario.onActivity { activity ->
                        assertTrue(activity.window.decorView.findViewWithTag<View>("sports-my-teams").isSelected)
                        activity.window.decorView.findViewWithTag<View>("sports-league:NFL").performClick()
                    }
                    // The league's day leads with Seattle's next game, though Seattle does not play today.
                    waitFor(scenario) { cards(it).take(2) == listOf("NFL:sea-next", "NFL:other-live") }
                    scenario.onActivity { activity ->
                        assertTrue(activity.window.decorView.findViewWithTag<View>("sports-today").isSelected)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("sports-my-teams").isSelected)
                    }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.followedTeams = emptySet(); store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun fightPromotionsJoinChosenLeaguesOnceAndListTheirNextFightNight() {
        // A TV that chose its leagues before UFC and PFL existed gets them once; turning one off afterwards is kept.
        val preferences = SecureStore::class.java.getDeclaredField("preferences").apply { isAccessible = true }.get(store) as android.content.SharedPreferences
        store.sportsLeagues = setOf("NFL"); preferences.edit().remove("sports-leagues-known").commit()
        store.addNewSportsLeagues(); assertEquals(setOf("NFL", "UFC", "PFL"), store.sportsLeagues)
        store.sportsLeagues = setOf("NFL", "PFL"); store.addNewSportsLeagues(); assertEquals(setOf("NFL", "PFL"), store.sportsLeagues)
        FixtureServer().use { server ->
            val source = Source(name = "Fight fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL", "UFC")
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = {
                        val now = System.currentTimeMillis()
                        val fighters = listOf(SportsFighters.id("UFC", "21", "Israel Adesanya")!!, SportsFighters.id("UFC", "22", "Shara Magomedov")!!)
                        // Five days out: a league that showed only today's games would list nothing.
                        LiveSportsSnapshot(listOf(LiveEvent("night", fighters.toSet(), now + 5 * 86400000L, "pre", listOf("Paramount+"), status = "Main card 1:00 PM",
                            league = "UFC", records = mapOf(fighters[0] to "24-6-0"), event = "UFC Fight Night: Adesanya vs. Magomedov"),
                            LiveEvent("other-live", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL")), now, setOf("NFL", "UFC"))
                    }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:UFC")?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:UFC").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("UFC:night")?.isShown == true }
                    scenario.onActivity { activity ->
                        val card = activity.window.decorView.findViewWithTag<View>("UFC:night")
                        fun texts(view: View): List<String> = if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else listOfNotNull((view as? TextView)?.text?.toString())
                        val shown = texts(card).joinToString(" | ")
                        assertTrue(shown, "Israel Adesanya" in shown && "Shara Magomedov" in shown && "UFC Fight Night ·" in shown)
                        assertEquals("One fight night, no other league's games", 1, activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount)
                        assertEquals("Upcoming and recent · 1 fight night · 0 live", activity.window.decorView.findViewWithTag<TextView>("sports-context").text.toString())
                    }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun sportsSaysWhenShowOnlyMyTeamsHidesTheDaysGames() {
        FixtureServer().use { server ->
            val source = Source(name = "My teams fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL"); store.followedTeams = setOf("nfl:SEA"); store.sportsFollowedOnly = true
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(LiveEvent("teams-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL")), now, setOf("NFL")) }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    // Live now ignores the team setting; a league's day respects it.
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:NFL")?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:NFL").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-context")?.text?.toString() == "Today · Showing only your teams · 1 other game hidden" }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-live").performClick() }
                    // Live now shows every live game whatever the team setting, as before.
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.adapter?.itemCount == 2 }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.sportsFollowedOnly = false; store.followedTeams = emptySet(); store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun sportsOpensOnLiveNowAcrossLeaguesAndALeagueShowsItsDay() {
        FixtureServer().use { server ->
            val source = Source(name = "Live first fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL", "NBA"); store.sportsLastLeague = "NBA"
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(
                        LiveEvent("live-nfl", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL"),
                        LiveEvent("live-nba", setOf("nba:LAL", "nba:BOS"), now - 300000, league = "NBA"),
                        LiveEvent("later-nfl", setOf("nfl:SEA", "nfl:SF"), laterToday(now), "pre", league = "NFL")), now, setOf("NFL", "NBA")) }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    // Live now: both leagues' live games (and RedZone), not the later game; no league chosen.
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.adapter?.itemCount == 3 && it.window.decorView.findViewWithTag<View>("sports-live").isSelected }
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        assertNotNull(root.findViewWithTag<View>("NFL:live-nfl")); assertNotNull(root.findViewWithTag<View>("NBA:live-nba"))
                        assertFalse(root.findViewWithTag<View>("sports-league:NFL").isSelected || root.findViewWithTag<View>("sports-league:NBA").isSelected)
                        root.findViewWithTag<View>("sports-league:NFL").performClick()
                    }
                    // A league shows its day: the live game, the later game and RedZone.
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-today").isSelected && it.window.decorView.findViewWithTag<View>("NFL:later-nfl")?.isShown == true }
                    scenario.onActivity { activity -> assertNull(activity.window.decorView.findViewWithTag<View>("NBA:live-nba")) }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun liveNowListsGuideGolfAndOkPlaysItsChannel() {
        FixtureServer(channelCount = 3).use { server ->
            val source = Source(name = "Guide events fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis()
            val channels = (1..3).map { Channel("ge:$it", listOf("News", "Golf Channel", "Movies")[it - 1], "Sports", "${server.base}/channel$it.mp4", guideId = "ge:$it") }
            val programmes = mapOf("ge:1" to listOf(Programme("ge:1", "Evening News", now - 600000, now + 3600000)),
                "ge:2" to listOf(Programme("ge:2", "LIVE: PGA Tour Golf - Fixture Classic, Round 3", now - 600000, now + 3 * 3600000)),
                "ge:3" to listOf(Programme("ge:3", "Golf Movie Night", now - 600000, now + 3600000)))
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            store.sportsLeagues = setOf("NBA"); store.sportsLastLeague = "NBA"
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { LiveSportsSnapshot(emptyList(), System.currentTimeMillis(), setOf("NBA")) }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    fun card(activity: MainActivity): View? = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.let { grid ->
                        (0 until grid.childCount).map(grid::getChildAt).firstOrNull { (it.tag as? String)?.startsWith("coverage:golf:") == true } }
                    waitFor(scenario) { card(it)?.isShown == true }
                    scenario.onActivity { activity ->
                        assertEquals("Only the live golf listing becomes a card", 1, activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount)
                        assertEquals("PGA Tour Golf - Fixture Classic, Round 3", activity.window.decorView.findViewWithTag<TextView>("sports-coverage-title:${card(activity)!!.tag}").text.toString())
                        card(activity)!!.performClick()
                    }
                    waitFor(scenario) { playingPath(scenario) == "/channel2.mp4" }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun okOnSportsInTheMenuLeavesTheMenuWhileScoresAreStillLoading() {
        FixtureServer().use { server ->
            val source = Source(name = "Slow scores fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NFL")
            val release = java.util.concurrent.CountDownLatch(1)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = {
                    // On a TV the first scoreboard reply can take seconds.
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(LiveEvent("slow-nfl", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL")), now, setOf("NFL"))
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").requestFocus() }
                    waitFor(scenario) { it.currentFocus?.tag == "sports-mode" }
                    device.pressDPadCenter()
                    // The cursor leaves the menu at once and rests on Live now while the scores load.
                    waitFor(scenario) { it.currentFocus?.tag == "sports-live" && !it.window.decorView.findViewWithTag<View>("app-navigation").hasFocus() }
                    release.countDown()
                    // Then the games take the cursor as soon as they arrive.
                    waitFor(scenario) { it.currentFocus?.tag == "NFL:slow-nfl" }
                }
            } finally {
                release.countDown(); lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun lowMemoryWhileWatchingFreesHiddenPicturesAndKeepsPlaying() {
        FixtureServer().use { server ->
            val source = Source(name = "Low memory fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                    var player: Player? = null
                    scenario.onActivity { activity ->
                        player = find<PlayerView>(activity.window.decorView)!!.player
                        @Suppress("DEPRECATION") activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                    }
                    // A slow emulator can be buffering at any instant; playback must carry on, on the same player.
                    waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player === player && player!!.isPlaying }
                    scenario.onActivity { activity -> assertTrue("The guide on screen stays", channelList(activity)?.isShown == true) }
                }
            } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sportsMenuOverlaysWithoutMovingCardsAndBackGoesToTheGuide() {
        FixtureServer().use { server ->
            val source = Source(name = "Menu fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NFL"); store.hiddenDock = true
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot((1..8).map { LiveEvent("menu-$it", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL") }, now, setOf("NFL")) }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.hasFocus() == true }
                fun cards(): String { var at = ""; scenario.onActivity { a -> val v = a.window.decorView.findViewWithTag<View>("sports-games"); val p = IntArray(2); v.getLocationOnScreen(p); at = "${p[0]},${p[1]} ${v.width}x${v.height}" }; return at }
                val before = cards()
                // Left from the first card opens the (hidden) menu, as on the remote.
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
                device.pressDPadLeft()
                waitFor(scenario) { it.window.decorView.findViewWithTag<NavigationRail>("app-navigation").hasFocus() }
                instrumentation.waitForIdleSync(); Thread.sleep(400)
                assertEquals("Opening the menu draws over Sports without moving the cards", before, cards())
                device.pressBack()
                waitFor(scenario) { channelList(it)?.isShown == true && it.window.decorView.findViewWithTag<View>("sports-panel")?.isShown != true }
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.hiddenDock = false; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sportsCardsAreBuiltAheadWhileSportsIsClosed() {
        FixtureServer().use { server ->
            val source = Source(name = "Prewarm fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NFL")
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot((1..6).map { LiveEvent("warm-$it", setOf("nfl:BUF", "nfl:KC"), now - 300000, league = "NFL") }, now, setOf("NFL")) }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<SportsPanel>("sports-panel").prewarmCards() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").recycledViewPool.getRecycledViewCount(0) >= 12 }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("NFL:warm-1")?.isShown == true }
                scenario.onActivity { assertTrue("Opening Sports used the cards built ahead", it.window.decorView.findViewWithTag<RecyclerView>("sports-games").recycledViewPool.getRecycledViewCount(0) < 12) }
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun everyLiveSportsCardFindsItsStreamWithoutTheCursorMoving() {
        FixtureServer().use { server ->
            val source = Source(name = "Slate fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA")
            // Twelve live games: four rows, so half the cards start below the screen.
            val pairs = SportsCatalog.teams.filter { it.league == "NBA" }.take(24).chunked(2)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { val now = System.currentTimeMillis()
                    LiveSportsSnapshot(pairs.mapIndexed { i, (home, away) -> LiveEvent("slate-$i", setOf(home.id, away.id), now - 300000, league = "NBA") }, now, setOf("NBA")) }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { activity ->
                    val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val snapshot = guideField.get(activity) as Guide
                    val feeds = pairs.mapIndexed { i, (home, away) -> Channel("slate-feed-$i", "NBA ${i + 1}: ${away.name} vs ${home.name}", "NBA", "${server.base}/channel2.mp4") }
                    guideField.set(activity, snapshot.copy(channels = snapshot.channels + feeds))
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.adapter?.itemCount == pairs.size && it.window.decorView.findViewWithTag<View>("NBA:slate-0")?.isShown == true }
                scenario.onActivity { assertNull("The last row starts below the screen", it.window.decorView.findViewWithTag<View>("NBA:slate-${pairs.size - 1}")?.takeIf { card -> card.isShown }) }
                // No key is pressed: every card, on screen or not, looks for its stream on its own. The short fixture
                // channel stops behind Sports meanwhile, which used to hold the search back for good.
                val deadline = System.currentTimeMillis() + 90_000
                var missing = pairs.indices.toList()
                while (missing.isNotEmpty() && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        @Suppress("UNCHECKED_CAST") val summaries = MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>
                        missing = pairs.indices.filterNot { summaries["NBA:slate-$it"]?.startsWith("NBA ${it + 1}:") == true }
                    }
                    if (missing.isNotEmpty()) Thread.sleep(250)
                }
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST") val summaries = MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>
                    assertTrue("Cards still without a stream: ${missing.map { summaries["NBA:slate-$it"] }}", missing.isEmpty())
                    assertTrue(Diagnostics.sportsLines().joinToString("\n"), Diagnostics.sportsLines().any { it.contains("Every live card has looked for streams · ${pairs.size} games · ${pairs.size} found one") })
                }
                // A card scrolled into view shows the stream it found while it was off screen.
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").scrollToPosition(pairs.size - 1) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NBA:slate-${pairs.size - 1}")?.takeIf { view -> view.isShown }?.text?.startsWith("NBA ${pairs.size}:") == true }
                assertNull("Looking for streams never opens one", server.requests["/channel2.mp4"])
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sportsCardsAreReadyFromTheBackgroundBeforeSportsOpens() {
        FixtureServer(longVideo = true).use { server ->
            val source = Source(name = "Background slate fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA")
            // Fourteen games: enough that the background pass asks for the guide index partway through.
            val pairs = SportsCatalog.teams.filter { it.league == "NBA" }.take(28).chunked(2)
            // A schedule another test saved from the real scoreboard would add its own live games to this slate.
            store.delete("sports-schedule-default.enc"); store.delete("sports-schedule.enc")
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { val now = System.currentTimeMillis()
                    LiveSportsSnapshot(pairs.mapIndexed { i, (home, away) -> LiveEvent("ready-$i", setOf(home.id, away.id), now - 600000 + i * 1000L, league = "NBA") }, now, setOf("NBA")) }
            }
            fun summaries(activity: MainActivity) = @Suppress("UNCHECKED_CAST") (MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>)
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                val media = playingPath(scenario)
                scenario.onActivity { activity ->
                    val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val snapshot = guideField.get(activity) as Guide
                    // The last game is on no channel: its card says so, and keeps saying so.
                    val feeds = pairs.dropLast(1).mapIndexed { i, (home, away) -> Channel("ready-feed-$i", "NBA ${i + 1}: ${away.name} vs ${home.name}", "NBA", "${server.base}/channel2.mp4") }
                    val now = System.currentTimeMillis()
                    val listings = feeds.associate { snapshot.key(it) to listOf(Programme(it.id, "NBA Basketball", now - 3_600_000, now + 3_600_000)) }
                    guideField.set(activity, snapshot.copy(channels = snapshot.channels + feeds, programmes = snapshot.programmes + listings))
                    // Playback has been steady for more than a minute (instead of waiting for it in the test).
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity)
                    PlaybackWarmup::class.java.getDeclaredField("token").apply { isAccessible = true }.setInt(warmup, MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity))
                    PlaybackWarmup::class.java.getDeclaredField("readySince").apply { isAccessible = true }.setLong(warmup, 1L)
                }
                // Live TV plays in full screen and Sports has never been opened: every live game looks for its stream anyway.
                val deadline = System.currentTimeMillis() + 60_000
                val unnamed = pairs.lastIndex
                fun ready(text: String?, i: Int) = if (i == unnamed) text == "No stream names this game" else text?.startsWith("NBA ${i + 1}:") == true
                var missing = pairs.indices.toList()
                while (missing.isNotEmpty() && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity -> missing = pairs.indices.filterNot { ready(summaries(activity)["NBA:ready-$it"], it) } }
                    if (missing.isNotEmpty()) Thread.sleep(250)
                }
                scenario.onActivity { activity ->
                    assertTrue("Cards still without a result: ${missing.map { summaries(activity)["NBA:ready-$it"] }}", missing.isEmpty())
                    assertFalse(activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                }
                val passDeadline = System.currentTimeMillis() + 30_000
                fun counts() = Regex("(\\d+) live games · (\\d+) found a stream").find(Diagnostics.sportsBackgroundLine())?.destructured?.let { (live, found) -> live.toInt() to found.toInt() }
                while (counts()?.let { (live, found) -> live >= pairs.size && found >= pairs.size - 1 } != true && System.currentTimeMillis() < passDeadline) Thread.sleep(250)
                assertTrue("Background line: ${Diagnostics.sportsBackgroundLine()}", counts()?.let { (live, found) -> live >= pairs.size && found >= pairs.size - 1 } == true)
                val indexed = Regex("(\\d+) with the guide index").find(Diagnostics.sportsBackgroundLine())?.groupValues?.get(1)?.toInt() ?: 0
                assertTrue("The background pass used the guide index: ${Diagnostics.sportsBackgroundLine()}", indexed > 0)
                // Scores two and a half minutes old count as saved; the background pass still sees the live games and every
                // card keeps its result, including the game no channel names.
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }
                    field.set(activity, (field.get(activity) as LiveSportsSnapshot).let { it.copy(checked = System.currentTimeMillis() - 150_000) })
                    MainActivity::class.java.getDeclaredField("sportsBackgroundNotBefore").apply { isAccessible = true }.setLong(activity, 0L)
                }
                Thread.sleep(4000)
                scenario.onActivity { activity ->
                    val lost = pairs.indices.filterNot { ready(summaries(activity)["NBA:ready-$it"], it) }
                    assertTrue("Cards lost their result: ${lost.map { summaries(activity)["NBA:ready-$it"] }}", lost.isEmpty())
                    // Sports opens with current scores (it would otherwise show saved scores until its next scoreboard check).
                    val field = MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }
                    field.set(activity, (field.get(activity) as LiveSportsSnapshot).let { it.copy(checked = System.currentTimeMillis()) })
                }
                assertEquals("Looking for streams never changes the channel", media, playingPath(scenario))
                assertNull("Looking for streams never opens one", server.requests["/channel2.mp4"])
                // Opening Sports shows the streams at once: before its own searches start (Sports settles for 3.5 s first).
                val opened = android.os.SystemClock.elapsedRealtime()
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                var shown: String? = null
                val until = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < until) {
                    scenario.onActivity { shown = it.window.decorView.findViewWithTag<TextView>("sports-coverage:NBA:ready-0")?.takeIf { view -> view.isShown }?.text?.toString() }
                    if (shown?.startsWith("NBA 1:") == true) break
                    Thread.sleep(100)
                }
                scenario.onActivity { activity -> assertTrue("First card: $shown · summaries ${summaries(activity)}", shown?.startsWith("NBA 1:") == true) }
                assertTrue("The first card showed its stream ${android.os.SystemClock.elapsedRealtime() - opened} ms after Sports opened",
                    android.os.SystemClock.elapsedRealtime() - opened < 3000)
                waitFor(scenario) { Diagnostics.sportsLines().any { it.contains("live cards were ready from the background") } }
                // The last card is far below the screen and may not be built at all; a built one says no stream names it.
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NBA:ready-$unnamed")?.let { view -> !view.isShown || view.text.toString() == "No stream names this game" } != false }
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun anEventSlotNamingTheGamesDayAndStartTimePlaysOnOkDespiteAStaleListing() {
        FixtureServer().use { server ->
            val source = Source(name = "Dated slot fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA"); store.sportsAutoPlay = true
            val (home, away) = SportsCatalog.teams.filter { it.league == "NBA" }.take(2)
            val start = (System.currentTimeMillis() / 60_000 - 5) * 60_000
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = {
                    LiveSportsSnapshot(listOf(LiveEvent("slot-0", setOf(home.id, away.id), start, league = "NBA")), System.currentTimeMillis(), setOf("NBA")) }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                val eastern = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.of("America/New_York"))
                val name = "NBA 07: ${away.name} vs ${home.name} (${eastern.format(java.time.format.DateTimeFormatter.ofPattern("MM.dd hh:mm a", java.util.Locale.US))} ET)"
                scenario.onActivity { activity ->
                    val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val snapshot = guideField.get(activity) as Guide
                    val slot = Channel("dated-slot", name, "NBA Events", "${server.base}/channel2.mp4")
                    // The slot's listing still shows the last event it carried.
                    val stale = Programme(slot.id, "NBA: Los Angeles Lakers at Golden State Warriors", start - 3_600_000, start + 4 * 3_600_000)
                    guideField.set(activity, snapshot.copy(channels = snapshot.channels + slot, programmes = snapshot.programmes + (snapshot.key(slot) to listOf(stale))))
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NBA:slot-0")?.text?.toString() == name }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("NBA:slot-0").performClick() }
                waitFor(scenario) { playingPath(scenario) == "/channel2.mp4" }
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun theCursorSparePlacedStaysOnTheTopCardWithoutBlinkingAsGamesArriveAndEnd() {
        FixtureServer().use { server ->
            val source = Source(name = "Top card fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA")
            val pairs = SportsCatalog.teams.filter { it.league == "NBA" }.take(24).chunked(2)
            val began = System.currentTimeMillis()
            // Games 0-8 at first; 9-11 start earlier and arrive later. Scores change between replies.
            data class Slate(val shown: Set<Int>, val ended: Set<Int>, val points: Int)
            val slate = java.util.concurrent.atomic.AtomicReference(Slate((0..8).toSet(), emptySet(), 10))
            fun snapshot(value: Slate, now: Long = System.currentTimeMillis()) = LiveSportsSnapshot(pairs.withIndex().filter { it.index in value.shown }.map { (i, teams) ->
                val (home, away) = teams
                LiveEvent("top-$i", setOf(home.id, away.id), began - (if (i >= 9) 7_200_000L else 3_600_000L) + i * 60_000L, state = if (i in value.ended) "post" else "in",
                    status = if (i in value.ended) "Final" else "2nd", scores = mapOf(home.id to "${value.points + i}", away.id to "${value.points}"), league = "NBA") }, now, setOf("NBA"))
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { snapshot(slate.get()) }
            }
            @Suppress("UNCHECKED_CAST") fun cards(activity: MainActivity) = SportsPanel::class.java.getDeclaredField("games").apply { isAccessible = true }
                .get(activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")) as List<SportsGame>
            fun grid(activity: MainActivity) = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
            fun focusedPosition(activity: MainActivity) = grid(activity).let { g -> g.findFocus()?.let { g.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1 }
            val changes = java.util.concurrent.atomic.AtomicInteger()
            val listener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> changes.incrementAndGet() }
            fun update(scenario: ActivityScenario<MainActivity>, next: Slate) = scenario.onActivity { activity ->
                slate.set(next)
                MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, snapshot(next))
                MainActivity::class.java.getDeclaredMethod("refreshSports").apply { isAccessible = true }.invoke(activity)
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { cards(it).size == 9 && focusedPosition(it) == 0 }
                Thread.sleep(1500)
                scenario.onActivity { it.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(listener) }
                // New scores only: the highlighted top card keeps the highlight the whole time.
                update(scenario, Slate((0..8).toSet(), emptySet(), 20))
                waitFor(scenario) { cards(it).first().scores.values.contains("20") }
                Thread.sleep(800)
                scenario.onActivity { activity -> assertEquals("Focus changes on a score update", 0, changes.get()); assertEquals(0, focusedPosition(activity)) }
                // Three games that started earlier arrive: the cursor moves once, to the new top card.
                update(scenario, Slate((0..11).toSet(), emptySet(), 30))
                waitFor(scenario) { cards(it).size == 12 && cards(it).first().key == "NBA:top-9" }
                Thread.sleep(800)
                scenario.onActivity { activity -> assertEquals(0, focusedPosition(activity)); assertTrue("Focus changes when new games arrive: ${changes.get()}", changes.get() <= 2) }
                // The top game ends and leaves Live now: the cursor stays on the top card and the grid does not scroll.
                update(scenario, Slate((0..11).toSet(), setOf(9), 40))
                waitFor(scenario) { cards(it).none { game -> game.key == "NBA:top-9" } }
                Thread.sleep(800)
                scenario.onActivity { activity ->
                    assertEquals("NBA:top-10", cards(activity).first().key)
                    assertEquals(0, focusedPosition(activity))
                    assertEquals(0, (grid(activity).layoutManager as androidx.recyclerview.widget.GridLayoutManager).findFirstVisibleItemPosition())
                }
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aCardWithoutItsOwnStreamNamesTheGameAlreadyPlayingAndOkReturnsToIt() {
        val (home, away) = SportsCatalog.teams.filter { it.league == "NBA" }.take(2)
        FixtureServer(longVideo = true, programmeTitle = "NBA: ${away.name} at ${home.name}").use { server ->
            val source = Source(name = "Playing game fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA"); store.sportsAutoPlay = true
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = {
                    LiveSportsSnapshot(listOf(LiveEvent("playing-0", setOf(home.id, away.id), System.currentTimeMillis() - 1_800_000, league = "NBA")), System.currentTimeMillis(), setOf("NBA")) }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                val media = playingPath(scenario)
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("NBA:playing-0")?.isShown == true }
                // The card's own search has found nothing yet, but the playing channel's guide listing names this game:
                // the card names that channel, and OK returns to it full screen.
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("stopSportsPrefetch", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                    @Suppress("UNCHECKED_CAST") val summaries = MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as MutableMap<String, String>
                    summaries["NBA:playing-0"] = "No stream names this game"
                    activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel").updateStreamSummaries(summaries.toMap())
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NBA:playing-0")?.text?.startsWith("Playing now · ") == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("NBA:playing-0").performClick() }
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                Thread.sleep(1000)
                assertEquals("The same channel keeps playing", media, playingPath(scenario))
                assertTrue(Diagnostics.actionLines().any { it.contains("already playing") })
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aGameAboutToStartIsLookedUpBeforehandAndItsCardIsReadyAtTipOff() {
        FixtureServer(longVideo = true).use { server ->
            val source = Source(name = "Tip-off fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA")
            store.delete("sports-schedule-default.enc"); store.delete("sports-schedule.enc")
            val (home, away) = SportsCatalog.teams.filter { it.league == "NBA" }.take(2)
            val tipOff = System.currentTimeMillis() + 90_000
            val started = java.util.concurrent.atomic.AtomicBoolean(false)
            fun slate() = LiveSportsSnapshot(listOf(LiveEvent("soon-0", setOf(home.id, away.id), tipOff, state = if (started.get()) "in" else "pre", league = "NBA")), System.currentTimeMillis(), setOf("NBA"))
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { slate() }
            }
            fun summaries(activity: MainActivity) = @Suppress("UNCHECKED_CAST") (MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>)
            fun attempted(activity: MainActivity) = @Suppress("UNCHECKED_CAST") (MainActivity::class.java.getDeclaredField("sportsWarmAttempted").apply { isAccessible = true }.get(activity) as Map<String, Long>)
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { activity ->
                    val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val snapshot = guideField.get(activity) as Guide
                    // The provider has already named the event channel for tonight's game.
                    val slot = Channel("soon-slot", "NBA 01: ${away.name} vs ${home.name}", "NBA Events", "${server.base}/channel2.mp4")
                    guideField.set(activity, snapshot.copy(channels = snapshot.channels + slot))
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity)
                    PlaybackWarmup::class.java.getDeclaredField("token").apply { isAccessible = true }.setInt(warmup, MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity))
                    PlaybackWarmup::class.java.getDeclaredField("readySince").apply { isAccessible = true }.setLong(warmup, 1L)
                }
                // Before tip-off the background pass looks the game up; its card shows nothing yet.
                waitFor(scenario) { activity -> "pre:NBA:soon-0" in attempted(activity) }
                scenario.onActivity { activity -> assertNull(summaries(activity)["NBA:soon-0"]) }
                // Tip-off: the game goes live, and its card has the stream without being looked up again.
                while (System.currentTimeMillis() < tipOff - 50_000) Thread.sleep(250)
                started.set(true)
                fun steady(activity: MainActivity) {
                    // A busy emulator can make the fixture video rebuffer, which restarts the 30-second "steady playback" wait.
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity)
                    PlaybackWarmup::class.java.getDeclaredField("token").apply { isAccessible = true }.setInt(warmup, MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity))
                    PlaybackWarmup::class.java.getDeclaredField("readySince").apply { isAccessible = true }.setLong(warmup, 1L)
                }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, slate())
                    MainActivity::class.java.getDeclaredField("sportsBackgroundNotBefore").apply { isAccessible = true }.setLong(activity, 0L)
                    steady(activity)
                }
                val readyBy = System.currentTimeMillis() + 40_000
                var label: String? = null
                while (System.currentTimeMillis() < readyBy) {
                    scenario.onActivity { activity -> label = summaries(activity)["NBA:soon-0"]; steady(activity) }
                    if (label?.startsWith("NBA 01:") == true) break
                    Thread.sleep(500)
                }
                scenario.onActivity { activity ->
                    assertTrue("Card: $label · looked up: ${attempted(activity).keys} · background: ${Diagnostics.sportsBackgroundLine()}", label?.startsWith("NBA 01:") == true)
                }
                scenario.onActivity { activity -> assertFalse("The live game needed no new search", "NBA:soon-0" in attempted(activity)) }
                assertNull("Looking for streams never opens one", server.requests["/channel2.mp4"])
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aGameEndingBeforeTheViewersCardLeavesTheCursorWhereItIs() {
        FixtureServer().use { server ->
            val source = Source(name = "Hold fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA")
            val pairs = SportsCatalog.teams.filter { it.league == "NBA" }.take(18).chunked(2)
            val began = System.currentTimeMillis()
            // Games start today: after midnight, a game that started yesterday and ends leaves today's Sports instead of
            // showing its final, and this test failed when run between midnight and 1 AM.
            val today = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val first = maxOf(began - 3_600_000, today + 60_000)
            val finished = java.util.concurrent.atomic.AtomicReference(emptySet<Int>())
            fun slate(ended: Set<Int>, now: Long = System.currentTimeMillis()) = LiveSportsSnapshot(pairs.mapIndexed { i, (home, away) ->
                LiveEvent("hold-$i", setOf(home.id, away.id), first + i * 60_000L, state = if (i in ended) "post" else "in", status = if (i in ended) "Final" else "", league = "NBA") }, now, setOf("NBA"))
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { slate(finished.get()) }
            }
            @Suppress("UNCHECKED_CAST") fun cards(activity: MainActivity) = SportsPanel::class.java.getDeclaredField("games").apply { isAccessible = true }
                .get(activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")) as List<SportsGame>
            fun focusedPosition(activity: MainActivity): Int {
                val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                return grid.findFocus()?.let { grid.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
            }
            fun scores(scenario: ActivityScenario<MainActivity>, ended: Set<Int>) = scenario.onActivity { activity ->
                finished.set(ended)
                MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, slate(ended))
                MainActivity::class.java.getDeclaredMethod("refreshSports").apply { isAccessible = true }.invoke(activity)
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { cards(it).size == pairs.size && focusedPosition(it) == 0 }
                // The viewer moves to the middle card of the second row.
                device.pressDPadRight(); waitFor(scenario) { focusedPosition(it) == 1 }
                device.pressDPadDown(); waitFor(scenario) { focusedPosition(it) == 4 }
                // Game 1, before the cursor, ends and leaves Live now.
                scores(scenario, setOf(1))
                waitFor(scenario) { cards(it).getOrNull(1)?.state == "post" }
                Thread.sleep(500)
                scenario.onActivity { activity ->
                    assertEquals("The cursor stays on its card", 4, focusedPosition(activity))
                    assertEquals("NBA:hold-4", cards(activity)[4].key)
                    assertEquals("The ended game keeps its place and shows it ended", "Final", cards(activity)[1].status)
                    assertEquals(pairs.size, cards(activity).size)
                }
                // Game 7, after the cursor, ends too: cards after the cursor follow the live order at once.
                scores(scenario, setOf(1, 7))
                waitFor(scenario) { cards(it).none { game -> game.key == "NBA:hold-7" } }
                scenario.onActivity { activity -> assertEquals(4, focusedPosition(activity)); assertEquals("NBA:hold-4", cards(activity)[4].key) }
                // Once the cursor leaves the cards, the next update puts Live now back in order.
                device.pressDPadUp(); waitFor(scenario) { focusedPosition(it) == 1 }
                device.pressDPadUp(); waitFor(scenario) { focusedPosition(it) == -1 }
                scores(scenario, setOf(1, 7))
                waitFor(scenario) { cards(it).none { game -> game.key == "NBA:hold-1" } }
                scenario.onActivity { activity -> assertEquals(pairs.size - 2, cards(activity).size) }
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aGuideLinkMadeForTheProviderListsEventSlotsByStreamNumberWhenTheirNamesAgree() {
        FixtureServer(eventSlots = listOf("NBA 01: Hawks vs Celtics" to "NBA 01: Hawks vs Celtics", "NBA 02: No Event" to "NBA 02: No Event", "NBA 03: No Event" to "NBA 03: No Event")).use { server ->
            val source = Source(name = "Numbered guide fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true,
                epgUrl = "${server.base}/numbered-guide.xml")
            store.saveSources(listOf(source)); store.active = source.id
            fun titles(activity: MainActivity, id: String): List<String> {
                val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                val channel = guide.channels.firstOrNull { it.native().id == id } ?: return emptyList()
                return guide.programmes[guide.key(channel)].orEmpty().map { it.title }
            }
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity -> "Numbered guide 1" in titles(activity, "xc:1") }
                assertTrue(server.requests.keys.any { it == "/numbered-guide.xml" })
                scenario.onActivity { activity -> assertTrue("A number whose guide name is another channel does not attach", titles(activity, "xc:2").none { it.startsWith("Numbered guide") }) }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aGuideLinkWinsWhereItHasAChannelAndTheProvidersGuideFillsTheRest() {
        FixtureServer(channelCount = 3, fullGuide = true).use { server ->
            val source = Source(name = "Partial guide fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true,
                epgUrl = "${server.base}/partial-guide.xml")
            store.saveSources(listOf(source)); store.active = source.id
            fun titles(activity: MainActivity, id: String): List<String> {
                val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                val channel = guide.channels.firstOrNull { it.native().id == id } ?: return emptyList()
                return guide.programmes[guide.key(channel)].orEmpty().map { it.title }
            }
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try { waitFor(scenario) { activity -> "Linked guide" in titles(activity, "xc:1") && "World News" in titles(activity, "xc:2") && "Named guide" in titles(activity, "xc:3") } }
                catch (failure: AssertionError) { throw AssertionError("requests=${server.requests}, error=${store.refreshError(source)}", failure) }
                assertEquals(1, server.requests["/partial-guide.xml"]); assertEquals(1, server.requests["/xmltv.php"])
                scenario.onActivity { activity ->
                    assertTrue("The link's channel never mixes in the provider's listings", titles(activity, "xc:1").none { it == "World News" })
                    assertTrue(titles(activity, "xc:3").none { it == "World News" })
                }
                assertFalse("The downloaded guide file is removed after reading", java.io.File(context.cacheDir, "guide-download/guide.xml").exists())
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sportsCardsUseTheEventNamesSavedLastTimeBeforeTheProviderAnswers() {
        val pairs = SportsCatalog.teams.filter { it.league == "NBA" }.take(4).chunked(2)
        val renamed = pairs[0]
        // The lineup still says "No Event"; the provider renamed the slot for game 0 and holds that answer back.
        val fresh = "NBA 01: ${renamed[1].name} vs ${renamed[0].name}"
        FixtureServer(eventSlots = listOf("NBA 01: No Event" to fresh, "NBA 02: No Event" to "NBA 02: No Event", "NBA 03: No Event" to "NBA 03: No Event")).use { server ->
            val source = Source(name = "Saved names fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA")
            // Spare read these names five minutes ago, before it was restarted.
            store.write("event-names.enc", EventChannelNames.Saved(System.currentTimeMillis() - 300_000, mapOf(source.id to mapOf("xc:1" to fresh))).json().toByteArray())
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { val now = System.currentTimeMillis()
                    LiveSportsSnapshot(pairs.mapIndexed { i, (home, away) -> LiveEvent("saved-$i", setOf(home.id, away.id), now - 300000, league = "NBA") }, now, setOf("NBA")) }
            }
            lifecycle.addLifecycleCallback(fixture)
            fun summaries(activity: MainActivity) = @Suppress("UNCHECKED_CAST") (MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>)
            fun found(text: String?) = text != null && (text.startsWith("NBA 01:") || text.startsWith("1 stream names this game"))
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                // The provider has not answered, yet the renamed slot is found with the saved names, and the game nothing
                // names says so instead of waiting for the provider.
                waitFor(scenario) { activity -> found(summaries(activity)["NBA:saved-0"]) && summaries(activity)["NBA:saved-1"] == "No stream names this game" }
                assertTrue(Diagnostics.sportsLines().joinToString("\n"), Diagnostics.sportsLines().any { it.contains("Event channel names saved 5 min ago · 1 channels") })
                assertTrue(Diagnostics.sportsLines().none { it.contains("Event channel names read") && it.contains("channels in") })
                // The held answer outlasts Spare's 8-second wait; once the provider answers, the next reading succeeds.
                server.releaseEventNames.countDown()
                fun read() = Diagnostics.sportsLines().any { it.contains("Event channel names read") && it.contains("channels in") }
                val until = System.currentTimeMillis() + 40_000
                while (!read() && System.currentTimeMillis() < until) {
                    // Ask again once the reading that gave up has finished (a reading still under way may yet succeed).
                    scenario.onActivity { activity ->
                        val task = MainActivity::class.java.getDeclaredField("eventNamesTask").apply { isAccessible = true }.get(activity) as java.util.concurrent.Future<*>?
                        if (task?.isDone != false) {
                            MainActivity::class.java.getDeclaredField("eventNamesAt").apply { isAccessible = true }.setLong(activity, 0L)
                            MainActivity::class.java.getDeclaredMethod("refreshEventNames").apply { isAccessible = true }.invoke(activity)
                        }
                    }
                    Thread.sleep(1000)
                }
                assertTrue(Diagnostics.sportsLines().joinToString("\n"), read())
                // The fresh reading is saved for the next start.
                waitFor(scenario) { EventChannelNames.Saved.from(store.read("event-names.enc")!!.toString(Charsets.UTF_8), setOf(source.id), System.currentTimeMillis())?.let { System.currentTimeMillis() - it.at < 60_000 } == true }
                // A channel hidden after a failure returns 15 minutes later: every card keeps what it found.
                scenario.onActivity { activity ->
                    val unavailable = MainActivity::class.java.getDeclaredField("unavailable").apply { isAccessible = true }.get(activity)
                    unavailable.javaClass.getMethod("hide", String::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(unavailable, "xc:3", System.currentTimeMillis() - 16 * 60000L)
                    (MainActivity::class.java.getDeclaredField("minuteTick").apply { isAccessible = true }.get(activity) as Runnable).run()
                    assertTrue(summaries(activity).toString(), found(summaries(activity)["NBA:saved-0"]))
                }
                Thread.sleep(2000)
                scenario.onActivity { activity -> assertTrue(summaries(activity).toString(), found(summaries(activity)["NBA:saved-0"])) }
            } } finally { server.releaseEventNames.countDown(); lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source); store.delete("event-names.enc") }
        }
    }

    @Test fun sportsCardsSearchBeforeTheProvidersEventNamesArriveAndLookAgainAfter() {
        val pairs = SportsCatalog.teams.filter { it.league == "NBA" }.take(6).chunked(2)
        val named = pairs[0]; val renamed = pairs[1]
        // Game 0 is named in the lineup. Game 1's event slot is renamed for it only in the provider's fresh event names.
        // Game 2 is on no channel at all.
        val slots = listOf("NBA 01: ${named[1].name} vs ${named[0].name}" to "NBA 01: ${named[1].name} vs ${named[0].name}",
            "NBA 02: No Event" to "NBA 02: ${renamed[1].name} vs ${renamed[0].name}", "NBA 03: No Event" to "NBA 03: No Event")
        FixtureServer(eventSlots = slots).use { server ->
            val source = Source(name = "Event names fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA")
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { val now = System.currentTimeMillis()
                    LiveSportsSnapshot(pairs.mapIndexed { i, (home, away) -> LiveEvent("names-$i", setOf(home.id, away.id), now - 300000, league = "NBA") }, now, setOf("NBA")) }
            }
            lifecycle.addLifecycleCallback(fixture)
            fun summaries(activity: MainActivity) = @Suppress("UNCHECKED_CAST") (MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>)
            // An event slot the guide does not confirm is offered, not played on OK: "1 stream names this game".
            fun found(text: String?, slot: String) = text != null && (text.startsWith(slot) || text.startsWith("1 stream names this game"))
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                // The provider holds its event names back; the cards search anyway.
                waitFor(scenario) { activity -> found(summaries(activity)["NBA:names-0"], "NBA 01:") }
                waitFor(scenario) { activity -> (1..2).all { summaries(activity)["NBA:names-$it"] == "Checking event channels…" } }
                scenario.onActivity { activity ->
                    assertTrue("The provider was asked for its event names", server.requests.keys.any { it == "/player_api.php" })
                    assertTrue(Diagnostics.sportsLines().joinToString("\n"), Diagnostics.sportsLines().any { it.contains("Cards started looking for streams") })
                    assertTrue(Diagnostics.sportsLines().none { it.contains("Event channel names read") })
                    assertEquals("Waiting cards are not counted as searched", 0, Diagnostics.sportsLines().count { it.contains("Every live card has looked") })
                    // A card still looking shows its loading bar, not the words; a card with its stream shows the stream.
                    assertTrue(activity.window.decorView.findViewWithTag<CardStreamLine>("sports-coverage:NBA:names-1").looking)
                    assertFalse(activity.window.decorView.findViewWithTag<CardStreamLine>("sports-coverage:NBA:names-0").looking)
                }
                server.releaseEventNames.countDown()
                // With the fresh names every card looks again: the renamed slot is found, and the third game says nothing names it.
                waitFor(scenario) { activity -> found(summaries(activity)["NBA:names-1"], "NBA 02: ${renamed[1].name}") && summaries(activity)["NBA:names-2"] == "No stream names this game" }
                waitFor(scenario) { activity -> (0..2).none { activity.window.decorView.findViewWithTag<CardStreamLine>("sports-coverage:NBA:names-$it").looking } }
                scenario.onActivity { activity ->
                    assertTrue(found(summaries(activity)["NBA:names-0"], "NBA 01:"))
                    val lines = Diagnostics.sportsLines()
                    assertTrue(lines.joinToString("\n"), lines.any { it.contains("Event channel names read in") && it.contains("3 channels in 1 categories") })
                    assertTrue(lines.indexOfFirst { it.contains("Cards started looking for streams") } < lines.indexOfFirst { it.contains("Event channel names read") })
                }
                waitFor(scenario) { Diagnostics.sportsLines().any { it.contains("Every live card has looked for streams · 3 games · 2 found one") } }
            } } finally { server.releaseEventNames.countDown(); lifecycle.removeLifecycleCallback(fixture); store.saveSources(emptyList()); store.removeCache(source); store.delete("event-names.enc") }
        }
    }

    @Test fun sportsLeagueRowShowsScrollCuesWhenLeaguesOverflow() {
        FixtureServer().use { server ->
            val source = Source(name = "League overflow fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("league-feed", "News", "News", "${server.base}/channel1.mp4")
            val now = System.currentTimeMillis()
            val snapshot = LiveSportsSnapshot(listOf(LiveEvent("overflow-nfl", setOf("nfl:SEA", "nfl:SF"), now - 60000, league = "NFL")), now, setOf("NFL"))
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            store.sportsLeagues = SportsLeagues.byId.keys; store.sportsLastLeague = "NFL"
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { snapshot }; activity.calendarSportsLoader = { _, _ -> snapshot }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:NFL")?.isShown == true }
                scenario.onActivity { activity ->
                    assertEquals("Calendar", (activity.window.decorView.findViewWithTag<View>("sports-date") as TextView).text.toString())
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league-more-right")?.isShown == true }
                scenario.onActivity { activity ->
                    assertFalse("Nothing is hidden before the first league", activity.window.decorView.findViewWithTag<View>("sports-league-more-left").isShown)
                }
                // Live now spans every league; choosing NFL shows that league's day and keeps the cursor on it.
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                device.pressDPadUp()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-live").hasFocus() }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:NFL").apply { requestFocus(); performClick() } }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:NFL").hasFocus() && it.window.decorView.findViewWithTag<View>("sports-today").isSelected }
                // Walk right with the remote to the last league; the row scrolls and the cues swap sides.
                repeat(SportsLeagues.all.size - 1) { device.pressDPadRight() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:${SportsLeagues.all.last().id}")?.hasFocus() == true }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league-more-left")?.isShown == true }
                scenario.onActivity { activity ->
                    assertFalse("Nothing is hidden after the last league", activity.window.decorView.findViewWithTag<View>("sports-league-more-right").isShown)
                    val focused = activity.window.decorView.findViewWithTag<View>("sports-league:${SportsLeagues.all.last().id}")
                    val cue = activity.window.decorView.findViewWithTag<View>("sports-league-more-right")
                    val at = IntArray(2); focused.getLocationOnScreen(at); val edge = IntArray(2); cue.getLocationOnScreen(edge)
                    assertTrue("The focused league is fully visible", at[0] + focused.width <= edge[0] + cue.width)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-league-overflow.png"))
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.sportsLeagues = SportsLeagues.byId.keys; store.sportsLastLeague = "NFL" }
        }
    }

    @Test fun addPlaylistStartsOnThePhoneOptionAndFillsTheFormFromAPhone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var dialog: AlertDialog? = null
            scenario.onActivity { activity -> dialog = SourceDialog.show(activity, null, {}, {}) }
            waitFor(scenario) { dialog?.window?.decorView?.findViewWithTag<View>("playlist-from-phone")?.hasFocus() == true }
            device.pressDPadCenter() // The remote reaches the phone option without pressing Down first.
            var link = ""
            waitFor(scenario) {
                link = device.findObject(androidx.test.uiautomator.By.textStartsWith("Or open http://"))?.text?.removePrefix("Or open ").orEmpty()
                link.isNotEmpty()
            }
            // Opening the form on the phone tells the TV the scan worked.
            assertTrue(java.net.URL(link).openStream().bufferedReader().use { it.readText() }.contains("Send to TV"))
            assertNotNull(device.wait(Until.findObject(By.text("✓ Phone connected")), 5000))
            assertNull("The link is hidden once a phone is connected", device.findObject(By.textStartsWith("Or open http://")))
            val connection = java.net.URL(link).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.outputStream.use { it.write("type=xtream&server=http%3A%2F%2Fprovider.test%3A8080&username=viewer&password=secret+1".toByteArray()) }
            assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.contains("Sent to your TV"))
            waitFor(scenario) { dialog!!.window!!.decorView.findViewWithTag<EditText>("Xtream username")?.text?.toString() == "viewer" }
            scenario.onActivity {
                val root = dialog!!.window!!.decorView
                assertEquals("http://provider.test:8080", root.findViewWithTag<EditText>("Playlist URL").text.toString())
                assertEquals("secret 1", root.findViewWithTag<EditText>("Xtream password").text.toString())
                assertTrue("Save is ready after the phone sends", dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).hasFocus())
                dialog!!.dismiss()
            }
        }
    }

    @Test fun firstLaunchSetupKeepsTheRemoteOnItsOwnButtonsOnEveryStep() {
        store.saveSources(emptyList()); store.active = ""; store.onboarded = false; store.resetSportsLeagues()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun title(activity: MainActivity) = activity.window.decorView.findViewWithTag<TextView>("onboarding-title")?.takeIf { it.isShown }?.text?.toString()
            fun tagged(activity: MainActivity, tag: String) = activity.window.decorView.findViewWithTag<View>(tag)
            fun inside(panel: View, view: View?): Boolean { var at: View? = view; while (at != null && at !== panel) at = at.parent as? View; return at === panel }
            /** From every button of the step, in every direction, the next stop is still one of setup's buttons. */
            fun everyMoveStaysInSetup(activity: MainActivity) {
                val panel = tagged(activity, "onboarding")
                val buttons = panel.getFocusables(View.FOCUS_DOWN)
                assertTrue(buttons.size > 1)
                buttons.forEach { from -> listOf(View.FOCUS_UP, View.FOCUS_DOWN, View.FOCUS_LEFT, View.FOCUS_RIGHT).forEach { direction ->
                    assertTrue("${(from as? TextView)?.text} direction $direction leaves setup", inside(panel, from.focusSearch(direction)))
                } }
            }
            fun pressAndCheck(code: Int, check: (MainActivity) -> Unit) {
                device.pressKeyCode(code); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); scenario.onActivity { check(it) }
            }
            waitFor(scenario) { title(it) == "Welcome to Spare" && tagged(it, "onboarding-type").hasFocus() }
            scenario.onActivity(::everyMoveStaysInSetup)
            pressAndCheck(KeyEvent.KEYCODE_DPAD_RIGHT) { assertTrue(tagged(it, "onboarding-type").hasFocus()) }
            pressAndCheck(KeyEvent.KEYCODE_DPAD_LEFT) { assertTrue(tagged(it, "onboarding-skip").hasFocus()) }
            device.pressDPadCenter()
            listOf("Which sports do you watch?", "Pick a look").forEach { step ->
                waitFor(scenario) { title(it) == step }
                scenario.onActivity(::everyMoveStaysInSetup)
                var reached = false
                repeat(8) { if (!reached) pressAndCheck(KeyEvent.KEYCODE_DPAD_DOWN) { reached = tagged(it, "onboarding-next").hasFocus() } }
                assertTrue("Down reaches Continue on $step", reached)
                // Continue sits at the edge: pressing on past it stays put instead of landing on the guide behind setup.
                listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN).forEach { code ->
                    pressAndCheck(code) { assertTrue("Continue keeps the remote on $step", tagged(it, "onboarding-next").hasFocus()) }
                }
                device.pressDPadCenter()
            }
            waitFor(scenario) { title(it) == "How should the guide work?" }
            scenario.onActivity(::everyMoveStaysInSetup)
            var done = false
            repeat(8) { if (!done) pressAndCheck(KeyEvent.KEYCODE_DPAD_DOWN) { done = tagged(it, "onboarding-done").hasFocus() } }
            assertTrue("Down reaches Start watching", done)
            pressAndCheck(KeyEvent.KEYCODE_DPAD_RIGHT) { assertTrue(tagged(it, "onboarding-done").hasFocus()) }
            device.pressDPadCenter()
            waitFor(scenario) { it.window.decorView.findViewWithTag<View>("onboarding") == null }
            assertTrue(store.onboarded)
        } } finally { store.onboarded = true; store.resetSportsLeagues() }
    }

    @Test fun firstLaunchSetupTakesAPlaylistFromAPhoneThenSportsThemeAndGuideChoices() {
        FixtureServer().use { server ->
            store.saveSources(emptyList()); store.active = ""; store.onboarded = false; store.resetSportsLeagues()
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun title(activity: MainActivity) = activity.window.decorView.findViewWithTag<TextView>("onboarding-title")?.takeIf { it.isShown }?.text?.toString()
                var link = ""
                waitFor(scenario) { activity ->
                    link = activity.window.decorView.findViewWithTag<TextView>("phone-setup-link")?.text?.toString()?.removePrefix("Or open ").orEmpty()
                    title(activity) == "Welcome to Spare" && link.startsWith("http://") && activity.window.decorView.findViewWithTag<View>("onboarding-type").hasFocus()
                }
                assertTrue("The QR link is the private one-time form", link.matches(Regex("http://[0-9.]+:\\d+/p/[A-Za-z0-9_-]{22}")))
                device.takeScreenshot(File(context.getExternalFilesDir(null), "onboarding-phone.png"))
                // The phone's side: open the form, then send a playlist.
                fun phone(body: String?): String {
                    val connection = java.net.URL(link).openConnection() as java.net.HttpURLConnection
                    if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
                    return connection.inputStream.bufferedReader().use { it.readText() }
                }
                assertTrue(phone(null).contains("Send to TV"))
                val playlist = "${server.base}/playlist.m3u"
                assertTrue(phone("type=m3u&name=Phone+list&m3u=" + java.net.URLEncoder.encode(playlist, "UTF-8")).contains("Sent to your TV"))
                // The TV shows the details filled in, with Save ready.
                waitFor(scenario) {
                    device.findObject(androidx.test.uiautomator.By.text(playlist)) != null && device.findObject(androidx.test.uiautomator.By.text("Save"))?.isFocused == true
                }
                assertNotNull("Name came from the phone", device.findObject(androidx.test.uiautomator.By.text("Phone list")))
                device.pressDPadCenter()
                waitFor(scenario) { title(it) == "Which sports do you watch?" && store.sources().size == 1 }
                scenario.onActivity { activity ->
                    assertEquals("Phone list", store.sources().single().name); assertEquals(playlist, store.sources().single().url)
                    assertTrue("Pro leagues start on", activity.window.decorView.findViewWithTag<View>("onboarding-league:NFL").isSelected)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("onboarding-league:NCAAF").isSelected)
                    activity.window.decorView.findViewWithTag<View>("onboarding-league:NCAAF").performClick()
                    activity.window.decorView.findViewWithTag<View>("onboarding-league:EPL").performClick()
                }
                // The remote reaches Continue and never wanders to the guide loaded behind setup.
                fun focusInSetup(activity: MainActivity) = activity.window.decorView.findViewWithTag<View>("onboarding").hasFocus()
                fun onContinue(activity: MainActivity) = activity.window.decorView.findViewWithTag<View>("onboarding-next")?.hasFocus() == true
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 8 }
                fun walkToContinue() {
                    var reached = false
                    repeat(8) {
                        if (!reached) {
                            device.pressDPadDown(); InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                            scenario.onActivity { activity -> assertTrue("Down stays in setup", focusInSetup(activity)); reached = onContinue(activity) }
                        }
                    }
                    assertTrue("Down reaches Continue", reached)
                    listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT).forEach { code ->
                        device.pressKeyCode(code); InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        scenario.onActivity { activity -> assertTrue("Continue keeps the remote at the edge of setup", onContinue(activity)) }
                    }
                }
                walkToContinue()
                // Something behind setup taking focus costs nothing: the next press carries on from where the remote was.
                scenario.onActivity { activity -> channelList(activity)?.requestFocus(); assertFalse(focusInSetup(activity)) }
                device.pressDPadRight(); InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity -> assertTrue("Focus came back to Continue", onContinue(activity)) }
                device.pressDPadCenter()
                waitFor(scenario) { title(it) == "Pick a look" }
                walkToContinue()
                device.pressDPadCenter()
                waitFor(scenario) { title(it) == "How should the guide work?" }
                device.pressBack()
                waitFor(scenario) { title(it) == "Pick a look" }
                scenario.onActivity { activity ->
                    assertTrue("NCAAF" in store.sportsLeagues); assertFalse("EPL" in store.sportsLeagues)
                    activity.window.decorView.findViewWithTag<View>("onboarding-theme:Midnight").performClick()
                    assertEquals("Ocean", store.theme)
                    activity.window.decorView.findViewWithTag<View>("onboarding-next").performClick()
                }
                waitFor(scenario) { title(it) == "How should the guide work?" }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "onboarding-guide.png"))
                device.pressBack(); waitFor(scenario) { title(it) == "Pick a look" }
                device.pressBack(); waitFor(scenario) { title(it) == "Which sports do you watch?" }
                device.pressBack(); waitFor(scenario) { title(it) == "Your playlist is ready" }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("onboarding-next").performClick()
                    activity.window.decorView.findViewWithTag<View>("onboarding-next").performClick()
                    activity.window.decorView.findViewWithTag<View>("onboarding-next").performClick()
                    assertEquals("How should the guide work?", title(activity))
                    activity.window.decorView.findViewWithTag<View>("onboarding-guide:Guide background:Solid").performClick()
                    activity.window.decorView.findViewWithTag<View>("onboarding-guide:Channel while browsing:Small preview").performClick()
                    assertTrue(store.smallPreview)
                    activity.window.decorView.findViewWithTag<View>("onboarding-guide:Channel while browsing:Behind the guide").performClick()
                    assertFalse(store.smallPreview)
                    activity.window.decorView.findViewWithTag<View>("onboarding-guide:Text size:Large").performClick()
                    activity.window.decorView.findViewWithTag<View>("onboarding-guide:OK on a channel:Preview first").performClick()
                    assertEquals(100, store.guideOpacity); assertEquals(115, store.guideTextScale); assertFalse(store.guideOpenFullscreen)
                    activity.window.decorView.findViewWithTag<View>("onboarding-done").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("onboarding") == null && channelList(it)?.isShown == true && channelList(it)?.adapter?.itemCount == 8 }
                assertTrue(store.onboarded)
                scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { activity ->
                    assertNull("Setup runs once", activity.window.decorView.findViewWithTag<View>("onboarding"))
                    val next = activity.window.decorView.findViewWithTag<TextView>("guide-up-next")
                    assertTrue("The header names what follows: ${next.text}", next.text.startsWith("Up next  ·  ") && next.text.endsWith("Live Report"))
                    // The same choice lives in Settings › Guide, not only deep in Theme.
                    activity.window.decorView.findViewWithTag<View>("settings").performClick()
                    val panel = activity.window.decorView.findViewWithTag<SettingsPanel>("settings-panel")
                    panel.selectSetting("Guide")
                    panel.findViewWithTag<View>("settings-option:Guide video").performClick()
                    assertTrue(store.smallPreview)
                }
            } } finally { store.saveSources(emptyList()); store.active = ""; store.onboarded = true; store.guideTextScale = 100; store.guideOpenFullscreen = true }
        }
    }

    @Test fun sportsLeagueRailPreservesEachLeagueAndCalendar() {
        FixtureServer().use { server ->
            val source = Source(name = "League setup fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("league-feed", "News", "News", "${server.base}/channel1.mp4")
            val now = System.currentTimeMillis()
            val snapshot = LiveSportsSnapshot(listOf(
                LiveEvent("rail-nba", setOf("nba:POR", "nba:LAL"), now - 60000, league = "NBA"),
                LiveEvent("rail-nfl-1", setOf("nfl:SEA", "nfl:SF"), now - 60000, league = "NFL"),
                LiveEvent("rail-nfl-2", setOf("nfl:BUF", "nfl:KC"), now - 60000, league = "NFL")
            ), now, setOf("NFL", "NBA"))
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source); store.sportsLeagues = setOf("NFL", "NBA")
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { calls.incrementAndGet(); snapshot }; activity.calendarSportsLoader = { _, _ -> snapshot }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var playing: Player? = null
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> playing = p; p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                // Leagues are picked during first-run setup; Sports itself never stops to ask.
                // Three live games, plus RedZone when the NFL window is on.
                waitFor(scenario) { (it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.adapter?.itemCount ?: 0) >= 3 }
                scenario.onActivity { activity ->
                    assertNull("Sports never interrupts with a league picker", activity.window.decorView.findViewWithTag<View>("sports-league-picker"))
                    assertEquals(setOf("NFL", "NBA"), store.sportsLeagues)
                    assertNull("No mixed-league option", activity.window.decorView.findViewWithTag<View>("sports-league:all"))
                    assertNull("Disabled leagues stay out of the rail", activity.window.decorView.findViewWithTag<View>("sports-league:MLB"))
                    activity.window.decorView.findViewWithTag<View>("sports-league:NFL").apply { requestFocus(); performClick() }
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewWithTag<View>("NFL:rail-nfl-2") != null && it.window.decorView.findViewWithTag<View>("NBA:rail-nba") == null }
                scenario.onActivity { activity ->
                    // Each league remembers its own focused game.
                    activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewWithTag<View>("NFL:rail-nfl-2").requestFocus()
                    activity.window.decorView.findViewWithTag<View>("sports-league:NBA").apply { requestFocus(); performClick() }
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 1 && it.window.decorView.findViewWithTag<View>("NBA:rail-nba")?.isShown == true }
                scenario.onActivity { activity ->
                    assertEquals("NBA", store.sportsLastLeague)
                    assertTrue("Loading cannot steal focus from the rail", activity.window.decorView.findViewWithTag<View>("sports-league:NBA").hasFocus())
                    assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playing!!.isPlaying)
                }
                device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:NBA").hasFocus() }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-date").requestFocus() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-date").hasFocus() }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("calendar-games")?.adapter?.itemCount == 1 }
                device.pressBack()
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:NFL").apply { requestFocus(); performClick() } }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 3 }
                device.pressDPadDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("NFL:rail-nfl-2")?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:NBA").performClick() }
                waitFor(scenario) { store.sportsLastLeague == "NBA" && it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 1 }
                scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("NBA:rail-nba")?.isShown == true }
                scenario.onActivity { activity ->
                    assertEquals(setOf("NFL", "NBA"), store.sportsLeagues)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-league-filter.png"))
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.sportsLeagues = SportsLeagues.byId.keys; store.sportsLastLeague = "NFL" }
        }
    }

    @Test fun backFromAGameReturnsToCardsButARecalledChannelReturnsToTheGuide() {
        FixtureServer(channelCount = 2).use { server ->
            val source = Source(name = "Sports back fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = listOf(Channel("back:1", "News", "News", "${server.base}/channel1.mp4"), Channel("back:2", "YES", "Sports", "${server.base}/channel2.mp4"))
            val now = System.currentTimeMillis()
            store.saveSources(listOf(source)); store.active = source.id; store.followedTeams = setOf("mlb:NYY"); store.sportsTeam = "mlb:NYY"; store.sportsLastLeague = "MLB"
            store.cache(source, Guide(channels, mapOf("back:2" to listOf(Programme("back:2", "MLB: NYY @ BOS", now - 300000, now + 3600000))))); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.pressBack(); scenario.onActivity { fixtureLiveSports(it); it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { (it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount ?: 0) >= 1 }
                scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.performClick(); activity.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:back:2")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:back:2").performClick() }
                waitFor(scenario) { playingPath(scenario) == "/channel2.mp4" && !it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                scenario.onActivity { assertFalse("Back from a game lands on the cards", it.window.decorView.findViewWithTag<RecyclerView>("sports-streams")?.isShown == true) }
                scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick(); activity.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:back:2")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:back:2").performClick() }
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                device.pressDPadLeft()
                waitFor(scenario) { playingPath(scenario) == "/channel1.mp4" }
                device.pressBack()
                waitFor(scenario) { channelList(it)?.isShown == true && channelList(it)?.findFocus() != null }
                scenario.onActivity { activity ->
                    assertFalse("A recalled non-game channel returns to the guide", activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                    val list = channelList(activity)!!; val row = list.findContainingViewHolder(list.findFocus()!!)!!
                    assertEquals("Guide focus lands on the channel being watched", 0, row.bindingAdapterPosition)
                }
            }
        }
    }
    @Test fun sportsFindsFollowedGamesSelectsPreviewAndPreservesRemoteFocus() {
        FixtureServer(channelCount = 4).use { server ->
            val source = Source(name = "Sports fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..4).map { Channel("sports:$it", listOf("News", "ESPN", "YES", "CBS")[it - 1], if (it == 1) "News" else "Sports", "${server.base}/channel$it.mp4") }
            val now = System.currentTimeMillis()
            val programmes = mapOf("sports:2" to listOf(Programme("sports:2", "Yankees vs Red Sox", now - 300000, now + 3600000)),
                "sports:3" to listOf(Programme("sports:3", "MLB: NYY @ BOS", now - 300000, now + 3600000)),
                "sports:4" to listOf(Programme("sports:4", "NFL: Bills at Chiefs", now - 300000, now + 4 * 3600000)))
            store.saveSources(listOf(source)); store.active = source.id; store.smallPreview = true; store.followedTeams = setOf("mlb:NYY"); store.sportsTeam = "mlb:NYY"; store.sportsLastLeague = "MLB"
            store.toggleFavorite(source, channels[2]); store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var samePlayer: Player? = null; scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player }
                device.pressBack(); scenario.onActivity { fixtureLiveSports(it); it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:MLB")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:MLB").performClick(); it.window.decorView.findViewWithTag<SportsPanel>("sports-panel").home() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 1 }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:mlb-fixture")?.text?.let { label -> label.startsWith("ESPN") || label.startsWith("YES") } == true }
                val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val matcher = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    matcher.submit { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                }
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                try {
                    scenario.onActivity { activity ->
                        store.sportsAutoPlay = true
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick()
                        val path = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem?.localConfiguration?.uri?.path
                        assertTrue("A discovered card starts its cached stream without waiting for the busy search worker: $path", path in setOf("/channel2.mp4", "/channel3.mp4"))
                    }
                } finally { release.countDown(); store.sportsAutoPlay = false }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-panel").isShown == false }
                var gameMediaId = ""
                scenario.onActivity { activity -> gameMediaId = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId; activity.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                scenario.onActivity { it.window.decorView.findViewWithTag<MediaBrowserPanel>("library-panel").focusToolbar() }
                device.pressBack()
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                scenario.onActivity { assertEquals("Browsing Library must preserve the exact game connection", gameMediaId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                scenario.onActivity { assertFalse("Back from a game returns to the cards, not its stream list", it.window.decorView.findViewWithTag<RecyclerView>("sports-streams")?.isShown == true) }
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<View>("sports-panel")
                    assertTrue(panel.height > activity.window.decorView.height * .75f); assertTrue(panel.width > activity.window.decorView.width * .8f)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("guide-header").isShown)
                    assertTrue("Hero: ${activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text} / ${activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text}; focus ${activity.currentFocus?.tag}", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.contains("LIVE"))
                    assertNull(activity.window.decorView.findViewWithTag<View>("sports-leagues"))
                    activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick()
                    activity.window.decorView.findViewWithTag<View>("sports-choose-stream").requestFocus()
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports.png")); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:sports:3")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:sports:3").performClick() }
                waitFor(scenario) { store.last(source) == "sports:3" }; assertEquals("/channel3.mp4", playingPath(scenario))
                scenario.onActivity { assertFalse("Selecting a Sports result opens full screen", it.window.decorView.findViewWithTag<View>("sports-panel").isShown) }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                scenario.onActivity { activity ->
                    assertFalse(activity.window.decorView.findViewWithTag<RecyclerView>("sports-streams")?.isShown == true)
                    activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick()
                    activity.window.decorView.findViewWithTag<View>("sports-choose-stream").requestFocus()
                }
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:sports:2")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:sports:2").performLongClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<PlayerView>("multiview-second")?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var retainedPlayer: Player? = null; var retainedSurface: android.view.Surface? = null; var retainedTile: View? = null
                scenario.onActivity { activity ->
                    val extra = activity.window.decorView.findViewWithTag<PlayerView>("multiview-second")
                    retainedPlayer = extra.player; retainedSurface = (extra.videoSurfaceView as android.view.SurfaceView).holder.surface
                    retainedTile = activity.window.decorView.findViewWithTag<View>("multiview-tile:1")
                    activity.window.decorView.findViewWithTag<View>("multiview-add-channel").performClick()
                }
                waitFor(scenario) { channelList(it)?.isShown == true }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-controls")?.isShown == true && retainedPlayer?.isPlaying == true }
                scenario.onActivity { activity ->
                    val extra = activity.window.decorView.findViewWithTag<PlayerView>("multiview-second")
                    assertSame(retainedPlayer, extra.player); assertTrue("Returning keeps playback requested", retainedPlayer!!.playWhenReady)
                    assertSame(retainedSurface, (extra.videoSurfaceView as android.view.SurfaceView).holder.surface)
                    assertSame("Returning reuses the existing pane controls", retainedTile, activity.window.decorView.findViewWithTag<View>("multiview-tile:1"))
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("multiview-add-game").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:NFL")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:NFL").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 }
                scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.performClick(); activity.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:sports:4")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:sports:4").performLongClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<PlayerView>("multiview-third")?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("multiview-add-channel").performClick() }
                waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { channelList(it)!!.findViewHolderForAdapterPosition(0)!!.itemView.performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<PlayerView>("multiview-fourth")?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { activity ->
                    val players = listOf(samePlayer!!) + listOf("multiview-second", "multiview-third", "multiview-fourth").map { activity.window.decorView.findViewWithTag<PlayerView>(it).player!! }
                    assertEquals(1, players.count { it.volume > 0f }); assertFalse(activity.window.decorView.findViewWithTag<View>("multiview-two-up").isEnabled)
                    activity.window.decorView.findViewWithTag<View>("multiview-tile:0").requestFocus(); assertEquals(1f, samePlayer!!.volume, 0f)
                    activity.window.decorView.findViewWithTag<View>("multiview-tile:3").requestFocus(); assertEquals(0f, samePlayer!!.volume, 0f)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "multiview.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("multiview-remove").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-fourth") == null }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("multiview-tile:2").requestFocus(); it.window.decorView.findViewWithTag<View>("multiview-remove").performClick(); it.window.decorView.findViewWithTag<View>("multiview-two-up").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-third") == null && it.window.decorView.findViewWithTag<View>("multiview-two-up").isEnabled }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-second") == null && channelList(it)?.isShown == true }
                scenario.onActivity { activity -> assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player); activity.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(LiveEvent("nfl-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000)), now, SportsLeagues.byId.keys) }
                    pollSportsFixture(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 && it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Buffalo") }
                scenario.onActivity { activity ->
                    val games = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                    games.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                    activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(LiveEvent("nfl-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000, status = "Updated score")), now, SportsLeagues.byId.keys) }
                    pollSportsFixture(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-detail").text.contains("Updated score") }
                scenario.onActivity { assertTrue("A scoreboard update keeps game-card focus", it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus()); assertFalse(it.window.decorView.findViewWithTag<NavigationRail>("app-navigation").hasFocus()) }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                device.pressDPadDown(); device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown }
            }
            store.saveSources(emptyList()); store.removeCache(source); store.followedTeams = emptySet()
        }
    }

    @Test fun performanceOverlayIsOptInAndKeepsThePlayingConnection() {
        FixtureServer().use { server ->
            val source = Source(name = "Performance fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.performanceOverlay = false
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) {
                        find<PlayerView>(it.window.decorView)?.player?.let { player ->
                            player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying
                        } == true
                    }
                    var mediaId = ""
                    scenario.onActivity {
                        assertNull(it.window.decorView.findViewWithTag<View>("performance-overlay"))
                        mediaId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                    }
                    device.pressMenu()
                    activateSetting(scenario, "Playback diagnostics")
                    activateSetting(scenario, "Developer tools")
                    activateSetting(scenario, "Performance overlay")
                    waitFor(scenario) {
                        it.window.decorView.findViewWithTag<TextView>("performance-overlay")?.text?.contains("UI 10s") == true
                    }
                    scenario.onActivity { activity ->
                        val journal = MainActivity::class.java.getDeclaredField("playbackJournal").apply { isAccessible = true }.get(activity) as PlaybackJournal
                        assertTrue(journal.report("fixture").contains("performance · Display"))
                        assertEquals(mediaId, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    }
                    activateSetting(scenario, "Performance overlay")
                    scenario.onActivity {
                        assertNull(it.window.decorView.findViewWithTag<View>("performance-overlay"))
                        assertFalse(store.performanceOverlay)
                        assertEquals(mediaId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    }
                }
            } finally { store.performanceOverlay = false; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun activityMemoryTrimKeepsVisibleSportsCardsAndReloadsOnReturn() {
        FixtureServer().use { server ->
            val source = Source(name = "Trim fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val stamp = System.nanoTime()
            val logos = mapOf("nfl:SEA" to "https://fixture.invalid/$stamp-sea.png", "nfl:SF" to "https://fixture.invalid/$stamp-sf.png")
            val bytes = ByteArrayOutputStream().also { output ->
                Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
                }
            }.toByteArray()
            val files = ArtworkFiles(context)
            logos.values.forEach { url -> files.bytes(url) { bytes } }
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL")
            val now = System.currentTimeMillis()
            val event = LiveEvent("trim-game", logos.keys, now - 60_000, league = "NFL", logos = logos)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { LiveSportsSnapshot(listOf(event), now, setOf("NFL")) }
                }
            }
            fun cardBitmaps(activity: MainActivity): List<Bitmap> {
                val banner = activity.window.decorView.findViewWithTag<ViewGroup>("sports-card-banner:NFL:trim-game")
                if (banner?.isShown != true) return emptyList()
                val crests = banner.getChildAt(0) as ViewGroup
                return (0 until crests.childCount).mapNotNull { index ->
                    ((crests.getChildAt(index) as ImageView).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) {
                        find<PlayerView>(it.window.decorView)?.player?.let { player ->
                            player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying
                        } == true
                    }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { cardBitmaps(it).size == 2 }
                    scenario.onActivity { activity ->
                        val before = cardBitmaps(activity)
                        activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                        val after = cardBitmaps(activity)
                        assertEquals(2, after.size)
                        before.zip(after).forEach { (old, current) -> assertSame(old, current); assertFalse(current.isRecycled) }
                        activity.window.decorView.findViewWithTag<View>("guide-mode").performClick()
                    }
                    waitFor(scenario) { channelList(it)?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { cardBitmaps(it).size == 2 }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun largeSportsImagesStayBoundAfterMemoryCacheEviction() {
        val files = ArtworkFiles(context)
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(2048, 1365, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
            }
        }.toByteArray()
        val urls = (1..2).map { "https://images.example/large-${System.nanoTime()}-$it.png" }
        urls.forEach { files.bytes(it) { bytes } }
        val loader = LogoLoader(1280, 32 * 1024, files) // Each image deliberately exceeds the LRU.
        val images = List(2) { android.widget.ImageView(context) }
        val callbacks = IntArray(2)
        try {
            instrumentation.runOnMainSync { images.forEachIndexed { i, image -> loader.bind(image, urls[i]) { callbacks[i]++ } } }
            val deadline = System.currentTimeMillis() + 5000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = images.all { it.drawable != null } }; Thread.sleep(30)
            }
            assertTrue("Large artwork must finish even when evicted before its callback", ready)
            instrumentation.runOnMainSync {
                val original = images.map { (it.drawable as android.graphics.drawable.BitmapDrawable).bitmap }
                original.forEach { assertTrue(maxOf(it.width, it.height) <= 1280) }
                repeat(32) { images.forEachIndexed { i, image -> loader.bind(image, urls[i]) { callbacks[i]++ } } }
                images.forEachIndexed { i, image -> assertSame(original[i], (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap); assertEquals(2, callbacks[i]) }
            }
            val retryUrl = "https://images.example/retry-${System.nanoTime()}.png"
            files.bytes(retryUrl) { bytes }
            val retryImage = android.widget.ImageView(context)
            instrumentation.runOnMainSync {
                loader.pause(); loader.bind(retryImage, retryUrl)
                assertNull(retryImage.drawable)
                loader.resume(); loader.bind(retryImage, retryUrl)
            }
            val retryDeadline = System.currentTimeMillis() + 5000
            var retried = false
            while (!retried && System.currentTimeMillis() < retryDeadline) {
                instrumentation.runOnMainSync { retried = retryImage.drawable != null }; Thread.sleep(30)
            }
            assertTrue("A bind skipped while paused must retry the same image after resume", retried)
        } finally { instrumentation.runOnMainSync { loader.close() } }
    }

    @Test fun sportsScoreDelayWithholdsNewScoresAcrossCardsHeroOverlayAndCalendar() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Delayed score fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis(); val ids = setOf("nba:POR", "nba:LAL")
            val first = LiveEvent("delayed-fixture", ids, earlierToday(now), scores = mapOf("nba:POR" to "11", "nba:LAL" to "22"), status = "Q1", league = "NBA")
            val initial = LiveSportsSnapshot(listOf(first), now, setOf("NBA"))
            var latest = initial
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { latest }; activity.calendarSportsLoader = { _, _ -> latest }
                }
            }
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA"); store.sportsScoreDelay = 30
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    var playing: Player? = null
                    scenario.onActivity { activity ->
                        playing = find<PlayerView>(activity.window.decorView)!!.player
                        // Score delay is checked on a league's day, where a game stays after it ends (Live now drops it).
                        MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.set(activity, "today")
                        MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-hero-score")?.text?.toString() == "Score delayed" }
                    scenario.onActivity { activity ->
                        val history = MainActivity::class.java.getDeclaredField("delayedScores").apply { isAccessible = true }.get(activity) as SportsScoreDelay
                        val tick = android.os.SystemClock.elapsedRealtime(); history.clear(); history.observe(initial, tick - 31000)
                        latest = initial.copy(events = listOf(first.copy(state = "post", scores = mapOf("nba:POR" to "44", "nba:LAL" to "22"), status = "Final")))
                        history.observe(latest, tick)
                        MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, latest)
                        MainActivity::class.java.getDeclaredMethod("scheduleSports").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-hero-score")?.text?.contains("11") == true }
                    scenario.onActivity { activity ->
                        repeat(2) { index -> assertFalse(activity.window.decorView.findViewWithTag<TextView>("sports-card-score:NBA:delayed-fixture:$index").text.contains("44")) }
                        assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-hero-badge").text.contains("LIVE"))
                        assertFalse(activity.window.decorView.findViewWithTag<TextView>("sports-coverage:NBA:delayed-fixture").text.contains("catch-up", true))
                        MainActivity::class.java.getDeclaredMethod("openSportsScoreDelay").apply { isAccessible = true }.invoke(activity)
                        val manager = activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager")
                        manager.findViewWithTag<View>("settings-option:Current channel").performClick()
                        manager.findViewWithTag<View>("settings-option:15 seconds").performClick()
                        val channel = MainActivity::class.java.getDeclaredField("current").apply { isAccessible = true }.get(activity) as Channel
                        assertEquals(15, store.channelScoreDelay("${source.id}:${channel.nativeId}")); assertEquals(30, store.sportsScoreDelay); assertTrue(manager.opened)
                        manager.selectSetting("Score delay"); manager.findViewWithTag<View>("settings-option:Off · immediate scores").performClick()
                        assertEquals(0, store.sportsScoreDelay)
                        manager.findViewWithTag<View>("settings-option:30 seconds").performClick()
                        MainActivity::class.java.getDeclaredMethod("closeSportsManager").apply { isAccessible = true }.invoke(activity)
                        MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                        MainActivity::class.java.getDeclaredMethod("openScoreStrip").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { device.hasObject(By.textContains("POR 11")) }
                    scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("openSportsCalendar").apply { isAccessible = true }.invoke(activity) }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("calendar-game:NBA:delayed-fixture")?.isShown == true }
                    scenario.onActivity { activity ->
                        val row = activity.window.decorView.findViewWithTag<ViewGroup>("calendar-game:NBA:delayed-fixture")
                        assertTrue((row.getChildAt(0) as TextView).text.contains("11")); assertFalse((row.getChildAt(0) as TextView).text.contains("44"))
                        assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playing!!.isPlaying)
                    }
                }
            } finally { lifecycle.removeLifecycleCallback(fixture); store.resetScoreDelays(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sportsHeroRailAndFullScreenTeamManagerKeepFocusAndLeaguePreferences() {
        FixtureServer().use { server ->
            val artFiles = ArtworkFiles(context)
            val artBytes = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(2048, 1365, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, out); recycle() } }.toByteArray()
            val artUrls = (1..16).map { "https://images.example/grid-${System.nanoTime()}-$it.png" }; artUrls.forEach { artFiles.bytes(it) { artBytes } }
            val source = Source(name = "Sports focus fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("focus", "NFL RedZone FHD", "NFL", "${server.base}/channel1.mp4")
            store.saveSources(listOf(source)); store.active = source.id; store.hiddenDock = true
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            try {
                val scoresReady = java.util.concurrent.CountDownLatch(1)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var playback: Player? = null
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> playback = p; p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                    device.pressBack()
                    scenario.onActivity { activity ->
                        val now = System.currentTimeMillis()
                        val snapshot = LiveSportsSnapshot((1..8).map { LiveEvent("focus-$it", setOf("nfl:BUF", "nfl:KC"), now - 300000, logos = mapOf("nfl:BUF" to artUrls[(it - 1) * 2], "nfl:KC" to artUrls[(it - 1) * 2 + 1]), league = "NFL") }, now, setOf("NFL"))
                        activity.liveSportsLoader = { scoresReady.await(5, java.util.concurrent.TimeUnit.SECONDS); snapshot }
                        activity.calendarSportsLoader = { day, leagues ->
                            val today = java.time.LocalDate.now()
                            val start = if (day == today) maxOf(System.currentTimeMillis() - 60000, day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) else day.atTime(17, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            LiveSportsSnapshot(listOf(LiveEvent("calendar-$day", setOf("nfl:BUF", "nfl:KC"), start, if (day == today) "in" else if (day.isBefore(today)) "post" else "pre", scores = mapOf("nfl:BUF" to "24", "nfl:KC" to "21"), league = "NFL")), System.currentTimeMillis(), leagues)
                        }
                        activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-skeleton")?.isShown == true }
                    var loadingHeroHeight = 0
                    scenario.onActivity { activity ->
                        assertEquals(0, activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter!!.itemCount)
                        assertFalse("Loading no longer parks the cursor on a league filter", activity.currentFocus?.tag.toString().startsWith("sports-league:"))
                        loadingHeroHeight = activity.window.decorView.findViewWithTag<View>("sports-hero").height
                    }
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-loading.png")); scoresReady.countDown()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 9 && it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-scroll-hint")?.let { hint -> hint.isShown && hint.visibility == View.VISIBLE && hint.text.contains("Scroll down ↓") } == true }
                    var heroHeight = 0; var firstKey: Any? = null
                    scenario.onActivity { activity ->
                        assertNull(activity.window.decorView.findViewWithTag<View>("sports-leagues"))
                        val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                        firstKey = grid.findFocus().tag; heroHeight = activity.window.decorView.findViewWithTag<View>("sports-hero").height
                        assertEquals(loadingHeroHeight, heroHeight); assertFalse(activity.window.decorView.findViewWithTag<View>("sports-skeleton").isShown)
                        pollSportsFixture(activity)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertEquals(firstKey, activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().tag)
                        assertEquals(heroHeight, activity.window.decorView.findViewWithTag<View>("sports-hero").height)
                        val rail = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                        assertFalse(rail.expanded); rail.contentHasFocus = false; rail.sports.requestFocus(); assertFalse(rail.expanded)
                    }
                    // A cache update may be busy on another thread while the viewer keeps browsing.
                    // Holding its monitor makes any accidental UI-thread cache access observable.
                    val cacheHeld = java.util.concurrent.CountDownLatch(1)
                    val releaseCache = java.util.concurrent.CountDownLatch(1)
                    val uiResponsive = java.util.concurrent.CountDownLatch(1)
                    val cacheOwner = Executors.newSingleThreadExecutor()
                    lateinit var cache: SportsStreamCache
                    lateinit var focusListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener
                    scenario.onActivity { activity ->
                        cache = MainActivity::class.java.getDeclaredField("sportsStreamCache").apply { isAccessible = true }.get(activity) as SportsStreamCache
                        focusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
                            if ((focused?.tag as? String)?.startsWith("NFL:focus-") == true && focused?.tag != firstKey) {
                                focused?.post { uiResponsive.countDown() }
                            }
                        }
                        activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
                    }
                    var responsive = false
                    try {
                        cacheOwner.submit { synchronized(cache) { cacheHeld.countDown(); releaseCache.await(10, java.util.concurrent.TimeUnit.SECONDS) } }
                        assertTrue(cacheHeld.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        val now = android.os.SystemClock.uptimeMillis()
                        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                            val event = KeyEvent(now, now, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0).apply { this.source = android.view.InputDevice.SOURCE_DPAD }
                            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, false))
                        }
                        responsive = uiResponsive.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } finally {
                        releaseCache.countDown(); cacheOwner.shutdownNow()
                        scenario.onActivity { it.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener) }
                    }
                    assertTrue("Sports focus and the main looper must respond while stream-cache work is blocked", responsive)
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    waitFor(scenario) { activity ->
                        val card = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)?.itemView
                        val logo = card?.let { find<android.widget.ImageView>(it) }
                        val drawable = logo?.drawable as? android.graphics.drawable.BitmapDrawable
                        // Sharp means at least as many pixels as the space it is drawn in, not more (bigger slowed Sports).
                        val space = logo?.let { minOf(it.width - it.paddingLeft - it.paddingRight, it.height - it.paddingTop - it.paddingBottom) } ?: Int.MAX_VALUE
                        drawable != null && maxOf(drawable.bitmap.width, drawable.bitmap.height) >= space && drawable.isFilterBitmap
                    }
                    scenario.onActivity { selectSportsSchedule(it, "sports-date") }
                    val calendarToday = java.time.LocalDate.now()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-calendar")?.isShown == true && it.window.decorView.findViewWithTag<View>("calendar-day:$calendarToday")?.hasFocus() == true }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("calendar-games").adapter?.itemCount == 1 && !it.window.decorView.findViewWithTag<View>("calendar-games-loading").isShown }
                    scenario.onActivity { activity ->
                        val calendar = activity.window.decorView.findViewWithTag<View>("sports-calendar")
                        assertEquals(activity.window.decorView.width, calendar.width); assertEquals(activity.window.decorView.height, calendar.height)
                        assertSame(playback, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playback!!.isPlaying)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                        val image = Bitmap.createBitmap(calendar.width, calendar.height, Bitmap.Config.ARGB_8888)
                        calendar.draw(Canvas(image))
                        File(context.getExternalFilesDir(null), "sports-calendar.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                    }
                    if (calendarToday.dayOfWeek == java.time.DayOfWeek.SATURDAY) {
                        // Right at the grid edge opens the agenda. Reach Sunday via the next row instead.
                        device.pressDPadDown(); repeat(6) { device.pressDPadLeft() }
                    } else device.pressDPadRight()
                    device.pressDPadCenter()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("calendar-game:NFL:calendar-${calendarToday.plusDays(1)}")?.hasFocus() == true }
                    scenario.onActivity { activity ->
                        val panel = activity.window.decorView.findViewWithTag<SportsCalendarPanel>("sports-calendar")
                        panel.submit(calendarToday, emptyList(), false, false, false)
                        assertEquals("A stale date response cannot clear the current games", 1, activity.window.decorView.findViewWithTag<RecyclerView>("calendar-games").adapter!!.itemCount)
                        activity.window.decorView.findViewWithTag<View>("calendar-open-date").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-calendar")?.isShown == false && it.window.decorView.findViewWithTag<View>("sports-date")?.isSelected == true }
                    scenario.onActivity { activity ->
                        assertEquals("date:${calendarToday.plusDays(1)}", MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.get(activity))
                        selectSportsSchedule(activity, "sports-date")
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("calendar-day:${calendarToday.plusDays(1)}")?.hasFocus() == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("calendar-next").apply { requestFocus(); performClick() } }
                    device.pressBack()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-date")?.hasFocus() == true }
                    scenario.onActivity { activity ->
                        assertEquals("date:${calendarToday.plusDays(1)}", MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.get(activity))
                        selectSportsSchedule(activity, "sports-today")
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                    }
                    device.pressDPadDown(); device.pressDPadRight()
                    scenario.onActivity { activity -> assertFalse(activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation").expanded) }
                    device.pressDPadDown(); device.pressDPadRight()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.hasFocus() == true }
                    scenario.onActivity { activity ->
                        assertEquals("NFL COVERAGE", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.toString())
                        assertFalse(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.contains("LIVE"))
                    }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-redzone").performLongClick(); it.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:focus")?.isShown == true }
                    scenario.onActivity { activity -> assertFalse(activity.window.decorView.findViewWithTag<View>("sports-watch").isShown) }
                    device.pressBack()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.hasFocus() == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-teams").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-manager").isShown }
                    scenario.onActivity { activity ->
                        val manager = activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager")
                        manager.selectSetting("Preferences"); manager.findViewWithTag<View>("settings-option:Show only my teams").performClick()
                        assertTrue(manager.opened); assertTrue(store.sportsFollowedOnly)
                        manager.findViewWithTag<View>("settings-option:Play best stream on selection").performClick()
                        assertTrue("Sports preferences save automatic playback without closing", manager.opened && SecureStore(context).sportsAutoPlay)
                        manager.selectSetting("Leagues"); manager.focusOption("Champions League"); manager.findViewWithTag<View>("settings-option:Champions League").performClick()
                        assertTrue(manager.opened); assertFalse("UCL" in SecureStore(context).sportsLeagues)
                        listOf("NCAA Football", "NCAA Men's Basketball", "NCAA Women's Basketball", "NCAA Baseball").forEach { name ->
                            assertNotNull("College leagues appear in Sports preferences", manager.findViewWithTag<View>("settings-option:$name"))
                        }
                        manager.findViewWithTag<View>("settings-option:NCAA Football").performClick()
                        assertFalse("NCAAF" in SecureStore(context).sportsLeagues)
                        manager.findViewWithTag<View>("settings-option:NCAA Football").performClick()
                        assertTrue("NCAAF" in SecureStore(context).sportsLeagues)
                        manager.selectSetting("NCAA Football")
                    }
                    waitFor(scenario) { find<RecyclerView>(it.window.decorView.findViewWithTag<SettingsPanel>("sports-manager"))?.findViewHolderForAdapterPosition(0)?.itemView?.isShown == true }
                    device.pressDPadCenter(); waitFor(scenario) { "ncaaf:2000" in store.followedTeams }
                    scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager").selectSetting("NFL") }
                    device.pressDPadCenter(); waitFor(scenario) { "nfl:ARI" in store.followedTeams }
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-manager.png"))
                    scenario.onActivity { activity ->
                        val manager = activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager")
                        manager.selectSetting("Preferences"); manager.findViewWithTag<View>("settings-option:Reset Sports settings").performClick()
                        assertTrue(manager.findViewWithTag<View>("settings-option:Cancel").hasFocus())
                        manager.findViewWithTag<View>("settings-option:Cancel").performClick(); assertTrue("nfl:ARI" in store.followedTeams)
                        manager.findViewWithTag<View>("settings-option:Reset Sports settings").performClick(); manager.findViewWithTag<View>("settings-option:Reset Sports").performClick()
                        assertTrue(store.followedTeams.isEmpty()); assertEquals(SportsLeagues.byId.keys, store.sportsLeagues)
                        assertFalse("Reset returns to the default leagues", store.sportsLeaguesChosen)
                        assertFalse(store.sportsFollowedOnly); assertTrue("OK plays live games by default", SecureStore(context).sportsAutoPlay); assertEquals(GuideFilters(), store.sportsFilters(source)); assertTrue(store.hideCompletedScores); assertEquals("today", store.sportsTeam)
                        assertEquals(source.id, store.active)
                    }
                    device.pressBack(); device.pressBack()
                    waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("sports-manager").isShown && it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                    scenario.onActivity { assertEquals(OnboardingPanel.DEFAULT_LEAGUES, store.sportsLeagues) }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 9 }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-live").requestFocus() }
                    device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                    device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "sports.png"))
                    val focusFrames = java.util.concurrent.CountDownLatch(1)
                    scenario.onActivity { activity ->
                        val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                        val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                        val target = (0 until grid.adapter!!.itemCount).first { position ->
                            val card = grid.findViewHolderForAdapterPosition(position)?.itemView
                            card == null || card.top < 0 || card.bottom > grid.height
                        }
                        grid.suppressLayout(true)
                        SportsPanel::class.java.getDeclaredMethod("focusAt", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(panel, target)
                        // A user can move to the header before a card scroll finishes.
                        // The delayed scroll must not steal focus back.
                        activity.window.decorView.findViewWithTag<View>("sports-today").requestFocus()
                        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                        grid.suppressLayout(false)
                        grid.postOnAnimation { grid.postOnAnimation { focusFrames.countDown() } }
                    }
                    assertTrue("Sports focus layout completes", focusFrames.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    scenario.onActivity { assertTrue("New header input wins over delayed card focus", it.window.decorView.findViewWithTag<View>("sports-my-teams").hasFocus()) }
                    scenario.onActivity { selectSportsSchedule(it, "sports-date") }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("calendar-game:NFL:calendar-${java.time.LocalDate.now()}")?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("calendar-game:NFL:calendar-${java.time.LocalDate.now()}").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-calendar")?.isShown == false && it.window.decorView.findViewWithTag<View>("sports-watch").isShown }
                    scenario.onActivity { assertSame(playback, find<PlayerView>(it.window.decorView)!!.player); assertTrue(playback!!.isPlaying) }
                    device.pressHome(); instrumentation.waitForIdleSync()
                    // Home reaches onPause asynchronously; a slow emulator can still be playing right after idle.
                    run {
                        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                        var paused = false
                        while (!paused && android.os.SystemClock.uptimeMillis() < deadline) {
                            instrumentation.runOnMainSync { paused = !playback!!.playWhenReady }
                            if (!paused) Thread.sleep(100)
                        }
                        assertTrue("Leaving the app pauses playback", paused)
                    }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    instrumentation.runOnMainSync { assertEquals(Player.STATE_IDLE, playback!!.playbackState) }
                }
            } finally { store.sportsLeagues = SportsLeagues.byId.keys; store.sportsFollowedOnly = false; store.hiddenDock = false; store.followedTeams = emptySet(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun perChannelGuideTimeCanRewindResetAndReachNowWithRemote() {
        FixtureServer().use { server ->
            val source = Source(name = "Timeline fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..2).map { Channel("time:$it", "Channel $it", "News", "${server.base}/channel$it.mp4") }
            val now = System.currentTimeMillis(); val slot = GuideTimeline.live(now)
            val programmes = channels.associate { c -> c.id to listOf(Programme(c.id, "Live ${c.id}", slot, slot + 2 * GuideTimeline.SLOT), Programme(c.id, "Later ${c.id}", slot + 2 * GuideTimeline.SLOT, slot + 3 * 3600000)) }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                repeat(2) { device.pressDPadRight() }; waitFor(scenario) { device.hasObject(By.text("Later time:1")) }
                device.pressDPadDown(); waitFor(scenario) { device.hasObject(By.text("Live time:2")) }
                device.pressDPadUp(); waitFor(scenario) { device.hasObject(By.text("Later time:1")) }
                repeat(2) { device.pressDPadLeft() }; waitFor(scenario) { device.hasObject(By.text("Live time:1")) }
                device.pressDPadRight(); device.pressBack(); waitFor(scenario) { device.hasObject(By.text("Live time:1")) && channelList(it)?.hasFocus() == true }
                device.pressDPadRight(); device.pressDPadUp()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-now").hasFocus() }
                device.pressDPadCenter(); waitFor(scenario) { device.hasObject(By.text("Live time:1")) }
                device.pressDPadDown(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search").requestFocus() }
                repeat(6) { device.pressDPadLeft() }
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("search").hasFocus()) }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Guide")), 5000)); activateSetting(scenario, "Time format")
                assertTrue(device.wait(Until.hasObject(By.text("24 hour · 19:30")), 5000)); activateSetting(scenario, "24 hour · 19:30"); leaveSettings(scenario)
                waitFor(scenario) { store.clock24 && GuideClock.use24Hour }
                scenario.recreate(); waitFor(scenario) { GuideClock.use24Hour && find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun favoritesClearSearchAndImmediatelyConfirmAndRedrawChanges() {
        // This test edits a query; the separate voice test covers microphone/recognition behavior.
        // A clean CI install must not route the Favorites activation into a permission prompt.
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        FixtureServer().use { server ->
            val source = Source(name = "Favorites fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..3).map { Channel("favorite:$it", listOf("One", "Two", "Three")[it - 1], "News", "${server.base}/channel$it.mp4") }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            channels.take(2).forEach { store.toggleFavorite(source, it) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search").performClick() }
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("One") }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:favorite:1")?.isShown == true }
                // The first Back closes the on-screen keyboard, the next closes Search.
                repeat(3) { var open = false; scenario.onActivity { open = it.window.decorView.findViewWithTag<View>("playlist-search")?.isShown == true }; if (open) { device.pressBack(); instrumentation.waitForIdleSync() } }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playlist-search")?.isShown == false }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(1)!!.itemView.requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 2 && channelList(it)?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val row = channelList(activity)!!.findViewHolderForAdapterPosition(0)!!.itemView
                    row.requestFocus(); val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888); row.draw(Canvas(bitmap))
                    val d = activity.resources.displayMetrics.density
                    assertEquals("Favorite star must be dark on a highlighted row", Color.BLACK, bitmap.getPixel(((GuideAdapter.CHANNEL_WIDTH_DP - 12) * d).toInt(), row.height / 2))
                    bitmap.recycle(); row.performLongClick()
                }
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 1 && device.hasObject(By.text("Removed from Favorites · One")) }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 3 && (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "favorite:1" }
                device.pressDPadDown(); device.pressDPadDown()
                waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "favorite:3" }
                device.executeShellCommand("input dpad keyevent --duration ${ViewConfiguration.getLongPressTimeout() + 500} 23")
                waitFor(scenario) { device.hasObject(By.text("Added to Favorites · Three")) && (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "favorite:3" }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "favorite-feedback.png"))
                scenario.onActivity { activity ->
                    val icon = activity.getDrawable(R.drawable.ic_spare)!!
                    val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888); icon.setBounds(0, 0, 256, 256); icon.draw(Canvas(image))
                    File(context.getExternalFilesDir(null), "icon.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(1)!!.itemView.requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { (channelList(it)?.adapter as? GuideAdapter)?.channels?.map { it.id } == listOf("favorite:2", "favorite:3") }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun sportsReplayFindsArchiveHidesScoresAndReturnsToTheGame() {
        FixtureServer(archivedSports = true).use { server ->
            val source = Source(name = "Sports replay fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channel = Channel("xc:1", "ESPN HD", "NFL", "${server.base}/live/test/test-secret/1.mp4", catchupDays = 2, catchupMode = "xc", reportedQuality = VideoInfo(1280, 720, 30f))
            store.setSportsFilters(source, GuideFilters(1080, FrameRateFilter.sixtyOrBetter))
            store.saveSources(listOf(source)); store.active = source.id
            context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().remove("sports-hide-completed-scores").commit()
            assertTrue("Completed scores are hidden before the first Sports visit", store.hideCompletedScores)
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var player: Player? = null
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> player = p; p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                    device.pressBack()
                    scenario.onActivity { activity ->
                        val now = System.currentTimeMillis()
                        val finals = LiveSportsSnapshot(listOf(LiveEvent("sports-archive", setOf("nfl:SEA", "nfl:SF"), now - 7200000, "post", networks = listOf("ESPN"), scores = mapOf("nfl:SEA" to "24", "nfl:SF" to "21"), status = "Seahawks win 24–21", league = "NFL")), now, setOf("NFL"))
                        activity.liveSportsLoader = { finals }
                        MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, finals)
                        activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                        MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.set(activity, "date:${java.time.Instant.ofEpochMilli(now - 7200000).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}")
                        // As the Calendar does: a date shows one league, then the games take the cursor.
                        MainActivity::class.java.getDeclaredMethod("ensureSportsLeague").apply { isAccessible = true }.invoke(activity)
                        activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel").home()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-score").text == "Score hidden" && it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:sports-archive")?.text?.contains("Play replay") == true }
                    waitFor(scenario) { !MainActivity::class.java.getDeclaredField("sportsComputing").apply { isAccessible = true }.getBoolean(it) }
                    scenario.onActivity { activity ->
                        @Suppress("UNCHECKED_CAST") val cache = MainActivity::class.java.getDeclaredField("replayCache").apply { isAccessible = true }.get(activity) as MutableMap<String, Pair<Long, List<SportsReplay>>>
                        cache.entries.toList().forEach { entry -> cache[entry.key] = entry.value.first - SportsReplayDiscovery.HISTORY_MS - 1000L to entry.value.second }
                        MainActivity::class.java.getDeclaredMethod("refreshSports").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { !MainActivity::class.java.getDeclaredField("sportsComputing").apply { isAccessible = true }.getBoolean(it) && it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:sports-archive")?.text?.contains("Play replay") == true }
                    scenario.onActivity { activity ->
                        assertFalse(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-detail")?.text?.contains("win") == true)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("sports-watch").isShown)
                        assertEquals("Automatic discovery confirms the lower-quality archive", "REPLAY", activity.window.decorView.findViewWithTag<TextView>("sports-badge:NFL:sports-archive").text.toString())
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick()
                    }
                    assertEquals("Only the matching broadcaster's metadata archive is queried", 1, server.archiveRequests.get())
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/timeshift/test/test-secret/60/") == true && (player?.videoSize?.width ?: 0) > 0 }
                    scenario.onActivity { assertSame(player, find<PlayerView>(it.window.decorView)!!.player) }
                    closeDemandControls(scenario)
                    device.pressBack()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() && find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/live/") == true }
                    scenario.onActivity { activity ->
                        assertFalse("Completed games have no Hide/Reveal score button", activity.window.decorView.findViewWithTag<View>("sports-reminder").isShown)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:sports-archive")?.text?.contains("Play replay") == true }
                    scenario.onActivity { activity ->
                        val badge = activity.window.decorView.findViewWithTag<TextView>("sports-badge:NFL:sports-archive")
                        assertEquals("A lower-quality archive still gets a confirmed replay badge", "REPLAY", badge.text.toString())
                        val availableWidth = badge.width - badge.compoundPaddingLeft - badge.compoundPaddingRight
                        assertTrue("The full replay label fits inside its badge", badge.paint.measureText(badge.text.toString()) <= availableWidth)
                        assertEquals(0xFF123D68.toInt(), badge.currentTextColor)
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick()
                    }
                    waitFor(scenario) { player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/timeshift/") == true }
                    assertEquals("Repeat replay lookup uses its saved match", 1, server.archiveRequests.get())
                    closeDemandControls(scenario)
                    device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    scenario.onActivity { activity ->
                        assertEquals("Score hidden", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-score").text.toString())
                        activity.window.decorView.findViewWithTag<View>("settings").performClick()
                        activity.window.decorView.findViewWithTag<SettingsPanel>("settings-panel").selectSetting("Sports")
                        // Settings › Sports › Preferences opens the one Sports settings screen.
                        activity.window.decorView.findViewWithTag<View>("settings-option:Preferences").performClick()
                        activity.window.decorView.findViewWithTag<View>("sports-manager").findViewWithTag<View>("settings-option:Hide completed-game scores").performClick()
                        assertFalse(SecureStore(context).hideCompletedScores)
                        MainActivity::class.java.getDeclaredMethod("closeSportsManager").apply { isAccessible = true }.invoke(activity)
                        MainActivity::class.java.getDeclaredMethod("closeSettings").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-score").text.contains("24") }
                    scenario.onActivity { activity -> assertFalse(activity.window.decorView.findViewWithTag<View>("sports-reminder").isShown) }
                    waitFor(scenario) { store.read("sports-replays-${source.id}.enc") != null }
                    scenario.recreate()
                    waitFor(scenario) { activity ->
                        @Suppress("UNCHECKED_CAST") val restored = MainActivity::class.java.getDeclaredField("replayCache").apply { isAccessible = true }.get(activity) as Map<String, Pair<Long, List<SportsReplay>>>
                        restored.entries.any { it.key.endsWith(":NFL:sports-archive") && it.value.second.singleOrNull()?.channel?.id == channel.id }
                    }
                    assertEquals("Restart restores a confirmed replay without searching the provider again", 1, server.archiveRequests.get())
                }
            } finally { store.hideCompletedScores = false; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sportsSchedulesShowLiveAndUpcomingWithoutScanningPlaylistAndLibraryIsReachable() {
        store.delete("game-reminders.enc")
        FixtureServer(channelCount = 12).use { server ->
            val source = Source(name = "Schedule fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channels = (1..12).map { Channel("xc:$it", "ESPN $it", if (it == 12) "NFL Game Pass" else "Sports", "${server.base}/live/test/test-secret/$it.mp4", guideId = "xc:$it", reportedQuality = if (it == 12) VideoInfo(1920, 1080, 0f) else null) }
            val cachedAt = System.currentTimeMillis()
            val midnight = java.time.Instant.ofEpochMilli(cachedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val upcomingAt = cachedAt + minOf(3600000L, (midnight - cachedAt) / 2)
            val programmes = channels.associate { it.guideId to listOf(Programme(it.guideId, "Cached guide", cachedAt - 60000, cachedAt + 3 * 3600000)) } + mapOf(channels.first().guideId to listOf(
                Programme(channels.first().guideId, "Cached guide", cachedAt - 60000, upcomingAt),
                Programme(channels.first().guideId, "NFL: Seahawks vs 49ers", upcomingAt, upcomingAt + 3 * 3600000))) + mapOf(channels.last().guideId to listOf(Programme(channels.last().guideId, "NFL RedZone", cachedAt - 60000, cachedAt + 3 * 3600000)))
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            store.setSportsFilters(source, GuideFilters(minHeight = 1080)); store.followedTeams = setOf("nfl:SEA")
            val scoreboard = LiveSportsSnapshot(listOf(LiveEvent("live", setOf("nfl:BUF", "nfl:KC"), cachedAt - 300000),
                LiveEvent("next", setOf("nfl:SEA", "nfl:SF"), upcomingAt, "pre", records = mapOf("nfl:SEA" to "6-2", "nfl:SF" to "2-6"))), cachedAt, setOf("NFL", "MLB"))
            val destinationStarted = java.util.concurrent.CountDownLatch(1)
            val finishDestination = java.util.concurrent.CountDownLatch(1)
            val firstBatchDrawn = java.util.concurrent.CountDownLatch(1)
            val finishScoreboard = java.util.concurrent.CountDownLatch(1)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { player -> player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying } == true }
                val openedAt = android.os.SystemClock.elapsedRealtime()
                scenario.onActivity { activity ->
                    activity.redZoneLoader = { snapshot, profile, quality, at ->
                        destinationStarted.countDown()
                        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
                        while (finishDestination.count > 0 && System.nanoTime() < deadline) try { finishDestination.await(100, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                        SportsDestinations.redZone(snapshot, profile, quality, at)
                    }
                    activity.liveSportsLoader = {
                        val request = MainActivity::class.java.getDeclaredField("liveSportsRequest").apply { isAccessible = true }.getInt(activity)
                        val publish = MainActivity::class.java.getDeclaredMethod("publishSportsSchedule", LiveSportsSnapshot::class.java, java.time.LocalDate::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                        publish.invoke(activity, scoreboard.copy(events = scoreboard.events.takeLast(1)), null, request, false)
                        assertTrue(firstBatchDrawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        publish.invoke(activity, scoreboard, null, request, false)
                        finishScoreboard.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        scoreboard.copy(checked = System.currentTimeMillis())
                    }
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                    // Upcoming games belong to a league's day: Today, rather than Live now.
                    activity.window.decorView.findViewWithTag<View>("sports-today").performClick()
                    activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel").home()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 }
                firstBatchDrawn.countDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 3 && it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                assertTrue("Cold scoreboard results must not wait behind RedZone discovery", android.os.SystemClock.elapsedRealtime() - openedAt < 3000)
                scenario.onActivity { assertTrue("More scoreboard results are still arriving", MainActivity::class.java.getDeclaredField("liveSportsChecking").apply { isAccessible = true }.getBoolean(it)) }
                scenario.onActivity { activity ->
                    val request = MainActivity::class.java.getDeclaredField("liveSportsRequest").apply { isAccessible = true }.getInt(activity)
                    MainActivity::class.java.getDeclaredMethod("publishSportsSchedule", LiveSportsSnapshot::class.java, java.time.LocalDate::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(activity, LiveSportsSnapshot(emptyList(), System.currentTimeMillis(), setOf("NBA")), null, request, false)
                }
                waitFor(scenario) { activity ->
                    val scores = MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.get(activity) as LiveSportsSnapshot
                    "NBA" in scores.leagues && activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 3
                }
                assertEquals("Auxiliary guide scans wait until the scoreboard finishes", 1L, destinationStarted.count)
                finishScoreboard.countDown()
                assertTrue("RedZone discovery resumes once Sports settles", destinationStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                finishDestination.countDown()
                waitFor(scenario) { activity ->
                    @Suppress("UNCHECKED_CAST") val summaries = MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>
                    summaries["live"]?.startsWith("Finding stream") == false
                }
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST") val summaries = MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>
                    assertFalse("Upcoming games must not start automatic playlist searches", summaries.containsKey("next"))
                }
                assertEquals("Displaying schedules must not query every provider channel", 0, server.epgTargets.size)
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-redzone-coverage")?.text.toString().contains("ESPN 12") }
                scenario.onActivity { activity ->
                    assertTrue("Hero: ${activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text}; focus ${activity.currentFocus?.tag}", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Seattle"))
                    assertTrue(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.contains("UPCOMING"))
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-hero-title").text.contains("(6-2)"))
                    val card = activity.window.decorView.findViewWithTag<View>("sports-card-teams:next")
                    assertEquals("Seattle Seahawks", card.findViewWithTag<TextView>("sports-card-team-name:next:0").text.toString())
                    assertEquals("(6-2)", card.findViewWithTag<TextView>("sports-card-team-record:next:0").text.toString())
                    assertEquals("San Francisco 49ers", card.findViewWithTag<TextView>("sports-card-team-name:next:1").text.toString())
                    assertEquals("(2-6)", card.findViewWithTag<TextView>("sports-card-team-record:next:1").text.toString())
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-redzone-coverage").text.toString().contains("ESPN 12"))
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-records.png"))
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("sports-today").isSelected)
                    store.sportsFollowedOnly = true
                    selectSportsSchedule(activity, "sports-live")
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 && it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Buffalo") }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("sports-live").isSelected)
                    assertNotNull("RedZone remains reachable in Live", activity.window.decorView.findViewWithTag<View>("sports-redzone"))
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-live").hasFocus() }
                device.pressDPadRight(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 && it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Seattle") }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-reminder").isShown }
                device.pressDPadRight(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.Button>("sports-reminder").text.contains("Reminder set") }
                device.pressBack(); waitFor(scenario) { it.currentFocus?.tag == "next" }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("sports-redzone").performLongClick()
                    activity.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-status").text.contains("1 RedZone channels") }
                assertEquals("A cached Game Pass listing needs no provider lookup", 0, server.epgTargets.size)
                device.pressBack(); waitFor(scenario) { it.currentFocus?.tag == "sports-redzone" }
                device.pressDPadUp(); waitFor(scenario) { it.currentFocus?.tag == "sports-league:NFL" }
                device.pressDPadDown(); waitFor(scenario) { it.currentFocus?.tag == "sports-redzone" }
                device.pressDPadLeft(); waitFor(scenario) { it.currentFocus?.tag == "next" }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                scenario.onActivity { activity ->
                    val rail = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                    assertTrue(rail.contentHasFocus); assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, rail.descendantFocusability)
                    activity.window.decorView.findViewWithTag<View>("sports-live").requestFocus()
                }
                var liveMediaId = ""
                scenario.onActivity { liveMediaId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressDPadLeft(); device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<MediaBrowserPanel>("library-panel").focusToolbar() }
                device.pressBack(); waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown && channelList(it)?.isShown == false }
                scenario.onActivity { assertEquals("Back from Library preserves the playing stream without retuning", liveMediaId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
            }
            store.delete("game-reminders.enc")
            store.saveSources(emptyList()); store.removeCache(source); store.followedTeams = emptySet(); store.sportsFollowedOnly = false
        }
    }

    @Test fun missingVodMatchDoesNotStartAnAutomaticCatalogDownload() {
        FixtureServer(longVideo = true, programmeTitle = "Missing from the VOD fixture", slowVod = true, parallelClients = true).use { server ->
            val source = Source(name = "Missing VOD", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                var media = ""
                scenario.onActivity { activity ->
                    media = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                    MainActivity::class.java.getDeclaredMethod("showDetails").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("vod-match-status")?.contentDescription?.contains("Open Library or Search") == true }
                scenario.onActivity { activity ->
                    assertFalse(activity.window.decorView.findViewWithTag<View>("vod-match-progress").isShown)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("channel-vod-start").isShown)
                    assertEquals(media, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
                assertTrue("OK only checks saved titles", server.catalogTargets.isEmpty())
                scenario.onActivity {
                    val root = it.window.decorView
                    root.findViewWithTag<View>("search").performClick()
                    root.findViewWithTag<EditText>("guide-search-input").setText("Missing from the VOD fixture")
                }
                waitFor(scenario) { server.catalogTargets.isNotEmpty() }
                server.finishVodCatalog.countDown()
                waitFor(scenario) { activity ->
                    val search = MainActivity::class.java.getDeclaredField("playlistSearch").apply { isAccessible = true }.get(activity) as PlaylistSearch
                    search.catalogStatus(source, setOf("movie", "series")).complete
                }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("clearGuideSearch").apply { isAccessible = true }.invoke(activity)
                    MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                    MainActivity::class.java.getDeclaredMethod("showDetails").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("vod-match-status")?.contentDescription?.contains("Not available in your VOD library") == true }
                assertEquals(0, server.archiveRequests.get())
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun liveProgrammeCanStartItsVodMovieOrIdentifiedEpisodeWithoutUsingCatchup() {
        listOf("Fixture movie (2025)" to "/movie/test/test-secret/101.mp4", "Fixture series S01E02" to "/series/test/test-secret/202.mp4").forEach { (title, path) ->
            FixtureServer(longVideo = true, programmeTitle = title, slowVod = title.startsWith("Fixture movie"), slowEpisodes = title.startsWith("Fixture series"), parallelClients = true).use { server ->
                val source = Source(name = "VOD start fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
                store.saveSources(listOf(source)); store.active = source.id
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                    importVodForProgramme(scenario, source, title)
                    device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true && device.hasObject(By.textContains(title)) }
                    holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }; device.pressDPadCenter()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                    val lookupStarted = android.os.SystemClock.elapsedRealtime()
                    scenario.onActivity { activity ->
                        val label = activity.window.decorView.findViewWithTag<View>("channel-vod-start").contentDescription.toString()
                        assertTrue(label.contains(if (title.startsWith("Fixture series")) "Fixture series · S01E02" else "Fixture movie (2025)"))
                    }
                    scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("channel-catchup").isShown); val player = find<PlayerView>(it.window.decorView)!!.player!!
                        player.volume = 0f; player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
                        it.window.decorView.findViewWithTag<View>("channel-vod-start").performClick()
                        // A cached, unique movie may already be playing synchronously.
                        assertFalse(it.window.decorView.findViewWithTag<View>("playlist-search").isShown) }
                    if (title.startsWith("Fixture series")) {
                        waitFor(scenario) { server.episodesRequested.count == 0L && it.window.decorView.findViewWithTag<View>("vod-start-panel").isShown }
                        scenario.onActivity { activity ->
                            assertFalse("Episode lookup must never open the Library", activity.window.decorView.findViewWithTag<View>("library-panel").isShown)
                            assertFalse(activity.window.decorView.findViewWithTag<View>("playlist-search").isShown)
                            assertTrue(activity.window.decorView.findViewWithTag<View>("vod-start-panel").hasFocus())
                            assertEquals("Fixture series · S01E02", activity.window.decorView.findViewWithTag<TextView>("vod-start-title").text.toString())
                        }
                        device.takeScreenshot(File(context.getExternalFilesDir(null), "vod-opening.png"))
                        device.pressBack()
                        waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("vod-start-panel").isShown }
                        assertFalse(playingPath(scenario).orEmpty().startsWith("/series/"))
                        server.finishEpisodes.countDown()
                        device.pressDPadCenter()
                        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                        scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-vod-start").performClick() }
                    }
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == path }
                    scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<View>("library-panel").isShown) }
                    if (title.startsWith("Fixture movie")) {
                        assertTrue("Autoplay must not wait for the complete catalog", android.os.SystemClock.elapsedRealtime() - lookupStarted < 5000)
                        assertEquals(1L, server.finishVodCatalog.count)
                        server.finishVodCatalog.countDown()
                    }
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.isPlaying && p.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_AUDIO) && p.volume == 1f } == true }
                    assertEquals("VOD start must never query the channel archive", 0, server.archiveRequests.get())
                }
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
        FixtureServer(longVideo = true, programmeTitle = "Other movie 16", slowVod = true, parallelClients = true).use { server ->
            val source = Source(name = "Late VOD match", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                server.finishVodCatalog.countDown(); importVodForProgramme(scenario, source, "Other movie 16")
                var liveId = ""
                scenario.onActivity { liveId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true && device.hasObject(By.textContains("Other movie 16")) }
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }; device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                scenario.onActivity { activity ->
                    assertFalse(activity.window.decorView.findViewWithTag<View>("vod-match-progress").isShown)
                    assertTrue(activity.window.decorView.findViewWithTag<View>("vod-match-status").contentDescription.toString().contains("Other movie 16"))
                    assertEquals(liveId, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
        FixtureServer(longVideo = true, programmeTitle = "Fixture movie", slowVod = true, ambiguousVod = true).use { server ->
            val source = Source(name = "VOD release choice", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                server.finishVodCatalog.countDown(); importVodForProgramme(scenario, source, "Fixture movie", 2)
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true && device.hasObject(By.textContains("Fixture movie")) }
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-vod-start").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("vod-start-choice:movie:102")?.isShown == true }
                scenario.onActivity {
                    assertEquals(2, it.window.decorView.findViewWithTag<ViewGroup>("vod-start-options").childCount)
                    assertFalse(it.window.decorView.findViewWithTag<View>("playlist-search").isShown)
                }
                device.pressBack()
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("vod-start-panel").isShown }
                assertFalse(playingPath(scenario).orEmpty().startsWith("/movie/"))
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-vod-start").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("vod-start-choice:movie:101")?.hasFocus() == true }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/movie/test/test-secret/102.mp4" }
                server.finishVodCatalog.countDown()
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun guideAndLibraryShareSearchAcrossChannelsMoviesSeriesAndKnownEpisodes() {
        store.performanceOverlay = false
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Search fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channel = Channel("xc:1", "Fixture live", "Other", "${server.base}/live/test/test-secret/1.mp4", logo = "${server.base}/logo.png", catchupDays = 2, catchupMode = "xc")
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity {
                    assertTrue(channelList(it)!!.getChildAt(0).contentDescription.toString().contains("Catch-up: 2 days"))
                    it.window.decorView.findViewWithTag<View>("search").performClick()
                    it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Fixture")
                }
                // Live channels open first and are never mixed with on-demand posters.
                waitFor(scenario) { activity ->
                    val channelResult = activity.window.decorView.findViewWithTag<View>("search-result:live:xc:1")
                    channelResult?.isShown == true && find<ImageView>(channelResult)?.drawable is android.graphics.drawable.BitmapDrawable
                }
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    assertNull("Live TV lists no on-demand titles", root.findViewWithTag<View>("search-result:movie:101")?.takeIf { it.isShown })
                    assertTrue(root.findViewWithTag<TextView>("search-kind:live").text.contains("Live TV · 1"))
                    assertTrue(root.findViewWithTag<TextView>("search-kind:vod").text.contains("Movies and series · "))
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:vod").performClick() }
                waitFor(scenario) { activity ->
                    val root = activity.window.decorView
                    val movie = root.findViewWithTag<View>("search-result:movie:101")
                    movie?.isShown == true && root.findViewWithTag<View>("search-result:series:1")?.isShown == true &&
                        find<ImageView>(movie)?.drawable is android.graphics.drawable.BitmapDrawable &&
                        root.findViewWithTag<View>("search-result:live:xc:1")?.isShown != true
                }
                // This test's voice search times out and returns focus to Voice, so place focus directly.
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-result:movie:101").requestFocus() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:movie:101")?.hasFocus() == true }
                // Posters carry no text, as in Library: the title under the cursor and what it is show above the grid.
                scenario.onActivity { activity -> val root = activity.window.decorView
                    assertEquals("Fixture movie", root.findViewWithTag<TextView>("search-spot-title").text.toString())
                    assertTrue(root.findViewWithTag<TextView>("search-spot-meta").text.toString(), root.findViewWithTag<TextView>("search-spot-meta").text.startsWith("Movie"))
                    val tile = root.findViewWithTag<ViewGroup>("search-result:movie:101"); val words = mutableListOf<String>()
                    fun texts(view: View) { if (view is TextView && view.isShown && view.text.isNotBlank() && view.tag != "poster-placeholder") words += view.text.toString(); (view as? ViewGroup)?.let { g -> (0 until g.childCount).forEach { texts(g.getChildAt(it)) } } }
                    texts(tile); assertTrue("A search poster carries no text: $words", words.isEmpty())
                }
                device.pressDPadUp()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-kind:vod")?.hasFocus() == true }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search-all.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:series").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("search-poster-grid")?.findViewWithTag<View>("search-result:series:1")?.hasFocus() == true }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search-poster-grid.png"))
                device.pressDPadDown(); device.pressDPadRight()
                scenario.onActivity { assertTrue("Search owns remote focus at the results boundary", it.window.decorView.findViewWithTag<View>("playlist-search").hasFocus()) }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:episode:201")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-search").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playlist-search")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Pilot") }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:episode:201")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:episode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("search-poster-grid")?.findViewWithTag<View>("search-result:episode:201")?.hasFocus() == true }
                device.pressDPadCenter()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/series/test/test-secret/201.mp4" }
                assertEquals(1, server.catalogTargets.count { !it.contains("category_id=") })
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun indexedProgrammeSearchRendersResultsOnAHundredThousandChannelsAndTunes() {
        store.performanceOverlay = false
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Search benchmark", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val initial = Channel("initial", "Initial live feed", "Fixture", "${server.base}/channel1.mp4")
            val now = System.currentTimeMillis()
            val channels = List(100_000) { id -> Channel("benchmark:$id", "Feed $id", "Television", "${server.base}/channel2.mp4", logo = "${server.base}/logo.png") }
            val titles = List(1000) { "Studio $it Edition" }
            val guide = Guide(channels, channels.mapIndexed { i, channel ->
                val start = if (i < 3_000) now - 60_000 else now + 3_600_000
                channel.id to listOf(Programme(channel.id, titles[i % titles.size], start, start + 3_600_000))
            }.toMap())
            val prepared = GuideProgrammeIndex.Builder().prepare(guide, now)
            store.recentFeeds = emptyList()
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(initial), emptyMap())); store.markPlaylistRefreshed(source)
            val release = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                openTypedSearch(scenario, "")
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, guide)
                    MainActivity::class.java.getDeclaredField("programmeIndex").apply { isAccessible = true }.set(activity, prepared)
                    val worker = MainActivity::class.java.getDeclaredField("liveSearchWorker").apply { isAccessible = true }
                        .get(activity) as java.util.concurrent.ExecutorService
                    worker.submit { release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    activity.window.decorView.findViewWithTag<EditText>("guide-search-input").setText(titles[19])
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-skeleton")?.isShown == true }
                instrumentation.waitForIdleSync()
                Thread.sleep(120)
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search-loading.png"))
                release.countDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:benchmark:19")?.isShown == true }

                val elapsed = mutableListOf<Long>()
                for (id in 20 until 44) {
                    val rendered = java.util.concurrent.CountDownLatch(1)
                    var began = 0L
                    var ended = 0L
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        val observer = root.viewTreeObserver
                        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                if (root.findViewWithTag<View>("search-result:live:benchmark:$id")?.isShown == true) {
                                    ended = android.os.SystemClock.elapsedRealtimeNanos()
                                    observer.removeOnGlobalLayoutListener(this)
                                    rendered.countDown()
                                }
                            }
                        }
                        observer.addOnGlobalLayoutListener(listener)
                        began = android.os.SystemClock.elapsedRealtimeNanos()
                        root.findViewWithTag<EditText>("guide-search-input").setText(titles[id])
                    }
                    assertTrue("Render a programme-title match for query $id", rendered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    elapsed.add((ended - began) / 1_000_000)
                }
                val ordered = elapsed.sorted()
                println("Search benchmark 100000 channels and programmes, ${elapsed.size} queries: p50=${ordered[11]}ms p95=${ordered[22]}ms")
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:live").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:benchmark:43")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val row = activity.window.decorView.findViewWithTag<View>("search-result:live:benchmark:43")
                    assertTrue(row.contentDescription.contains(titles[43]))
                    assertTrue(row.contentDescription.contains("Live"))
                    assertFalse(row.contentDescription.contains(source.name))
                }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search-live.png"))
                device.pressDPadCenter()
                waitFor(scenario) {
                    it.window.decorView.findViewWithTag<View>("playlist-search")?.isShown == false &&
                        find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/channel2.mp4"
                }
            } } finally {
                release.countDown()
                store.recentFeeds = emptyList(); store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun upNextOffersTheNextEpisodeOfAStartedSeries() {
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Up next fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val series = OnDemand("series:1", "Fixture series", "Category 1", kind = "series")
            val pilot = OnDemand("episode:201", "Pilot", "Season 1", kind = "episode", seriesId = series.id, season = 1, episode = 1)
            // Finished the pilot: Up next offers episode 2 of a series that is not in the Watchlist.
            store.editFollowedSeries(source) { it.follow(series) }
            store.editLibraryFavorites(source) { it.completed(listOf(pilot.id)) }
            store.saveWatchProgress(source, pilot, 1000, 60000)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:upnext")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-tab:upnext").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:episode:202")?.isShown == true }
                waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:episode:202" }
                scenario.onActivity { activity ->
                    assertEquals("Fixture series", activity.window.decorView.findViewWithTag<TextView>("library-spot-title").text.toString())
                    assertEquals("Next · S1 E2", activity.window.decorView.findViewWithTag<TextView>("library-spot-meta").text.toString())
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "library-up-next.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:episode:202").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.endsWith("/202.mp4") == true }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun libraryMoviesSeriesArchiveAndFontUseSamePlayerAndReturnToLive() {
        FixtureServer(libraryCategories = true, longVideo = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var samePlayer: Player? = null
                scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                // Entering Library from the menu puts the cursor on the first poster of the rows home, not the menu or filters.
                waitFor(scenario) { (it.currentFocus?.tag as? String)?.startsWith("library-item:") == true }
                scenario.onActivity { assertTrue("Library opens on rows", it.window.decorView.findViewWithTag<View>("library-shelves").isShown) }
                // See all opens the category grid, where the rest of this flow runs.
                device.pressDPadRight(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-shelf-more:1" }
                device.pressDPadCenter()
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<RecyclerView>("library-items").let { it.isShown && it.hasFocus() } }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "library-posters.png"))
                scenario.onActivity { activity ->
                    val tile = activity.window.decorView.findViewWithTag<View>("library-item:movie:101")
                    // Category rows show portrait 2:3 artwork, never a square crop.
                    assertTrue("Poster tile ${tile.width}x${tile.height} must be portrait", tile.height > tile.width * 1.4f)
                    assertTrue("Categories stay visible beside the grid", activity.window.decorView.findViewWithTag<View>("library-categories").isShown)
                    assertNull(activity.window.decorView.findViewWithTag<View>("library-tab:catchup"))
                    assertEquals("Watchlist", activity.window.decorView.findViewWithTag<android.widget.Button>("library-tab:favorites").text.toString())
                    store.theme = "Ocean"; store.themeStyle = "Flat"; store.guideOpacity = 85
                    MainActivity::class.java.getDeclaredMethod("applyTheme").apply { isAccessible = true }.invoke(activity)
                    fun colors(view: View): List<Int> {
                        val background = view.background.constantState!!.newDrawable()
                        val bitmap = Bitmap.createBitmap(8, 2, Bitmap.Config.ARGB_8888)
                        background.setBounds(0, 0, 8, 2); background.draw(Canvas(bitmap))
                        return listOf(bitmap.getPixel(0, 0), bitmap.getPixel(7, 0)).also { bitmap.recycle() }
                    }
                    val guide = MainActivity::class.java.getDeclaredField("guidePanel").apply { isAccessible = true }.get(activity) as View
                    assertEquals("Library must use the Guide palette and opacity", colors(guide), colors(activity.window.decorView.findViewWithTag<View>("library-panel")))
                }
                assertTrue("Initial browsing requests its category or the whole catalog, never other categories", server.catalogTargets.any { it.contains("category_id=1") || !it.contains("category_id=") })
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").requestFocus() }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:movie").hasFocus() }
                // The tabs run along the top: Right walks them, Down enters the titles.
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:series").hasFocus() }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:movie").hasFocus() }
                device.pressDPadDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").hasFocus() }
                device.pressDPadLeft()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-categories").hasFocus() }
                device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").hasFocus() }
                assertTrue("Browsing one category must not download other categories", server.catalogTargets.none { it.contains("category_id=2") || it.contains("category_id=3") })
                chooseLibraryCategory(scenario, 2)
                waitFor(scenario) { server.actionCatalogStarted.count == 0L && it.window.decorView.findViewWithTag<RecyclerView>("library-items").adapter?.itemCount == 80 }
                scenario.onActivity { activity ->
                    assertEquals("Action", activity.window.decorView.findViewWithTag<TextView>("library-heading").text.toString())
                    assertFalse("An ongoing category download must not animate a bar over browsable titles", activity.window.decorView.findViewWithTag<View>("library-loading").isShown)
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("library-items").findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
                device.pressKeyCode(KeyEvent.KEYCODE_PAGE_DOWN)
                waitFor(scenario) { activity -> activity.window.decorView.findViewWithTag<RecyclerView>("library-items").let { list -> list.focusedChild?.let(list::getChildAdapterPosition)?.let { it >= PosterGridLayout.COLUMNS } == true } }
                device.pressKeyCode(KeyEvent.KEYCODE_PAGE_UP)
                waitFor(scenario) { activity -> activity.window.decorView.findViewWithTag<RecyclerView>("library-items").let { list -> list.focusedChild?.let(list::getChildAdapterPosition) == 0 } }
                // Sort cycles A–Z and Newest and is remembered; "Action title 10" sorts right after "Action title 1".
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-sort").performClick() }
                waitFor(scenario) { activity -> (activity.window.decorView.findViewWithTag<TextView>("library-sort").text.toString() == "Sort · A–Z") &&
                    activity.window.decorView.findViewWithTag<RecyclerView>("library-items").let { list -> (list.findViewHolderForAdapterPosition(1)?.itemView?.tag as? String) == "library-item:movie:2010" } }
                assertEquals(LibraryCatalog.SORT_TITLE, store.librarySort)
                scenario.onActivity { activity -> repeat(2) { activity.window.decorView.findViewWithTag<View>("library-sort").performClick() } }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("library-sort").text.toString() == "Sort · Provider order" }
                chooseLibraryCategory(scenario, 3)
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("library-heading").text.toString() == "Comedy" }
                server.finishActionCatalog.countDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:3001")?.isShown == true }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").hasFocus() }
                // The first entry of the category list is Home: back to the rows, on the tile the grid was opened from.
                chooseLibraryCategory(scenario, 0)
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("library-heading").text.toString() == "Movies" && it.window.decorView.findViewWithTag<View>("library-shelves").isShown && (it.currentFocus?.tag as? String) == "library-shelf-more:1" }
                device.pressDPadCenter()
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<RecyclerView>("library-items").let { it.isShown && it.hasFocus() } && a.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                device.pressMenu(); activateSetting(scenario, "Time format"); activateSetting(scenario, "24 hour · 19:30")
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel").isShown && store.clock24 }
                leaveSettings(scenario)
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown && it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown != true }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); it.window.decorView.findViewWithTag<View>("library-item:movie:101").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-play")?.isShown == true }
                // The details page carries the title and facts in the spotlight above poster, plot and actions.
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("library-spot-title")?.let { title -> title.isShown && title.text.toString() == "Fixture movie" } == true && it.window.decorView.findViewWithTag<View>("library-details").isShown && it.window.decorView.findViewWithTag<View>("library-play").hasFocus() }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "library-details.png"))
                scenario.onActivity {
                    assertFalse("Details show one Watchlist button, beside Play", it.window.decorView.findViewWithTag<View>("library-favorite").isShown)
                    it.window.decorView.findViewWithTag<View>("library-detail-watchlist").performClick()
                }
                waitFor(scenario) { store.libraryFavorites(source).contains("movie:101") }
                scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<View>("library-favorite").isShown) }
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<MediaBrowserPanel>("library-panel")
                    val play = panel.findViewWithTag<View>("library-play")
                    assertNull("Remote Back leaves details; no on-screen Back button", panel.findViewWithTag<View>("library-back"))
                    val watch = panel.findViewWithTag<android.widget.Button>("library-detail-watchlist")
                    assertEquals("✓ In Watchlist", watch.text.toString())
                    watch.requestFocus()
                    panel.detail(OnDemand("movie:101", "Fixture movie", "Fixture cinema", description = "Updated provider metadata"),
                        { title, action -> android.widget.Button(activity).apply { text = title; setOnClickListener { action() } } },
                        { play.performClick() }, false)
                    assertTrue("Provider metadata must preserve the focused action", panel.findViewWithTag<View>("library-detail-watchlist").hasFocus())
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-play").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/movie/test/test-secret/101.mp4" && samePlayer?.isPlaying == true }
                var beforeRecovery = ""; var resumePosition = 0L
                scenario.onActivity { activity ->
                    val control = PlaybackLoadControl(); val id = androidx.media3.exoplayer.analytics.PlayerId.UNSET
                    control.onPrepared(id)
                    fun parameters(bufferedUs: Long, rebuffering: Boolean, vod: Boolean): androidx.media3.exoplayer.LoadControl.Parameters {
                        val item = androidx.media3.common.MediaItem.Builder().setUri("https://example.test/movie.mp4").apply { if (vod) setTag(PlaybackLoadControl.VOD) }.build()
                        val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(60000000, true, false, false, null, item)
                        return androidx.media3.exoplayer.LoadControl.Parameters(id, timeline, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), 0, bufferedUs, 1f, true, rebuffering, androidx.media3.common.C.TIME_UNSET, androidx.media3.common.C.TIME_UNSET)
                    }
                    try {
                        assertTrue("Live channel startup stays fast", control.shouldStartPlayback(parameters(1500000, false, false)))
                        assertFalse("VOD must build its startup cushion", control.shouldStartPlayback(parameters(1500000, false, true)))
                        assertTrue(control.shouldStartPlayback(parameters(5000000, false, true)))
                        // After a stall a movie gathers 20 seconds, so a connection that only just keeps up pauses now and then
                        // instead of every few seconds.
                        assertFalse("VOD rebuffering builds a larger cushion", control.shouldStartPlayback(parameters(12000000, true, true)))
                        assertFalse(control.shouldStartPlayback(parameters(PlaybackLoadControl.VOD_REBUFFER_US - 1000000, true, true)))
                        assertTrue(control.shouldStartPlayback(parameters(PlaybackLoadControl.VOD_REBUFFER_US, true, true)))
                    } finally { control.onReleased(id) }
                    samePlayer!!.pause(); samePlayer!!.seekTo(samePlayer!!.duration / 3)
                }
                waitFor(scenario) { samePlayer?.playbackState == Player.STATE_READY && (samePlayer?.currentPosition ?: 0) > 0 }
                scenario.onActivity { activity ->
                    val progress = MainActivity::class.java.getDeclaredField("playbackProgress").apply { isAccessible = true }.get(activity) as PlaybackProgress
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    beforeRecovery = samePlayer!!.currentMediaItem!!.mediaId
                    progress.reset(); progress.stalled(token, android.os.SystemClock.elapsedRealtime() - 46000, samePlayer!!.currentPosition, null, true, true, bufferedMs = samePlayer!!.totalBufferedDuration)
                    (MainActivity::class.java.getDeclaredField("monitorPlayback").apply { isAccessible = true }.get(activity) as Runnable).run()
                    assertEquals("A paused episode must never reconnect", beforeRecovery, samePlayer!!.currentMediaItem!!.mediaId)
                    samePlayer!!.play()
                }
                waitFor(scenario) { samePlayer?.isPlaying == true }
                scenario.onActivity { activity ->
                    val progress = MainActivity::class.java.getDeclaredField("playbackProgress").apply { isAccessible = true }.get(activity) as PlaybackProgress
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    resumePosition = samePlayer!!.currentPosition
                    progress.reset(); progress.stalled(token, android.os.SystemClock.elapsedRealtime() - 46000, resumePosition, null, true, true, bufferedMs = samePlayer!!.totalBufferedDuration)
                    (MainActivity::class.java.getDeclaredField("monitorPlayback").apply { isAccessible = true }.get(activity) as Runnable).run()
                    assertEquals("Recovery must save the exact VOD position before stopping", resumePosition, MainActivity::class.java.getDeclaredField("demandPosition").apply { isAccessible = true }.getLong(activity))
                }
                waitFor(scenario) { samePlayer?.currentMediaItem?.mediaId != beforeRecovery && samePlayer?.isPlaying == true }
                scenario.onActivity { activity ->
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player)
                    assertEquals("VOD recovery must never tune a live channel", "/movie/test/test-secret/101.mp4", samePlayer!!.currentMediaItem!!.localConfiguration!!.uri.path)
                    assertEquals(resumePosition, MainActivity::class.java.getDeclaredField("demandPosition").apply { isAccessible = true }.getLong(activity))
                }
                scenario.onActivity {
                    assertTrue(it.window.decorView.findViewWithTag<View>("on-demand-controls").isShown)
                    it.window.decorView.findViewWithTag<View>("on-demand-pause").performClick()
                }
                waitFor(scenario) { samePlayer?.playWhenReady == false }
                scenario.onActivity { activity ->
                    val seek = activity.window.decorView.findViewWithTag<android.widget.SeekBar>("on-demand-seek")
                    assertTrue("Seekable movie exposes its native seek bar", seek.isEnabled); seek.requestFocus()
                }
                device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("on-demand-seek").hasFocus() && (samePlayer?.currentPosition ?: 0) > 0 }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("on-demand-restart").performClick() }
                waitFor(scenario) { samePlayer?.currentPosition == 0L && samePlayer?.playWhenReady == false }
                scenario.onActivity { samePlayer!!.repeatMode = Player.REPEAT_MODE_OFF; samePlayer!!.seekTo(samePlayer!!.duration - 20); samePlayer!!.play() }
                waitFor(scenario) { samePlayer?.playbackState == Player.STATE_ENDED }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("on-demand-pause").performClick() }
                waitFor(scenario) { samePlayer?.playbackState == Player.STATE_READY && samePlayer?.isPlaying == true }
                scenario.onActivity { samePlayer!!.repeatMode = Player.REPEAT_MODE_ONE }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("on-demand-library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true && samePlayer?.playWhenReady == false }
                // Reproduce a large category reduction without a quadratic UI-thread diff.
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<MediaBrowserPanel>("library-panel")
                    val entries = (1..25000).map { BrowserRow("bulk:$it", "Title $it") {} }
                    panel.submit(entries, entries.associate { it.key to if (it.key.substringAfter(':').toInt() % 2 == 0) "Science fiction" else "Drama" })
                }
                chooseLibraryCategory(scenario, 2)
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").adapter?.itemCount == 12500 }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").isShown }
                // Focus after a category pick is timing dependent on a slow runner; start from the grid explicitly.
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("library-items").requestFocus() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").hasFocus() }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-categories").hasFocus() }
                device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").hasFocus() }
                device.pressDPadDown()
                scenario.onActivity { assertNull(it.window.decorView.findViewWithTag<View>("library-live")) }
                // Back from a grid opened from the rows returns to the rows, then to the Library sections, then leaves Library.
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-shelves").isShown && (it.currentFocus?.tag as? String) == "library-shelf-more:1" }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:movie").hasFocus() }
                device.pressBack()
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-mode").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/live/test/test-secret/1.mp4" && channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick(); it.window.decorView.findViewWithTag<View>("library-tab:series").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:series:1")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:series:1").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:episode:201")?.isShown == true }
                scenario.onActivity { activity ->
                    val seasons = activity.window.decorView.findViewWithTag<RecyclerView>("library-categories")
                    val chosen = (0 until seasons.childCount).map { seasons.getChildAt(it) as TextView }.filter { it.isSelected }.map { it.text.toString() }
                    assertEquals("A series opens on a season, not every episode at once", listOf("Season 1"), chosen)
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-favorite").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.Button>("library-favorite").text.toString() == "✓ In Watchlist" && store.libraryFavorites(source).contains("series:1") }
                val beforeEpisodeRefresh = server.requests["/player_api.php"] ?: 0
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-refresh").apply { requestFocus(); performClick() } }
                waitFor(scenario) {
                    (server.requests["/player_api.php"] ?: 0) > beforeEpisodeRefresh &&
                        it.window.decorView.findViewWithTag<View>("library-loading").isShown == false &&
                        it.window.decorView.findViewWithTag<View>("library-item:episode:201")?.isShown == true
                }
                scenario.onActivity { assertTrue("Episode refresh must not move focus away from Refresh", it.window.decorView.findViewWithTag<View>("library-refresh").hasFocus()) }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "library.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:episode:201").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/series/test/test-secret/201.mp4" && samePlayer?.playbackState == Player.STATE_READY }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("on-demand-next").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/series/test/test-secret/202.mp4" && samePlayer?.playbackState == Player.STATE_READY }
                assertEquals("On-demand titles must not replace live channel history", "xc:1", store.last(source))
                closeDemandControls(scenario)
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:episode:201")?.isShown == true && samePlayer?.currentMediaItem == null }
                scenario.onActivity { activity ->
                    assertEquals("Stopping an episode leaves the primary player stopped", Player.STATE_IDLE, samePlayer!!.playbackState)
                    activity.window.decorView.findViewWithTag<View>("library-mark-watched").performClick()
                    activity.window.decorView.findViewWithTag<View>("library-tab:favorites").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:series:1")?.contentDescription?.toString()?.contains("All caught up") == true && store.libraryFavorites(source).watched == setOf("episode:201", "episode:202") }
                assertEquals(setOf("episode:201", "episode:202"), store.libraryFavorites(source).watched)
                scenario.onActivity { assertNull("Catch-up is no longer a Library tab", it.window.decorView.findViewWithTag<View>("library-tab:catchup")) }
                // The cursor is on the Watchlist titles: Back steps out to the side list first, then leaves Library.
                fun guideShown(): Boolean { var shown = false; scenario.onActivity { shown = channelList(it)?.isShown == true }; return shown }
                repeat(3) { if (!guideShown()) { device.pressBack(); Thread.sleep(700) } }
                waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-catchup").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:xc:1")?.isShown == true }
                scenario.onActivity {
                    assertFalse(it.window.decorView.findViewWithTag<View>("library-tab:movie").isShown)
                    assertTrue(it.window.decorView.findViewWithTag<View>("archive-channels").isShown)
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:xc:1").performClick() }
                waitFor(scenario) { device.hasObject(By.textContains("Archived fixture")) && !it.window.decorView.findViewWithTag<View>("library-loading").isShown }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("library-items").findViewHolderForAdapterPosition(0)!!.itemView.performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/timeshift/test/test-secret/60/") == true && samePlayer?.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED) && (samePlayer?.videoSize?.width ?: 0) > 0 }
                scenario.onActivity {
                    assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player)
                    val controls = it.window.decorView.findViewWithTag<ViewGroup>("on-demand-controls")
                    fun live(v: View): View? { if (v is android.widget.Button && v.text.toString() == "Live TV") return v; if (v is ViewGroup) for (i in 0 until v.childCount) live(v.getChildAt(i))?.let { return it }; return null }
                    live(controls)!!.performClick()
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/live/test/test-secret/1.mp4" && channelList(it)?.isShown == true }
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                val liveAttempt = playingPath(scenario)
                var liveMediaId = ""; scenario.onActivity { liveMediaId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-catchup")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-catchup").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                device.pressBack(); waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown && channelList(it)?.isShown == false }
                assertEquals(liveAttempt, playingPath(scenario))
                scenario.onActivity { assertEquals("Closing the Catch-up browser must preserve the live connection", liveMediaId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
                device.pressMenu(); setThemeControl(scenario, "Type", "Font", "Monospace")
                val selectedFont = AppFont.faceFor("Monospace", true)
                waitFor(scenario) { store.font == "Monospace" && it.window.decorView.findViewWithTag<TextView>("settings-page-title").typeface == selectedFont }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(selectedFont, it.window.decorView.findViewWithTag<android.widget.TextView>("settings-page-title").typeface) }
                leaveSettings(scenario)
                scenario.recreate(); waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                assertEquals("Monospace", store.font)
                scenario.onActivity { activity ->
                    val m3u = source.copy(id = "m3u-library-return", kind = "m3u")
                    val movie = Channel("fixture-film", "M3U film", "Movies", "${server.base}/channel1.mp4", media = true)
                    val controller = LibraryController(activity, store,
                        { title, action -> android.widget.Button(activity).apply { text = title; setOnClickListener { action() } } },
                        { m3u }, { Guide(listOf(movie), emptyMap()) }, { _, _ -> }, {})
                    try {
                        controller.open("movie")
                        val panel = controller.panel
                        panel.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
                        panel.layout(0, 0, 960, 540)
                        panel.findViewWithTag<View>("library-item:m3u:fixture-film").performClick()
                        assertTrue(panel.findViewWithTag<View>("library-details").visibility == View.VISIBLE)
                        controller.back()
                        assertEquals("Movies", panel.findViewWithTag<android.widget.TextView>("library-heading").text.toString())
                        assertEquals("Returning to an M3U movie catalog must retain its titles", 1, panel.findViewWithTag<RecyclerView>("library-items").adapter!!.itemCount)
                        assertEquals("M3U Back must not start an Xtream category request", View.GONE, panel.findViewWithTag<View>("library-loading").visibility)
                    } finally { controller.destroy() }
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun missingVodSegmentsKeepTheTitleWhileMissingEndpointsHideIt() {
        FixtureServer(missingStatus = 404, transientSegmentErrors = 20, transientSegmentStatus = 404).use { server ->
            val source = Source(name = "VOD errors", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val live = Channel("xc:1", "Live fixture", "News", "${server.base}/channel1.mp4")
            val movie = OnDemand("movie:101", "Segment fixture", "Movies", "${server.base}/live/test/test-secret/1.m3u8", playlistId = source.id)
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(live), emptyMap())); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                lateinit var repository: LibraryCatalog
                lateinit var snapshot: LibraryCatalog.Snapshot
                scenario.onActivity { activity ->
                    val library = MainActivity::class.java.getDeclaredField("library").apply { isAccessible = true }.get(activity) as LibraryController
                    repository = library.repository
                }
                snapshot = repository.Writer(source, "movie").apply { append(listOf(movie)) }.finish(emptyMap())
                fun play(item: OnDemand) = scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("playOnDemand", OnDemand::class.java, Long::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(activity, item, 0L)
                }
                fun waitForFailure() = waitFor(scenario) { activity ->
                    (MainActivity::class.java.getDeclaredField("retryPanel").apply { isAccessible = true }.get(activity) as View).isShown
                }
                play(movie)
                waitForFailure()
                assertTrue("The HLS child was requested", (server.requests["/segment.ts"] ?: 0) > 0)
                assertEquals("A missing HLS child must not remove a valid title from Library", listOf(movie.id), repository.query(snapshot, null, "").entries.map { it.id })

                // The same catalog entry is hidden only when its own media endpoint is missing.
                play(movie.copy(url = "${server.base}/live/test/test-secret/2.m3u8"))
                waitForFailure()
                assertTrue("A missing media endpoint is still hidden", repository.query(snapshot, null, "").entries.isEmpty())
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun continueWatchingPositionsAreEncryptedScopedAndClearedWhenFinished() {
        val source = Source(name = "Progress", kind = "xtream", url = "https://example.com", username = "secret-user", password = "secret-password")
        val item = OnDemand("movie:101", "Fixture movie", "Films", "https://example.com/movie/secret-user/secret-password/101.mp4")
        try {
            store.saveWatchProgress(source, item, 45000, 120000)
            val restored = SecureStore(context).watchProgress(source).single()
            assertEquals(45000, restored.position); assertEquals(item, restored.item)
            val bytes = File(context.filesDir, "watch-${source.id}-${source.providerKey().take(16)}.enc").readBytes().toString(Charsets.UTF_8)
            assertFalse(bytes.contains("secret-password")); assertFalse(bytes.contains("Fixture movie"))
            assertTrue(store.watchProgress(source.copy(id = "other-source")).isEmpty())
            store.bindProvider(source)
            val edited = source.copy(password = "other-account")
            assertTrue(store.bindProvider(edited)); assertTrue(store.watchProgress(edited).isEmpty())
            store.saveVideoInfo(source, mapOf("old" to VideoInfo(3840, 2160, 60f)))
            assertTrue("An old background writer cannot restore the edited account's old quality cache", store.videoInfo(edited).isEmpty())
            assertEquals("The old account's encrypted progress remains available to that account", 45000, store.watchProgress(source).single().position)
            if (store.bindProvider(source)) store.invalidateProviderFiles(source)

            store.saveWatchProgress(source, item, 120000, 120000, ended = true); assertTrue(store.watchProgress(source).isEmpty())
            store.cacheCatalog(source, "movie", listOf(item)); assertEquals(item, SecureStore(context).catalog(source, "movie")!!.second.single())
        } finally { store.removeCache(source) }
        assertFalse(File(context.filesDir, "watch-${source.id}.enc").exists()); assertFalse(File(context.filesDir, "library-${source.id}-movie.enc").exists())
    }

    @Test fun providerRedirectChainPreservesByteRangeAndEncryptedAddonSettings() {
        FixtureServer().use { server ->
            val source = ProviderDataSource { true }
            val spec = androidx.media3.datasource.DataSpec.Builder().setUri("${server.base}/redirect.mp4").setPosition(12).setLength(40).build()
            try {
                assertEquals(40, source.open(spec)); val bytes = ByteArray(40); var read = 0
                while (read < bytes.size) { val count = source.read(bytes, read, bytes.size - read); assertTrue(count > 0); read += count }
                val expected = instrumentation.context.assets.open("sample.mp4").use { it.readBytes() }.copyOfRange(12, 52)
                assertArrayEquals(expected, bytes); assertEquals(-1, source.read(ByteArray(1), 0, 1)); assertEquals("/channel1.mp4", source.uri!!.path)
                assertEquals(1, server.requests["/redirect.mp4"]); assertEquals(1, server.requests["/redirect-relative.mp4"])
            } finally { source.close() }
        }
        FixtureServer().use { server ->
            var panel: MediaBrowserPanel? = null; var cover: android.widget.ImageView? = null; var fallback: ((String) -> Unit)? = null
            instrumentation.runOnMainSync {
                val activityContext = android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_NoActionBar)
                panel = MediaBrowserPanel(activityContext, { text, action -> android.widget.Button(activityContext).apply { this.text = text; setOnClickListener { action() } } }, {}, {})
                panel!!.visibility = View.VISIBLE; panel!!.allowArtworkHttp(true)
                panel!!.submit(listOf(BrowserRow("missing", "Missing artwork", requestPoster = { fallback = it }) {}))
                panel!!.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY)); panel!!.layout(0, 0, 960, 540)
                cover = find<android.widget.ImageView>(panel!!.findViewWithTag<View>("library-item:missing"))!!
                assertNotNull(fallback); assertNull((cover!!.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap); fallback!!("${server.base}/logo.png")
            }
            val deadline = System.currentTimeMillis() + 7000
            var loaded = false
            while (!loaded && System.currentTimeMillis() < deadline) { instrumentation.runOnMainSync { loaded = (cover!!.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap != null }; Thread.sleep(50) }
            assertTrue("Metadata poster is applied to the visible card", loaded)
            instrumentation.runOnMainSync { panel!!.closeImages() }
        }
    }

    private fun fixtureLiveSports(activity: MainActivity) {
        activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(
            LiveEvent("mlb-fixture", setOf("mlb:NYY", "mlb:BOS"), now - 300000), LiveEvent("nfl-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000)), now, setOf("MLB", "NFL")) }
    }
    private fun fastRemote(key: Int, count: Int) {
        val down = android.os.SystemClock.uptimeMillis()
        repeat(count) { repeat ->
            instrumentation.sendKeySync(android.view.KeyEvent(down, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_DOWN, key, repeat))
            android.os.SystemClock.sleep(12)
        }
        instrumentation.sendKeySync(android.view.KeyEvent(down, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, key, 0))
    }
    @Test fun rapidCategoryScrollingKeepsHighlightAndHeldUpStopsAtFirstChannel() {
        FixtureServer().use { server ->
            val source = Source(name = "Held remote fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..120).map { Channel("held:$it", "Channel $it", "Category $it", "${server.base}/channel1.mp4") }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }; device.pressDPadLeft()
                fastRemote(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 80)
                waitFor(scenario) { activity ->
                    val list = activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories")
                    (list.findFocus() as? android.widget.TextView)?.text?.toString()?.removePrefix("Category ")?.toIntOrNull()?.let { it >= 20 } == true
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "held-categories.png"))
                device.pressDPadRight(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { activity ->
                    assertEquals("Highlighted categories update the guide without OK", 1, channelList(activity)!!.adapter!!.itemCount)
                    activity.window.decorView.findViewWithTag<View>("guide-all").performClick()
                }
                waitFor(scenario) { channelList(it)?.hasFocus() == true && channelList(it)?.adapter?.itemCount == 120 }
                fastRemote(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 50)
                fastRemote(android.view.KeyEvent.KEYCODE_DPAD_UP, 100)
                waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel != null }
                scenario.onActivity { channelList(it)!!.scrollToPosition(0) }
                waitFor(scenario) { channelList(it)?.findViewHolderForAdapterPosition(0) != null }
                scenario.onActivity { channelList(it)!!.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
                device.pressDPadUp(); waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "held:120" }
                device.pressDPadDown(); waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "held:1" }
                device.pressDPadLeft(); device.pressDPadLeft()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-mode").hasFocus() }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun startupUpdatePromptOffersInstallOrLaterWithoutInterruptingPlayback() {
        FixtureServer().use { server ->
            val source = Source(name = "Startup update fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            var updater: Updater? = null; val checks = java.util.concurrent.atomic.AtomicInteger()
            val update = Release("99.0.0", 999999, "https://github.com/mot1us/spare/releases/download/v99.0.0/spare.apk", "a".repeat(64))
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null; var media = ""
                waitFor(scenario) { player = find<PlayerView>(it.window.decorView)?.player; player?.repeatMode = Player.REPEAT_MODE_ONE; player?.isPlaying == true }
                scenario.onActivity { activity ->
                    media = player!!.currentMediaItem!!.mediaId
                    updater = Updater(activity, store, lookup = { checks.incrementAndGet(); update }, enabled = true) { }
                    updater!!.check(startup = true)
                }
                waitFor(scenario) { checks.get() == 1 }
                instrumentation.waitForIdleSync()
                assertFalse("A backgrounded update check must not open a dialog", device.hasObject(By.text("Install now")))
                scenario.onActivity { updater!!.resume() }
                assertTrue(device.wait(Until.hasObject(By.text("Install now")), 5000))
                scenario.onActivity { assertEquals(media, player!!.currentMediaItem!!.mediaId); assertTrue(player!!.isPlaying) }
                device.findObject(By.text("Later")).click()
                assertTrue(device.wait(Until.gone(By.text("Install now")), 5000))
                scenario.onActivity { updater!!.check(startup = true); updater!!.pause(); updater!!.resume() }
                instrumentation.waitForIdleSync(); assertEquals(1, checks.get())
                assertFalse("Later dismisses this launch's offer", device.hasObject(By.text("Install now")))
            } } finally { updater?.close(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun surroundSoundCanBeDecodedOnThisBoxAndTheReportSaysHowSoundLeavesIt() {
        FixtureServer(tsMode = true).use { server ->
            val source = Source(name = "Audio fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.decodeAudioOnBox = false
            fun report(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("playbackDiagnostics").apply { isAccessible = true }.invoke(activity) as String
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var attempt = ""
                scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressMenu(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown == true }
                activateSetting(scenario, "Surround sound")
                assertTrue(SecureStore(context).decodeAudioOnBox); assertTrue(AudioOutput.decodeOnBox)
                // The stream starts again so the choice applies at once, and keeps playing.
                waitFor(scenario) { a -> find<PlayerView>(a.window.decorView)!!.player!!.let { it.isPlaying && it.currentMediaItem!!.mediaId != attempt } }
                waitFor(scenario) { a -> val player = find<PlayerView>(a.window.decorView)!!.player as androidx.media3.exoplayer.ExoPlayer
                    player.audioFormat == null || report(a).contains("decoded on this box") }
                scenario.onActivity { a -> val player = find<PlayerView>(a.window.decorView)!!.player as androidx.media3.exoplayer.ExoPlayer
                    if (player.audioFormat != null) {
                        assertTrue(report(a), Regex("Audio · \\S.* · \\d+ channels · decoded on this box").containsMatchIn(report(a)))
                        // The report keeps the stream's sound, for reading after a movie has stopped.
                        assertTrue(Diagnostics.report(), Regex("Last sound: .* \\(playing\\).* · decoded on this box").containsMatchIn(Diagnostics.report()))
                    } }
                activateSetting(scenario, "Surround sound")
                assertFalse(SecureStore(context).decodeAudioOnBox); assertFalse(AudioOutput.decodeOnBox)
            } } finally { store.decodeAudioOnBox = false; AudioOutput.decodeOnBox = false; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun twoPaneSettingsKeepsFullscreenVideoAndLauncherUsesWordmarkBanner() {
        FixtureServer().use { server ->
            val source = Source(name = "Settings fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.smallPreview = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var samePlayer: Player? = null; var attempt = ""
                scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player; attempt = samePlayer!!.currentMediaItem!!.mediaId }
                device.pressMenu(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown == true }
                scenario.onActivity { activity ->
                    val nav = activity.window.decorView.findViewWithTag<View>("settings-navigation"); val content = activity.window.decorView.findViewWithTag<View>("settings-content")
                    val left = IntArray(2); val right = IntArray(2); nav.getLocationOnScreen(left); content.getLocationOnScreen(right)
                    assertTrue(left[0] + nav.width < right[0]); assertEquals(activity.window.decorView.width, find<PlayerView>(activity.window.decorView)!!.width)
                    listOf("Playback", "Theme", "Guide", "Categories").forEach { assertNotNull(activity.window.decorView.findViewWithTag<View>("settings-nav:$it")) }
                    listOf("Font", "Guide video", "Subtitles", "Time format").forEach { assertNull(activity.window.decorView.findViewWithTag<View>("settings-nav:$it")) }
                    activity.window.decorView.findViewWithTag<View>("settings-nav:Local channels").requestFocus()
                    activity.window.decorView.findViewWithTag<View>("settings-nav:Playback").requestFocus()
                    assertEquals("Rapid menu movement does not build skipped pages", "Playlists", activity.window.decorView.findViewWithTag<TextView>("settings-page-title").text.toString())
                    activity.window.decorView.findViewWithTag<View>("settings-nav:Theme").requestFocus()
                }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-mode:Presets")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("theme-preset:Classic").isShown)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("theme-tab:Look").isShown)
                    activity.window.decorView.findViewWithTag<View>("theme-preset:Nostalgia").performClick()
                    assertEquals("Nostalgia", store.themeStyle); assertEquals("Monospace", store.font)
                    activity.window.decorView.findViewWithTag<View>("theme-preset:Glass").performClick()
                }
                waitFor(scenario) { GuideTheme.style == "Glass" && GuideTheme.current.name == "Monochrome" }
                device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "theme-presets.png"))
                // Moving onto a preset previews it before OK applies it.
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("theme-preset:Synthwave").requestFocus() }
                device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "theme-preset-preview.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("theme-mode:Advanced").requestFocus() }
                device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-tab:Look")?.hasFocus() == true }
                device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-control:Palette")?.hasFocus() == true }
                var samePalette: View? = null; var samePreview: View? = null; var previousPreview: Bitmap? = null
                scenario.onActivity { activity ->
                    samePalette = activity.window.decorView.findViewWithTag<View>("theme-control:Palette")
                    samePreview = activity.window.decorView.findViewWithTag<View>("theme-preview")
                    assertTrue(samePreview!!.width > 0 && samePreview!!.height > 0)
                    previousPreview = Bitmap.createBitmap(samePreview!!.width, samePreview!!.height, Bitmap.Config.ARGB_8888).also { samePreview!!.draw(Canvas(it)) }
                }
                device.pressDPadRight()
                waitFor(scenario) { store.theme == "Ocean" && GuideTheme.current.name == "Ocean" && it.window.decorView.findViewWithTag<View>("theme-control:Palette")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    assertSame(samePalette, activity.window.decorView.findViewWithTag<View>("theme-control:Palette"))
                    assertSame(samePreview, activity.window.decorView.findViewWithTag<View>("theme-preview"))
                    val changed = Bitmap.createBitmap(samePreview!!.width, samePreview!!.height, Bitmap.Config.ARGB_8888).also { samePreview!!.draw(Canvas(it)) }
                    assertFalse("Palette changes immediately redraw the local preview", previousPreview!!.sameAs(changed))
                    previousPreview!!.recycle(); changed.recycle()
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId)
                }
                device.pressDPadUp(); device.pressDPadLeft(); device.pressDPadLeft(); device.pressDPadLeft()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel").isShown && it.window.decorView.findViewWithTag<View>("settings-nav:Theme").hasFocus() }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel").isShown && it.window.decorView.findViewWithTag<View>("settings").hasFocus() }
                device.pressDPadRight(); device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-mode:Presets")?.hasFocus() == true }
                device.pressDPadRight(); device.pressDPadDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-tab:Look")?.hasFocus() == true }
                device.pressDPadDown(); device.pressDPadLeft()
                waitFor(scenario) { store.theme == "Monochrome" && it.window.decorView.findViewWithTag<View>("theme-control:Palette")?.hasFocus() == true }
                device.pressDPadCenter(); waitFor(scenario) { store.theme == "Ocean" && it.window.decorView.findViewWithTag<View>("theme-control:Palette")?.hasFocus() == true }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "settings.png"))
                setThemeControl(scenario, "Look", "Style", "Flat"); waitFor(scenario) { store.themeStyle == "Flat" && GuideTheme.style == "Flat" }
                setThemeControl(scenario, "Look", "Palette", "Violet"); setThemeControl(scenario, "Look", "Style", "Outline")
                setThemeControl(scenario, "Look", "Corners", "12 dp"); setThemeControl(scenario, "Look", "Borders", "2 dp")
                activateSetting(scenario, "Theme")
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("theme-mode:Advanced").performClick()
                    activity.window.decorView.findViewWithTag<View>("theme-tab:Colors").performClick()
                    val accent = activity.window.decorView.findViewWithTag<EditText>("theme-control:Accent hex")
                    val background = activity.window.decorView.findViewWithTag<EditText>("theme-control:Background hex")
                    accent.requestFocus(); accent.setText("123456"); background.requestFocus(); background.setText("07131B")
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(background.windowToken, 0)
                }
                waitFor(scenario) { store.customAccent == 0xFF123456.toInt() && store.customBackground == 0x07131B && GuideTheme.current.accent == 0xFF123456.toInt() }
                setThemeControl(scenario, "Type", "Font", "Light"); setThemeControl(scenario, "Type", "Guide text size", "110%")
                setThemeControl(scenario, "Guide", "Row height", "36 dp"); setThemeControl(scenario, "Guide", "Row gap", "5 dp")
                setThemeControl(scenario, "Guide", "Opacity", "80%"); setThemeControl(scenario, "Guide", "Guide video", "Small preview")
                scenario.onActivity { activity ->
                    val saved = SecureStore(context)
                    assertEquals("Violet", saved.theme); assertEquals("Outline", saved.themeStyle); assertEquals("Light", saved.font)
                    assertEquals(0xFF123456.toInt(), saved.customAccent); assertEquals(0x07131B, saved.customBackground)
                    assertEquals(12, saved.cornerRadius); assertEquals(2, saved.borderWidth); assertEquals(110, saved.guideTextScale)
                    assertEquals(36, saved.guideRowHeight); assertEquals(5, saved.guideRowGap); assertEquals(80, saved.guideOpacity); assertTrue(saved.smallPreview)
                    assertEquals(AppFont.face(true), activity.window.decorView.findViewWithTag<TextView>("settings-page-title").typeface)
                    assertEquals(activity.window.decorView.width, find<PlayerView>(activity.window.decorView)!!.width)
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId)
                }
                activateSetting(scenario, "Theme")
                scenario.onActivity { assertNull(it.window.decorView.findViewWithTag<View>("theme-control:Nostalgia")) }
                setThemeControl(scenario, "Look", "Style", "Nostalgia")
                waitFor(scenario) { store.themeStyle == "Nostalgia" }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId); assertTrue(it.window.decorView.findViewWithTag<View>("theme-control:Style").hasFocus()) }
                leaveSettings(scenario); device.pressBack()
                waitFor(scenario) { channelList(it)?.isShown == true }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "nostalgia.png"))
                device.pressMenu()
                activateSetting(scenario, "Frame-rate matching"); activateSetting(scenario, "Off")
                assertFalse(SecureStore(context).frameRateMatching)
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId); assertTrue(it.window.decorView.findViewWithTag<View>("settings-panel").isShown); assertEquals(0, it.window.attributes.preferredDisplayModeId) }
                activateSetting(scenario, "On"); assertTrue(SecureStore(context).frameRateMatching)
                scenario.onActivity { activity ->
                    val matching = MainActivity::class.java.getDeclaredField("frameRates").apply { isAccessible = true }.get(activity) as FrameRates
                    val saved = matching.requestedRate
                    matching.request(60f); assertEquals(60f, matching.requestedRate, .001f)
                    matching.request(59.94f); assertEquals("Live TV must not discard fractional display requests", 59.94f, matching.requestedRate, .001f)
                    if (saved > 0) matching.request(saved)
                    val diagnostic = MainActivity::class.java.getDeclaredMethod("playbackDiagnostics").apply { isAccessible = true }.invoke(activity) as String
                    assertTrue(diagnostic.contains("dropped frames this playback attempt")); assertTrue(diagnostic.contains("Hz · Requested"))
                    assertTrue("The decoder in use is named: $diagnostic", Regex("Decoder \\S+ · (hardware|SOFTWARE|type unknown)").containsMatchIn(diagnostic))
                    assertTrue("Tunneled playback is reported: $diagnostic", diagnostic.contains("tunneled"))
                    android.util.Log.i("SpareTunnel", diagnostic.lines().first { "Decoder" in it && "tunneled" in it })
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player)
                    assertEquals("Matching corrections must preserve the playing connection", attempt, samePlayer!!.currentMediaItem!!.mediaId)
                }
                activateSetting(scenario, "Subtitles"); activateSetting(scenario, "Subtitle appearance")
                activateSetting(scenario, "Medium"); activateSetting(scenario, "Shadow")
                waitFor(scenario) { store.subtitleSize == "Medium" && store.subtitleStyle == "Shadow" }
                scenario.onActivity { activity ->
                    val info = activity.packageManager.getActivityInfo(android.content.ComponentName(activity, MainActivity::class.java), 0)
                    assertEquals(R.drawable.tv_banner, info.banner); assertEquals(R.drawable.ic_spare, info.icon)
                    val banner = info.loadBanner(activity.packageManager)!!; val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
                    banner.setBounds(0, 0, 960, 540); banner.draw(Canvas(bitmap))
                    for (x in 0 until bitmap.width) { assertEquals(Color.rgb(11, 16, 24), bitmap.getPixel(x, 0)); assertEquals(Color.rgb(11, 16, 24), bitmap.getPixel(x, bitmap.height - 1)) }
                    for (y in 0 until bitmap.height) { assertEquals(Color.rgb(11, 16, 24), bitmap.getPixel(0, y)); assertEquals(Color.rgb(11, 16, 24), bitmap.getPixel(bitmap.width - 1, y)) }
                    File(context.getExternalFilesDir(null), "launcher-banner.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                leaveSettings(scenario); scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    private fun importVodForProgramme(scenario: ActivityScenario<MainActivity>, source: Source, title: String, count: Int = 1) {
        val target = VodStart.request(Programme("fixture", title, 1, 2))!!
        openTypedSearch(scenario, target.query)
        waitFor(scenario) { activity ->
            val library = MainActivity::class.java.getDeclaredField("library").apply { isAccessible = true }.get(activity) as LibraryController
            library.repository.matches(source, target).size >= count
        }
        scenario.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("clearGuideSearch").apply { isAccessible = true }.invoke(activity)
            MainActivity::class.java.getDeclaredMethod("applyFilter").apply { isAccessible = true }.invoke(activity)
            MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
        }
    }

    private fun activateSetting(scenario: ActivityScenario<MainActivity>, label: String) {
        val parent = when (label) {
            "Frame-rate matching", "Surround sound", "Audio track", "Subtitles", "Stream format", "Playback diagnostics", "Temporarily hidden channels" -> "Playback"
            "Navigation dock", "Time format", "Switch categories on highlight", "Open channels full screen", "Loop guide channels" -> "Guide"
            "Category order" -> "Categories"
            else -> null
        }
        waitFor(scenario) { activity ->
            val panel = activity.window.decorView.findViewWithTag<View>("settings-panel")
            if (panel?.isShown != true) false else {
                val control = panel.findViewWithTag<View>("settings-nav:$label") ?: panel.findViewWithTag<View>("settings-option:$label")
                if (control != null) { control.requestFocus(); control.performClick(); true }
                else { parent?.let { panel.findViewWithTag<View>("settings-nav:$it")?.performClick() }; false }
            }
        }
    }
    private fun setThemeControl(scenario: ActivityScenario<MainActivity>, tab: String, title: String, value: String) {
        activateSetting(scenario, "Theme")
        scenario.onActivity { activity ->
            activity.window.decorView.findViewWithTag<View>("theme-mode:Advanced").performClick()
            activity.window.decorView.findViewWithTag<View>("theme-tab:$tab").apply { requestFocus(); performClick() }
        }
        scenario.onActivity { activity ->
            val control = activity.window.decorView.findViewWithTag<View>("theme-control:$title")
            control.requestFocus()
            var found = false
            repeat(32) {
                if (control.contentDescription.toString().startsWith("$title, $value.")) found = true
                if (!found) control.performClick()
            }
            assertTrue("Theme control $title must offer $value", found)
            assertTrue("Adjusting a theme row keeps remote focus", control.hasFocus())
        }
    }
    private fun pollSportsFixture(activity: MainActivity) {
        assertNull("Sports updates automatically without a Refresh control", activity.window.decorView.findViewWithTag<View>("sports-refresh"))
        MainActivity::class.java.getDeclaredField("liveSportsAttempt").apply { isAccessible = true }.setLong(activity, 0)
        MainActivity::class.java.getDeclaredMethod("checkLiveSports", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
        MainActivity::class.java.getDeclaredMethod("scheduleSports").apply { isAccessible = true }.invoke(activity)
    }
    private fun leaveSettings(scenario: ActivityScenario<MainActivity>) {
        repeat(5) {
            var open = false; scenario.onActivity { activity -> open = activity.window.decorView.findViewWithTag<View>("settings-panel")?.isShown == true }
            if (open) { device.pressBack(); instrumentation.waitForIdleSync() }
        }
        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown != true }
    }

    private fun activateDialogButton(scenario: ActivityScenario<MainActivity>, id: Int) {
        // TV buttons may focus on first touch or click immediately when already focused.
        // Set native focus directly and send exactly one remote activation instead of mixing both inputs.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            waitFor(scenario) {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }
                val button = root?.findViewById<android.widget.Button>(id)
                button?.isShown == true && button.requestFocus() && button.hasFocus()
            }
            device.pressDPadCenter(); instrumentation.waitForIdleSync()
        } else fail("The isolated automated TV suite requires API 29 or newer")
    }

    private fun holdBack() { device.executeShellCommand("input dpad keyevent --duration ${ViewConfiguration.getLongPressTimeout() + 500} 4"); instrumentation.waitForIdleSync() }
    private fun repeatTransport(input: ByteArray, cycles: Int): ByteArray {
        val result = ByteArray(input.size * cycles); val counters = mutableMapOf<Int, Int>()
        fun timestamp(bytes: ByteArray, offset: Int, delta: Long) {
            val a = (0..4).map { bytes[offset + it].toInt() and 255 }
            val pts = ((a[0].toLong() shr 1 and 7) shl 30) or (a[1].toLong() shl 22) or ((a[2].toLong() shr 1) shl 15) or (a[3].toLong() shl 7) or (a[4].toLong() shr 1)
            val shifted = (pts + delta) and 0x1ffffffffL
            bytes[offset] = ((a[0] and 0xf1) or (((shifted shr 30).toInt() and 7) shl 1)).toByte()
            bytes[offset + 1] = (shifted shr 22).toByte(); bytes[offset + 2] = (((shifted shr 15).toInt() and 127) shl 1 or 1).toByte()
            bytes[offset + 3] = (shifted shr 7).toByte(); bytes[offset + 4] = ((shifted.toInt() and 127) shl 1 or 1).toByte()
        }
        repeat(cycles) { cycle ->
            val bytes = input.copyOf(); val delta = cycle * 225000L
            for (offset in bytes.indices step 188) {
                if (offset + 188 > bytes.size) break
                val pid = ((bytes[offset + 1].toInt() and 31) shl 8) or (bytes[offset + 2].toInt() and 255)
                val flags = bytes[offset + 3].toInt() and 255
                if (flags and 16 != 0) { val count = counters[pid] ?: 0; bytes[offset + 3] = ((flags and 240) or count).toByte(); counters[pid] = (count + 1) % 16 }
                var payload = offset + 4
                if (flags and 32 != 0) {
                    val length = bytes[payload].toInt() and 255
                    if (length >= 7 && bytes[payload + 1].toInt() and 16 != 0) {
                        val start = payload + 2
                        var base = 0L; for (index in 0..3) base = (base shl 8) or (bytes[start + index].toLong() and 255)
                        base = (base shl 1) or (bytes[start + 4].toLong() ushr 7 and 1); base = (base + delta) and 0x1ffffffffL
                        for (index in 0..3) bytes[start + index] = (base shr (25 - index * 8)).toByte()
                        bytes[start + 4] = ((bytes[start + 4].toInt() and 127) or ((base.toInt() and 1) shl 7)).toByte()
                    }
                    payload += length + 1
                }
                if (bytes[offset + 1].toInt() and 64 != 0 && payload + 14 < offset + 188 && bytes[payload] == 0.toByte() && bytes[payload + 1] == 0.toByte() && bytes[payload + 2] == 1.toByte()) {
                    val ptsFlags = bytes[payload + 7].toInt() and 192
                    if (ptsFlags and 128 != 0) timestamp(bytes, payload + 9, delta)
                    if (ptsFlags == 192 && payload + 19 < offset + 188) timestamp(bytes, payload + 14, delta)
                }
            }
            bytes.copyInto(result, cycle * input.size)
        }
        return result
    }
    @Test fun enabledPlaylistsShareSearchAndSportsWithoutMixingProviderState() {
        FixtureServer().use { first -> FixtureServer().use { second -> FixtureServer().use { disabled ->
            val a = Source(name = "Provider A", kind = "xtream", url = first.base, username = "test", password = "test-secret", allowHttp = true)
            val b = Source(name = "Provider B", kind = "xtream", url = second.base, username = "test", password = "test-secret", allowHttp = true, maxStreams = 1)
            val c = Source(name = "Disabled provider", kind = "m3u", url = "${disabled.base}/playlist.m3u", allowHttp = true, enabled = false)
            val ca = Channel("xc:1", "KING 5 HD", "USA News", "${first.base}/channel1.mp4", reportedQuality = VideoInfo(1280, 720, 30f))
            val cb = ca.copy(name = "KING 5 FHD", group = "NFL Game Pass", url = "${second.base}/channel1.mp4", reportedQuality = VideoInfo(1920, 1080, 60f))
            val backup = cb.copy(id = "xc:2", name = "Backup feed", url = "${second.base}/channel2.mp4", reportedQuality = VideoInfo(1280, 720, 30f))
            val now = System.currentTimeMillis()
            val listing = Programme("xc:1", "NFL Football: Seattle Seahawks at San Francisco 49ers", now - 60000, now + 3 * 3600000)
            store.saveSources(listOf(a, b, c)); store.active = a.id; store.catalogRoot = a.id; store.recentFeeds = emptyList()
            store.cache(a, Guide(listOf(ca), mapOf(ca.id to listOf(listing))))
            store.cache(b, Guide(listOf(cb, backup), mapOf(cb.id to listOf(listing), backup.id to listOf(listing.copy(channel = backup.id)))))
            store.markPlaylistRefreshed(a); store.markPlaylistRefreshed(b); store.toggleFavorite(a, ca)
            val movieA = OnDemand("movie:42", "Orbit", "1", "${first.base}/movie.mp4", year = "2025")
            val movieB = movieA.copy(url = "${second.base}/movie.mp4")
            val catalog = LibraryCatalog(store)
            listOf(a to movieA, b to movieB).forEach { (provider, movie) -> catalog.Writer(provider, "movie").apply { append(listOf(movie)); finish(mapOf("1" to "Movies")) } }
            catalog.close()
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var playing: Player? = null; var ownedB: Channel? = null
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    val p = find<PlayerView>(activity.window.decorView)?.player
                    p?.repeatMode = Player.REPEAT_MODE_ONE
                    if (guide.channels.size == 3 && p?.isPlaying == true) { playing = p; ownedB = guide.channels.firstOrNull { it.playlistId == b.id && it.nativeId == "xc:1" }; true } else false
                }
                scenario.onActivity { activity ->
                    assertNotNull(ownedB); assertNotEquals(ca.id, ownedB!!.id)
                    assertEquals(first.base.substringAfterLast(':'), playing!!.currentMediaItem!!.localConfiguration!!.uri.port.toString())
                    val denied = b.copy(allowHttp = false)
                    MainActivity::class.java.getDeclaredMethod("configurePlaylists", List::class.java).apply { isAccessible = true }.invoke(activity, listOf(a, denied, c))
                    val attempt = playing!!.currentMediaItem!!.mediaId
                    MainActivity::class.java.getDeclaredMethod("tune", Channel::class.java).apply { isAccessible = true }.invoke(activity, ownedB)
                    assertEquals("Another provider's HTTP permission cannot authorize this feed", attempt, playing!!.currentMediaItem!!.mediaId)
                    MainActivity::class.java.getDeclaredMethod("configurePlaylists", List::class.java).apply { isAccessible = true }.invoke(activity, listOf(a, b, c))
                    MainActivity::class.java.getDeclaredMethod("toggleFavorite", Channel::class.java).apply { isAccessible = true }.invoke(activity, ownedB)
                    assertEquals(setOf("xc:1"), store.favorites(a)); assertEquals(setOf("xc:1"), store.favorites(b))
                    MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playlist:${b.id}")?.isShown == true }
                scenario.onActivity { activity ->
                    val attempt = playing!!.currentMediaItem!!.mediaId
                    activity.window.decorView.findViewWithTag<View>("playlist:${b.id}").performClick()
                    assertFalse(store.playlistCollapsed(b.id))
                    assertEquals("Expanding another playlist does not restart playback", attempt, playing!!.currentMediaItem!!.mediaId)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playlist-group:${b.id}:NFL Game Pass") != null }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("playlist-group:${b.id}:NFL Game Pass").performClick()
                    assertTrue((channelList(activity)!!.adapter as GuideAdapter).channels.all { it.playlistId == b.id })
                    val scores = LiveSportsSnapshot(listOf(LiveEvent("multi-game", setOf("nfl:SEA", "nfl:SF"), now - 60000, league = "NFL")), System.currentTimeMillis(), setOf("NFL"))
                    activity.liveSportsLoader = { scores.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                    val game = SportsSchedule.games(scores, Guide.EMPTY, "today", emptySet(), System.currentTimeMillis(), matchChannels = false).single()
                    MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, game, true)
                }
                waitFor(scenario) { playing!!.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') && playing!!.isPlaying }
                device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
                scenario.onActivity { activity ->
                    assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player)
                    assertFalse("Watch best starts the other provider full screen", channelList(activity)?.isShown == true)
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    val secondB = guide.channels.first { it.playlistId == b.id && it.nativeId == "xc:2" }
                    MainActivity::class.java.getDeclaredMethod("startMultiview", Channel::class.java).apply { isAccessible = true }.invoke(activity, secondB)
                    val views = MainActivity::class.java.getDeclaredField("multiview").apply { isAccessible = true }.get(activity) as Multiview
                    assertEquals("Provider B's configured connection limit is respected", 1, views.count)
                    MainActivity::class.java.getDeclaredMethod("startMultiview", Channel::class.java).apply { isAccessible = true }.invoke(activity, ca)
                    assertEquals("Another provider can supply the second view", 2, views.count)
                    views.close()
                    openTypedSearch(activity, "KING 5")
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:xc:1") != null && it.window.decorView.findViewWithTag<View>("search-result:live:${ownedB!!.id}") != null }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("search-result:live:xc:1").contentDescription.contains(a.name))
                    assertTrue(activity.window.decorView.findViewWithTag<View>("search-result:live:${ownedB!!.id}").contentDescription.contains(b.name))
                    openTypedSearch(activity, "Orbit")
                }
                // The same movie on both playlists is one card, led by the active playlist; the other's copy is a version on its page.
                waitFor(scenario) { activity ->
                    val library = MainActivity::class.java.getDeclaredField("library").apply { isAccessible = true }.get(activity) as LibraryController
                    activity.window.decorView.findViewWithTag<View>("search-result:movie:42") != null && library.repository.memory(b, "movie")?.complete == true
                }
                Thread.sleep(1000)
                scenario.onActivity { assertNull("One card for the movie on both playlists", it.window.decorView.findViewWithTag<View>("search-result:${b.id}:movie:42")) }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-result:movie:42").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("library-version:1")?.text?.toString()?.endsWith("· ${b.name}") == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-version:1").performClick() }
                waitFor(scenario) { activity ->
                    val item = MainActivity::class.java.getDeclaredField("onDemand").apply { isAccessible = true }.get(activity) as? OnDemand
                    item?.playlistId == b.id && playing!!.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') && playing!!.isPlaying
                }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("returnToLive").apply { isAccessible = true }.invoke(activity)
                    MainActivity::class.java.getDeclaredMethod("setPlaylistEnabled", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, b, false)
                }
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.channels.size == 1 && playing!!.currentMediaItem?.localConfiguration?.uri?.port.toString() == first.base.substringAfterLast(':') && playing!!.isPlaying
                }
                assertEquals("Disabling a playlist keeps its own favorites", setOf("xc:1"), store.favorites(b))
                assertTrue("Disabled sources are not fetched", disabled.requests.isEmpty())
                scenario.onActivity { activity ->
                    val toggle = MainActivity::class.java.getDeclaredMethod("setPlaylistEnabled", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    toggle.invoke(activity, a, false)
                    assertTrue((MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.isEmpty())
                    toggle.invoke(activity, a, true)
                }
                waitFor(scenario) { (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(it) as Guide).channels.size == 1 && playing!!.isPlaying }
                scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("setPlaylistEnabled", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, b, true) }
                waitFor(scenario) { activity -> (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.size == 3 }
                var base = Guide.EMPTY
                scenario.onActivity { base = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(it) as Guide }
                val archivedAt = System.currentTimeMillis() - 3600000
                val archives = (1000..12999).map { Channel("xc:$it", "Station $it", "Other", "${first.base}/live/test/test-secret/$it.ts", catchupDays = 2, catchupMode = "xc") }
                val history = archives.associate { it.id to listOf(Programme(it.id, "Unrelated film", archivedAt, archivedAt + 1800000, "Local weather and community report. ".repeat(16))) }
                val complete = LiveSportsSnapshot((1..40).map { LiveEvent("final-$it", setOf("nfl:SEA", "nfl:SF"), archivedAt, "post", league = "NFL") }, System.currentTimeMillis(), setOf("NFL"))
                val openedAt = android.os.SystemClock.elapsedRealtime(); var attempt = ""
                scenario.onActivity { activity ->
                    attempt = playing!!.currentMediaItem!!.mediaId
                    MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, Guide(base.channels + archives, base.programmes + history))
                    activity.liveSportsLoader = { complete.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, complete)
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                    // Opening Sports intentionally selects Today. Apply the historical fixture date afterward.
                    val day = Instant.ofEpochMilli(archivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.set(activity, "date:$day")
                    MainActivity::class.java.getDeclaredField("liveSportsDay").apply { isAccessible = true }.set(activity, day)
                }
                waitFor(scenario) { (it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount ?: 0) >= 40 }
                assertTrue("Fixtures must appear before scanning forty games across a large archive", android.os.SystemClock.elapsedRealtime() - openedAt < 5000)
                scenario.onActivity { activity -> assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertEquals("Opening Sports keeps playback on its existing attempt", attempt, playing!!.currentMediaItem!!.mediaId) }
            } } finally { store.saveSources(emptyList()); listOf(a, b, c).forEach(store::removeCache); store.catalogRoot = ""; store.recentFeeds = emptyList() }
        } } }
    }

    @Test fun redZonePublishesNamesBeforeGuideAndPromotesFhdFiftyWithoutMovingFocus() {
        FixtureServer().use { first -> FixtureServer().use { second ->
            val a = Source(name = "RedZone primary", kind = "m3u", url = "${first.base}/playlist.m3u", allowHttp = true)
            val b = Source(name = "RedZone secondary", kind = "m3u", url = "${second.base}/playlist.m3u", allowHttp = true)
            val low = Channel("redzone", "NFL RedZone HD", "NFL", "${first.base}/channel1.mp4", reportedQuality = VideoInfo(1280, 720, 60f))
            val high = Channel("sky", "Sky Sports Mix FHD", "UK Sports", "${second.base}/channel1.mp4", reportedQuality = VideoInfo(1920, 1080, 50f))
            val now = System.currentTimeMillis()
            store.saveSources(listOf(a, b)); store.active = a.id; store.catalogRoot = a.id
            store.cache(a, Guide(listOf(low), emptyMap())); store.cache(b, Guide(listOf(high), mapOf(high.id to listOf(Programme(high.id, "NFL RedZone", now - 1000, now + 3600000)))))
            listOf(a, b).forEach(store::markPlaylistRefreshed)
            val finish = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var samePlayer: Player? = null
                waitFor(scenario) { activity -> val p = find<PlayerView>(activity.window.decorView)?.player; p?.repeatMode = Player.REPEAT_MODE_ONE; samePlayer = p; p?.isPlaying == true && (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.size == 2 }
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { LiveSportsSnapshot(emptyList(), System.currentTimeMillis(), setOf("NFL")) }
                    activity.redZoneLoader = { snapshot, profile, quality, at -> finish.await(6, java.util.concurrent.TimeUnit.SECONDS); SportsDestinations.redZone(snapshot, profile, quality, at) }
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-redzone").performLongClick(); it.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:redzone")?.isShown == true }
                scenario.onActivity { activity ->
                    assertTrue("Named results are usable before guide discovery completes", finish.count > 0)
                    activity.window.decorView.findViewWithTag<View>("sports-stream:redzone").requestFocus()
                }
                finish.countDown()
                val highId = "playlist:${b.id}:sky"
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:$highId")?.contentDescription.toString().contains("50 fps") && it.window.decorView.findViewWithTag<TextView>("sports-status").text.toString().contains("2 RedZone channels") }
                scenario.onActivity { activity ->
                    assertTrue("Refinement preserves the selected row", activity.window.decorView.findViewWithTag<View>("sports-stream:redzone").hasFocus())
                    val rows = activity.window.decorView.findViewWithTag<RecyclerView>("sports-streams")
                    assertEquals("Higher resolution beats 60 FPS at a lower resolution", "sports-stream:$highId", rows.getChildViewHolder(rows.getChildAt(0)).itemView.tag)
                    activity.window.decorView.findViewWithTag<View>("sports-stream:$highId").performClick()
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') } == true }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player) }
            } } finally { finish.countDown(); store.saveSources(emptyList()); listOf(a, b).forEach(store::removeCache); store.catalogRoot = ""; store.recentFeeds = emptyList() }
        } }
    }

    @Test fun confirmedSportsCardsPlayFromSessionCacheWithoutWaitingForAnotherSearch() {
        FixtureServer().use { server ->
            val source = Source(name = "Sports session cache", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val now = System.currentTimeMillis()
            val news = Channel("xc:1", "News", "News", "${server.base}/channel1.mp4")
            val feed = Channel("xc:2", "Game feed 4K", "NFL", "${server.base}/channel2.mp4", reportedQuality = VideoInfo(3840, 2160, 59.94f))
            val listing = Programme(feed.id, "NFL: Seahawks vs 49ers", now - 1800000, now + 7200000)
            val scores = LiveSportsSnapshot(listOf(LiveEvent("session-cache", setOf("nfl:SEA", "nfl:SF"), now - 1200000, league = "NFL")), now, setOf("NFL"))
            store.recentFeeds = emptyList(); store.saveSources(listOf(source)); store.active = source.id; store.sportsAutoPlay = true; store.performanceOverlay = false
            store.cache(source, Guide(listOf(news, feed), mapOf(news.id to listOf(listing.copy(channel = news.id, title = "World News")), feed.id to listOf(listing)))); store.markPlaylistRefreshed(source)
            val release = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { scores.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:session-cache")?.text?.contains(feed.name) == true }
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val guide = field.get(activity) as Guide
                    val cache = MainActivity::class.java.getDeclaredField("sportsStreamCache").apply { isAccessible = true }.get(activity) as SportsStreamCache
                    val generation = MainActivity::class.java.getDeclaredField("generation").apply { isAccessible = true }.getInt(activity)
                    val game = SportsSchedule.games(scores, Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
                    val token = cache.use(source.id, generation, guide, emptySet())
                    val match = cache.get(token, game, now)!!
                    assertTrue(cache.put(token, match, now - 600000))
                    field.set(activity, guide.copy(programmes = guide.programmes + ("unrelated" to listOf(listing.copy(title = "Headlines")))))
                    val worker = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    worker.submit { release.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                    activity.window.decorView.findViewWithTag<View>(game.key).performClick()
                    assertFalse("A confirmed card opens playback in the same frame, without the search worker", activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                    assertEquals("/channel2.mp4", find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.localConfiguration!!.uri.path)
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:session-cache")?.text?.contains(feed.name) == true }
                scenario.onActivity { activity ->
                    val journal = MainActivity::class.java.getDeclaredField("playbackJournal").apply { isAccessible = true }.get(activity) as PlaybackJournal
                    assertFalse("No Sports trace is recorded while diagnostics are off", journal.report("fixture").contains("Sports #"))
                    store.performanceOverlay = true
                    MainActivity::class.java.getDeclaredMethod("syncPerformanceOverlay").apply { isAccessible = true }.invoke(activity)
                    activity.window.decorView.findViewWithTag<View>("NFL:session-cache").performClick()
                    assertFalse("Returning to Sports and selecting again reuses the same discovered game", activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("performance-overlay")?.text?.contains("first frame") == true }
                scenario.onActivity { activity ->
                    val summary = activity.window.decorView.findViewWithTag<TextView>("performance-overlay").text.toString()
                    assertTrue(summary, summary.contains("peek hit"))
                    assertTrue(summary, summary.contains("sent 0 / completed 0"))
                    assertTrue(summary, summary.contains("auto→frame"))
                    println("Sports trace warm fixture: " + summary.substringAfter("Sports #").replace("\n", " | "))
                }
                assertTrue("Known guide coverage needs no provider re-confirmation", server.requestedEpgTargets.isEmpty())
            } } finally { release.countDown(); store.performanceOverlay = false; store.sportsAutoPlay = false; store.recentFeeds = emptyList(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sustainedSportsBufferingUsesAnotherMatchedProviderOnTheSamePlayer() {
        FixtureServer(stalled = true, stallSeconds = 30).use { first -> FixtureServer().use { second ->
            val a = Source(name = "Primary game", kind = "m3u", url = "${first.base}/playlist.m3u", allowHttp = true)
            val b = Source(name = "Backup game", kind = "m3u", url = "${second.base}/playlist.m3u", allowHttp = true)
            val normal = Channel("normal", "News", "News", "${first.base}/channel2.mp4")
            val stalled = Channel("stall", "NFL main 4K", "NFL", "${first.base}/channel1.ts", reportedQuality = VideoInfo(3840, 2160, 60f))
            val backup = Channel("backup", "NFL backup FHD", "NFL", "${second.base}/channel1.mp4", reportedQuality = VideoInfo(1920, 1080, 50f))
            val now = System.currentTimeMillis(); val listing = Programme(stalled.id, "NFL: Seahawks vs 49ers", now - 1000, now + 3600000)
            store.saveSources(listOf(a, b)); store.active = a.id; store.catalogRoot = a.id; store.recentFeeds = emptyList()
            store.cache(a, Guide(listOf(normal, stalled), mapOf(stalled.id to listOf(listing))))
            store.cache(b, Guide(listOf(backup), mapOf(backup.id to listOf(listing.copy(channel = backup.id)))))
            listOf(a, b).forEach(store::markPlaylistRefreshed); store.sportsAutoFailover = true; store.sportsFailoverSeconds = 10
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var samePlayer: Player? = null
                waitFor(scenario) { activity -> val p = find<PlayerView>(activity.window.decorView)?.player; p?.repeatMode = Player.REPEAT_MODE_ONE; samePlayer = p; p?.isPlaying == true && (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.size == 3 }
                scenario.onActivity { activity ->
                    val snapshot = LiveSportsSnapshot(listOf(LiveEvent("recovery", setOf("nfl:SEA", "nfl:SF"), now - 1000, league = "NFL")), System.currentTimeMillis(), setOf("NFL"))
                    activity.liveSportsLoader = { snapshot.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, snapshot)
                    MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("NFL:recovery")?.isShown == true }
                scenario.onActivity { activity -> store.sportsAutoPlay = true; activity.window.decorView.findViewWithTag<View>("NFL:recovery").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { it.currentMediaItem?.localConfiguration?.uri?.path == "/channel1.ts" && it.isPlaying } == true }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') } == true }
                scenario.onActivity { assertSame("Recovery reuses one player", samePlayer, find<PlayerView>(it.window.decorView)!!.player) }
            } } finally { store.sportsAutoPlay = false; store.sportsAutoFailover = false; first.resumeStream.countDown(); store.saveSources(emptyList()); listOf(a, b).forEach(store::removeCache); store.catalogRoot = ""; store.recentFeeds = emptyList() }
        } }
    }

    @Test fun pinnedScoreStripSharesLiveScoresPreservesFocusAndCancelsPendingTunes() {
        store.delete("game-reminders.enc")
        FixtureServer().use { server ->
            val source = Source(name = "Score strip fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis()
            val channels = (1..2).map { Channel("score$it", "Sports feed $it", "Sports", "${server.base}/channel$it.mp4") }
            val one = LiveEvent("one", setOf("nfl:SEA", "nfl:SF"), now - 1000, scores = mapOf("nfl:SEA" to "7", "nfl:SF" to "3"), league = "NFL")
            val two = LiveEvent("two", setOf("nfl:BUF", "nfl:KC"), now - 1000, scores = mapOf("nfl:BUF" to "10", "nfl:KC" to "7"), league = "NFL")
            var snapshot = LiveSportsSnapshot(listOf(one, two, one.copy(id = "final", state = "post", start = maxOf(now - 60000, java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())), two.copy(id = "upcoming", state = "pre", start = now + 3600000)), now, setOf("NFL"))
            val programmes = mapOf(channels[0].id to listOf(Programme(channels[0].id, "NFL: Seahawks vs 49ers", now - 1000, now + 3600000)), channels[1].id to listOf(Programme(channels[1].id, "NFL: Bills vs Chiefs", now - 1000, now + 3600000)))
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null; var original = ""; val polls = java.util.concurrent.atomic.AtomicInteger()
                waitFor(scenario) { activity -> val p = find<PlayerView>(activity.window.decorView)?.player; p?.repeatMode = Player.REPEAT_MODE_ONE; player = p; p?.isPlaying == true }
                scenario.onActivity { activity ->
                    original = player!!.currentMediaItem!!.mediaId
                    activity.liveSportsLoader = { polls.incrementAndGet(); snapshot.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, snapshot)
                }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-scores")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-scores").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:one")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val strip = activity.window.decorView.findViewWithTag<SportsScoreStrip>("sports-score-strip")
                    assertEquals(android.view.Gravity.BOTTOM, (strip.layoutParams as android.widget.FrameLayout.LayoutParams).gravity)
                    assertEquals(3, activity.window.decorView.findViewWithTag<RecyclerView>("score-strip-games").adapter!!.itemCount)
                    assertEquals(original, player!!.currentMediaItem!!.mediaId)
                    assertNull(activity.window.decorView.findViewWithTag<View>("score-strip-pin"))
                    assertTrue("Opening scores pins automatically", SecureStore(context).scoreOverlayPinned)
                }
                device.pressBack()
                scenario.onActivity { activity -> val strip = activity.window.decorView.findViewWithTag<SportsScoreStrip>("sports-score-strip"); assertTrue(strip.isShown); assertFalse(strip.interactive); assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, strip.descendantFocusability); assertTrue(SecureStore(context).scoreOverlayPinned); store.scoreOverlayPosition = "Top" }
                device.pressDPadUp()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:one")?.hasFocus() == true }
                scenario.onActivity { assertEquals("Up focuses scores instead of changing channels", original, player!!.currentMediaItem!!.mediaId) }
                scenario.onActivity { activity ->
                    assertEquals(android.view.Gravity.TOP, (activity.window.decorView.findViewWithTag<View>("sports-score-strip").layoutParams as android.widget.FrameLayout.LayoutParams).gravity)
                    snapshot = snapshot.copy(events = listOf(one.copy(scores = mapOf("nfl:SEA" to "14", "nfl:SF" to "3")), two), checked = System.currentTimeMillis())
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, snapshot)
                    MainActivity::class.java.getDeclaredMethod("updateScoreStrip").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:one")?.contentDescription.toString().contains("14") }
                val blocked = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
                var worker: java.util.concurrent.ExecutorService? = null
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("score-game:NFL:one").hasFocus()); assertEquals(original, player!!.currentMediaItem!!.mediaId)
                    worker = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    worker!!.submit { blocked.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                }
                assertTrue(blocked.await(2, java.util.concurrent.TimeUnit.SECONDS))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("score-game:NFL:two").performClick() }
                device.pressBack(); release.countDown(); worker!!.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
                scenario.onActivity { assertEquals("Cancel keeps the current game playing", original, player!!.currentMediaItem!!.mediaId) }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-scores")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-scores").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:two")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("score-game:NFL:two").performClick() }
                waitFor(scenario) { player!!.currentMediaItem?.localConfiguration?.uri?.path == "/channel2.mp4" && player!!.isPlaying }
                scenario.onActivity { activity ->
                    assertSame(player, find<PlayerView>(activity.window.decorView)!!.player)
                    val strip = activity.window.decorView.findViewWithTag<SportsScoreStrip>("sports-score-strip"); assertTrue(strip.isShown); assertFalse(strip.interactive)
                    assertFalse(channelList(activity)?.isShown == true)
                    assertEquals("Visible score polling shares the minute budget", 1, polls.get())
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "score-strip.png"))
                device.pressDPadUp(); waitFor(scenario) { it.window.decorView.findViewWithTag<SportsScoreStrip>("sports-score-strip")?.interactive == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("score-strip-close").performClick(); assertFalse(SecureStore(context).scoreOverlayPinned) }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { activity -> assertFalse(activity.window.decorView.findViewWithTag<View>("sports-score-strip").isShown); assertEquals("Top", SecureStore(context).scoreOverlayPosition) }
            } } finally { store.scoreOverlayPinned = false; store.scoreOverlayPosition = "Bottom"; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun redZoneTileAutoplaysTheBestFeedAndDetailsRetainManualChoices() {
        FixtureServer().use { server ->
            val source = Source(name = "RedZone autoplay", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val normal = Channel("normal", "News", "News", "${server.base}/channel1.mp4")
            val low = Channel("low", "NFL RedZone HD", "NFL", "${server.base}/channel2.mp4", reportedQuality = VideoInfo(1280, 720, 60f))
            val best = Channel("best", "Sky Sports Mix FHD", "UK Sports", "${server.base}/channel3.mp4", reportedQuality = VideoInfo(1920, 1080, 50f))
            val now = System.currentTimeMillis()
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(listOf(normal, low, best), mapOf(best.id to listOf(Programme(best.id, "NFL RedZone", now - 1000, now + 3600000))))); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null
                waitFor(scenario) { val p = find<PlayerView>(it.window.decorView)?.player; p?.repeatMode = Player.REPEAT_MODE_ONE; player = p; p?.isPlaying == true }
                scenario.onActivity { activity -> activity.liveSportsLoader = { LiveSportsSnapshot(emptyList(), System.currentTimeMillis(), setOf("NFL")) }; activity.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.isShown == true && it.window.decorView.findViewWithTag<TextView>("sports-redzone-coverage")?.text.toString().contains("Sky Sports") }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-redzone").performClick() }
                waitFor(scenario) { player!!.currentMediaItem?.localConfiguration?.uri?.path == "/channel3.mp4" && player!!.isPlaying }
                scenario.onActivity { assertFalse(channelList(it)?.isShown == true); assertSame(player, find<PlayerView>(it.window.decorView)!!.player) }
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-game-streams")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-game-streams").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:best")?.isShown == true && it.window.decorView.findViewWithTag<View>("sports-stream:low")?.isShown == true }
                scenario.onActivity { assertEquals("Opening alternatives preserves playback", "/channel3.mp4", player!!.currentMediaItem!!.localConfiguration!!.uri.path) }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun playbackAlternativesFinishWhenScoreboardRefreshesToAnotherGame() {
        FixtureServer().use { server ->
            val source = Source(name = "Playback alternatives", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis()
            val current = Channel("watched", "Sports One", "NFL", "${server.base}/channel1.mp4")
            val alternate = Channel("alternate", "Sports Two", "NFL", "${server.base}/channel2.mp4")
            val other = Channel("other", "Sports Three", "NFL", "${server.base}/channel3.mp4")
            val guess = Channel("guess", "CBS 4K", "NFL", "${server.base}/missing.mp4", reportedQuality = VideoInfo(3840, 2160, 60f))
            val programme = Programme(current.id, "NFL: Seahawks vs 49ers", now - 60000, now + 3600000)
            val event = LiveEvent("watched", setOf("nfl:SEA", "nfl:SF"), now - 60000, networks = listOf("CBS"), league = "NFL")
            var scores = LiveSportsSnapshot(listOf(event), now, SportsLeagues.byId.keys)
            val game = SportsSchedule.games(scores, Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(listOf(current, other, guess), mapOf(current.id to listOf(programme)))); store.markPlaylistRefreshed(source)
            val release = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null
                waitFor(scenario) { val p = find<PlayerView>(it.window.decorView)?.player; player = p; p?.repeatMode = Player.REPEAT_MODE_ONE; p?.isPlaying == true }
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { scores }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>(game.key)?.isShown == true }
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, game, true) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-panel")?.isShown == false && player!!.isPlaying }
                var playing = ""
                val blocked = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    playing = player!!.currentMediaItem!!.mediaId
                    val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val guide = guideField.get(activity) as Guide
                    guideField.set(activity, guide.copy(channels = guide.channels + alternate, programmes = guide.programmes + (alternate.id to listOf(programme.copy(channel = alternate.id)))))
                    scores = scores.copy(events = listOf(event.copy(id = "different-game", teams = setOf("nfl:BUF", "nfl:KC"))))
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    val worker = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    worker.submit { blocked.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                }
                assertTrue(blocked.await(2, java.util.concurrent.TimeUnit.SECONDS))
                device.pressDPadCenter()
                focusDetailsAction(scenario, "channel-game-streams")
                device.pressDPadCenter()
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("refreshSports").apply { isAccessible = true }.invoke(it) }
                waitFor(scenario) { activity ->
                    val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                    @Suppress("UNCHECKED_CAST") val games = SportsPanel::class.java.getDeclaredField("games").apply { isAccessible = true }.get(panel) as List<SportsGame>
                    games.singleOrNull()?.eventId == "different-game" && !MainActivity::class.java.getDeclaredField("sportsComputing").apply { isAccessible = true }.getBoolean(activity)
                }
                scenario.onActivity { activity ->
                    val selected = MainActivity::class.java.getDeclaredField("selectedGame").apply { isAccessible = true }.get(activity) as SportsGame?
                    assertEquals("Scoreboard refresh cannot replace the active alternatives search", game.key, selected?.key)
                    assertEquals("Searching alternatives keeps the original stream", playing, player!!.currentMediaItem!!.mediaId)
                }
                release.countDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:alternate")?.isShown == true }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-hero-title").text.contains("Seattle"))
                    assertNull("A generic 4K broadcaster must not appear beside confirmed game feeds", activity.window.decorView.findViewWithTag<View>("sports-stream:guess"))
                    assertEquals(playing, player!!.currentMediaItem!!.mediaId)
                    activity.window.decorView.findViewWithTag<View>("sports-stream:alternate").performClick()
                }
                waitFor(scenario) { player!!.currentMediaItem?.localConfiguration?.uri?.path == "/channel2.mp4" && player!!.isPlaying }
                device.pressDPadCenter(); focusDetailsAction(scenario, "channel-game-streams"); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:watched")?.isShown == true && it.window.decorView.findViewWithTag<View>("sports-stream:alternate")?.isShown == true }
                scenario.onActivity { assertSame(player, find<PlayerView>(it.window.decorView)!!.player); assertEquals("/channel2.mp4", player!!.currentMediaItem!!.localConfiguration!!.uri.path) }
            } } finally { release.countDown(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun aLiveCardWithoutSavedListingsAsksItsBroadcasterAndPlaysIt() = broadcasterFirst(linked = false)
    // 0.57.2 skipped every provider check in Sports for a playlist with a guide link, though the link lacks this channel.
    @Test fun aGuideLinkWithoutTheBroadcastersListingsStillLetsSportsAskTheProvider() = broadcasterFirst(linked = true)
    private fun broadcasterFirst(linked: Boolean) {
        // 0.51.31 on an onn: no saved listing confirmed the game, the provider was asked about Atlanta locals, 4K and
        // League Pass channels instead of the network on the scoreboard, and OK opened the stream list.
        FixtureServer(longVideo = true, parallelClients = true, liveListing = 40 to "NBA Basketball: Atlanta Hawks at Oklahoma City Thunder").use { server ->
            val source = Source(name = "Broadcaster first", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true,
                epgUrl = if (linked) "${server.base}/partial-guide.xml" else "")
            val now = System.currentTimeMillis()
            val news = Channel("xc:1", "News", "News", "${server.base}/channel1.mp4")
            val locals = (2..9).map { Channel("xc:$it", "GA | Atlanta | Local $it", "US | Locals", "${server.base}/unused$it.mp4", reportedQuality = VideoInfo(1920, 1080, 0f)) }
            val uhd = (10..19).map { Channel("xc:$it", "4K Event $it", "4K", "${server.base}/unused$it.mp4", reportedQuality = VideoInfo(3840, 2160, 0f)) }
            val league = (20..39).map { Channel("xc:$it", "NBA LP %02d".format(it), "NBA", "${server.base}/unused$it.mp4", reportedQuality = VideoInfo(1920, 1080, 0f)) }
            val carrying = Channel("xc:40", "US: FanDuel SN Southeast HD", "US | Sports", "${server.base}/channel2.mp4", reportedQuality = VideoInfo(1280, 720, 0f))
            val scores = LiveSportsSnapshot(listOf(LiveEvent("hawks", setOf("nba:ATL", "nba:OKC"), now - 1200000, league = "NBA", networks = listOf("FanDuel SN Southeast"))), now, setOf("NBA"))
            store.recentFeeds = emptyList(); store.sportsLastLeague = "NBA"; store.saveSources(listOf(source)); store.active = source.id; store.sportsAutoPlay = true
            store.cache(source, Guide(listOf(news) + locals + uhd + league + carrying, emptyMap())); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null
                waitFor(scenario) { activity -> player = find<PlayerView>(activity.window.decorView)?.player; player?.repeatMode = Player.REPEAT_MODE_ONE; player?.isPlaying == true }
                val game = SportsSchedule.games(scores, Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { scores.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>(game.key)?.isShown == true }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("stopSportsPrefetch", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                    (MainActivity::class.java.getDeclaredField("sportsStreamCache").apply { isAccessible = true }.get(activity) as SportsStreamCache).clear()
                    server.requestedEpgTargets.clear()
                    activity.window.decorView.findViewWithTag<View>(game.key).performClick()
                }
                waitFor(scenario) { player?.let { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.path == "/channel2.mp4" } == true }
                assertTrue("The scoreboard's network was asked", server.requestedEpgTargets.any { it.contains("stream_id=40&") || it.endsWith("stream_id=40") })
                scenario.onActivity { activity -> assertFalse("OK played the game instead of opening the stream list", activity.window.decorView.findViewWithTag<View>("sports-panel").isShown) }
            } } finally { store.sportsAutoPlay = false; store.recentFeeds = emptyList(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun basketballSearchFindsTheTeamFeedInTheSecondPlaylistInsteadOfRadio() {
        FixtureServer(parallelClients = true).use { first -> FixtureServer(programmeTitle = "NBA: Bucks at Celtics", parallelClients = true).use { second ->
            val a = Source(name = "Basketball primary", kind = "xtream", url = first.base, username = "test", password = "test-secret", allowHttp = true)
            val b = a.copy(id = java.util.UUID.randomUUID().toString(), name = "Basketball alternate", url = second.base)
            val now = System.currentTimeMillis()
            val news = Channel("xc:1", "News", "News", "${first.base}/channel1.mp4")
            val radio = Channel("xc:2", "Celtics vs Bucks Radio 4K", "Radio", "${first.base}/channel2.mp4", reportedQuality = VideoInfo(3840, 2160, 60f))
            val generic = (100..159).map { Channel("xc:$it", "NBA Event $it 4K", "NBA", "${first.base}/unused$it.mp4", reportedQuality = VideoInfo(3840, 2160, 0f)) }
            val filler = (1000..26999).map { Channel("xc:$it", "Local news $it", "News", "${first.base}/unused$it.mp4") }
            val video = Channel("xc:9", "Boston Celtics FHD", "NBA Teams", "${second.base}/channel1.mp4", reportedQuality = VideoInfo(1920, 1080, 0f))
            val listing = Programme(radio.id, "NBA: Bucks at Celtics", now - 1000, now + 3600000)
            store.recentFeeds = emptyList(); store.sportsLastLeague = "NBA"; store.saveSources(listOf(a, b)); store.active = a.id; store.catalogRoot = a.id; store.performanceOverlay = true
            store.cache(a, Guide(listOf(news, radio) + generic + filler, mapOf(radio.id to listOf(listing)))); store.cache(b, Guide(listOf(video), emptyMap()))
            listOf(a, b).forEach(store::markPlaylistRefreshed)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    player = find<PlayerView>(activity.window.decorView)?.player; player?.repeatMode = Player.REPEAT_MODE_ONE
                    player?.isPlaying == true && guide.channels.any { it.playlistId == b.id }
                }
                val scores = LiveSportsSnapshot(listOf(LiveEvent("basketball", setOf("nba:BOS", "nba:MIL"), now - 1000, league = "NBA")), now, SportsLeagues.byId.keys)
                val game = SportsSchedule.games(scores, Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
                scenario.onActivity { activity -> activity.liveSportsLoader = { scores }; MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores); activity.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>(game.key)?.isShown == true }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("stopSportsPrefetch", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                    val cache = MainActivity::class.java.getDeclaredField("sportsStreamCache").apply { isAccessible = true }.get(activity) as SportsStreamCache
                    cache.clear()
                    MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, game, true)
                }
                waitFor(scenario) { player?.let { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') } == true }
                scenario.onActivity { activity ->
                    assertSame(player, find<PlayerView>(activity.window.decorView)!!.player)
                    val results = MainActivity::class.java.getDeclaredField("sportsResults").apply { isAccessible = true }.get(activity)!!
                    val matched = results.javaClass.getDeclaredField("game").apply { isAccessible = true }.get(results) as SportsGame
                    assertTrue("Both provider passes finish before no-match can be reported", matched.coverageComplete)
                    assertTrue(matched.broadcasts.any { it.channel.playlistId == b.id })
                    assertFalse(matched.broadcasts.any { it.channel.name.contains("Radio") })
                }
                assertTrue("The team channel's owning provider is queried", second.requestedEpgTargets.any { it.contains("stream_id=9") })
                assertNull("A radio match must never be auto-tuned", first.requests["/channel2.mp4"])
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("performance-overlay")?.text?.contains("first frame") == true }
                scenario.onActivity { activity ->
                    val summary = activity.window.decorView.findViewWithTag<TextView>("performance-overlay").text.toString()
                    assertTrue(summary, summary.contains("peek miss"))
                    assertTrue(summary, summary.contains("channels /"))
                    val journal = MainActivity::class.java.getDeclaredField("playbackJournal").apply { isAccessible = true }.get(activity) as PlaybackJournal
                    val report = journal.report("fixture")
                    assertTrue(report, report.contains("candidate 1 first frame"))
                    assertFalse(report, report.contains(second.base) || report.contains(a.password))
                    println("Sports trace cold fixture: " + summary.substringAfter("Sports #").replace("\n", " | "))
                }
                device.pressDPadCenter(); focusDetailsAction(scenario, "channel-game-streams"); device.pressDPadCenter()
                val rowTag = "sports-stream:playlist:${b.id}:xc:9"
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>(rowTag)?.isShown == true }
                scenario.onActivity { activity ->
                    val row = activity.window.decorView.findViewWithTag<View>(rowTag)
                    assertTrue("Sports results use the Guide's channel row", row is GuideAdapter.Row)
                    assertEquals((GuideTheme.rowHeightDp * activity.resources.displayMetrics.density).toInt(), row.height)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-results.png"))
            } } finally { store.performanceOverlay = false; store.saveSources(emptyList()); listOf(a, b).forEach(store::removeCache); store.catalogRoot = ""; store.recentFeeds = emptyList() }
        } }
    }

    private fun selectSportsSchedule(activity: MainActivity, option: String) {
        val root = activity.window.decorView
        root.findViewWithTag<View>(option).apply { requestFocus(); performClick() }
    }

    @Test fun videoOutputDiagnosticSeparatesScreensWithoutRetuning() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Output fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("output", "Fixture video", "Fixture", "${server.base}/channel1.ts")
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            val output = MainActivity::class.java.getDeclaredField("debugVideoOutput").apply { isAccessible = true }
            fun samples(activity: MainActivity) = (output.get(activity) as VideoOutputTrace).lines()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) {
                        find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true
                    }
                    waitFor(scenario) { samples(it).any { line -> line.startsWith("Full screen:") } }
                    var mediaId = ""
                    scenario.onActivity { activity ->
                        mediaId = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                        activity.window.decorView.findViewWithTag<View>("library").performClick()
                    }
                    waitFor(scenario) { samples(it).any { line -> line.startsWith("Library:") } }
                    scenario.onActivity { activity ->
                        activity.liveSportsLoader = { LiveSportsSnapshot.EMPTY }
                        activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                    }
                    waitFor(scenario) { samples(it).any { line -> line.startsWith("Sports:") } }
                    scenario.onActivity { activity ->
                        assertEquals(mediaId, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                        assertEquals(3, samples(activity).count { it.startsWith("Full screen:") || it.startsWith("Library:") || it.startsWith("Sports:") })
                    }
                }
            } finally {
                store.saveSources(emptyList())
                store.removeCache(source)
            }
        }
    }

    @Test fun performanceReportRecordsStallsHiccupsAndPosterTimes() {
        Diagnostics.reset(); Diagnostics.start()
        try {
            // A blocked screen is noticed and named by the method that blocked it.
            instrumentation.runOnMainSync { blockTheScreenBriefly() }
            Diagnostics.hiccup("Dropped 9 frames", 4200); Diagnostics.poster(12, 34, true); Diagnostics.job("Parse lineup", 1000, 3000)
            Thread.sleep(400)
            val report = Diagnostics.report()
            assertTrue(report, report.contains("blockTheScreenBriefly") && report.contains("Screen stalls (what blocked the screen)"))
            assertTrue(report, report.contains("Dropped 9 frames · buffer 4.2 s") && report.contains("Posters: 1 loaded") && report.contains("Parse lineup"))
            // Every hiccup and the report itself say what else was downloading at the time.
            assertTrue(report, Regex("Dropped 9 frames · buffer 4.2 s · .*other downloads: ").containsMatchIn(report) && report.contains("Network now: "))
            // What changed the screen, and which remote sent each key; repeats share a line.
            Diagnostics.action("Category: Fixture")
            repeat(3) { Diagnostics.key(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN)) }
            Diagnostics.key(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            val actions = Diagnostics.report().substringAfter("Recent actions")
            assertTrue(actions, actions.contains("Category: Fixture") && Regex("Key DPAD_DOWN · from .+ ×3").containsMatchIn(actions))
            Diagnostics.reset(); assertFalse(Diagnostics.report().contains("Recent actions"))
        } finally { Diagnostics.stop(); Diagnostics.reset() }
    }
    private fun blockTheScreenBriefly() { Thread.sleep(600) }

    @Test fun importedCatalogOpensCategoriesWithoutAskingTheProvider() {
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                val libraryField = MainActivity::class.java.getDeclaredField("library").apply { isAccessible = true }
                fun catalogReady(activity: MainActivity, mode: String) =
                    (libraryField.get(activity) as LibraryController).repository.memory(source, mode)?.complete == true
                assertTrue("Live startup does not import either catalog", server.vodImportTargets.isEmpty())
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { catalogReady(it, "movie") && it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                assertEquals("Movies downloads only Movies, once", listOf("movie"), server.vodImportTargets.toList())
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-tab:series").performClick() }
                waitFor(scenario) { catalogReady(it, "series") && it.window.decorView.findViewWithTag<View>("library-item:series:1")?.isShown == true }
                assertEquals(listOf("movie", "series"), server.vodImportTargets.toList())
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-tab:movie").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                assertEquals("Returning to the imported catalog asks the provider for nothing", listOf("movie", "series"), server.vodImportTargets.toList())

            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun libraryHomeShowsRowsFromTheImportedCatalogAndReturnsFromGridsAndSearch() {
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            // The whole catalog as a background import leaves it: three categories, with provider "added" times and ratings.
            // Saved 5 hours ago, so it is not yet due for the background refresh (anOldLibraryCatalogRefreshes… covers that).
            val seeded = LibraryCatalog(store)
            seeded.Writer(source, "movie", stamp = System.currentTimeMillis() - 5 * 3600000L).apply {
                (1..240).map { n -> OnDemand("movie:${5000 + n}", "Home title $n", "Category ${1 + n % 3}", "${server.base}/movie/test/test-secret/${5000 + n}.mp4", "movie", poster = "${server.base}/logo.png", year = "2024", added = 1_700_000_000L + n, rating = (n % 10).toFloat()) }
                    .chunked(64).forEach(::append)
                finish(mapOf("1" to "Fixture cinema", "2" to "Action", "3" to "Comedy"))
            }
            seeded.close()
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                // Library opens on rows, with the cursor on the newest title.
                waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5240" }
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    assertNotNull(root.findViewWithTag<View>("library-shelf:recent")); assertNotNull(root.findViewWithTag<View>("library-shelf:top"))
                    // The performance report traces the opening, so a slow first open on a real box can be explained.
                    val report = Diagnostics.report()
                    assertTrue("Library opening is traced: $report", "Last Library opening" in report && "Library chosen" in report && "Rows shown" in report && Regex("titles read in \\d+ ms").containsMatchIn(report))
                    assertFalse("Rows use the full width; the category list belongs to the grid", root.findViewWithTag<View>("library-categories").isShown)
                    assertNull("Special rows have no See all tile", root.findViewWithTag<View>("library-shelf-more:recent"))
                }
                assertTrue("A saved catalog under 6 hours old opens from disk without a provider import", server.vodImportTargets.isEmpty())
                Thread.sleep(1200); device.takeScreenshot(File(context.getExternalFilesDir(null), "library-home.png"))
                // The main menu rests as icons and widens with labels over the screen when the cursor enters it.
                scenario.onActivity { activity ->
                    val rail = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                    assertFalse(rail.wide); val resting = rail.width
                    activity.window.decorView.findViewWithTag<View>("library").requestFocus()
                    rail.post { assertTrue(rail.wide); assertTrue("The open menu shows labels", rail.width > resting * 2) }
                }
                Thread.sleep(500); device.takeScreenshot(File(context.getExternalFilesDir(null), "library-menu.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:movie:5240").requestFocus() }
                waitFor(scenario) { a -> !a.window.decorView.findViewWithTag<NavigationRail>("app-navigation").wide && (a.currentFocus?.tag as? String) == "library-item:movie:5240" }
                // Down: Top rated (best rating first), then the first provider category.
                device.pressDPadDown(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5239" }
                device.pressDPadDown(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5003" }
                // Leaving and reopening Library returns to the same poster, with the banner filled in for it.
                var bannerTitle = ""
                waitFor(scenario) { a -> bannerTitle = a.window.decorView.findViewWithTag<TextView>("library-spot-title").text.toString(); bannerTitle.isNotBlank() }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-mode").performClick() }
                waitFor(scenario) { channelList(it)?.isShown == true && !it.window.decorView.findViewWithTag<View>("library-shelves").isShown }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5003" }
                Thread.sleep(1500)
                scenario.onActivity { a -> assertEquals("The banner is filled in when Library reopens", bannerTitle, a.window.decorView.findViewWithTag<TextView>("library-spot-title").text.toString()) }
                // The category row ends with See all, which opens the category grid; Back returns to the rows on the same tile.
                repeat(20) { device.pressDPadRight(); Thread.sleep(120) }
                waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-shelf-more:1" }
                device.pressDPadCenter()
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<RecyclerView>("library-items").let { it.isShown && it.adapter?.itemCount == 80 } && a.window.decorView.findViewWithTag<View>("library-categories").isShown }
                scenario.onActivity { assertEquals("Fixture cinema", it.window.decorView.findViewWithTag<TextView>("library-heading").text.toString()) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").hasFocus() }
                // Grid posters look like the rows: artwork only at 2:3, with the spotlight above naming the title under the cursor.
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<TextView>("library-spot-title").text.isNotBlank() }
                scenario.onActivity { a ->
                    val tile = a.window.decorView.findViewWithTag<RecyclerView>("library-items").getChildAt(0) as ViewGroup
                    val shown = mutableListOf<String>()
                    fun texts(view: View) { if (view is TextView && view.isShown && view.text.isNotBlank() && view.tag != "poster-placeholder") shown += view.text.toString(); (view as? ViewGroup)?.let { g -> (0 until g.childCount).forEach { texts(g.getChildAt(it)) } } }
                    texts(tile)
                    assertTrue("A grid poster carries no text under it: $shown", shown.isEmpty())
                    val art = tile.getChildAt(0)
                    assertEquals("The artwork keeps its 2:3 shape", art.width * 3 / 2.0, art.height.toDouble(), 3.0)
                }
                // Picking another category from the list puts the cursor on its first title, and it stays there:
                // it must not end up on the Movies/Series buttons when the new titles replace the old ones.
                fun heading(): String { var text = ""; scenario.onActivity { text = it.window.decorView.findViewWithTag<TextView>("library-heading").text.toString() }; return text }
                fun onTitle(): Boolean { var tag = ""; scenario.onActivity { tag = it.currentFocus?.tag as? String ?: "" }; return tag.startsWith("library-item:") }
                for ((step, expected) in listOf(1 to "Action", -1 to "Fixture cinema")) {
                    device.pressDPadLeft(); waitFor(scenario) { (it.currentFocus?.tag as? String)?.startsWith("library-category:") == true }
                    if (step > 0) device.pressDPadDown() else device.pressDPadUp()
                    Thread.sleep(300); device.pressDPadCenter()
                    waitFor(scenario) { a -> a.window.decorView.findViewWithTag<TextView>("library-heading").text.toString() == expected && (a.currentFocus?.tag as? String)?.startsWith("library-item:") == true }
                    Thread.sleep(1500); assertTrue("The cursor stays on the titles of $expected", onTitle() && heading() == expected)
                }
                device.pressBack(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-shelf-more:1" }
                // Each row remembers its place: back up to the newest title.
                device.pressDPadUp(); device.pressDPadUp(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5240" }
                // Browsing shelves and category grids uses the stored catalog even after the cursor settles.
                assertEquals("Focus must not fetch movie details", 0, server.movieDetailRequests.get())
                // OK fetches the full details. Back lands on the same tile again.
                device.pressDPadCenter()
                waitFor(scenario) {
                    val details = it.window.decorView.findViewWithTag<View>("library-details")
                    details?.isShown == true &&
                        details.findViewWithTag<TextView>("library-detail-plot")?.text?.toString() == "A provider-supplied film description."
                }
                assertEquals("Selecting a movie fetches details once", 1, server.movieDetailRequests.get())
                device.pressBack(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5240" }
                // Typing searches the whole catalog in a grid; clearing the text returns to the rows.
                scenario.onActivity { it.window.decorView.findViewWithTag<android.widget.EditText>("library-search").setText("Home title 77") }
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<RecyclerView>("library-items").let { it.isShown && it.adapter?.itemCount == 1 } && a.window.decorView.findViewWithTag<View>("library-item:movie:5077")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<android.widget.EditText>("library-search").setText("") }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-shelves").isShown && !it.window.decorView.findViewWithTag<View>("library-items").isShown }
                // Leaving Library from a category grid and coming back returns to that grid, and Back still leads to the rows.
                fun focusTag(): String { var tag = ""; scenario.onActivity { tag = it.currentFocus?.tag as? String ?: "" }; return tag }
                scenario.onActivity { it.window.decorView.findViewWithTag<MediaBrowserPanel>("library-panel").requestContentFocus() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-shelves").hasFocus() }
                // Down the rows to the first category row (its 20 tiles are every third title from 5003), then along it to See all.
                val categoryRow = (5003..5060 step 3).map { "library-item:movie:$it" }.toSet() + "library-shelf-more:1"
                repeat(6) { if (focusTag() !in categoryRow) { device.pressDPadDown(); Thread.sleep(400) } }
                assertTrue("Down reaches the category row, was on ${focusTag()}", focusTag() in categoryRow)
                repeat(21) { if (focusTag() != "library-shelf-more:1") { device.pressDPadRight(); Thread.sleep(150) } }
                waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-shelf-more:1" }
                device.pressDPadCenter()
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<RecyclerView>("library-items").let { it.isShown && it.hasFocus() } }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-mode").performClick() }
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<RecyclerView>("library-items").let { it.isShown && it.hasFocus() } && a.window.decorView.findViewWithTag<TextView>("library-heading").text.toString() == "Fixture cinema" }
                device.pressBack(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-shelf-more:1" }
                assertTrue("Browsing rows, a category and search asks the provider for no titles", server.catalogTargets.none { it.contains("get_vod_streams") })
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun anOldLibraryCatalogRefreshesWhenOpenedWithoutBreakingTheTitlesOnScreen() {
        FixtureServer(libraryCategories = true, slowVod = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            val titles = (1..240).map { n -> OnDemand("movie:${5000 + n}", "Home title $n", "Category ${1 + n % 3}", "${server.base}/movie/test/test-secret/${5000 + n}.mp4", "movie", poster = "${server.base}/logo.png", year = "2024", added = 1_700_000_000L + n, rating = (n % 10).toFloat()) }
            val seeded = LibraryCatalog(store)
            val old = seeded.Writer(source, "movie", System.currentTimeMillis() - 3 * 86400000L).run { titles.chunked(64).forEach(::append); finish(mapOf("1" to "Fixture cinema", "2" to "Action", "3" to "Comedy")) }
            seeded.close()
            val database = CatalogDb.get(context); val scope = database.scopeKey(source, "movie", "")
            fun status(activity: MainActivity) = activity.window.decorView.findViewWithTag<TextView>("library-status").text.toString()
            fun search(scenario: ActivityScenario<MainActivity>, text: String) = scenario.onActivity { it.window.decorView.findViewWithTag<android.widget.EditText>("library-search").setText(text) }
            fun grid(activity: MainActivity) = activity.window.decorView.findViewWithTag<RecyclerView>("library-items")
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                // The rows open at once on the saved copy and say a refresh is under way; the provider sends half the new catalog and waits.
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-shelves").isShown && status(it) == "240 titles · Saved 3 days ago · Checking for new titles…" }
                waitFor(scenario) { server.catalogTargets.any { "get_vod_streams" in it } }
                // Search on the rows opens a grid over the copy on screen.
                search(scenario, "Home title 77")
                waitFor(scenario) { a -> grid(a).let { it.isShown && it.adapter?.itemCount == 1 } && a.window.decorView.findViewWithTag<View>("library-item:movie:5077")?.isShown == true }
                server.finishVodCatalog.countDown()
                waitFor(scenario) { database.meta(scope)?.let { it.gen != old.dbGen && it.count == 16 } == true }
                // The refresh replaced the saved copy while the grid was open: the grid keeps reading the copy it shows.
                Thread.sleep(600)
                search(scenario, "Home title 78")
                waitFor(scenario) { a -> grid(a).let { it.isShown && it.adapter?.itemCount == 1 } && a.window.decorView.findViewWithTag<View>("library-item:movie:5078")?.isShown == true }
                assertTrue("The copy on screen keeps its titles while Library is open", database.titles(scope, old.dbGen!!, 0, 1).isNotEmpty())
                // Back on the rows, they are built from the new copy.
                search(scenario, "")
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-shelves").isShown && status(it) == "16 titles · Saved today" }
                // Leaving Library deletes the replaced copy.
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-mode").performClick() }
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown && database.titles(scope, old.dbGen!!, 0, 1).isEmpty() }
                // A fresh copy is not downloaded again when Library reopens.
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-shelves").isShown && status(it) == "16 titles · Saved today" }
                Thread.sleep(1000)
                assertEquals("One refresh for one old catalog", 1, server.catalogTargets.count { "get_vod_streams" in it })
            } } finally { server.finishVodCatalog.countDown(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun libraryHomeLeadsWithContinueWatchingAndWatchlistAndCountsFrames() {
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.bindProvider(source)
            val titles = (1..240).map { n -> OnDemand("movie:${5000 + n}", "Home title $n", "Category ${1 + n % 3}", "${server.base}/movie/test/test-secret/${5000 + n}.mp4", "movie", poster = "${server.base}/logo.png", year = "2024", added = 1_700_000_000L + n, rating = (n % 10).toFloat()) }
            val seeded = LibraryCatalog(store)
            seeded.Writer(source, "movie").apply { titles.chunked(64).forEach(::append); finish(mapOf("1" to "Fixture cinema", "2" to "Action", "3" to "Comedy")) }
            seeded.close()
            // A movie stopped part-way, an episode (which belongs to the Series rows, not Movies) and one Watchlist movie.
            store.saveWatchProgress(source, titles[4], 60_000, 600_000)
            store.saveWatchProgress(source, OnDemand("episode:9001", "Pilot", "", "${server.base}/series/test/test-secret/9001.mp4", "episode", seriesId = "series:1", season = 1, episode = 1), 90_000, 600_000)
            store.editLibraryFavorites(source) { it.save(titles[8], true) }
            fun shelfOf(view: View?): String? { var at: View? = view; while (at != null) { (at.tag as? String)?.takeIf { it.startsWith("library-shelf:") }?.let { return it }; at = at.parent as? View }; return null }
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                // The rows open on Continue watching, above everything from the catalog.
                waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5005" && shelfOf(it.currentFocus) == "library-shelf:continue" }
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    // The tile carries the resume point; the banner may already show the provider's fuller details.
                    assertTrue(root.findViewWithTag<View>("library-item:movie:5005").contentDescription.toString().endsWith("Resume at 1:00"))
                    assertNull("Episodes resume from the Series rows", root.findViewWithTag<View>("library-item:episode:9001"))
                    assertNull("A short row has no See all tile", root.findViewWithTag<View>("library-shelf-more:continue"))
                    // Only rows on screen are laid out (0.51.21); Recently added follows below and Down reaches it next.
                    val rows = root.findViewWithTag<RecyclerView>("library-shelves")
                    assertEquals(listOf("library-shelf:continue", "library-shelf:watchlist"),
                        (0 until rows.childCount).map { rows.getChildAt(it) }.sortedBy { it.top }.take(2).map { it.tag })
                }
                // Once the provider's details arrive the banner still leads with the resume point, and its fade has finished.
                Thread.sleep(900)
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    assertTrue(root.findViewWithTag<TextView>("library-spot-meta").text.toString().startsWith("Resume at 1:00"))
                    assertEquals(1f, root.findViewWithTag<View>("library-spotlight").alpha)
                }
                device.pressDPadDown(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5009" && shelfOf(it.currentFocus) == "library-shelf:watchlist" }
                scenario.onActivity { assertTrue(it.currentFocus?.contentDescription.toString(), it.currentFocus?.contentDescription.toString().endsWith("In Watchlist")) }
                device.pressDPadDown(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5240" && shelfOf(it.currentFocus) == "library-shelf:recent" }
                device.pressDPadUp(); device.pressDPadUp(); waitFor(scenario) { shelfOf(it.currentFocus) == "library-shelf:continue" }
                // OK opens the title's page (Play / Resume); Back lands on the same tile of the same row.
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-details")?.isShown == true }
                device.pressBack(); waitFor(scenario) { (it.currentFocus?.tag as? String) == "library-item:movie:5005" && shelfOf(it.currentFocus) == "library-shelf:continue" }
                // Removing the title from the Watchlist removes its row the next time the rows are shown.
                store.editLibraryFavorites(source) { it.save(titles[8], false) }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-tab:series").performClick() }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-tab:movie").performClick() }
                waitFor(scenario) { a -> a.window.decorView.findViewWithTag<RecyclerView>("library-shelves").let { it.isShown && a.window.decorView.findViewWithTag<View>("library-shelf:watchlist") == null && a.window.decorView.findViewWithTag<View>("library-shelf:continue") != null } }
                // Moving through the rows drew frames; the count is kept for Settings after Library closes.
                waitFor(scenario) { LibraryFrames.summary() != null }
                assertTrue("Rows, resume points and the Watchlist ask the provider for no titles", server.catalogTargets.none { it.contains("get_vod_streams") })
                device.pressBack(); device.pressBack(); device.pressBack()
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                val counted = LibraryFrames.summary(); Thread.sleep(500)
                assertEquals("Nothing is counted once Library is off screen", counted, LibraryFrames.summary())
                scenario.onActivity { activity ->
                    val summary = MainActivity::class.java.getDeclaredMethod("refreshSummary", Source::class.java).apply { isAccessible = true }.invoke(activity, source) as String
                    assertTrue(summary, Regex("\\nLibrary frames \\(last visit\\): [0-9]+ drawn · [0-9]+ slow \\([0-9.]+%\\) · about [0-9]+ dropped · worst [0-9]+ ms").containsMatchIn(summary))
                }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    private fun chooseLibraryCategory(scenario: ActivityScenario<MainActivity>, position: Int) {
        waitFor(scenario) {
            val categories = it.window.decorView.findViewWithTag<RecyclerView>("library-categories")
            categories.isShown && categories.findViewHolderForAdapterPosition(position) != null
        }
        scenario.onActivity {
            it.window.decorView.findViewWithTag<RecyclerView>("library-categories")
                .findViewHolderForAdapterPosition(position)!!.itemView.performClick()
        }
    }

    private fun focusDetailsAction(scenario: ActivityScenario<MainActivity>, tag: String) {
        waitFor(scenario) { it.window.decorView.findViewWithTag<View>(tag)?.isShown == true }
        scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>(tag).requestFocus()) }
    }

    @Test fun playbackActionsAndTodaysOverlayStayBoundedAndHideInSettings() {
        FixtureServer().use { server ->
            val source = Source(name = "Overlay menu fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis()
            val channel = Channel("overlay", "Sports feed", "Sports", "${server.base}/channel1.mp4")
            val one = LiveEvent("overlay-live", setOf("nfl:SEA", "nfl:SF"), now - 1000, league = "NFL")
            val two = LiveEvent("overlay-other", setOf("nfl:BUF", "nfl:KC"), now - 1000, league = "NFL")
            val finalStart = maxOf(now - 7200000, java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            val final = LiveEvent("overlay-final", setOf("nba:BOS", "nba:MIL"), finalStart, "post", scores = mapOf("nba:BOS" to "123", "nba:MIL" to "112"), league = "NBA")
            val snapshot = LiveSportsSnapshot(listOf(one, two, final), now, SportsLeagues.byId.keys)
            val game = SportsSchedule.games(LiveSportsSnapshot(listOf(one), now, setOf("NFL")), Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(channel), mapOf(channel.id to listOf(Programme(channel.id, "NFL: Seahawks vs 49ers", now - 1000, now + 3600000))))); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null; val polls = java.util.concurrent.atomic.AtomicInteger()
                waitFor(scenario) { player = find<PlayerView>(it.window.decorView)?.player; player?.repeatMode = Player.REPEAT_MODE_ONE; player?.isPlaying == true }
                device.pressBack()
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { polls.incrementAndGet(); snapshot.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSportsAttempt").apply { isAccessible = true }.setLong(activity, now)
                    MainActivity::class.java.getDeclaredField("reminderGame").apply { isAccessible = true }.set(activity, game.copy(state = "pre"))
                    MainActivity::class.java.getDeclaredMethod("watchGameReminder").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { activity ->
                    val scores = MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.get(activity) as LiveSportsSnapshot
                    scores.events.any { it.id == two.id } && player?.isPlaying == true && activity.window.decorView.findViewWithTag<View>("game-watch-panel")?.isShown != true
                }
                assertEquals("A reminder forces the full scoreboard refresh despite the minute cooldown", 1, polls.get())
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-scores")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val row = activity.window.decorView.findViewWithTag<ViewGroup>("playback-actions")
                    assertTrue(row.childCount >= 8)
                    repeat(row.childCount) { i -> val button = row.getChildAt(i) as android.widget.Button; assertNotNull(button.compoundDrawables[1]); assertEquals(row, button.parent) }
                    assertNull(activity.window.decorView.findViewWithTag<View>("channel-reconnect"))
                    assertEquals("Sports overlay", activity.window.decorView.findViewWithTag<android.widget.Button>("channel-scores").text.toString())
                }
                device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "playback-actions.png"))
                repeat(3) { device.pressDPadLeft() }
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("channel-scores").hasFocus()) }
                device.pressDPadRight(); scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("channel-guide-action").hasFocus()) }
                device.pressDPadRight(); device.pressDPadDown(); scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("channel-game-streams").hasFocus()) }
                device.pressDPadLeft(); device.pressDPadLeft(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:overlay-live")?.hasFocus() == true }
                scenario.onActivity { assertEquals(3, it.window.decorView.findViewWithTag<RecyclerView>("score-strip-games").adapter!!.itemCount) }
                repeat(4) { device.pressDPadLeft() }
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("score-game:NFL:overlay-live").hasFocus()) }
                device.pressDPadRight(); device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final")?.hasFocus() == true }
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final").contentDescription.toString().contains("FINAL")) }
                scenario.onActivity { activity -> store.hideCompletedScores = true; MainActivity::class.java.getDeclaredMethod("updateScoreStrip").apply { isAccessible = true }.invoke(activity) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final")?.contentDescription.toString().contains("Scores hidden") }
                scenario.onActivity { activity ->
                    assertFalse(activity.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final").contentDescription.toString().contains("123"))
                    store.hideCompletedScores = false; MainActivity::class.java.getDeclaredMethod("updateScoreStrip").apply { isAccessible = true }.invoke(activity)
                }
                device.pressDPadRight(); repeat(4) { device.pressDPadRight() }
                scenario.onActivity { assertTrue("The right edge stops at Close instead of wrapping", it.window.decorView.findViewWithTag<View>("score-strip-close").hasFocus()) }
                device.pressDPadLeft(); device.pressBack()
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("playbackMenu").apply { isAccessible = true }.invoke(activity)
                    assertEquals("Settings hides the elevated overlay in the same frame", View.GONE, activity.window.decorView.findViewWithTag<View>("sports-score-strip").visibility)
                }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-score-strip")?.isShown == true }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("sportsTeams").apply { isAccessible = true }.invoke(activity)
                    assertEquals(View.GONE, activity.window.decorView.findViewWithTag<View>("sports-score-strip").visibility)
                    MainActivity::class.java.getDeclaredMethod("closeSportsManager").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-score-strip")?.isShown == true }
                device.pressDPadUp(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final")?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-actions")?.isShown == true && it.window.decorView.findViewWithTag<TextView>("sports-hero-badge")?.text.toString().contains("FINAL") }
                scenario.onActivity { assertEquals("Overlay details open in the game's league", "NBA", store.sportsLastLeague) }
                scenario.onActivity { assertSame(player, find<PlayerView>(it.window.decorView)!!.player); assertTrue(player!!.isPlaying) }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-overlay-final.png"))
            } } finally { store.scoreOverlayPinned = false; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    /** A start time a minute ago, but never before today's midnight: just after midnight (CI runs on UTC) it would be yesterday. */
    private fun earlierToday(now: Long): Long {
        val midnight = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return maxOf(now - 60000, minOf(now, midnight + 1000))
    }
    /** A start time later today on this device's clock, whatever the time zone (CI emulators run on UTC). */
    private fun laterToday(now: Long): Long {
        val midnight = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().plusDays(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return minOf(now + 30 * 60000L, now + (midnight - now) / 2)
    }
    private fun playingPath(scenario: ActivityScenario<MainActivity>): String? {
        var result: String? = null
        scenario.onActivity { result = find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path }
        return result
    }
    /** Search opens listening since 0.51.22; these tests type their query, so the microphone stops at once. */
    private fun openTypedSearch(scenario: ActivityScenario<MainActivity>, text: String) {
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        scenario.onActivity { openTypedSearch(it, text) }
    }
    private fun openTypedSearch(activity: MainActivity, text: String) {
        MainActivity::class.java.getDeclaredMethod("searchWithQuery", String::class.java).apply { isAccessible = true }.invoke(activity, text)
        (MainActivity::class.java.getDeclaredField("voiceInput").apply { isAccessible = true }.get(activity) as NativeVoiceInput).stop()
    }

    /** A title's controls show as it starts; the first Back closes them, and only the next one leaves the title. */
    private fun closeDemandControls(scenario: ActivityScenario<MainActivity>) {
        var shown = false
        scenario.onActivity { shown = it.window.decorView.findViewWithTag<View>("on-demand-controls")?.isShown == true }
        if (!shown) return
        device.pressBack()
        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("on-demand-controls")?.isShown != true }
    }
    private fun waitFor(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        val ready = AtomicBoolean()
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { ready.set(predicate(it)) }
            if (ready.get()) return
            Thread.sleep(100)
        }
        device.takeScreenshot(File(context.getExternalFilesDir(null), "failure.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "failure.xml"))
        var focusedTag = ""
        scenario.onActivity { activity -> focusedTag = activity.currentFocus?.let { "${it.tag ?: it.javaClass.simpleName}" } ?: "none" }
        scenario.onActivity { activity ->
            val player = find<PlayerView>(activity.window.decorView)?.player
            player?.playerError?.let { android.util.Log.e("SpareFixture", "Synthetic playback failed", it) }
            android.util.Log.e("SpareFixture", "State=${player?.playbackState}; video=${player?.videoSize}; duration=${player?.duration}; archive=${player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/timeshift/")}; textDisabled=${player?.trackSelectionParameters?.disabledTrackTypes}; language=${player?.trackSelectionParameters?.preferredTextLanguages}; selectDefault=${player?.trackSelectionParameters?.selectTextByDefault}; text=${player?.currentTracks?.let { tracks -> CaptionTracks.choices(tracks).map { listOf(it.key, it.group.isTrackSelected(it.index)) } }}")
        }
        fail("Timed out waiting for app state · focus=$focusedTag")
    }
    private inline fun <reified T : View> find(root: View): T? {
        if (root is T) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            val found = findView(root.getChildAt(i), T::class.java)
            if (found != null) return found
        }
        return null
    }
    private fun <T : View> findView(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) findView(root.getChildAt(i), type)?.let { return it }
        return null
    }

    /** Invoked only by the release smoke runner against the separately installed minified APK. */
    fun releaseArtifactSmoke() {
        val releasePackage = "io.github.mot1us.spare"
        fun button(text: String) = device.wait(Until.findObject(By.pkg(releasePackage).text(text)), 8000)
            ?: error("Could not find $text in release app")
        FixtureServer().use { server ->
            val launch = device.executeShellCommand("am start -W -n $releasePackage/.MainActivity")
            assertTrue(launch, launch.contains("Status: ok"))
            assertTrue(device.wait(Until.hasObject(By.pkg(releasePackage)), 10000))
            // A fresh install opens first-run setup; the release build must get through it with the remote.
            assertNotNull("First-run setup offers a phone QR code", device.wait(Until.findObject(By.pkg(releasePackage).desc("QR code for setting up from your phone")), 8000))
            button("Type it on the TV").click()
            val fields = device.wait(Until.findObjects(By.pkg(releasePackage).clazz(EditText::class.java)), 8000)
            check(fields != null && fields.size >= 2) { "Playlist URL field is missing" }
            val playlist = "${server.base}/playlist.m3u"
            fields[1].text = playlist
            assertEquals("Playlist URL entry", playlist, fields[1].text)
            val consent = button("Allow HTTP-only links from this provider")
            assertTrue("HTTP links are allowed by default for a new playlist", consent.isChecked)
            // Clicking moves focus to the box (and toggles it); leave it on, as the fixture needs HTTP.
            consent.click()
            if (!consent.isChecked) consent.click()
            assertTrue("Fixture requires HTTP consent", consent.isChecked)
            // Down passes the folded "Show advanced options" box on its way to Save.
            repeat(2) { if (!button("Save").isFocused) device.pressDPadDown() }
            assertTrue("Focus reached Save", button("Save").isFocused)
            device.pressDPadCenter()
            // A tap on a TV button first gives it focus; tap again if the step has not moved on.
            fun leave(title: String, action: String) {
                button(title)
                repeat(2) { if (device.hasObject(By.pkg(releasePackage).text(title))) { button(action).click(); device.wait(Until.gone(By.pkg(releasePackage).text(title)), 3000) } }
                assertFalse("Setup moved past $title", device.hasObject(By.pkg(releasePackage).text(title)))
            }
            leave("Which sports do you watch?", "Continue")
            leave("Pick a look", "Continue")
            leave("How should the guide work?", "Start watching")
            val firstChannel = By.pkg(releasePackage).descStartsWith("Test Channel 1.")
            val playbackDeadline = android.os.SystemClock.elapsedRealtime() + 20000
            var selectedChannel = false
            while (server.requests["/channel1.mp4"] == null && android.os.SystemClock.elapsedRealtime() < playbackDeadline) {
                if (!selectedChannel) {
                    device.findObject(firstChannel)?.let { channel -> channel.click(); selectedChannel = true }
                }
                Thread.sleep(100)
            }
            assertNotNull("The minified app must request the fixture video; requests: ${server.requests.keys.sorted()}", server.requests["/channel1.mp4"])
            device.pressMenu()
            button("Playback").click()
            val diagnosticsSelector = By.pkg(releasePackage).desc("Playback diagnostics")
            if (!device.hasObject(diagnosticsSelector)) {
                val content = By.pkg(releasePackage).scrollable(true)
                    .hasDescendant(By.pkg(releasePackage).descStartsWith("Sleep timer"))
                device.wait(Until.findObject(content), 5000)?.scrollUntil(
                    androidx.test.uiautomator.Direction.DOWN, Until.hasObject(diagnosticsSelector))
            }
            val diagnostics = device.wait(Until.findObject(diagnosticsSelector), 8000)
                ?: error("Could not find Playback diagnostics in release app")
            diagnostics.click()
            val refresh = By.pkg(releasePackage).desc("Refresh diagnostics")
            if (!device.wait(Until.hasObject(refresh), 1000)) {
                assertTrue("Focus reached Playback diagnostics", diagnostics.isFocused)
                device.pressDPadCenter()
            }
            val frame = By.pkg(releasePackage).textContains(" · first frame · ")
            assertTrue("Playback diagnostics opened", device.wait(Until.hasObject(refresh), 5000))
            if (!device.hasObject(frame)) {
                device.findObject(By.pkg(releasePackage).scrollable(true).hasDescendant(refresh))
                    ?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.75f)
            }
            assertTrue("The minified app must render a video frame", device.hasObject(frame))
        }
    }

    private inner class FixtureServer(private val failCategories: Boolean = false, channelCount: Int = 1, private val tsMode: Boolean = false, private val fallbackHls: Boolean = false, private val slowPlaylist: Boolean = false, private val missingStatus: Int = 0, private val regionGroups: Boolean = false, private val stalled: Boolean = false, private val stallSeconds: Int = 14, private val captionsMode: Boolean = false, private val embeddedCaptions: Int = 0, private val embeddedHls: Boolean = false, private val slowSportsGuide: Boolean = false, private val libraryCategories: Boolean = false, private val longVideo: Boolean = false, private val archivedSports: Boolean = false, private val transientSegmentErrors: Int = 0, private val transientSegmentStatus: Int = 503, private val programmeTitle: String = "World News", private val slowVod: Boolean = false, private val ambiguousVod: Boolean = false, private val liveCycles: Int = 16, parallelClients: Boolean = false, private val slowEpisodes: Boolean = false, private val fullGuide: Boolean = false, private val heldPlaylist: Boolean = false, private val liveListing: Pair<Int, String>? = null, private val cutVodOnce: Boolean = false, private val shelfCategories: Int = 0, private val hugeCatalog: Int = 0, private val heldWholeVod: Boolean = false, private val historyListings: Boolean = false,
        /** An Xtream event category: each slot's name in the lineup and the fresh name its category answers with. */
        private val eventSlots: List<Pair<String, String>>? = null,
        /** Movie 5501 drops its connection 16 seconds in, refuses the first reconnect with 403, then serves byte ranges. */
        private val droppingMovie: Boolean = false) : AutoCloseable {
        /** Holds the event category's answer until the test releases it. */
        val releaseEventNames = java.util.concurrent.CountDownLatch(1)
        /** Resume requests for movie 5501: the first is refused, the rest are served. */
        val refusedMovie = java.util.concurrent.atomic.AtomicInteger()
        val resumedMovie = java.util.concurrent.atomic.AtomicInteger()
        private val droppedMovie = java.util.concurrent.atomic.AtomicBoolean()
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}"
        private val worker = Executors.newSingleThreadExecutor()
        private val connections = if (parallelClients) Executors.newFixedThreadPool(4) else null
        private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
        val requests = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val transientLiveFailures = java.util.concurrent.atomic.AtomicInteger()
        val resumeStream = java.util.concurrent.CountDownLatch(1)
        private val video = instrumentation.context.assets.open("sample.mp4").use { it.readBytes() }
        private val transport = instrumentation.context.assets.open("no-aud.ts").use { it.readBytes() }
        private val avTransport by lazy { instrumentation.context.assets.open("stall-av.ts").use { it.readBytes() } }
        private val dtsMovie by lazy { instrumentation.context.assets.open("dts-hd-ma.mkv").use { it.readBytes() } }
        private val captionTransport by lazy { EmbeddedCaptionFixture.add(repeatTransport(avTransport, 8), embeddedCaptions == 708) }
        private val longTransport by lazy { repeatTransport(avTransport, liveCycles) }
        private fun eventStreams(fresh: Boolean) = eventSlots.orEmpty().withIndex().joinToString(",", "[", "]") { (i, names) ->
            """{"stream_id":${i + 1},"name":"${if (fresh) names.second else names.first}","category_id":"1","container_extension":"mp4","epg_channel_id":"","tv_archive":0}"""
        }.toByteArray()
        private val streams = if (eventSlots != null) eventStreams(false) else (1..channelCount).joinToString(",", "[", "]") { id ->
            """{"stream_id":$id,"name":"Xtream Test $id","category_id":"${if (regionGroups) listOf("uk", "us-news", "us-sports", "ca")[(id - 1) % 4] else "1"}","container_extension":"${if (fallbackHls || missingStatus != 0) "ts" else "mp4"}","epg_channel_id":"${if (fullGuide) "test$id" else ""}","tv_archive":1,"tv_archive_duration":7}"""
        }.toByteArray()
        private val logo = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            canvas.drawText("S", 22f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f })
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }.toByteArray()
        private val playlist = ("#EXTM3U url-tvg=\"$base/guide.xml\"\n" + (1..8).joinToString("\n") {
            "#EXTINF:-1 tvg-id=\"test$it\" tvg-logo=\"$base/logo.png\" group-title=\"${if (regionGroups) listOf("UK | News", "USA | News", "US | MLB", "CA | News")[(it - 1) % 4] else if (it <= 4) "News" else "Culture"}\",Test Channel $it\n$base/channel$it.${if (captionsMode || embeddedHls) "m3u8" else if (tsMode || stalled || longVideo || embeddedCaptions != 0) "ts" else "mp4"}"
        }).toByteArray()
        private val xml: ByteArray
        private val numberedGuide: ByteArray
        private val partialGuide: ByteArray
        val finishSportsGuide = java.util.concurrent.CountDownLatch(1)
        val sportsTailBlocked = java.util.concurrent.CountDownLatch(1)
        private val liveSportsSent = AtomicBoolean(false)
        val episodesRequested = java.util.concurrent.CountDownLatch(1)
        val finishEpisodes = java.util.concurrent.CountDownLatch(1)
        val finishVodCatalog = java.util.concurrent.CountDownLatch(1)
        private val vodCuts = java.util.concurrent.atomic.AtomicInteger()
        val playlistStarted = java.util.concurrent.CountDownLatch(1)
        val finishPlaylist = java.util.concurrent.CountDownLatch(1)
        private val slowMovies by lazy { (1..16).joinToString(",", "[", "]") { id -> org.json.JSONObject().put("stream_id", 100 + id).put("name", if (id == 1) "EN | Fixture movie (2025) 4K DV" else if (ambiguousVod && id == 2) "Fixture movie (1990) FHD" else "Other movie $id").put("category_id", "1").put("container_extension", "mp4").put("plot", "x".repeat(1024)).toString() }.toByteArray() }
        val actionCatalogStarted = java.util.concurrent.CountDownLatch(1)
        val finishActionCatalog = java.util.concurrent.CountDownLatch(1)
        private val actionMovies = (1..400).joinToString(",", "[", "]") { """{"stream_id":${2000+it},"name":"Action title $it","category_id":"2","container_extension":"mp4"}""" }.toByteArray()
        val movieDetailRequests = java.util.concurrent.atomic.AtomicInteger()
        val archiveRequests = java.util.concurrent.atomic.AtomicInteger()
        val epgTargets = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requestedEpgTargets = java.util.Collections.synchronizedList(mutableListOf<String>())
        val catalogTargets = java.util.concurrent.CopyOnWriteArrayList<String>()
        val vodImportTargets = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val shortEpg: ByteArray
        init {
            if (embeddedCaptions != 0) captionTransport // Build fixtures before starting HTTP, so generation errors fail directly.
            val now = System.currentTimeMillis() / 1800000L * 1800000L
            val encodedTitle = java.util.Base64.getEncoder().encodeToString(programmeTitle.toByteArray())
            shortEpg = """{"epg_listings":[{"title":"$encodedTitle","description":"U3BhcmUgcHJvZ3JhbW1lIGd1aWRlIHRlc3Qu","start_timestamp":"${now / 1000}","stop_timestamp":${(now + 3600000) / 1000}}]}""".toByteArray()
            val format = DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withZone(ZoneOffset.UTC)
            xml = ("<tv>" + (1..if (fullGuide) channelCount else 8).joinToString("") { channel -> (0..6).joinToString("") { slot ->
                "<programme channel=\"test$channel\" start=\"${format.format(Instant.ofEpochMilli(now + slot * 1800000L))}\" stop=\"${format.format(Instant.ofEpochMilli(now + (slot + 1) * 1800000L))}\"><title>${if (programmeTitle == "World News") listOf("World News", "Live Report", "Evening Update")[slot % 3] else programmeTitle}</title><desc>Spare playback and guide test fixture.</desc></programme>"
            } } + "</tv>").toByteArray()
            // A guide made for this provider: event slots under their stream numbers. Slot 1's name agrees; slot 2's guide name
            // is another channel, so its listing must not attach.
            numberedGuide = ("<tv><channel id=\"1\"><display-name>${eventSlots?.getOrNull(0)?.first.orEmpty()}</display-name></channel>" +
                "<channel id=\"2\"><display-name>Some Other Channel</display-name></channel>" + (1..2).joinToString("") { id ->
                "<programme channel=\"$id\" start=\"${format.format(Instant.ofEpochMilli(now - 1800000L))}\" stop=\"${format.format(Instant.ofEpochMilli(now + 3 * 3600000L))}\"><title>Numbered guide $id</title></programme>" } + "</tv>").toByteArray()
            // A guide link that lists channel 1 by its guide ID and channel 3 only by name, and lacks channel 2.
            partialGuide = ("<tv><channel id=\"test1\"><display-name>Xtream Test 1</display-name></channel>" +
                "<channel id=\"other3\"><display-name>Xtream Test 3</display-name></channel>" + listOf("test1" to "Linked guide", "other3" to "Named guide").joinToString("") { (id, title) ->
                "<programme channel=\"$id\" start=\"${format.format(Instant.ofEpochMilli(now - 1800000L))}\" stop=\"${format.format(Instant.ofEpochMilli(now + 3 * 3600000L))}\"><title>$title</title></programme>" } + "</tv>").toByteArray()
            fun serve(client: java.net.Socket) {
                clients.add(client)
                runCatching {
                    client.use { connection ->
                        connection.soTimeout = 5000
                        val reader = connection.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        var range = ""
                        for (i in 0 until 100) { val header = reader.readLine(); if (header.isNullOrEmpty()) break; if (header.startsWith("Range:", true)) range = header.substringAfter(':').trim() }
                        val target = request.split(' ').getOrNull(1).orEmpty()
                        val path = target.substringBefore('?'); requests.merge(path, 1, Int::plus)
                        // An event slot that never answers.
                        if (path.startsWith("/hang")) { runCatching { Thread.sleep(30000) }; return@use }
                        if (target.contains("get_short_epg")) requestedEpgTargets.add(target)
                        if (target.contains("action=get_vod_streams")) {
                            catalogTargets.add(target); vodImportTargets.add("movie")
                        }
                        if (target.contains("action=get_series&") || target.endsWith("action=get_series")) vodImportTargets.add("series")
                        if (target.contains("get_vod_info")) movieDetailRequests.incrementAndGet()
                        if (target.contains("get_simple_data_table")) archiveRequests.incrementAndGet()
                        if (slowEpisodes && target.contains("get_series_info")) { episodesRequested.countDown(); finishEpisodes.await(12, java.util.concurrent.TimeUnit.SECONDS) }
                        if (path == "/redirect.mp4" || path == "/redirect-relative.mp4") {
                            val location = if (path == "/redirect.mp4") "$base/redirect-relative.mp4" else "/channel1.mp4"
                            connection.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use
                        }
                        var sportsPayload: ByteArray? = null
                        if (slowSportsGuide && target.contains("get_short_epg")) {
                            epgTargets.add(target)
                            val id = Regex("stream_id=([0-9]+)").find(target)?.groupValues?.get(1)?.toInt() ?: 1
                            // Socket arrival order can differ from request order. Delay only after
                            // the first live listing was actually served, never in front of it.
                            if (id > 2 && liveSportsSent.get()) { sportsTailBlocked.countDown(); finishSportsGuide.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                            if (id == 1) liveSportsSent.set(true)
                            val time = System.currentTimeMillis(); val title = if (id == 1) "Yankees vs Red Sox" else if (id == 2) "Bills vs Chiefs" else "World News"
                            val start = if (id == 2) time + 3600000 else time - 300000
                            sportsPayload = """{"epg_listings":[{"title":"$title","start_timestamp":${start / 1000},"stop_timestamp":${(start + 3600000) / 1000}}]}""".toByteArray()
                        }
                        if (liveListing != null && target.contains("get_short_epg")) {
                            val id = Regex("stream_id=([0-9]+)").find(target)?.groupValues?.get(1)?.toInt() ?: 0
                            val start = System.currentTimeMillis() - 300000
                            val title = if (id == liveListing.first) liveListing.second else "World News"
                            sportsPayload = """{"epg_listings":[{"title":"$title","start_timestamp":${start / 1000},"stop_timestamp":${(start + 3600000) / 1000}}]}""".toByteArray()
                        }
                        val (body, mime) = when (path) {
                            "/playlist.m3u" -> playlist to "application/x-mpegURL"
                            "/guide.xml" -> xml to "application/xml"
                            "/numbered-guide.xml" -> numberedGuide to "application/xml"
                            "/partial-guide.xml" -> partialGuide to "application/xml"
                            "/logo.png" -> logo to "image/png"
                            "/av.ts" -> (if (embeddedCaptions != 0) captionTransport else avTransport) to "video/mp2t"
                            "/movie/test/test-secret/8801.mkv" -> dtsMovie to "video/x-matroska"
                            "/media.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:${if (embeddedCaptions != 0) 20 else 3}\n#EXTINF:${if (embeddedCaptions != 0) 20 else 2.5},\n$base/av.ts\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            "/captions.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:2.5,\n$base/captions.vtt\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            "/captions.vtt" -> "WEBVTT\n\n00:00:00.000 --> 00:00:30.000\nSpare test captions\n".toByteArray() to "text/vtt"
                            // An event slot after its game: the provider answers with a page, not a playlist.
                            "/ended.m3u8" -> "<html><body>No event</body></html>".toByteArray() to "application/vnd.apple.mpegurl"
                            "/player_api.php" -> (if (target.contains("get_vod_categories") || target.contains("get_series_categories")) (if (hugeCatalog > 0) (1..300).joinToString(",", "[", "]") { """{"category_id":"$it","category_name":"Category $it"}""" } else if (shelfCategories > 0) (1..shelfCategories).joinToString(",", "[", "]") { """{"category_id":"$it","category_name":"Shelf $it"}""" } else if (libraryCategories) """[{"category_id":"1","category_name":"Fixture cinema"},{"category_id":"2","category_name":"Action"},{"category_id":"3","category_name":"Comedy"}]""" else """[{"category_id":"1","category_name":"Fixture cinema"}]""").toByteArray()
                                else if (target.contains("get_vod_streams")) when {
                                    slowVod -> slowMovies
                                    libraryCategories && target.contains("category_id=2") -> actionMovies
                                    libraryCategories && target.contains("category_id=3") -> """[{"stream_id":3001,"name":"Comedy title","category_id":"3","container_extension":"mp4"}]""".toByteArray()
                                    else -> """[{"stream_id":101,"name":"Fixture movie","category_id":"1","container_extension":"mp4","stream_icon":"$base/logo.png","year":"2025"}]""".toByteArray()
                                }
                                else if (target.contains("get_vod_info")) """{"info":{"plot":"A provider-supplied film description.","cover_big":"$base/logo.png","releasedate":"2025-01-01","duration":"01:30:00","genre":"Drama"}}""".toByteArray()
                                else if (target.contains("get_series_info")) """{"episodes":{"1":[{"id":201,"title":"Pilot","episode_num":1,"container_extension":"mp4"},{"id":202,"title":"Second episode","episode_num":2,"container_extension":"mp4"}]}}""".toByteArray()
                                else if (target.contains("get_series")) """[{"series_id":1,"name":"Fixture series","category_id":"1"}]""".toByteArray()
                                else if (historyListings && target.contains("get_simple_data_table")) { val time = System.currentTimeMillis() / 1000
                                    """{"epg_listings":[{"title":"Archived fixture","start_timestamp":${time - 10800},"stop_timestamp":${time - 7200},"has_archive":1},{"title":"Not archived fixture","start_timestamp":${time - 7200},"stop_timestamp":${time - 3600},"has_archive":0},{"title":"Later fixture","start_timestamp":${time + 14400},"stop_timestamp":${time + 18000},"has_archive":0}]}""".toByteArray() }
                                else if (target.contains("get_simple_data_table")) { val time = System.currentTimeMillis(); """{"epg_listings":[{"title":"${if (archivedSports) "NFL: Seattle Seahawks vs San Francisco 49ers" else "Archived fixture"}","start_timestamp":${(time - 7200000) / 1000},"stop_timestamp":${(time - 3600000) / 1000}}]}""".toByteArray() }
                                else if (target.contains("get_short_epg")) sportsPayload ?: shortEpg else if (target.contains("get_live_categories")) (if (eventSlots != null) """[{"category_id":"1","category_name":"NBA Events"}]""" else if (regionGroups) """[{"category_id":"us-news","category_name":"USA | News"},{"category_id":"ca","category_name":"CA | News"},{"category_id":"uk","category_name":"UK | News"},{"category_id":"us-sports","category_name":"US | MLB"}]""" else """[{"category_id":"1","category_name":"News"}]""").toByteArray()
                                else if (eventSlots != null && target.contains("get_live_streams") && target.contains("category_id=")) { releaseEventNames.await(30, java.util.concurrent.TimeUnit.SECONDS); eventStreams(true) }
                                else streams) to "application/json"
                            "/xmltv.php" -> xml to "application/xml"
                            "/live/test/test-secret/1.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2,\n$base/segment.ts\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            else -> if (embeddedHls && path.endsWith(".m3u8")) "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,CLOSED-CAPTIONS=NONE\n$base/media.m3u8\n".toByteArray() to "application/vnd.apple.mpegurl" else if (embeddedCaptions != 0 && path.endsWith(".ts")) captionTransport to "video/mp2t" else if (captionsMode && path.endsWith(".m3u8")) "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"$base/captions.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,SUBTITLES=\"subs\"\n$base/media.m3u8\n".toByteArray() to "application/vnd.apple.mpegurl" else if (longVideo && path.endsWith(".ts")) longTransport to "video/mp2t" else if (stalled && path == "/channel1.ts") longTransport to "video/mp2t" else if (path.endsWith(".ts")) transport to "video/mp2t" else video to "video/mp4"
                        }
                        val output = connection.getOutputStream()
                        if (droppingMovie && path == "/movie/test/test-secret/5501.ts") {
                            val whole = longTransport
                            val from = Regex("bytes=(\\d+)-").find(range)?.groupValues?.get(1)?.toInt() ?: 0
                            when {
                                from == 0 && !droppedMovie.get() -> { output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${whole.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    output.write(whole, 0, whole.size * 2 / 5); output.flush(); Thread.sleep(3000); droppedMovie.set(true) }
                                // Only the reconnect after the drop is refused; the player's look at the file's end is served.
                                droppedMovie.get() && refusedMovie.getAndIncrement() == 0 -> output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                else -> { resumedMovie.incrementAndGet()
                                    output.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp2t\r\nContent-Range: bytes $from-${whole.size - 1}/${whole.size}\r\n" +
                                        "Content-Length: ${whole.size - from}\r\nConnection: close\r\n\r\n").toByteArray()); output.write(whole, from, whole.size - from) }
                            }
                            output.flush(); return@use
                        }
                        if (path == "/channel1.ts" && transientLiveFailures.getAndUpdate { count -> (count - 1).coerceAtLeast(0) } > 0) {
                            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush(); return@use
                        }
                        val status = if (path == "/segment.ts" && (requests[path] ?: 0) <= transientSegmentErrors) "$transientSegmentStatus Temporary" else if (missingStatus != 0 && path in setOf("/live/test/test-secret/2.ts", "/live/test/test-secret/2.m3u8")) "$missingStatus Missing" else if ((failCategories && target.contains("get_live_categories")) || (fallbackHls && path == "/live/test/test-secret/1.ts")) "404 Not Found" else "200 OK"
                        val length = if (stalled && path == "/channel1.ts" || hugeCatalog > 0 && target.contains("get_vod_streams") && !target.contains("category_id")) "" else "Content-Length: ${body.size}\r\n"
                        output.write("HTTP/1.1 $status\r\nContent-Type: $mime\r\n${length}Connection: close\r\n\r\n".toByteArray())
                        if (stalled && path == "/channel1.ts") {
                            val split = avTransport.size * 2
                            output.write(body, 0, split); output.flush()
                            resumeStream.await(stallSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                            output.write(body, split, body.size - split); output.flush()
                        } else if (hugeCatalog > 0 && target.contains("get_vod_streams") && !target.contains("category_id")) {
                            // A provider-sized movie list, written as it is made rather than held in memory.
                            val out = java.io.BufferedOutputStream(output, 65536); out.write('['.code)
                            for (n in 1..hugeCatalog) {
                                if (n > 1) out.write(','.code)
                                out.write(("""{"num":$n,"name":"EN - Fixture feature film number $n (${1980 + n % 45})","stream_type":"movie","stream_id":${100000 + n},"stream_icon":"https://image.tmdb.org/t/p/w600_and_h900_bestv2/fixture$n.jpg","rating":"6.${n % 10}","added":"${1600000000 + n}","category_id":"${1 + n % 300}","container_extension":"mkv","direct_source":""}""").toByteArray())
                            }
                            out.write(']'.code); out.flush(); return@use
                        } else if (heldWholeVod && target.contains("get_vod_streams") && !target.contains("category_id")) {
                            // A whole list that takes minutes: part of it, then nothing until the test lets the rest through.
                            output.write(body, 0, body.size / 2); output.flush()
                            finishVodCatalog.await(30, java.util.concurrent.TimeUnit.SECONDS)
                            output.write(body, body.size / 2, body.size - body.size / 2); output.flush()
                        } else if (cutVodOnce && target.contains("get_vod_streams") && vodCuts.getAndIncrement() == 0) {
                            // Like a provider that resets a long response: part of the list, then a reset connection.
                            val value = body.toString(Charsets.UTF_8); var cut = 0
                            repeat(8) { cut = value.indexOf("},{", cut) + 2 }
                            output.write(body, 0, cut); output.flush()
                            runCatching { connection.setSoLinger(true, 0) }; connection.close(); return@use
                        } else if (slowVod && target.contains("get_vod_streams")) {
                            val value = body.toString(Charsets.UTF_8); var cut = 0
                            repeat(8) { cut = value.indexOf("},{", cut) + 2 }
                            output.write(body, 0, cut); output.flush()
                            finishVodCatalog.await(15, java.util.concurrent.TimeUnit.SECONDS)
                            output.write(body, cut, body.size - cut); output.flush()
                        } else if (libraryCategories && target.contains("get_vod_streams") && target.contains("category_id=2")) {
                            val value = body.toString(Charsets.UTF_8); var cut = 0
                            repeat(264) { cut = value.indexOf("},{", cut) + 2 }
                            output.write(body, 0, cut); output.flush(); actionCatalogStarted.countDown()
                            finishActionCatalog.await(15, java.util.concurrent.TimeUnit.SECONDS)
                            output.write(body, cut, body.size - cut); output.flush()
                        } else if (heldPlaylist && path == "/playlist.m3u") {
                            playlistStarted.countDown()
                            finishPlaylist.await(15, java.util.concurrent.TimeUnit.SECONDS)
                            output.write(body); output.flush()
                        } else if (slowPlaylist && path == "/playlist.m3u") {
                            for (chunk in body.asList().chunked(200)) { output.write(chunk.toByteArray()); output.flush(); Thread.sleep(200) }
                        } else { output.write(body); output.flush() }
                    }
                }
                clients.remove(client)
            }
            worker.execute {
                while (!socket.isClosed) runCatching {
                    val client = socket.accept()
                    if (connections != null) connections.execute { serve(client) } else serve(client)
                }
            }
        }
        override fun close() { finishEpisodes.countDown(); finishVodCatalog.countDown(); finishPlaylist.countDown(); finishActionCatalog.countDown(); finishSportsGuide.countDown(); resumeStream.countDown(); socket.close(); clients.forEach { runCatching { it.close() } }; connections?.shutdownNow(); worker.shutdownNow() }
    }
}
