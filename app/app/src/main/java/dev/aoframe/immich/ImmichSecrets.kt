package dev.aoframe.immich

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ImmichSecrets(
    // Blank (not required) when photoSource is "local" - Immich isn't
    // used at all in that mode. MainActivity.syncAssets() is what
    // actually enforces "configured" per mode, not this loader.
    val baseUrl: String = "",
    val apiKey: String = "",
    // "immich" (default/unset) or "local" - see localsource/LocalAssetSync.kt.
    // Local mode reads from the AOFrame reference server's CRUD photo API
    // (localServerBaseUrl) instead of Immich, for a fully self-hosted
    // setup with no Immich instance at all.
    val photoSource: String? = null,
    val localServerBaseUrl: String? = null,
    // Endpoints for two other optional LAN services this app can talk to
    // (a video-transcode gate, a config-backup server). Null when unset;
    // each consumer treats its endpoint as best-effort and simply runs
    // with that feature off if it's not configured.
    val videoGateBaseUrl: String? = null,
    val piBaseUrl: String? = null,
    // Weather widget coordinates - every user of this app has a different
    // location, so these are config, not a source default. weatherLatitude/
    // weatherLongitude drive the land forecast; weatherSeaLatitude/
    // weatherSeaLongitude are an independent, optional point for a
    // separate sea-surface-temperature reading (see WeatherClient).
    val weatherLatitude: Double? = null,
    val weatherLongitude: Double? = null,
    val weatherSeaLatitude: Double? = null,
    val weatherSeaLongitude: Double? = null,
    // BCP 47 language tag ("en", "uk", ...) for date/weekday/countdown
    // display text. Defaults to English when unset or unrecognized -
    // see AppLocale for the languages with dedicated translations vs.
    // the generic fallback everything else gets.
    val locale: String? = null
)

/**
 * Loads `{"baseUrl": "...", "apiKey": "...", "photoSource": "immich"|"local",
 * "localServerBaseUrl": "...", "videoGateBaseUrl": "...",
 * "piBaseUrl": "...", "weatherLatitude": ..., "weatherLongitude": ...,
 * "weatherSeaLatitude": ..., "weatherSeaLongitude": ...}` (everything
 * optional - `baseUrl`/`apiKey` only matter when `photoSource` is
 * "immich", the default) from the app's own internal storage. This file is
 * gitignored - copy `immich-secrets.example.json`
 * to `immich-secrets.json` and fill in real values, then push it onto the
 * device:
 *
 *   adb push immich-secrets.json /data/local/tmp/immich-secrets.json
 *   adb shell run-as <applicationId> cp /data/local/tmp/immich-secrets.json files/
 *
 * `run-as` only works against a debuggable build. A release build has
 * `debuggable=false`, so `run-as` fails with "package not debuggable" -
 * use root instead, if available on your device:
 *   adb shell su -c 'cp /data/local/tmp/immich-secrets.json /data/data/<applicationId>/files/immich-secrets.json && chown <uid>:<uid> ... && chmod 660 ...'
 * (match `<uid>` to `ls -la /data/data/<applicationId>` on the app's own
 * data dir - it's not a fixed value across installs).
 */
object ImmichSecretsStore {
    private const val FILE_NAME = "immich-secrets.json"

    fun load(context: Context): ImmichSecrets? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            ImmichSecrets(
                baseUrl = json.optString("baseUrl", ""),
                apiKey = json.optString("apiKey", ""),
                photoSource = json.optString("photoSource").ifBlank { null },
                localServerBaseUrl = json.optString("localServerBaseUrl").ifBlank { null },
                videoGateBaseUrl = json.optString("videoGateBaseUrl").ifBlank { null },
                piBaseUrl = json.optString("piBaseUrl").ifBlank { null },
                weatherLatitude = json.optDouble("weatherLatitude").takeUnless { it.isNaN() },
                weatherLongitude = json.optDouble("weatherLongitude").takeUnless { it.isNaN() },
                weatherSeaLatitude = json.optDouble("weatherSeaLatitude").takeUnless { it.isNaN() },
                weatherSeaLongitude = json.optDouble("weatherSeaLongitude").takeUnless { it.isNaN() },
                locale = json.optString("locale").ifBlank { null }
            )
        } catch (error: Exception) {
            null
        }
    }
}
