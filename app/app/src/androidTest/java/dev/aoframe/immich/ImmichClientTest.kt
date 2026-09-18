package dev.aoframe.immich

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Fast, deterministic JSON/HTTP-parsing tests for ImmichClient, using
 * MockWebServer instead of the real Immich server - no real Immich
 * server or live data dependency, runs on the emulator/device alongside
 * the live tests. Complements (doesn't replace) AssetRepositoryLiveTest/
 * AssetCacheSyncLiveTest: the exact class of bug covered by the
 * "explicit JSON null" test below was only found by a live test, which
 * is why both kinds of test matter.
 *
 * Instrumented (androidTest), not a plain JVM unit test with Robolectric
 * - Robolectric hit a cascade of environment-specific version
 * incompatibilities in this setup (unsupported API 27/targetSdk 37,
 * a known RoboCookieManager teardown bug, then an ASM/bytecode-version
 * mismatch), not worth fighting further - org.json works fine as a real
 * instrumented test since it's genuinely running on a real Android
 * runtime, no shadowing needed.
 */
class ImmichClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ImmichClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val secrets = ImmichSecrets(baseUrl = server.url("/").toString().removeSuffix("/"), apiKey = "test-key")
        client = ImmichClient(secrets)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun filtersTrashedAndArchivedAssets() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"assets": {"items": [
                    {"id": "a", "isTrashed": false, "isArchived": false, "width": 100, "height": 200},
                    {"id": "b", "isTrashed": true, "width": 100, "height": 200},
                    {"id": "c", "isArchived": true, "width": 100, "height": 200}
                ]}}
                """.trimIndent()
            )
        )

        val results = client.fetchAssetsOfType("IMAGE")

        assertEquals(1, results.size)
        assertEquals("a", results[0].id)
        assertEquals(ImmichAssetType.IMAGE, results[0].type)
    }

    @Test
    fun explicitJsonNullLivePhotoVideoIdIsNotTreatedAsALivePhoto() = runBlocking {
        // Regression test: org_json's optString(key, default) returns the
        // literal string "null" (not the fallback default) when the key
        // is present with a JSON null value - this bug shipped once
        // already and silently misclassified plain images as Live Photos.
        server.enqueue(
            MockResponse().setBody(
                """{"assets": {"items": [
                    {"id": "a", "livePhotoVideoId": null, "width": 100, "height": 200}
                ]}}"""
            )
        )

        val results = client.fetchAssetsOfType("IMAGE")

        assertEquals(1, results.size)
        assertEquals(ImmichAssetType.IMAGE, results[0].type)
        assertNull(results[0].videoId)
    }

    @Test
    fun aRealLivePhotoVideoIdClassifiesTheAssetAsALivePhoto() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"assets": {"items": [
                    {"id": "a", "livePhotoVideoId": "clip-123", "width": 100, "height": 200}
                ]}}"""
            )
        )

        val results = client.fetchAssetsOfType("IMAGE")

        assertEquals(1, results.size)
        assertEquals(ImmichAssetType.LIVE_PHOTO, results[0].type)
        assertEquals("clip-123", results[0].videoId)
    }

    @Test
    fun paginationFollowsNextPageAcrossMultipleRequests() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"assets": {"items": [{"id": "a", "width": 1, "height": 1}], "nextPage": "2"}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"assets": {"items": [{"id": "b", "width": 1, "height": 1}]}}"""
            )
        )

        val results = client.fetchAssetsOfType("IMAGE")

        assertEquals(2, results.size)
        assertEquals(setOf("a", "b"), results.map { it.id }.toSet())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun fetchFaceTargetDropsFacesMuchSmallerThanTheLargestOneAndTargetsTheRemainingOne() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[
                    {"boundingBoxX1": 0, "boundingBoxX2": 10, "boundingBoxY1": 0, "boundingBoxY2": 10, "imageWidth": 100, "imageHeight": 100},
                    {"boundingBoxX1": 20, "boundingBoxX2": 60, "boundingBoxY1": 20, "boundingBoxY2": 60, "imageWidth": 100, "imageHeight": 100}
                ]"""
            )
        )

        val target = client.fetchFaceTarget("some-id")

        // Largest face is 40x40=1600. First face (10x10=100) is only
        // 6.25% of that - below MIN_FACE_AREA_FRACTION_OF_LARGEST (15%),
        // dropped as a background bystander relative to the other face.
        // Second face is the only one left, so its own center (40,40) is
        // the target.
        assertEquals(40.0f, target?.first)
        assertEquals(40.0f, target?.second)
    }

    @Test
    fun fetchFaceTargetUnionsTheBoundingBoxesOfEveryQualifyingFace() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[
                    {"boundingBoxX1": 10, "boundingBoxX2": 30, "boundingBoxY1": 10, "boundingBoxY2": 30, "imageWidth": 100, "imageHeight": 100},
                    {"boundingBoxX1": 60, "boundingBoxX2": 90, "boundingBoxY1": 60, "boundingBoxY2": 90, "imageWidth": 100, "imageHeight": 100}
                ]"""
            )
        )

        val target = client.fetchFaceTarget("some-id")

        // Largest face is 30x30=900. Both faces qualify (20x20=400 is
        // 44% of 900, well over the 15% cutoff) - the target is the
        // center of the box enclosing both ((10,10) to (90,90)), not
        // either face's own center.
        assertEquals(50.0f, target?.first)
        assertEquals(50.0f, target?.second)
    }

    @Test
    fun fetchFaceTargetAlwaysKeepsTheLoneFaceRegardlessOfItsAbsoluteSize() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[
                    {"boundingBoxX1": 0, "boundingBoxX2": 2, "boundingBoxY1": 0, "boundingBoxY2": 2, "imageWidth": 1000, "imageHeight": 1000}
                ]"""
            )
        )

        val target = client.fetchFaceTarget("some-id")

        // Only face is 2x2 out of a 1000x1000 image (0.0004% of the
        // frame) - a size a flat whole-image cutoff would have dropped,
        // but there's nothing else in the photo to judge it against, so
        // it's trivially "the largest face" and stays the target: center
        // (1,1) normalizes to 0.1%/0.1%.
        assertEquals(0.1f, target?.first)
        assertEquals(0.1f, target?.second)
    }

    @Test
    fun fetchFaceTargetReturnsNullWhenTheOnlyFaceHasZeroArea() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[
                    {"boundingBoxX1": 50, "boundingBoxX2": 50, "boundingBoxY1": 50, "boundingBoxY2": 50, "imageWidth": 100, "imageHeight": 100}
                ]"""
            )
        )

        val target = client.fetchFaceTarget("some-id")

        // A zero-width/height bounding box (malformed data) has nothing
        // to size anything else relative to.
        assertNull(target)
    }

    @Test
    fun fetchFaceTargetReturnsNullWhenNoFacesAreFound() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        val target = client.fetchFaceTarget("some-id")

        assertNull(target)
    }
}
