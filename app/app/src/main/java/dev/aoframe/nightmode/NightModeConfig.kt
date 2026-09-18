package dev.aoframe.nightmode

import android.content.Context
import android.util.Log
import dev.aoframe.backup.ConfigBackupClient
import org.json.JSONObject
import java.io.File
import java.time.LocalTime

private const val TAG = "NightModeStore"

data class NightModeConfig(
    val enabled: Boolean = false,
    val sleepTime: String = "01:00",
    val wakeTime: String = "08:00"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("sleepTime", sleepTime)
        .put("wakeTime", wakeTime)

    companion object {
        fun fromJson(json: JSONObject): NightModeConfig = NightModeConfig(
            enabled = json.optBoolean("enabled", false),
            sleepTime = json.optString("sleepTime", "01:00"),
            wakeTime = json.optString("wakeTime", "08:00")
        )
    }
}

/**
 * Persists the night-mode sleep/wake schedule to the app's own internal
 * storage - same file-backed-JSON pattern as ImmichSecretsStore. Written
 * by LocalControlServer (an admin panel's /frameo/nightmode form posts
 * here), read by NightModeController's scheduling loop. Defaults to
 * disabled - a fresh install never starts sleeping/waking the screen
 * until the Pi form explicitly turns it on, same safe-default pattern as
 * MMM-Countdown's own "enabled" flag.
 */
object NightModeStore {
    private const val FILE_NAME = "night-mode.json"

    fun load(context: Context): NightModeConfig {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return NightModeConfig()
        return try {
            NightModeConfig.fromJson(JSONObject(file.readText()))
        } catch (error: Exception) {
            NightModeConfig()
        }
    }

    fun save(context: Context, config: NightModeConfig) {
        File(context.filesDir, FILE_NAME).writeText(config.toJson().toString())
    }

    // Scheduled-window check (config-based, not the display's actual
    // Wakefulness state - see NightModeTiming.isWithinWindow()'s own
    // comment) - used by MainActivity.syncAssets() to skip downloading
    // fresh webcam clips nobody's watching during the night window, same
    // reasoning and mechanism as pi-video-gate's own isFrameAsleep() skip
    // on the capture side (server.js). A manual wake for dev/testing
    // doesn't change this - see the optimization's own scoping discussion.
    fun isWithinSleepWindowNow(context: Context): Boolean {
        val config = load(context)
        if (!config.enabled) return false
        val nowMinutes = NightModeTiming.minutesOfDay(LocalTime.now())
        return NightModeTiming.isWithinWindow(
            nowMinutes,
            NightModeTiming.parseMinutesOfDay(config.sleepTime),
            NightModeTiming.parseMinutesOfDay(config.wakeTime)
        )
    }

    // Same restore-on-missing-file mechanism as CountdownStore's own
    // (see that class's comment) - only acts when night-mode.json is
    // genuinely missing, never when it exists with enabled=false, and
    // best-effort by construction (ConfigBackupClient never throws).
    suspend fun restoreFromPiIfMissing(context: Context) {
        if (File(context.filesDir, FILE_NAME).exists()) return
        val json = ConfigBackupClient.fetchBackup(context, "/frameo/nightmode/backup") ?: return
        try {
            save(context, NightModeConfig.fromJson(json))
            Log.i(TAG, "Restored night-mode config from the Pi's backup")
        } catch (error: Exception) {
            Log.w(TAG, "Pi returned an unparsable night-mode backup", error)
        }
    }
}
