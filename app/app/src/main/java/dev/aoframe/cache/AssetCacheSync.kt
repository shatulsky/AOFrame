package dev.aoframe.cache

import android.content.Context
import android.util.Log
import dev.aoframe.immich.AssetRepository
import dev.aoframe.immich.ImmichAsset
import dev.aoframe.immich.ImmichAssetType
import dev.aoframe.immich.ImmichClient
import dev.aoframe.immich.ImmichSecretsStore
import java.io.File

enum class CacheKind { THUMBNAIL, BACKDROP, VIDEO }

private const val TAG = "AssetCacheSync"

// Shared with SlideshowRenderer's persisted-blurred-backdrop cache (a
// locally-computed file, not one of the three kinds this class downloads
// from Immich, but stored in the same directory/cached_files table so it
// rides the same eviction path below).
fun assetCacheDir(context: Context): File = File(context.filesDir, "asset-cache")

/**
 * Incremental local disk cache sync. Downloads compressed bytes to disk
 * only - never decodes into memory here. That's the renderer's job, a
 * much narrower budget (decode-ahead stays ~1-ahead/1-behind regardless
 * of how much is cached on disk).
 *
 * Files live under the app's durable filesDir, not cacheDir - cacheDir
 * is reclaimable by the OS under storage pressure, which would silently
 * undermine the whole point of caching for offline-resilience.
 */
class AssetCacheSync(
    context: Context,
    private val client: ImmichClient,
    private val db: AssetCacheDatabase
) {
    private val cacheDir = assetCacheDir(context)

    // Video-transcode gate sidecar - video fetches go here instead of
    // straight to Immich. Immich's own /video/playback never waits for
    // its async transcode job to finish (falls back to serving the raw
    // original immediately), and this app used to cache whatever that
    // returned permanently on the first sync that happened to race ahead
    // of transcoding. The gate only ever returns 200 once a real
    // transcoded file exists on disk, a plain 404 otherwise - see
    // downloadMissing()'s per-asset try/catch below for how that 404
    // turns into "just retry next sync" instead of a bad cache record or
    // an aborted sync pass. Read from config (immich-secrets.json's
    // videoGateBaseUrl) - blank if unset, which makes every VIDEO
    // download URL malformed and throw, caught by
    // downloadMissingSafely()'s per-asset try/catch same as any other
    // gate failure (logged, retried next sync, never blocks other
    // assets/kinds).
    private val videoGateBaseUrl = ImmichSecretsStore.load(context)?.videoGateBaseUrl.orEmpty()

    // Full sync pass: refresh the asset list (reusing already-cached
    // face targets instead of re-querying), evict anything no longer
    // present, then download any missing bytes in rotation (list) order.
    // Called from MainActivity's own in-process timer/coroutine
    // coordinator (see startSlideshowSync()), not WorkManager/
    // AlarmManager - this app is meant to run continuously in the
    // foreground, so a lighter-weight scheduling mechanism is enough.
    suspend fun sync(): List<ImmichAsset> {
        val knownFaceTargets = db.loadKnownFaceTargets()
        val freshAssets = AssetRepository(client).refreshAssets(knownFaceTargets)

        val staleIds = db.computeStaleAssetIds(freshAssets)
        for (id in staleIds) {
            db.cachedFilesForAsset(id).forEach { path -> File(path).delete() }
        }
        db.replaceAssets(freshAssets)

        for (asset in freshAssets) {
            // Each kind independently try/caught - a video that isn't
            // transcoded yet (a routine, expected 404 from the gate
            // above, not an exceptional condition) used to throw all
            // the way out of this loop with nothing catching it,
            // aborting thumbnail/backdrop downloads for every asset
            // still to come in this same pass. Logged, not silent -
            // but never allowed to block unrelated assets/kinds.
            downloadMissingSafely(asset, CacheKind.THUMBNAIL)
            downloadMissingSafely(asset, CacheKind.BACKDROP)
            if (asset.type != ImmichAssetType.IMAGE) {
                downloadMissingSafely(asset, CacheKind.VIDEO)
            }
        }

        return freshAssets
    }

    private suspend fun downloadMissingSafely(asset: ImmichAsset, kind: CacheKind) {
        try {
            downloadMissing(asset, kind)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to download ${kind.name} for ${asset.id} - will retry next sync", error)
        }
    }

    private suspend fun downloadMissing(asset: ImmichAsset, kind: CacheKind) {
        val existing = db.cachedFilePath(asset.id, kind.name)
        if (existing != null && File(existing).exists()) return

        val destination = File(cacheDir, "${asset.id}-${kind.name.lowercase()}")
        client.downloadToFile(immichPathFor(asset, kind), destination)
        db.recordCachedFile(asset.id, kind.name, destination.absolutePath, destination.length())
    }

    // Thumbnail/backdrop are the same two Immich endpoints, at different
    // sizes - backdrop deliberately uses the smaller `size=thumbnail`
    // variant: a heavily downscaled source stretched back up reads as a
    // natural soft blur with no GPU cost, unlike a real blur filter which
    // was visibly janky on this
    // device's GPU). VIDEO is an absolute URL, not an Immich-relative
    // path - see videoGateBaseUrl's comment above and
    // ImmichClient.downloadToFile()'s absolute-URL handling.
    private fun immichPathFor(asset: ImmichAsset, kind: CacheKind): String = when (kind) {
        CacheKind.THUMBNAIL -> "/api/assets/${asset.id}/thumbnail?size=preview"
        CacheKind.BACKDROP -> "/api/assets/${asset.id}/thumbnail?size=thumbnail"
        CacheKind.VIDEO -> "$videoGateBaseUrl/video/${asset.videoId ?: asset.id}"
    }
}
