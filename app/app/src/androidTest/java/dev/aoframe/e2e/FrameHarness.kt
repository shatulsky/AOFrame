package dev.aoframe.e2e

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.aoframe.MainActivity
import dev.aoframe.cache.assetCacheDir
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double for everything the frame talks to over HTTP: the Open-Meteo
 * forecast and marine endpoints, the local photo server and the webcam part
 * of the video gate (pi-video-gate). One MockWebServer answers all by path.
 *
 * Never run these tests on the real frame: [reset] deletes the app's
 * stored settings and the launched app binds port 8099.
 */
class FakeBackend {
    /** The first [failForecastTimes] forecast calls answer 500, then the forecast is good. */
    @Volatile var failForecastTimes = 0
    val forecastCalls = AtomicInteger()

    /** The local photo server's list (GET /api/photos). */
    @Volatile var photosJson = PHOTOS_JSON

    /** The video gate's webcam on/off switch (GET /webcam/enabled). */
    @Volatile var webcamEnabled = true
    val webcamRefreshCalls = AtomicInteger()

    private val onePixelPng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )

    /** A 4x2 landscape PNG: on the portrait panel it gets the blurred backdrop. */
    private val widePng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAQAAAACCAIAAADwyuo0AAAAEElEQVR4nGNocFCAIwZkDgBeGgcBzSxwNQAAAABJRU5ErkJggg=="
    )

    /** A 1 s, 96x64 H.264 clip from the test APK's assets (made with ffmpeg's testsrc). */
    private val clipBytes: ByteArray by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("e2e-clip.mp4").use { it.readBytes() }
    }

    val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/v1/forecast") -> forecast()
                    path.startsWith("/v1/marine") -> json(MARINE_JSON)
                    path == "/api/photos" -> json(photosJson)
                    path == "/api/photos/$CLIP_ID" ->
                        MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(clipBytes))
                    path == "/api/photos/$WIDE_ID" ->
                        MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(widePng))
                    path == "/webcam/enabled" -> json("""{"enabled":$webcamEnabled}""")
                    path == "/webcam/clips" -> json(if (webcamEnabled) """{"clips":["$WEBCAM_ID"]}""" else """{"clips":[]}""")
                    path == "/webcam/clip/$WEBCAM_ID" ->
                        MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(clipBytes))
                    path == "/webcam/refresh" -> { webcamRefreshCalls.incrementAndGet(); json("""{"status":"started"}""") }
                    path == "/webcam/status" -> json("""{"enabled":$webcamEnabled,"cycleInProgress":false,"cameras":[]}""")
                    path.startsWith("/api/photos/") ->
                        MockResponse().setHeader("Content-Type", "image/png")
                            .setBody(okio.Buffer().write(onePixelPng))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun forecast(): MockResponse {
        val call = forecastCalls.incrementAndGet()
        return if (call <= failForecastTimes) MockResponse().setResponseCode(500).setBody("down")
        else json(FORECAST_JSON)
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    fun start(): String {
        server.start()
        return server.url("/").toString().removeSuffix("/")
    }

    fun stop() = server.shutdown()

    companion object {
        const val PHOTOS_JSON = """[{"id":"p1","mimeType":"image/png"},{"id":"p2","mimeType":"image/png"}]"""
        const val CLIP_ID = "clip"
        const val WEBCAM_ID = "harbor"
        const val WIDE_ID = "wide"
        const val VIDEO_AND_WIDE_PHOTO_JSON =
            """[{"id":"$CLIP_ID","mimeType":"video/mp4"},{"id":"$WIDE_ID","mimeType":"image/png"}]"""
        const val MARINE_JSON = """{"current":{"sea_surface_temperature":17.6}}"""
        const val FORECAST_JSON = """{
          "current":{"temperature_2m":21.4,"apparent_temperature":19.6,"precipitation_probability":35,"weather_code":1},
          "daily":{"temperature_2m_max":[24.2,25.0,22.1],"temperature_2m_min":[15.1,14.8,13.0],
                   "precipitation_probability_max":[40,10,60],"weather_code":[1,2,61]}}"""
    }
}

/** Builds the app's config file (immich-secrets.json) for one scenario. */
class FrameConfig(private val baseUrl: String) {
    var weather = true
    var seaWeather = true
    var retrySeconds = 1L
    var photos = true
    var liveViewUrl: String? = null
    var videoGate = false

    fun toJson(): String = JSONObject().apply {
        put("locale", "en")
        if (photos) {
            put("photoSource", "local")
            put("localServerBaseUrl", baseUrl)
        }
        if (weather) {
            put("weatherLatitude", 50.45)
            put("weatherLongitude", 30.52)
            put("weatherBaseUrl", baseUrl)
            put("weatherMarineBaseUrl", baseUrl)
            put("weatherRetryInitialSeconds", retrySeconds)
            if (seaWeather) {
                put("weatherSeaLatitude", 46.48)
                put("weatherSeaLongitude", 30.72)
            }
        }
        liveViewUrl?.let { put("liveViewUrl", it) }
        if (videoGate) put("videoGateBaseUrl", baseUrl)
    }.toString()
}

object FrameState {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Clears every stored setting and cache so a scenario starts from a known state. */
    fun reset() {
        context.filesDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        context.deleteDatabase("asset-cache.db")
        assetCacheDir(context).deleteRecursively()
    }

    /** Stores the slide time before the app starts (the app allows 1 s at least). */
    fun writeSlideDuration(ms: Long) {
        File(context.filesDir, "slideshow-settings.json").writeText(JSONObject().put("durationMs", ms).toString())
    }

    fun writeConfig(config: FrameConfig) {
        File(context.filesDir, "immich-secrets.json").writeText(config.toJson())
    }

    fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)
}
