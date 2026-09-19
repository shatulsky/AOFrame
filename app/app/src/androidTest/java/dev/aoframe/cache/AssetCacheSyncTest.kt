package dev.aoframe.cache

import androidx.test.platform.app.InstrumentationRegistry
import dev.aoframe.immich.ImmichClient
import dev.aoframe.immich.ImmichSecrets
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fast, deterministic tests for AssetCacheSync's sync/cache/evict logic,
 * using MockWebServer instead of a real Immich server - same reasoning as
 * ImmichClientTest (see that file's header comment). Complements (doesn't
 * replace) AssetCacheSyncLiveTest, which needs a real Immich server and
 * device-pushed secrets and is skipped without them.
 */
class AssetCacheSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var db: AssetCacheDatabase
    private lateinit var sync: AssetCacheSync

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context.deleteDatabase("asset-cache.db")
        db = AssetCacheDatabase(context)
        val client = ImmichClient(ImmichSecrets(baseUrl = server.url("/").toString().removeSuffix("/"), apiKey = "test-key"))
        assetCacheDir(context).deleteRecursively()
        sync = AssetCacheSync(context, client, db)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("asset-cache.db")
        assetCacheDir(context).deleteRecursively()
        server.shutdown()
    }

    private fun enqueueImageSearch(itemsJson: String) {
        server.enqueue(MockResponse().setBody("""{"assets": {"items": [$itemsJson]}}"""))
    }

    private fun enqueueEmptySearch() {
        server.enqueue(MockResponse().setBody("""{"assets": {"items": []}}"""))
    }

    private fun enqueueFaces(faceJson: String?) {
        server.enqueue(MockResponse().setBody(if (faceJson == null) "[]" else "[$faceJson]"))
    }

    @Test
    fun syncQueriesFacesAndCachesThumbnailAndBackdropFilesForANewImage() = runBlocking {
        enqueueImageSearch("""{"id": "img1", "width": 100, "height": 100}""")
        enqueueEmptySearch() // VIDEO search
        enqueueFaces("""{"boundingBoxX1":10,"boundingBoxX2":30,"boundingBoxY1":10,"boundingBoxY2":30,"imageWidth":100,"imageHeight":100}""")
        server.enqueue(MockResponse().setBody("thumb-bytes"))
        server.enqueue(MockResponse().setBody("backdrop-bytes"))

        val result = sync.sync()

        assertEquals(1, result.size)
        assertEquals(20.0f, result[0].faceX)
        assertEquals(20.0f, result[0].faceY)

        val cachedFiles = db.cachedFilesForAsset("img1")
        assertEquals(2, cachedFiles.size)
        cachedFiles.forEach { path -> assertTrue("expected $path to exist on disk", java.io.File(path).exists()) }
    }

    @Test
    fun secondSyncSkipsFaceQueryAndRedownloadForAnAlreadyCachedAsset() = runBlocking {
        enqueueImageSearch("""{"id": "img1", "width": 100, "height": 100}""")
        enqueueEmptySearch()
        enqueueFaces("""{"boundingBoxX1":10,"boundingBoxX2":30,"boundingBoxY1":10,"boundingBoxY2":30,"imageWidth":100,"imageHeight":100}""")
        server.enqueue(MockResponse().setBody("thumb-bytes"))
        server.enqueue(MockResponse().setBody("backdrop-bytes"))
        sync.sync()
        assertEquals(5, server.requestCount) // sanity: search x2 + face query + 2 downloads

        // Second pass: same asset still present. No face-query response or
        // download response is enqueued - if AssetCacheSync tried to make
        // either request, MockWebServer would block/fail on the missing
        // response, so a clean pass proves both were skipped (known face
        // target reused, cached file already on disk).
        enqueueImageSearch("""{"id": "img1", "width": 100, "height": 100}""")
        enqueueEmptySearch()

        val result = sync.sync()

        assertEquals(1, result.size)
        assertEquals(20.0f, result[0].faceX)
        assertEquals(7, server.requestCount)
    }

    @Test
    fun syncEvictsAssetsNoLongerPresentAndDeletesTheirCachedFiles() = runBlocking {
        enqueueImageSearch("""{"id": "img1", "width": 100, "height": 100}""")
        enqueueEmptySearch()
        enqueueFaces(null)
        server.enqueue(MockResponse().setBody("thumb-bytes"))
        server.enqueue(MockResponse().setBody("backdrop-bytes"))
        sync.sync()
        val staleFile = java.io.File(db.cachedFilesForAsset("img1").first())
        assertTrue(staleFile.exists())

        // img1 drops out of the library entirely in this pass.
        enqueueImageSearch("""{"id": "img2", "width": 100, "height": 100}""")
        enqueueEmptySearch()
        enqueueFaces(null)
        server.enqueue(MockResponse().setBody("thumb-bytes-2"))
        server.enqueue(MockResponse().setBody("backdrop-bytes-2"))

        val result = sync.sync()

        assertEquals(1, result.size)
        assertEquals("img2", result[0].id)
        assertTrue(db.cachedFilesForAsset("img1").isEmpty())
        assertFalse(staleFile.exists())
        assertEquals(2, db.cachedFilesForAsset("img2").size)
    }
}
