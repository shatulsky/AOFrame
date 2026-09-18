package dev.aoframe.cache

import androidx.test.platform.app.InstrumentationRegistry
import dev.aoframe.immich.ImmichAsset
import dev.aoframe.immich.ImmichAssetType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fast, deterministic tests for AssetCacheDatabase's SQLite logic -
 * exercises a fresh DB per test (deleted and recreated in setUp/tearDown)
 * so it doesn't collide with AssetCacheSyncLiveTest, which uses the same
 * DB name against real downloaded files. Instrumented (androidTest), not
 * Robolectric - see ImmichClientTest's header comment for why (a real
 * device's SQLite needs no shadowing anyway, so there was no real
 * upside to Robolectric here once it stopped being free).
 */
class AssetCacheDatabaseTest {
    private lateinit var db: AssetCacheDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("asset-cache.db")
        db = AssetCacheDatabase(context)
    }

    @After
    fun tearDown() {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("asset-cache.db")
    }

    private fun image(id: String, faceX: Float? = null, faceY: Float? = null) =
        ImmichAsset(id = id, type = ImmichAssetType.IMAGE, width = 100, height = 100, faceX = faceX, faceY = faceY)

    private fun video(id: String) =
        ImmichAsset(id = id, type = ImmichAssetType.VIDEO, width = 100, height = 100)

    @Test
    fun replaceAssetsUpsertsFreshAssetsAndEvictsStaleOnesWithTheirCachedFiles() {
        db.replaceAssets(listOf(image("a"), image("b")))
        db.recordCachedFile("a", "THUMBNAIL", "/path/a-thumb", 100)
        db.recordCachedFile("b", "THUMBNAIL", "/path/b-thumb", 100)

        // "b" drops out of the library - replaceAssets should remove both
        // its assets row and its cached_files rows.
        db.replaceAssets(listOf(image("a")))

        assertEquals(listOf("/path/a-thumb"), db.cachedFilesForAsset("a"))
        assertTrue(db.cachedFilesForAsset("b").isEmpty())
    }

    @Test
    fun computeStaleAssetIdsIdentifiesIdsNoLongerInTheFreshList() {
        db.replaceAssets(listOf(image("a"), image("b"), image("c")))

        val stale = db.computeStaleAssetIds(listOf(image("a")))

        assertEquals(setOf("b", "c"), stale.toSet())
    }

    @Test
    fun loadKnownFaceTargetsDistinguishesPresentWithCoordsPendingNullAndAbsent() {
        db.replaceAssets(
            listOf(
                image("has-face", faceX = 12f, faceY = 34f),
                image("queried-no-face"), // IMAGE, no face found, but only queried once so far
                video("a-video") // never face-queried at all - not "known"
            )
        )

        val known = db.loadKnownFaceTargets()

        assertEquals(12f to 34f, known["has-face"])
        // Only one attempt so far - still within the retry budget, so
        // AssetRepository should re-query it, not treat it as final.
        assertTrue(
            "a once-queried null result should still be retried, not cached final",
            !known.containsKey("queried-no-face")
        )
        assertTrue("a video should never be marked has_face_target", !known.containsKey("a-video"))
    }

    @Test
    fun loadKnownFaceTargetsTreatsANullResultAsFinalOnceRetriesAreExhausted() {
        // Simulates the same asset losing the face-detection race on
        // every sync for MAX_FACE_QUERY_ATTEMPTS syncs in a row.
        repeat(3) { db.replaceAssets(listOf(image("queried-no-face"))) }

        val known = db.loadKnownFaceTargets()

        assertTrue(known.containsKey("queried-no-face"))
        assertNull(known["queried-no-face"])
    }

    @Test
    fun loadKnownFaceTargetsResumesRetryingIfAFaceTurnsUpBeforeRetriesAreExhausted() {
        db.replaceAssets(listOf(image("pending"))) // attempt 1: still null
        db.replaceAssets(listOf(image("pending", faceX = 12f, faceY = 34f))) // attempt 2: face found

        val known = db.loadKnownFaceTargets()

        assertEquals(12f to 34f, known["pending"])
    }

    @Test
    fun countsByTypeGroupsAssetsByTheirType() {
        db.replaceAssets(
            listOf(
                image("photo-a"),
                image("photo-b"),
                video("video-a"),
                ImmichAsset(id = "live-a", type = ImmichAssetType.LIVE_PHOTO, videoId = "live-a-video", width = 100, height = 100)
            )
        )

        val counts = db.countsByType()

        assertEquals(2, counts["IMAGE"])
        assertEquals(1, counts["VIDEO"])
        assertEquals(1, counts["LIVE_PHOTO"])
    }

    @Test
    fun countsByTypeIsEmptyForAnEmptyLibrary() {
        assertTrue(db.countsByType().isEmpty())
    }

    @Test
    fun faceQueryStatsCountsFoundNoneAndPendingSeparately() {
        // Each replaceAssets() call evicts anything not in that call's
        // list (it's a full-replace, same as a real sync) - so "found"
        // and "gave-up" must be included in every one of these repeated
        // calls to survive, not passed as one-off single-asset lists.
        val core = listOf(image("found", faceX = 12f, faceY = 34f), image("gave-up"))
        repeat(3) { db.replaceAssets(core) } // exhausts "gave-up"'s retries -> "none"
        // Adding "still-pending" only now (after "gave-up" is already
        // exhausted) queries it exactly once -> "pending".
        db.replaceAssets(core + image("still-pending") + video("a-video")) // video not face-queried at all - not counted

        val stats = db.faceQueryStats()

        assertEquals(1, stats.found)
        assertEquals(1, stats.none)
        assertEquals(1, stats.pending)
    }

    @Test
    fun resetFaceQueriesClearsCoordsAndCountsSoEverythingIsRetried() {
        // Same "include every survivor in every call" reasoning as above.
        val core = listOf(image("found", faceX = 12f, faceY = 34f), image("gave-up"))
        repeat(3) { db.replaceAssets(core) }

        db.resetFaceQueries()

        val known = db.loadKnownFaceTargets()
        assertTrue("a previously-found face should be cleared back to pending", !known.containsKey("found"))
        assertTrue("a previously-given-up asset should be cleared back to pending", !known.containsKey("gave-up"))

        val stats = db.faceQueryStats()
        assertEquals(0, stats.found)
        assertEquals(0, stats.none)
        assertEquals(2, stats.pending)
    }

    @Test
    fun cachedFileRoundTripViaRecordCachedFileAndCachedFilePath() {
        db.replaceAssets(listOf(image("a")))

        assertNull(db.cachedFilePath("a", "VIDEO"))

        db.recordCachedFile("a", "VIDEO", "/path/a-video", 12345)

        assertEquals("/path/a-video", db.cachedFilePath("a", "VIDEO"))
    }
}
