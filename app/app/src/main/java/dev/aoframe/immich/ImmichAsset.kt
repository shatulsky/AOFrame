package dev.aoframe.immich

enum class ImmichAssetType { IMAGE, VIDEO, LIVE_PHOTO }

/**
 * Per-asset shape returned by ImmichClient.fetchAssetsOfType().
 *
 * faceX/faceY: normalized 0-100 origin for face-targeted Ken Burns,
 * IMAGE assets only (null if no targetable face was found, or the asset
 * hasn't been face-queried yet).
 */
data class ImmichAsset(
    val id: String,
    val type: ImmichAssetType,
    val videoId: String? = null,
    val width: Int,
    val height: Int,
    val faceX: Float? = null,
    val faceY: Float? = null
)
