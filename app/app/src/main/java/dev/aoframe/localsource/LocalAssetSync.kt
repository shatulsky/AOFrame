package dev.aoframe.localsource

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import dev.aoframe.cache.AssetCacheDatabase
import dev.aoframe.cache.CacheKind
import dev.aoframe.cache.assetCacheDir
import dev.aoframe.immich.ImmichAsset
import dev.aoframe.immich.ImmichAssetType
import java.io.File

private const val TAG = "LocalAssetSync"

// SlideshowRenderer.showVideo() never reads width/height for VIDEO-type
// assets (same reasoning as WebcamClipSync's own placeholder constants) -
// only IMAGE assets need real dimensions, decoded from the downloaded
// file itself below, since this server doesn't report them up front.
private const val VIDEO_PLACEHOLDER_WIDTH = 1920
private const val VIDEO_PLACEHOLDER_HEIGHT = 1080

/**
 * `photoSource: local` counterpart to AssetCacheSync - same
 * evict-stale-then-download-missing shape and the same
 * AssetCacheDatabase tables, just sourced from the AOFrame reference
 * server's plain CRUD photo API instead of Immich. No automatic face
 * detection (no face-detection story exists for this source) - faceX/
 * faceY only ever come from a manual PATCH /api/photos/:id/face call
 * (see LocalPhotoClient.LocalPhoto), otherwise null, same as an
 * undetected face on the Immich side. No Live Photo pairing either (the
 * reference server's upload API is single-file-per-photo) - stays null/
 * absent, which SlideshowRenderer already tolerates.
 *
 * Deliberately a separate class rather than a second code path inside
 * AssetCacheSync - the two sources share the cache/renderer contract
 * (both produce plain ImmichAsset rows) but nothing about the fetch
 * logic itself, so a shared class would just be an if/else split on
 * every method.
 *
 * No transcoding on this side - the reference server itself normalizes
 * every uploaded video to 8-bit SDR H.264 on upload (see
 * server/routes/photos.js's transcodeVideo()), so whatever this
 * downloads is already guaranteed playable.
 */
class LocalAssetSync(
    context: Context,
    private val client: LocalPhotoClient,
    private val db: AssetCacheDatabase
) {
    private val cacheDir = assetCacheDir(context)

    suspend fun sync(): List<ImmichAsset> {
        val photos = client.fetchPhotos()
        val freshAssets = photos.map { photo ->
            ImmichAsset(
                id = photo.id,
                type = if (photo.mimeType.startsWith("video/")) ImmichAssetType.VIDEO else ImmichAssetType.IMAGE,
                videoId = null,
                width = VIDEO_PLACEHOLDER_WIDTH,
                height = VIDEO_PLACEHOLDER_HEIGHT,
                faceX = photo.faceX,
                faceY = photo.faceY
            )
        }

        val staleIds = db.computeStaleAssetIds(freshAssets)
        for (id in staleIds) {
            db.cachedFilesForAsset(id).forEach { path -> File(path).delete() }
        }

        // Real dimensions only matter for IMAGE assets (PhotoFit.shouldCover()) -
        // resolved after downloading below, then folded into the rows this
        // actually persists, since the placeholder values above are only
        // correct for VIDEO.
        val resolvedAssets = freshAssets.map { asset ->
            if (asset.type != ImmichAssetType.IMAGE) return@map asset
            downloadMissingSafely(asset, CacheKind.THUMBNAIL)
            val dimensions = decodeImageDimensions(asset)
            if (dimensions != null) asset.copy(width = dimensions.first, height = dimensions.second) else asset
        }
        db.replaceAssets(resolvedAssets)

        for (asset in resolvedAssets) {
            if (asset.type == ImmichAssetType.VIDEO) {
                downloadMissingSafely(asset, CacheKind.VIDEO)
            } else {
                // Same file already downloaded above (to decode its
                // dimensions) also backs BACKDROP - this server doesn't
                // generate a separate downscaled variant the way Immich's
                // thumbnail endpoint does, so the renderer's own
                // decode-at-target-size sampling is what keeps the
                // blurred-backdrop pass cheap instead.
                linkBackdropToThumbnail(asset)
            }
        }

        return resolvedAssets
    }

    private suspend fun downloadMissingSafely(asset: ImmichAsset, kind: CacheKind) {
        try {
            val existing = db.cachedFilePath(asset.id, kind.name)
            if (existing != null && File(existing).exists()) return
            val destination = File(cacheDir, "${asset.id}-${kind.name.lowercase()}")
            client.downloadToFile(asset.id, destination)
            db.recordCachedFile(asset.id, kind.name, destination.absolutePath, destination.length())
        } catch (error: Exception) {
            Log.w(TAG, "Failed to download ${kind.name} for ${asset.id} - will retry next sync", error)
        }
    }

    private fun linkBackdropToThumbnail(asset: ImmichAsset) {
        if (db.cachedFilePath(asset.id, CacheKind.BACKDROP.name) != null) return
        val thumbnailPath = db.cachedFilePath(asset.id, CacheKind.THUMBNAIL.name) ?: return
        val file = File(thumbnailPath)
        db.recordCachedFile(asset.id, CacheKind.BACKDROP.name, thumbnailPath, file.length())
    }

    private fun decodeImageDimensions(asset: ImmichAsset): Pair<Int, Int>? {
        val path = db.cachedFilePath(asset.id, CacheKind.THUMBNAIL.name) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }
}
