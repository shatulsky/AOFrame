package dev.aoframe.cache

import androidx.test.platform.app.InstrumentationRegistry
import dev.aoframe.immich.ImmichClient
import dev.aoframe.immich.ImmichSecretsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Exercises AssetCacheSync against the real Immich server and real disk
 * I/O - not a unit test, a live-hardware/network sanity check. Requires
 * immich-secrets.json already pushed to this app's internal storage.
 */
class AssetCacheSyncLiveTest {
    @Ignore("Live sanity check - needs immich-secrets.json pushed to a real device with a reachable Immich server. Remove @Ignore to run manually.")
    @Test
    fun syncDownloadsAndRecordsFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = ImmichSecretsStore.load(context)
            ?: fail("immich-secrets.json not found in app files dir - push it first")
                .let { return }

        // Fresh manifest DB for this test - deletes any prior run's DB
        // and cached files so counts below are unambiguous.
        context.deleteDatabase("asset-cache.db")
        File(context.filesDir, "asset-cache").deleteRecursively()

        val db = AssetCacheDatabase(context)
        val sync = AssetCacheSync(context, ImmichClient(secrets), db)

        val firstPassAssets = runBlocking { sync.sync() }
        assertTrue("expected at least one asset from the live library", firstPassAssets.isNotEmpty())

        val cacheDir = File(context.filesDir, "asset-cache")
        val cachedFileCount = cacheDir.listFiles()?.size ?: 0
        val totalBytes = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

        // Second pass: should download nothing new (everything already
        // cached). Face queries may still repeat for any IMAGE asset that
        // came back with no face on the first pass - that's retried for
        // up to MAX_FACE_QUERY_ATTEMPTS syncs (see AssetCacheDatabase) in
        // case Immich's ML pipeline just hadn't finished yet - so this
        // only confirms the "skip what's already cached/known" logic
        // doesn't error, not a precise assertion on call counts.
        val secondPassAssets = runBlocking { sync.sync() }
        val cachedFileCountAfterSecondPass = cacheDir.listFiles()?.size ?: 0

        val distinctIds = firstPassAssets.map { it.id }.toSet()
        val perKindCounts = listOf("thumbnail", "backdrop", "video").associateWith { kind ->
            firstPassAssets.count { db.cachedFilePath(it.id, kind.uppercase()) != null }
        }

        println(
            "AssetCacheSyncLiveTest: first pass ${firstPassAssets.size} asset(s) " +
                "(${distinctIds.size} distinct id(s)), $cachedFileCount cached file(s) on disk " +
                "($totalBytes bytes total), manifest counts: $perKindCounts; " +
                "second pass ${secondPassAssets.size} asset(s), " +
                "$cachedFileCountAfterSecondPass cached file(s) (should be unchanged)"
        )

        assertTrue(
            "second pass shouldn't add new cached files",
            cachedFileCountAfterSecondPass == cachedFileCount
        )
    }
}
