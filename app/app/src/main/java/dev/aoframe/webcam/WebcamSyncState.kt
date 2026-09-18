package dev.aoframe.webcam

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Per-camera "when did the frame last actually download this clip"
 * timestamps - answers a different question than the video-transcode
 * gate's own webcam-state.json (which only knows when it last captured a
 * clip, not whether/when this particular frame ever picked it up). Backs
 * a "last synced to frame" column per camera on the admin panel
 * (/frameo/webcam), alongside the gate's own capture stats.
 *
 * Same persisted-JSON-in-filesDir pattern as WebcamTestModeConfig/
 * NightModeConfig - plain `{camId: epochMillis}` map, updated by
 * WebcamClipSync.downloadClip() on every successful download (every sync,
 * not just when the clip changed - matches that class's own "unconditional
 * re-download" comment, so this always reflects the true last-download
 * time even if the bytes happened to be identical to before).
 */
object WebcamSyncState {
    private const val FILE_NAME = "webcam-sync-state.json"

    fun load(context: Context): Map<String, Long> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            json.keys().asSequence().associateWith { json.getLong(it) }
        } catch (error: Exception) {
            emptyMap()
        }
    }

    fun recordSynced(context: Context, camId: String) {
        val file = File(context.filesDir, FILE_NAME)
        val current = load(context)
        val updated = JSONObject()
        for ((id, at) in current) updated.put(id, at)
        updated.put(camId, System.currentTimeMillis())
        file.writeText(updated.toString())
    }
}
