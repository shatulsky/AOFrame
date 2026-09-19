package dev.aoframe.localsource

import androidx.test.platform.app.InstrumentationRegistry
import dev.aoframe.cache.AssetCacheDatabase
import dev.aoframe.cache.assetCacheDir
import dev.aoframe.immich.ImmichAssetType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Fast, deterministic tests for LocalAssetSync's sync/cache/evict logic -
 * the `photoSource: local` counterpart to AssetCacheSyncTest, using
 * MockWebServer against a real AssetCacheDatabase/filesDir cache instead
 * of a real AOFrame reference server (same reasoning as AssetCacheSyncTest,
 * see its header comment).
 */
class LocalAssetSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var db: AssetCacheDatabase
    private lateinit var sync: LocalAssetSync

    // A genuine 1x1 transparent PNG - LocalAssetSync decodes real image
    // bytes via BitmapFactory to resolve an IMAGE asset's dimensions, so a
    // placeholder string won't do here.
    private val onePixelPng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context.deleteDatabase("asset-cache.db")
        db = AssetCacheDatabase(context)
        assetCacheDir(context).deleteRecursively()
        val client = LocalPhotoClient(server.url("/").toString().removeSuffix("/"))
        sync = LocalAssetSync(context, client, db)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("asset-cache.db")
        assetCacheDir(context).deleteRecursively()
        server.shutdown()
    }

    @Test
    fun syncDownloadsAnImageDecodesItsRealDimensionsAndLinksBackdropToTheSameFile() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id": "img1", "mimeType": "image/jpeg"}]"""))
        server.enqueue(MockResponse().setBody(Buffer().write(onePixelPng)))

        val result = sync.sync()

        assertEquals(1, result.size)
        assertEquals(ImmichAssetType.IMAGE, result[0].type)
        assertEquals(1, result[0].width)
        assertEquals(1, result[0].height)

        val thumbnailPath = db.cachedFilePath("img1", "THUMBNAIL")
        val backdropPath = db.cachedFilePath("img1", "BACKDROP")
        assertEquals(thumbnailPath, backdropPath)
        assertTrue(File(thumbnailPath!!).exists())
    }

    @Test
    fun syncDownloadsAVideoWithPlaceholderDimensionsAndNoBackdropLink() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id": "vid1", "mimeType": "video/mp4"}]"""))
        server.enqueue(MockResponse().setBody("video-bytes"))

        val result = sync.sync()

        assertEquals(1, result.size)
        assertEquals(ImmichAssetType.VIDEO, result[0].type)
        assertEquals(1920, result[0].width)
        assertEquals(1080, result[0].height)
        assertTrue(File(db.cachedFilePath("vid1", "VIDEO")!!).exists())
        assertEquals(null, db.cachedFilePath("vid1", "BACKDROP"))
    }

    @Test
    fun syncCarriesThroughAManuallySetFaceTarget() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id": "img1", "mimeType": "image/jpeg", "faceX": 30.0, "faceY": 60.0}]"""))
        server.enqueue(MockResponse().setBody(Buffer().write(onePixelPng)))

        val result = sync.sync()

        assertEquals(30.0f, result[0].faceX)
        assertEquals(60.0f, result[0].faceY)
    }

    @Test
    fun syncEvictsAssetsNoLongerPresentAndDeletesTheirCachedFiles() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id": "img1", "mimeType": "image/jpeg"}]"""))
        server.enqueue(MockResponse().setBody(Buffer().write(onePixelPng)))
        sync.sync()
        val staleFile = File(db.cachedFilePath("img1", "THUMBNAIL")!!)
        assertTrue(staleFile.exists())

        server.enqueue(MockResponse().setBody("""[{"id": "img2", "mimeType": "image/jpeg"}]"""))
        server.enqueue(MockResponse().setBody(Buffer().write(onePixelPng)))

        val result = sync.sync()

        assertEquals(listOf("img2"), result.map { it.id })
        assertTrue(db.cachedFilesForAsset("img1").isEmpty())
        assertFalse(staleFile.exists())
    }
}
