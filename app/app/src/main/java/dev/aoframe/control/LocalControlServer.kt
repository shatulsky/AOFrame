package dev.aoframe.control

import android.content.Context
import android.util.Log
import dev.aoframe.countdown.CountdownConfig
import dev.aoframe.countdown.CountdownStore
import dev.aoframe.nightmode.NightModeConfig
import dev.aoframe.nightmode.NightModeStore
import dev.aoframe.slideshowsettings.SlideshowSettingsConfig
import dev.aoframe.slideshowsettings.SlideshowSettingsStore
import dev.aoframe.webcam.WebcamSyncState
import dev.aoframe.webcam.WebcamTestModeConfig
import dev.aoframe.webcam.WebcamTestModeStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

private const val TAG = "LocalControlServer"
const val LOCAL_CONTROL_PORT = 8099

/**
 * On-device local control server - NanoHTTPD, not a heavier embedded
 * server framework, for a narrower dependency surface. Single server
 * hosting every remote-config/status action this app exposes, rather
 * than one per feature.
 *
 * Config-style actions (night-mode, countdown, slideshow settings) all
 * follow the same shape: `GET` returns the current config as JSON, `POST`
 * (JSON body) saves a new one and echoes it back. The device owns the
 * config; anything remote (an admin panel, a backup server) is a thin
 * remote-editing proxy into this, not the source of truth.
 *
 * `refresh-cache`/`status`/`reset-faces` are a different shape - not
 * config the device owns, but actions/reads against live app state.
 * `onRefreshCacheRequested`, `onResetFacesRequested`, `onImmichWebhook`,
 * and `statusProvider` are callbacks into MainActivity rather than this
 * class owning that state itself - NanoHTTPD serves requests on its own
 * worker thread, so all four need to be safe to call off the main thread
 * (see MainActivity's own comments on each). `reset-faces` clears every
 * asset's cached face result so the next sync re-queries the whole
 * library under whatever the current heuristic is - see
 * `AssetCacheDatabase.resetFaceQueries()`.
 *
 * `immich-webhook`: point Immich's `AssetCreate` workflow directly at
 * this device (`http://<device-ip>:8099/action/immich-webhook`) so a new
 * upload triggers a sync immediately rather than waiting for the next
 * periodic pass. Deliberately a *separate* endpoint from `refresh-cache`,
 * not a shared one: a webhook firing because someone uploaded a photo
 * shouldn't visibly interrupt whatever's currently on screen the way the
 * manual refresh action does (see MainActivity's `runBackgroundSync()` vs
 * `refreshSlideshow()`).
 */
class LocalControlServer(
    private val context: Context,
    private val onRefreshCacheRequested: () -> Unit,
    private val onResetFacesRequested: () -> Unit,
    private val onReshuffleRequested: () -> Unit,
    private val onImmichWebhook: () -> Unit,
    private val onRefreshWebcamRequested: () -> Unit,
    private val onWebcamTestModeChanged: () -> Unit,
    private val statusProvider: () -> JSONObject
) : NanoHTTPD(LOCAL_CONTROL_PORT) {
    override fun serve(session: IHTTPSession): Response = try {
        when (session.uri) {
            "/action/night-mode" -> handleNightMode(session)
            "/action/countdown" -> handleCountdown(session)
            "/action/slideshow-settings" -> handleSlideshowSettings(session)
            "/action/refresh-cache" -> handleRefreshCache(session)
            "/action/reset-faces" -> handleResetFaces(session)
            "/action/reshuffle" -> handleReshuffle(session)
            "/action/immich-webhook" -> handleImmichWebhook(session)
            "/action/webcam-test-mode" -> handleWebcamTestMode(session)
            "/action/refresh-webcam" -> handleRefreshWebcam(session)
            "/action/webcam-sync-status" -> handleWebcamSyncStatus(session)
            "/status" -> handleStatus(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    } catch (error: Exception) {
        Log.e(TAG, "request to ${session.uri} failed", error)
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, error.message ?: "error")
    }

    private fun handleRefreshCache(session: IHTTPSession): Response = when (session.method) {
        Method.POST -> {
            // Fire-and-forget - a real sync can take a while depending on
            // library size, so this acks immediately rather than making
            // the Pi's HTTP client (and whoever's waiting on its
            // response) block on the whole thing.
            onRefreshCacheRequested()
            jsonResponse(JSONObject().put("status", "refreshing"))
        }
        else -> methodNotAllowed()
    }

    // Distinct from refresh-cache: an ordinary refresh reuses each
    // asset's already-resolved face target (found, or given up after
    // MAX_FACE_QUERY_ATTEMPTS - see AssetCacheDatabase) rather than
    // re-checking it, by design (that's what keeps a normal sync cheap).
    // This action is the explicit escape hatch - clears every asset's
    // face state first, then triggers the same fire-and-forget refresh,
    // so the whole library gets re-queried under whatever the current
    // face-targeting rules are.
    private fun handleResetFaces(session: IHTTPSession): Response = when (session.method) {
        Method.POST -> {
            onResetFacesRequested()
            jsonResponse(JSONObject().put("status", "resetting"))
        }
        else -> methodNotAllowed()
    }

    // Manual "shuffle photos" action (/frameo/settings) - a manual
    // trigger alongside the automatic per-loop shuffle, reordering the
    // current rotation on demand without touching what's cached/synced,
    // so unlike refresh-cache/reset-faces this never talks to Immich.
    private fun handleReshuffle(session: IHTTPSession): Response = when (session.method) {
        Method.POST -> {
            onReshuffleRequested()
            jsonResponse(JSONObject().put("status", "reshuffled"))
        }
        else -> methodNotAllowed()
    }

    private fun handleImmichWebhook(session: IHTTPSession): Response = when (session.method) {
        Method.POST -> {
            // Same fire-and-forget reasoning as refresh-cache above -
            // Immich doesn't need to wait on a full sync to get its 200.
            // Payload (the created asset's id/metadata) is ignored - a
            // full re-sync + diff already happens either way.
            onImmichWebhook()
            jsonResponse(JSONObject().put("status", "syncing"))
        }
        else -> methodNotAllowed()
    }

    private fun handleStatus(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> jsonResponse(statusProvider())
        else -> methodNotAllowed()
    }

    private fun handleNightMode(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> jsonResponse(NightModeStore.load(context).toJson())
        Method.POST -> {
            val json = readJsonBody(session)
            val config = NightModeConfig(
                enabled = json.optBoolean("enabled", false),
                sleepTime = json.optString("sleepTime", "01:00"),
                wakeTime = json.optString("wakeTime", "08:00")
            )
            NightModeStore.save(context, config)
            jsonResponse(config.toJson())
        }
        else -> methodNotAllowed()
    }

    private fun handleCountdown(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> jsonResponse(CountdownStore.load(context).toJson())
        Method.POST -> {
            val json = readJsonBody(session)
            val config = CountdownConfig(
                enabled = json.optBoolean("enabled", false),
                targetDate = json.optString("targetDate", ""),
                label = json.optString("label", ""),
                precision = json.optString("precision", "full")
            )
            CountdownStore.save(context, config)
            jsonResponse(config.toJson())
        }
        else -> methodNotAllowed()
    }

    // Photo duration + Ken Burns end-zoom (/frameo/slideshow-settings -
    // see SlideshowSettingsConfig's own doc comment). Same GET-returns-
    // current/POST-saves-and-echoes shape as night-mode/countdown above -
    // SlideshowSettingsConfig.fromJson() itself clamps both fields to
    // their sane bounds, so a POST with an out-of-range value is
    // silently clamped rather than rejected.
    private fun handleSlideshowSettings(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> jsonResponse(SlideshowSettingsStore.load(context).toJson())
        Method.POST -> {
            val config = SlideshowSettingsConfig.fromJson(readJsonBody(session))
            SlideshowSettingsStore.save(context, config)
            jsonResponse(config.toJson())
        }
        else -> methodNotAllowed()
    }

    // An admin panel's "force update on frame" button lands here -
    // distinct from refresh-cache: only re-syncs webcam clips
    // (MainActivity.refreshWebcamOnly()), never touches Immich, and never
    // interrupts what's currently showing (splices into the existing
    // rotation the same way a background sync does).
    private fun handleRefreshWebcam(session: IHTTPSession): Response = when (session.method) {
        Method.POST -> {
            onRefreshWebcamRequested()
            jsonResponse(JSONObject().put("status", "refreshing-webcam"))
        }
        else -> methodNotAllowed()
    }

    // Debug-only "show only webcam clips" switch, exposed via an admin
    // panel toggle. Same GET-current/POST-saves-and-echoes shape as night-mode/
    // countdown above, but with one deliberate difference: flipping this
    // one is explicitly disruptive (triggers refreshSlideshow() right
    // after saving) - the whole point of this switch is to see its effect
    // immediately for testing, unlike night-mode/countdown's schedule-only
    // changes, which naturally apply on their own at the next trigger time
    // rather than needing an immediate visible jump. Without this, saving
    // the toggle silently does nothing until whatever sync happens to run
    // next (the 30-min safety net, in the worst case).
    private fun handleWebcamTestMode(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> jsonResponse(WebcamTestModeStore.load(context).toJson())
        Method.POST -> {
            val json = readJsonBody(session)
            val config = WebcamTestModeConfig(enabled = json.optBoolean("enabled", false))
            WebcamTestModeStore.save(context, config)
            onWebcamTestModeChanged()
            jsonResponse(config.toJson())
        }
        else -> methodNotAllowed()
    }

    // Backs the admin panel's per-camera "last synced to frame" column -
    // distinct from the video-transcode gate's own /webcam/status, which
    // only knows when it last *captured* a clip, not whether this frame
    // ever actually downloaded it. Read-only, GET-only - there's nothing
    // to POST here.
    private fun handleWebcamSyncStatus(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> {
            val json = JSONObject()
            for ((camId, at) in WebcamSyncState.load(context)) json.put(camId, at)
            jsonResponse(json)
        }
        else -> methodNotAllowed()
    }

    private fun readJsonBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return JSONObject(files["postData"] ?: "{}")
    }

    private fun jsonResponse(json: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())

    private fun methodNotAllowed(): Response =
        newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
}
