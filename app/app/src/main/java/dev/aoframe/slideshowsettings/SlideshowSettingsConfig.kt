package dev.aoframe.slideshowsettings

import android.content.Context
import android.util.Log
import dev.aoframe.backup.ConfigBackupClient
import org.json.JSONObject
import java.io.File

private const val TAG = "SlideshowSettingsStore"

// Sane outer bounds, not just validation - an unclamped value posted
// through the Pi form could produce a genuinely broken slideshow (a
// duration of 0/negative would spin the advance timer as fast as the
// device can decode; an unbounded zoom crops past the source image's
// own resolution into visible upscaling blur). Wide enough to cover any
// reasonable taste, narrow enough that a typo can't wedge the frame.
private const val MIN_DURATION_MS = 1_000L
private const val MAX_DURATION_MS = 60_000L
private const val MIN_END_ZOOM = 1.0f
private const val MAX_END_ZOOM = 3.0f

data class SlideshowSettingsConfig(
    val durationMs: Long = 6_000L,
    // Default matches KenBurnsMath.DEFAULT_END_SCALE - see that
    // constant's own comment for where 1.455f came from.
    val endZoom: Float = 1.455f
) {
    fun toJson(): JSONObject = JSONObject()
        .put("durationMs", durationMs)
        // Rounded to 3 decimals, not a raw Float->Double widening - a
        // plain `endZoom.toDouble()` reproduces the Float's exact binary
        // value at double precision (e.g. 1.455f -> 1.4550000429153442),
        // which is technically correct but renders as garbage digits in
        // the Pi form's number/range inputs. Round-tripping through
        // whole-thousandths keeps it at the precision this value is ever
        // actually specified/edited at.
        .put("endZoom", Math.round(endZoom * 1000.0) / 1000.0)

    companion object {
        fun fromJson(json: JSONObject): SlideshowSettingsConfig {
            val defaults = SlideshowSettingsConfig()
            return SlideshowSettingsConfig(
                durationMs = json.optLong("durationMs", defaults.durationMs)
                    .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
                endZoom = json.optDouble("endZoom", defaults.endZoom.toDouble())
                    .toFloat().coerceIn(MIN_END_ZOOM, MAX_END_ZOOM)
            )
        }
    }
}

/**
 * Photo duration + Ken Burns end-zoom, file-backed JSON in `filesDir` -
 * same pattern as CountdownStore/NightModeStore. `START_SCALE` (the "no
 * zoom" starting point) deliberately stays hardcoded in KenBurnsMath -
 * only the end-of-pan-zoom scale is configurable here.
 *
 * Not read via an in-memory cache the way CountdownStore is - this is
 * read once per slide (every several seconds), not once a second, so a
 * plain disk read each time (like NightModeStore's own 30s-poll pattern)
 * is cheap enough and needs no cache-invalidation bookkeeping on save().
 */
object SlideshowSettingsStore {
    private const val FILE_NAME = "slideshow-settings.json"

    fun load(context: Context): SlideshowSettingsConfig {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return SlideshowSettingsConfig()
        return try {
            SlideshowSettingsConfig.fromJson(JSONObject(file.readText()))
        } catch (error: Exception) {
            SlideshowSettingsConfig()
        }
    }

    fun save(context: Context, config: SlideshowSettingsConfig) {
        File(context.filesDir, FILE_NAME).writeText(config.toJson().toString())
    }

    // Same restore-on-missing-file mechanism as CountdownStore/
    // NightModeStore (see either's own comment) - only acts when
    // slideshow-settings.json is genuinely missing (a wipe/fresh install),
    // never to override an intentional live edit, and best-effort by
    // construction (ConfigBackupClient never throws).
    suspend fun restoreFromPiIfMissing(context: Context) {
        if (File(context.filesDir, FILE_NAME).exists()) return
        val json = ConfigBackupClient.fetchBackup(context, "/frameo/settings/slideshow-settings/backup") ?: return
        try {
            save(context, SlideshowSettingsConfig.fromJson(json))
            Log.i(TAG, "Restored slideshow settings from the Pi's backup")
        } catch (error: Exception) {
            Log.w(TAG, "Pi returned an unparsable slideshow-settings backup", error)
        }
    }
}
