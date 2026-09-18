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
 * Fast, deterministic tests for AssetRepository's Live Photo dedup,
 * order-preservation, and known-face-target short-circuit logic - using
 * MockWebServer so no real Immich server/live data dependency. Runs as
 * an instrumented test, not Robolectric - see ImmichClientTest's header
 * comment for why. Complements AssetRepositoryLiveTest, which exercises
 * the same code against real data.
 */
class AssetRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: AssetRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val secrets = ImmichSecrets(baseUrl = server.url("/").toString().removeSuffix("/"), apiKey = "test-key")
        repository = AssetRepository(ImmichClient(secrets))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aLivePhotosPairedVideoIsRemovedFromThePlainVideoListOrderPreserved() = runBlocking {
        // fetchAssetsOfType("IMAGE")
        server.enqueue(
            MockResponse().setBody(
                """{"assets": {"items": [
                    {"id": "img1", "width": 1, "height": 1},
                    {"id": "live1", "livePhotoVideoId": "clip1", "width": 1, "height": 1}
                ]}}"""
            )
        )
        // fetchAssetsOfType("VIDEO")
        server.enqueue(
            MockResponse().setBody(
                """{"assets": {"items": [
                    {"id": "clip1", "width": 1, "height": 1},
                    {"id": "vid1", "width": 1, "height": 1}
                ]}}"""
            )
        )
        // face query for img1 (the only plain IMAGE - live1 is LIVE_PHOTO, not queried)
        server.enqueue(MockResponse().setBody("[]"))

        val result = repository.refreshAssets()

        assertEquals(listOf("img1", "live1", "vid1"), result.map { it.id })
        assertEquals(ImmichAssetType.IMAGE, result[0].type)
        assertEquals(ImmichAssetType.LIVE_PHOTO, result[1].type)
        assertEquals(ImmichAssetType.VIDEO, result[2].type)
    }

    @Test
    fun knownFaceTargetsAreReusedInsteadOfReQueried() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"assets": {"items": [{"id": "img1", "width": 1, "height": 1}]}}""")
        )
        server.enqueue(
            MockResponse().setBody("""{"assets": {"items": []}}""")
        )
        // Deliberately no face-query response enqueued - if the code
        // tried to query faces for img1 despite it being "known", this
        // test would fail with a MockWebServer "no more responses"
        // error rather than silently passing.

        val result = repository.refreshAssets(knownFaceTargets = mapOf("img1" to (10f to 20f)))

        assertEquals(2, server.requestCount)
        assertEquals(10f, result[0].faceX)
        assertEquals(20f, result[0].faceY)
    }

    @Test
    fun aKnownButAbsentFaceTargetIsReusedAsNoFaceNotReQueried() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"assets": {"items": [{"id": "img1", "width": 1, "height": 1}]}}""")
        )
        server.enqueue(
            MockResponse().setBody("""{"assets": {"items": []}}""")
        )

        // present-but-null: "already queried once, no face found" -
        // still shouldn't trigger a new query.
        val result = repository.refreshAssets(knownFaceTargets = mapOf("img1" to null))

        assertEquals(2, server.requestCount)
        assertNull(result[0].faceX)
        assertNull(result[0].faceY)
    }
}
