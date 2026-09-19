package dev.aoframe.control

import androidx.test.platform.app.InstrumentationRegistry
import dev.aoframe.countdown.CountdownStore
import dev.aoframe.nightmode.NightModeStore
import dev.aoframe.slideshowsettings.SlideshowSettingsStore
import dev.aoframe.webcam.WebcamSyncState
import dev.aoframe.webcam.WebcamTestModeStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private val JSON = "application/json".toMediaType()

/**
 * End-to-end tests for LocalControlServer's routes, hitting the real
 * NanoHTTPD server over loopback HTTP (not a mock) - a real Android
 * runtime is needed anyway for org.json/Context (see ImmichClientTest's
 * header comment), so there's no cost to exercising the real server
 * rather than calling `serve()` directly.
 *
 * The config stores this server delegates to (NightModeStore,
 * CountdownStore, ...) are exercised more thoroughly by their own *Store
 * tests - these tests focus on routing, method handling, and callback
 * wiring, always driving state through this server's own POST endpoints
 * rather than assuming a particular starting state (CountdownStore in
 * particular caches in-process across the whole instrumentation run - see
 * CountdownStoreTest's header comment).
 */
class LocalControlServerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val http = OkHttpClient()
    private val baseUrl = "http://127.0.0.1:$LOCAL_CONTROL_PORT"

    private var refreshCacheCalls = AtomicInteger(0)
    private var resetFacesCalls = AtomicInteger(0)
    private var reshuffleCalls = AtomicInteger(0)
    private var immichWebhookCalls = AtomicInteger(0)
    private var refreshWebcamCalls = AtomicInteger(0)
    private var webcamTestModeChangedCalls = AtomicInteger(0)

    private lateinit var server: LocalControlServer

    @Before
    fun setUp() {
        for (name in listOf("night-mode.json", "countdown.json", "slideshow-settings.json", "webcam-test-mode.json", "webcam-sync-state.json")) {
            File(context.filesDir, name).delete()
        }
        server = LocalControlServer(
            context = context,
            onRefreshCacheRequested = { refreshCacheCalls.incrementAndGet() },
            onResetFacesRequested = { resetFacesCalls.incrementAndGet() },
            onReshuffleRequested = { reshuffleCalls.incrementAndGet() },
            onImmichWebhook = { immichWebhookCalls.incrementAndGet() },
            onRefreshWebcamRequested = { refreshWebcamCalls.incrementAndGet() },
            onWebcamTestModeChanged = { webcamTestModeChangedCalls.incrementAndGet() },
            statusProvider = { JSONObject().put("ok", true) }
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        for (name in listOf("night-mode.json", "countdown.json", "slideshow-settings.json", "webcam-test-mode.json", "webcam-sync-state.json")) {
            File(context.filesDir, name).delete()
        }
    }

    private fun get(path: String) = http.newCall(Request.Builder().url(baseUrl + path).get().build()).execute()

    private fun post(path: String, body: String = "{}") =
        http.newCall(Request.Builder().url(baseUrl + path).post(body.toRequestBody(JSON)).build()).execute()

    @Test
    fun statusReturnsWhateverTheStatusProviderProduces() {
        val response = get("/status")

        assertEquals(200, response.code)
        assertTrue(JSONObject(response.body!!.string()).getBoolean("ok"))
    }

    @Test
    fun unknownPathReturns404() {
        assertEquals(404, get("/action/does-not-exist").code)
    }

    @Test
    fun postOnlyRoutesReject405ForGet() {
        assertEquals(405, get("/action/refresh-cache").code)
    }

    @Test
    fun refreshCacheInvokesItsCallbackAndAcksImmediately() {
        val response = post("/action/refresh-cache")

        assertEquals(200, response.code)
        assertEquals("refreshing", JSONObject(response.body!!.string()).getString("status"))
        assertEquals(1, refreshCacheCalls.get())
    }

    @Test
    fun resetFacesReshuffleAndImmichWebhookEachInvokeTheirOwnCallback() {
        post("/action/reset-faces")
        post("/action/reshuffle")
        post("/action/immich-webhook")

        assertEquals(1, resetFacesCalls.get())
        assertEquals(1, reshuffleCalls.get())
        assertEquals(1, immichWebhookCalls.get())
    }

    @Test
    fun refreshWebcamInvokesItsCallback() {
        post("/action/refresh-webcam")

        assertEquals(1, refreshWebcamCalls.get())
    }

    @Test
    fun nightModeGetReturnsWhatWasJustPosted() {
        val posted = post("/action/night-mode", """{"enabled": true, "sleepTime": "23:00", "wakeTime": "06:30"}""")
        assertEquals(200, posted.code)
        val postedJson = JSONObject(posted.body!!.string())
        assertTrue(postedJson.getBoolean("enabled"))

        val fetched = JSONObject(get("/action/night-mode").body!!.string())
        assertEquals("23:00", fetched.getString("sleepTime"))
        assertEquals("06:30", fetched.getString("wakeTime"))
        assertEquals(NightModeStore.load(context).sleepTime, fetched.getString("sleepTime"))
    }

    @Test
    fun countdownGetReturnsWhatWasJustPosted() {
        post("/action/countdown", """{"enabled": true, "targetDate": "2027-01-01T00:00", "label": "NYE", "precision": "daysOnly"}""")

        val fetched = JSONObject(get("/action/countdown").body!!.string())
        assertEquals("NYE", fetched.getString("label"))
        assertEquals("daysOnly", fetched.getString("precision"))
        assertEquals(CountdownStore.load(context).label, fetched.getString("label"))
    }

    @Test
    fun slideshowSettingsPostClampsOutOfRangeValuesInTheEchoedResponse() {
        val response = post("/action/slideshow-settings", """{"durationMs": 999999, "endZoom": 10.0}""")

        val json = JSONObject(response.body!!.string())
        assertEquals(60_000L, json.getLong("durationMs"))
        assertEquals(3.0, json.getDouble("endZoom"), 0.001)
        assertEquals(60_000L, SlideshowSettingsStore.load(context).durationMs)
    }

    @Test
    fun webcamTestModePostSavesConfigAndInvokesItsCallback() {
        val response = post("/action/webcam-test-mode", """{"enabled": true}""")

        assertTrue(JSONObject(response.body!!.string()).getBoolean("enabled"))
        assertTrue(WebcamTestModeStore.load(context).enabled)
        assertEquals(1, webcamTestModeChangedCalls.get())
    }

    @Test
    fun webcamSyncStatusReflectsRecordedTimestamps() {
        WebcamSyncState.recordSynced(context, "front")

        val json = JSONObject(get("/action/webcam-sync-status").body!!.string())

        assertTrue(json.has("front"))
    }

    @Test
    fun webcamSyncStatusIsReadOnly() {
        assertEquals(405, post("/action/webcam-sync-status").code)
    }

    @Test
    fun statusIsReadOnly() {
        assertEquals(405, post("/status").code)
    }

    @Test
    fun immichWebhookRejectsGet() {
        assertFalse(get("/action/immich-webhook").isSuccessful)
        assertEquals(405, get("/action/immich-webhook").code)
    }
}
