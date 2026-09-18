package dev.aoframe.webcam

import androidx.test.platform.app.InstrumentationRegistry
import dev.aoframe.cache.AssetCacheDatabase
import dev.aoframe.immich.ImmichAssetType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fast, deterministic tests for WebcamClipSync's manifest handling and
 * per-camera failure isolation, using MockWebServer instead of the real
 * pi-video-gate sidecar - same reasoning/instrumented-not-plain-JVM
 * choice as WeatherClientTest (org.json needs a real Android runtime).
 * Uses a real AssetCacheDatabase (not mocked) to also confirm the "never
 * touches the `assets` table, only `cached_files`" design actually holds.
 */
class WebcamClipSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AssetCacheDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("asset-cache.db")
        db = AssetCacheDatabase(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("asset-cache.db")
        // Already shut down by isCycleInProgressFailsOpenToFalseWhenUnreachable
        // itself, in that one test's case - double-shutdown throws.
        try { server.shutdown() } catch (error: Exception) {}
    }

    private fun sync(): WebcamClipSync {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return WebcamClipSync(context, db, videoGateBaseUrl = server.url("/").toString().removeSuffix("/"))
    }

    @Test
    fun downloadsEveryClipInTheManifestAndRegistersItWithoutTouchingTheAssetsTable() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/webcam/clips" -> MockResponse().setBody("""{"clips":["cam-a","cam-b"]}""")
                "/webcam/clip/cam-a" -> MockResponse().setBody("clip-a-bytes")
                "/webcam/clip/cam-b" -> MockResponse().setBody("clip-b-bytes")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val assets = sync().sync()

        assertEquals(2, assets.size)
        assertTrue(assets.all { it.type == ImmichAssetType.VIDEO })
        assertEquals(setOf("webcam-cam-a", "webcam-cam-b"), assets.map { it.id }.toSet())

        // Registered in cached_files (what SlideshowRenderer actually reads)...
        val pathA = db.cachedFilePath("webcam-cam-a", "VIDEO")
        assertTrue(pathA != null && java.io.File(pathA).readText() == "clip-a-bytes")
        // ...but never inserted into the Immich-owned `assets` table -
        // the key trick that keeps an Immich resync from ever evicting a
        // webcam clip (see AssetCacheDatabase.computeStaleAssetIds()).
        assertEquals(0, db.assetCount())
    }

    @Test
    fun aFailedManifestFetchReturnsAnEmptyListInsteadOfThrowing() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500)
        }

        val assets = sync().sync()

        assertEquals(emptyList<Any>(), assets)
    }

    @Test
    fun oneCameraFailingToDownloadIsOmittedWithoutAffectingTheOthers() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/webcam/clips" -> MockResponse().setBody("""{"clips":["cam-ok","cam-down"]}""")
                "/webcam/clip/cam-ok" -> MockResponse().setBody("clip-ok-bytes")
                // Simulates a camera the gate knows about but has no
                // fresh clip for right now (see the gate's 404 convention).
                "/webcam/clip/cam-down" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }

        val assets = sync().sync()

        assertEquals(listOf("webcam-cam-ok"), assets.map { it.id })
    }

    // WebcamSyncState backs the admin panel's "last synced to frame"
    // column - confirms downloadClip() actually records it, not just
    // recordCachedFile().
    @Test
    fun aSuccessfulDownloadRecordsItInWebcamSyncState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/webcam/clips" -> MockResponse().setBody("""{"clips":["cam-a"]}""")
                "/webcam/clip/cam-a" -> MockResponse().setBody("clip-a-bytes")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val before = System.currentTimeMillis()
        sync().sync()

        val syncedAt = WebcamSyncState.load(context)["cam-a"]
        assertTrue(syncedAt != null && syncedAt >= before)
    }

    // isFeatureEnabled() backs the on-screen button's visibility (the
    // master on/off switch) - fails open to true, same reasoning as
    // isCycleInProgress()'s own fail-open test below.
    @Test
    fun isFeatureEnabledReflectsTheEnabledEndpoint() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("""{"enabled":false}""")
        }

        assertEquals(false, sync().isFeatureEnabled())
    }

    @Test
    fun isFeatureEnabledFailsOpenToTrueWhenUnreachable() = runBlocking {
        server.shutdown()

        assertEquals(true, sync().isFeatureEnabled())
    }

    // forceRefreshOnPi()/isCycleInProgress() back the on-screen webcam-only
    // button's "force capture, wait for it" flow (MainActivity.
    // forceWebcamRefreshThenShow()) - both talk to pi-video-gate directly,
    // same host as the manifest/clip fetches above.
    @Test
    fun forceRefreshOnPiPostsToTheRefreshEndpoint() = runBlocking {
        var sawPost = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/webcam/refresh" && request.method == "POST") sawPost = true
                return MockResponse().setBody("""{"status":"started"}""")
            }
        }

        sync().forceRefreshOnPi()

        assertTrue(sawPost)
    }

    @Test
    fun isCycleInProgressReflectsTheStatusEndpoint() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("""{"cycleInProgress":true,"cameras":[]}""")
        }

        assertTrue(sync().isCycleInProgress())
    }

    @Test
    fun isCycleInProgressFailsOpenToFalseWhenUnreachable() = runBlocking {
        server.shutdown()

        assertEquals(false, sync().isCycleInProgress())
    }
}
