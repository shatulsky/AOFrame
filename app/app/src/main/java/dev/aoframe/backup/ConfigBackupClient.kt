package dev.aoframe.backup

import android.content.Context
import android.util.Log
import dev.aoframe.immich.ImmichSecretsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "ConfigBackupClient"

// Mirrors MainActivity's own SYNC_MAX_ATTEMPTS/SYNC_RETRY_DELAY_MS
// bounded retry (same reasoning: an ordinary transient network blip
// shouldn't be treated the same as "nothing to restore"). Shorter delay
// than that one (5s) since this is one small request to the Pi over LAN,
// not a full Immich sync.
private const val MAX_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 2_000L

/**
 * Best-effort client for a config-backup server's endpoints (GET
 * /frameo/countdown/backup, GET /frameo/nightmode/backup, etc). Used only
 * to restore a config file wiped by a reinstall - never throws out to the
 * caller. A 404 (nothing cached yet) means "no backup available", not an
 * error, and isn't retried. The frame has never depended on this server
 * being reachable for anything it does, and this must not become the
 * first exception - unreachable after every retry should just leave the
 * caller's config at its normal defaults.
 */
object ConfigBackupClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Config-driven Pi base URL (immich-secrets.json's piBaseUrl) -
    // unset/blank means there's nothing to restore from, same as a Pi
    // that's genuinely unreachable, so this returns null immediately
    // without spending the retry loop on it.
    suspend fun fetchBackup(context: Context, path: String): JSONObject? = withContext(Dispatchers.IO) {
        val piBaseUrl = ImmichSecretsStore.load(context)?.piBaseUrl
        if (piBaseUrl.isNullOrBlank()) return@withContext null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val request = Request.Builder().url("$piBaseUrl$path").get().build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 404) return@withContext null
                    if (!response.isSuccessful) throw IOException("Pi backup fetch ${response.code}")
                    return@withContext JSONObject(response.body?.string().orEmpty())
                }
            } catch (error: Exception) {
                Log.w(TAG, "Pi backup fetch failed (attempt $attempt/$MAX_ATTEMPTS): $path", error)
                if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
            }
        }
        null
    }
}
