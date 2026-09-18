package dev.aoframe.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private const val PAGE_SIZE = 200

// Minimum face size, as a fraction of the *largest* detected face's area
// in the same photo, to count as a real subject rather than a background
// bystander. A flat "% of the whole image" cutoff rejects almost every
// normally-framed photo of a person: a solo portrait at a normal,
// non-close-up distance rarely fills 2% of a full photo, so a fixed
// cutoff misclassifies it as faceless, while the photos that do pass are
// only close-up/portrait-distance shots. Sizing relative to the photo's
// own largest face instead adapts per photo: a lone face (or a
// same-distance group) is always kept regardless of its absolute size
// (the largest face is always >= itself), while a face much smaller than
// the photo's clear foreground subject(s) - a real background bystander
// - still gets dropped. Value chosen from real bounding-box data across
// several "photos with background people" examples: a genuine background
// face was consistently under ~8% of the largest face's area in the same
// photo, while every legitimate additional subject was at least ~16% -
// 0.15 sits with margin in that gap. Revisit if a wider sample suggests
// otherwise.
private const val MIN_FACE_AREA_FRACTION_OF_LARGEST = 0.15

/**
 * Direct-to-Immich HTTP client, called from the device rather than
 * proxied through a backend - plain OkHttp rather than a heavier client.
 *
 * No album scoping - shows every asset in the library, not a curated
 * subset.
 */
class ImmichClient(private val secrets: ImmichSecrets) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAssetsOfType(type: String): List<ImmichAsset> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ImmichAsset>()
        var page = 1
        while (true) {
            val requestBody = JSONObject().apply {
                put("size", PAGE_SIZE)
                put("page", page)
                put("type", type)
            }
            val response = requestObject("POST", "/api/search/metadata", requestBody)
            val assetsObject = response.optJSONObject("assets")
            val items = assetsObject?.optJSONArray("items") ?: JSONArray()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optBoolean("isTrashed", false) || item.optBoolean("isArchived", false)) {
                    continue
                }
                // A still with a paired motion clip (Apple Live Photo)
                // carries livePhotoVideoId pointing at that clip's own
                // (otherwise-hidden) asset id. Must check isNull()
                // explicitly first: org.json's optString(key, default)
                // returns the literal string "null" (not the fallback
                // default) when the key is present with a JSON null value
                // - confirmed against a real Immich response, which
                // caused assets with no Live Photo pairing to be
                // misclassified as one with videoId=="null", which then
                // 400'd downloading /api/assets/null/video/playback.
                val livePhotoVideoId = if (item.isNull("livePhotoVideoId")) {
                    null
                } else {
                    item.optString("livePhotoVideoId", "").ifEmpty { null }
                }
                results.add(
                    ImmichAsset(
                        id = item.getString("id"),
                        type = if (type == "IMAGE" && livePhotoVideoId != null) {
                            ImmichAssetType.LIVE_PHOTO
                        } else {
                            ImmichAssetType.valueOf(type)
                        },
                        videoId = livePhotoVideoId,
                        width = item.optInt("width"),
                        height = item.optInt("height")
                    )
                )
            }

            // Immich returns nextPage as a string even though the request
            // body's `page` field must be a number.
            val nextPage = assetsObject?.opt("nextPage")
            if (nextPage == null || nextPage == JSONObject.NULL || items.length() == 0) {
                break
            }
            page = nextPage.toString().toInt()
        }
        results
    }

    // Center of the bounding box enclosing every detected face big enough
    // to clear MIN_FACE_AREA_FRACTION_OF_LARGEST relative to this photo's
    // own largest face (i.e. every real subject, not a background
    // bystander), normalized to a 0-100 percentage of the image. Frames
    // every qualifying subject in a multi-person photo rather than
    // zooming toward just one of them (see MIN_FACE_AREA_FRACTION_OF_LARGEST's
    // own comment for why size is judged relative to the largest face,
    // not an absolute cutoff). Caller is responsible for only calling
    // this for plain IMAGE assets (videos/Live Photos have nothing to
    // zoom/pan toward on a playing clip). Note: re-queries on every call
    // - the caching layer (AssetCacheDatabase) is what makes this cache
    // permanently instead of re-querying every sync.
    suspend fun fetchFaceTarget(assetId: String): Pair<Float, Float>? = withContext(Dispatchers.IO) {
        val faces = try {
            requestArray("GET", "/api/faces?id=$assetId")
        } catch (error: Exception) {
            return@withContext null
        }
        if (faces.length() == 0) return@withContext null

        // First pass: find the largest face's area, so the second pass
        // can judge every other face relative to it.
        var largestArea = 0.0
        for (i in 0 until faces.length()) {
            val face = faces.getJSONObject(i)
            val area = (face.getDouble("boundingBoxX2") - face.getDouble("boundingBoxX1")) *
                (face.getDouble("boundingBoxY2") - face.getDouble("boundingBoxY1"))
            if (area > largestArea) largestArea = area
        }
        // A zero-area bounding box (malformed data) has nothing to
        // compare against - same as "no face found".
        if (largestArea <= 0.0) return@withContext null

        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var imageWidth = 0.0
        var imageHeight = 0.0

        for (i in 0 until faces.length()) {
            val face = faces.getJSONObject(i)
            val x1 = face.getDouble("boundingBoxX1")
            val x2 = face.getDouble("boundingBoxX2")
            val y1 = face.getDouble("boundingBoxY1")
            val y2 = face.getDouble("boundingBoxY2")

            val area = (x2 - x1) * (y2 - y1)
            if (area < largestArea * MIN_FACE_AREA_FRACTION_OF_LARGEST) continue

            imageWidth = face.getDouble("imageWidth")
            imageHeight = face.getDouble("imageHeight")
            if (x1 < minX) minX = x1
            if (y1 < minY) minY = y1
            if (x2 > maxX) maxX = x2
            if (y2 > maxY) maxY = y2
        }
        // The largest face itself always clears its own threshold, so
        // this can't actually be empty - defensive only.
        if (imageWidth <= 0.0) return@withContext null

        val faceX = (((minX + maxX) / 2) / imageWidth * 100).toFloat()
        val faceY = (((minY + maxY) / 2) / imageHeight * 100).toFloat()
        faceX to faceY
    }

    // Streams compressed bytes straight to disk - never holds a whole
    // asset in memory. The caching layer only ever caches encoded files
    // this way; decoding into memory is the renderer's job, a much
    // narrower budget.
    // `path` is normally relative (prefixed with secrets.baseUrl, i.e.
    // Immich itself) - but video fetches point at a video-transcode gate
    // instead (a different host entirely, see AssetCacheSync.kt's
    // immichPathFor()), so an already-absolute URL is used as-is rather
    // than double-prefixed.
    suspend fun downloadToFile(path: String, destination: File) = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) path else secrets.baseUrl + path
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", secrets.apiKey)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Immich API ${response.code} downloading $path")
            }
            val body = response.body ?: throw IOException("Empty body downloading $path")
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun requestObject(method: String, path: String, jsonBody: JSONObject?): JSONObject =
        JSONObject(rawRequest(method, path, jsonBody?.toString()))

    private fun requestArray(method: String, path: String): JSONArray =
        JSONArray(rawRequest(method, path, null))

    private fun rawRequest(method: String, path: String, jsonBody: String?): String {
        val requestBuilder = Request.Builder()
            .url(secrets.baseUrl + path)
            .header("x-api-key", secrets.apiKey)

        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post((jsonBody ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
            else -> error("Unsupported method: $method")
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Immich API ${response.code}: $text")
            }
            return text
        }
    }
}
