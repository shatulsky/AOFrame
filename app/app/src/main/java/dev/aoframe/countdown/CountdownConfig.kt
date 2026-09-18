package dev.aoframe.countdown

import android.content.Context
import android.util.Log
import dev.aoframe.backup.ConfigBackupClient
import org.json.JSONObject
import java.io.File

private const val TAG = "CountdownStore"

data class CountdownConfig(
    val enabled: Boolean = false,
    // "yyyy-MM-ddTHH:mm", empty = unset. Same combined-string shape any
    // config-backup form posts, parsed by CountdownTiming.
    val targetDate: String = "",
    val label: String = "",
    val precision: String = "full" // full | daysHours | daysOnly
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("targetDate", targetDate)
        .put("label", label)
        .put("precision", precision)

    companion object {
        fun fromJson(json: JSONObject): CountdownConfig = CountdownConfig(
            enabled = json.optBoolean("enabled", false),
            targetDate = json.optString("targetDate", ""),
            label = json.optString("label", ""),
            precision = json.optString("precision", "full")
        )
    }
}

/**
 * Persists the countdown config to the app's own internal storage - same
 * file-backed-JSON pattern as NightModeStore/ImmichSecretsStore. Config
 * is cached in memory after the first load and kept in sync on every
 * save() - unlike NightModeStore's every-30s reload, the countdown
 * widget re-renders every second (full precision needs it), so a cheap
 * in-memory read matters more here than it did there.
 *
 * Written by LocalControlServer (a remote admin form posts to
 * /frameo/countdown) - the device owns its config on-device rather than
 * being polled from a remote server, same pattern as night mode.
 */
object CountdownStore {
    private const val FILE_NAME = "countdown.json"

    @Volatile
    private var cached: CountdownConfig? = null

    fun load(context: Context): CountdownConfig {
        cached?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        val loaded = if (!file.exists()) {
            CountdownConfig()
        } else {
            try {
                CountdownConfig.fromJson(JSONObject(file.readText()))
            } catch (error: Exception) {
                CountdownConfig()
            }
        }
        cached = loaded
        return loaded
    }

    fun save(context: Context, config: CountdownConfig) {
        File(context.filesDir, FILE_NAME).writeText(config.toJson().toString())
        cached = config
    }

    // Called once at app startup (see MainActivity.onCreate) - only acts
    // when countdown.json is genuinely missing (a fresh install, or a
    // wipe from an instrumented test run), never when it exists with
    // enabled=false, which is a real, intentional "off" a backup restore
    // must not override. Best-effort: ConfigBackupClient already retries
    // internally and returns null rather than throwing on any failure
    // (backup server off/unreachable, nothing cached yet), so this just
    // leaves the config at its normal defaults in that case.
    suspend fun restoreFromPiIfMissing(context: Context) {
        if (File(context.filesDir, FILE_NAME).exists()) return
        val json = ConfigBackupClient.fetchBackup(context, "/frameo/countdown/backup") ?: return
        try {
            save(context, CountdownConfig.fromJson(json))
            Log.i(TAG, "Restored countdown config from the Pi's backup")
        } catch (error: Exception) {
            Log.w(TAG, "Pi returned an unparsable countdown backup", error)
        }
    }
}
