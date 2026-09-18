package dev.aoframe.immich

/**
 * Fetches images + videos, defensively dedups a Live Photo's paired clip
 * out of the plain video list, and resolves a face-targeted Ken Burns
 * origin for plain photos only - order-preserving.
 */
class AssetRepository(private val client: ImmichClient) {
    // knownFaceTargets: face coordinates already resolved and cached by
    // the caching layer (dev.aoframe.cache.AssetCacheDatabase) for
    // assets already seen in a previous sync - a *present* entry mapped
    // to null means "already queried, no face found" (still valid cached
    // knowledge, not re-queried); an *absent* entry means "never queried
    // yet". Without this, every sync would re-query faces for the whole
    // library every time.
    suspend fun refreshAssets(
        knownFaceTargets: Map<String, Pair<Float, Float>?> = emptyMap()
    ): List<ImmichAsset> {
        val images = client.fetchAssetsOfType("IMAGE")
        val rawVideos = client.fetchAssetsOfType("VIDEO")

        // Immich is documented to hide a Live Photo's paired motion clip
        // from normal listings (it should never come back from a plain
        // type:VIDEO search) - but defensively drop any video whose id
        // matches a still's videoId anyway.
        val livePhotoVideoIds = images
            .filter { it.type == ImmichAssetType.LIVE_PHOTO }
            .mapNotNull { it.videoId }
            .toSet()
        val videos = if (livePhotoVideoIds.isEmpty()) {
            rawVideos
        } else {
            rawVideos.filter { it.id !in livePhotoVideoIds }
        }

        // Face-targeted Ken Burns origin: plain photos only - videos and
        // Live Photos don't get it (nothing to zoom/pan toward on a
        // playing clip). Order-preserving pass.
        val targetedImages = images.map { image ->
            when {
                image.type != ImmichAssetType.IMAGE -> image
                knownFaceTargets.containsKey(image.id) -> {
                    val target = knownFaceTargets.getValue(image.id)
                    image.copy(faceX = target?.first, faceY = target?.second)
                }
                else -> {
                    val target = client.fetchFaceTarget(image.id)
                    if (target != null) image.copy(faceX = target.first, faceY = target.second) else image
                }
            }
        }

        return targetedImages + videos
    }
}
