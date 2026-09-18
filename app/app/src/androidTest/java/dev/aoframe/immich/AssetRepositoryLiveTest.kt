package dev.aoframe.immich

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Exercises AssetRepository against the real Immich server - not a unit
 * test, a live-hardware/network sanity check. Requires immich-secrets.json
 * already pushed to this app's internal storage (see ImmichSecretsStore's
 * header comment) - fails loudly rather than silently skipping if it's
 * missing, since a missing secrets file and a real connectivity/API
 * failure should look different.
 */
class AssetRepositoryLiveTest {
    @Test
    fun refreshAssetsReturnsRealData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = ImmichSecretsStore.load(context)
            ?: fail("immich-secrets.json not found in app files dir - push it first")
                .let { return }

        val repository = AssetRepository(ImmichClient(secrets))
        val assets = runBlocking { repository.refreshAssets() }

        assertNotNull(assets)
        val images = assets.count { it.type == ImmichAssetType.IMAGE }
        val liveCount = assets.count { it.type == ImmichAssetType.LIVE_PHOTO }
        val videos = assets.count { it.type == ImmichAssetType.VIDEO }
        val withFaces = assets.count { it.faceX != null }

        println(
            "AssetRepositoryLiveTest: ${assets.size} total - " +
                "$images image(s) ($withFaces with a targetable face), " +
                "$liveCount Live Photo(s), $videos video(s)"
        )
    }
}
