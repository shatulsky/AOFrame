package dev.aoframe.webcam

import android.content.Context
import org.json.JSONObject
import java.io.File

data class WebcamTestModeConfig(val enabled: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled)

    companion object {
        fun fromJson(json: JSONObject): WebcamTestModeConfig =
            WebcamTestModeConfig(enabled = json.optBoolean("enabled", false))
    }
}

/**
 * Debug-only toggle for showing only webcam clips, skipping the normal
 * Immich rotation. Same persisted-JSON-in-filesDir pattern as
 * nightmode/NightModeConfig.kt, but with no remote proxy form (a plain
 * curl POST to LocalControlServer's /action/webcam-test-mode is enough
 * for what's fundamentally a developer switch, not a real feature).
 * Defaults to disabled - a fresh install/rebuild always shows the normal
 * blended rotation until explicitly toggled on for testing.
 */
object WebcamTestModeStore {
    private const val FILE_NAME = "webcam-test-mode.json"

    fun load(context: Context): WebcamTestModeConfig {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return WebcamTestModeConfig()
        return try {
            WebcamTestModeConfig.fromJson(JSONObject(file.readText()))
        } catch (error: Exception) {
            WebcamTestModeConfig()
        }
    }

    fun save(context: Context, config: WebcamTestModeConfig) {
        File(context.filesDir, FILE_NAME).writeText(config.toJson().toString())
    }
}
