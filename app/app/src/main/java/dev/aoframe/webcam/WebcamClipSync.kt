package dev.aoframe.webcam

import android.content.Context
import android.util.Log
import dev.aoframe.cache.AssetCacheDatabase
import dev.aoframe.cache.assetCacheDir
import dev.aoframe.immich.ImmichAsset
import dev.aoframe.immich.ImmichAssetType
import dev.aoframe.immich.ImmichSecretsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "WebcamClipSync"

// Fixed placeholder, not probed per-clip - SlideshowRenderer.showVideo()
// never touches photoView/backdrop for VIDEO-type assets, so the real
// dimensions are never read.
private const val PLACEHOLDER_WIDTH = 1920
private const val PLACEHOLDER_HEIGHT = 1080

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Parallel, independent cache-registration pipeline for webcam clips
 * captured by an external video-transcode gate service. Deliberately
 * never touches AssetCacheDatabase's
 * `assets` table (Immich-owned only - see AssetCacheSync's own staleness
 * eviction, which operates exclusively on that table) - only registers
 * into the shared `cached_files` table via recordCachedFile(), so an
 * Immich resync can never evict a webcam clip. This class owns the
 * webcam clips' file lifecycle entirely instead (plain overwrite-in-place
 * on every sync).
 */
class WebcamClipSync(
    private val context: Context,
    private val db: AssetCacheDatabase,
    // Same video-transcode gate sidecar as AssetCacheSync.kt's own
    // videoGateBaseUrl (webcam clips are served by the same service).
    // Read from config (immich-secrets.json's videoGateBaseUrl) - blank
    // if unset, which fails every request below (already caught
    // per-call, same "fails open"/best-effort behavior as a gate that's
    // unreachable). Overridable (constructor param) for tests, same
    // pattern as WeatherClient's injectable baseUrl.
    private val videoGateBaseUrl: String = ImmichSecretsStore.load(context)?.videoGateBaseUrl.orEmpty()
) {
    private val cacheDir = assetCacheDir(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Any per-camera or manifest-fetch failure is caught here and simply
    // omits that camera from the returned list, never fails the whole
    // sync - some cameras may simply be unavailable at any given moment.
    // Caller (MainActivity.syncAssets()) merges this into the
    // normal Immich asset list.
    suspend fun sync(): List<ImmichAsset> = withContext(Dispatchers.IO) {
        val ids = try {
            fetchManifest()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to fetch webcam manifest - skipping this sync", error)
            return@withContext emptyList()
        }

        val assets = mutableListOf<ImmichAsset>()
        for (id in ids) {
            try {
                downloadClip(id)
                assets.add(
                    ImmichAsset(
                        id = "webcam-$id",
                        type = ImmichAssetType.VIDEO,
                        videoId = null,
                        width = PLACEHOLDER_WIDTH,
                        height = PLACEHOLDER_HEIGHT
                    )
                )
            } catch (error: Exception) {
                Log.w(TAG, "Failed to download webcam clip '$id' - omitting from this rotation", error)
            }
        }
        assets
    }

    // Backs the on-screen webcam-only button - the video-transcode gate's
    // own "force update" action, called directly from the frame (same
    // host it already talks to for the manifest/clips above). Fire-and-
    // forget on the gate's side (see its POST /webcam/refresh) - this
    // just triggers it, the caller is expected to poll isCycleInProgress()
    // afterwards.
    suspend fun forceRefreshOnPi() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$videoGateBaseUrl/webcam/refresh")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("webcam refresh trigger failed: ${response.code}")
        }
    }

    // Master on/off switch (an admin panel's /frameo/webcam toggle) -
    // backs MainActivity's decision to show/hide controlWebcamOnlyButton.
    // Fails open to `true` on any error, same direction as
    // isCycleInProgress() below - a gate that's briefly unreachable
    // shouldn't hide a button someone otherwise expects to be there;
    // pressing it while the gate is down just fails
    // gracefully like every other webcam action already does. Note this
    // is NOT what keeps webcam clips out of the shuffled rotation when
    // disabled - that's handled entirely server-side (server.js's
    // GET /webcam/clips returns an empty manifest when disabled, so
    // sync() below already returns nothing extra to check here).
    suspend fun isFeatureEnabled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$videoGateBaseUrl/webcam/enabled").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext true
                val body = response.body?.string().orEmpty()
                JSONObject(body).optBoolean("enabled", true)
            }
        } catch (error: Exception) {
            true
        }
    }

    // Polled after forceRefreshOnPi() - true while the Pi is still capturing
    // (see server.js's webcamCycleInProgress). A failed/unreachable check
    // reads as "not in progress" (fail open) so a control-server hiccup
    // can't strand the caller in an infinite poll loop.
    suspend fun isCycleInProgress(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$videoGateBaseUrl/webcam/status").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string().orEmpty()
                JSONObject(body).optBoolean("cycleInProgress", false)
            }
        } catch (error: Exception) {
            false
        }
    }

    private fun fetchManifest(): List<String> {
        val request = Request.Builder().url("$videoGateBaseUrl/webcam/clips").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("webcam manifest fetch failed: ${response.code}")
            val body = response.body?.string().orEmpty()
            val clips = JSONObject(body).optJSONArray("clips") ?: return emptyList()
            return (0 until clips.length()).map { clips.getString(it) }
        }
    }

    // Unconditional re-download on every sync, no freshness check - the
    // manifest carries no timestamp to compare against, and files are
    // small (a few MB) at a ~30-min cadence, so conditional-download
    // logic isn't worth the added complexity.
    private fun downloadClip(id: String) {
        val assetId = "webcam-$id"
        val destination = File(cacheDir, "$assetId-video")
        val request = Request.Builder().url("$videoGateBaseUrl/webcam/clip/$id").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("webcam clip '$id' fetch failed: ${response.code}")
            val responseBody = response.body ?: throw IOException("empty body for webcam clip '$id'")
            destination.parentFile?.mkdirs()
            responseBody.byteStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        db.recordCachedFile(assetId, "VIDEO", destination.absolutePath, destination.length())
        WebcamSyncState.recordSynced(context, id)
    }
}
