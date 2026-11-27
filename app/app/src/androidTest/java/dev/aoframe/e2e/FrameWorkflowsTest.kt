package dev.aoframe.e2e

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
import java.net.ServerSocket
import java.util.regex.Pattern

/**
 * One smoke per workflow, through the real screen of MainActivity on the
 * emulator. Each check is here because only a screen can show it. Logic
 * (retry timing, display rules, night-mode times) is tested lower down.
 * See docs/testing.md.
 */
class FrameWorkflowsTest {
    private val backend = FakeBackend()
    private lateinit var baseUrl: String
    private val http = OkHttpClient()
    private val controlUrl = "http://127.0.0.1:8099"

    @Before
    fun setUp() {
        FrameState.reset()
        baseUrl = backend.start()
    }

    @After
    fun tearDown() {
        backend.stop()
        FrameState.reset()
    }

    private fun page(config: FrameConfig = FrameConfig(baseUrl), body: (FramePage) -> Unit) {
        FrameState.writeConfig(config)
        FrameState.launch().use { scenario -> body(FramePage(scenario)) }
    }

    private fun get(path: String): JSONObject =
        http.newCall(Request.Builder().url("$controlUrl$path").build()).execute().use { JSONObject(it.body!!.string()) }

    private fun post(path: String, json: String): JSONObject =
        http.newCall(
            Request.Builder().url("$controlUrl$path")
                .post(json.toRequestBody("application/json".toMediaType())).build()
        ).execute().use { JSONObject(it.body!!.string()) }

    @Test
    fun slideshowRunsAndTheLoadingScreenGoesAway() = page { page ->
        eventually("loading screen dismissed") { !page.loadingOverlayShown }
        eventually("a photo is on screen") { page.photoShown }
    }

    @Test
    fun clockShowsTimeAndDateAndTheWeatherWidgetFillsFromTheForecast() = page { page ->
        assertTrue("clock time was '${page.clockTime}'", Pattern.matches("\\d{1,2}:\\d{2}\\d{2}", page.clockTime))
        assertTrue(page.clockDate.isNotBlank())
        eventually("weather widget visible") { page.weatherWidgetShown }
        assertEquals("21°", page.currentTemp)
        assertEquals("20°", page.feelsLike)
        assertEquals("35%", page.precipitation)
        assertEquals("Today", page.day0Label)
        assertEquals("24°", page.day0Max)
        assertEquals("13°", page.day2Min)
        assertTrue(page.seaTempShown)
        assertEquals("18°", page.seaTemp)
    }

    @Test
    fun weatherStaysHiddenWhileTheForecastFailsAndAppearsAfterTheRetry() {
        backend.failForecastTimes = 2
        page { page ->
            eventually("first forecast request made") { backend.forecastCalls.get() >= 1 }
            assertTrue("widget must stay hidden after a failed fetch", page.weatherWidgetGone)
            eventually("widget visible after the retries") { page.weatherWidgetShown }
            assertEquals("3 forecast requests: 2 failures, 1 success", 3, backend.forecastCalls.get())
            assertEquals("21°", page.currentTemp)
        }
    }

    @Test
    fun noWeatherCoordinatesHidesTheWholeWidget() {
        val config = FrameConfig(baseUrl).apply { weather = false }
        page(config) { page ->
            eventually("slideshow started") { !page.loadingOverlayShown }
            assertTrue(page.weatherWidgetGone)
            assertEquals(0, backend.forecastCalls.get())
        }
    }

    @Test
    fun noSeaPointHidesOnlyTheSeaTemperature() {
        val config = FrameConfig(baseUrl).apply { seaWeather = false }
        page(config) { page ->
            eventually("weather widget visible") { page.weatherWidgetShown }
            assertFalse(page.seaTempShown)
        }
    }

    @Test
    fun nightModeSettingsSavedThroughTheControlServerSurviveWhileTheAppRuns() = page { page ->
        eventually("control server answers") { runCatching { get("/status") }.isSuccess }
        val saved = post("/action/night-mode", """{"enabled":false,"sleepTime":"23:30","wakeTime":"07:15"}""")
        assertEquals("23:30", saved.getString("sleepTime"))
        val loaded = get("/action/night-mode")
        assertEquals("07:15", loaded.getString("wakeTime"))
        assertFalse(loaded.getBoolean("enabled"))
        assertFalse("screen stays on while night mode is off", get("/status").let { !it.getBoolean("screenAwake") })
    }

    @Test
    fun liveViewButtonIsHiddenWithoutAnAddress() = page { page ->
        eventually("slideshow started") { !page.loadingOverlayShown }
        assertFalse(page.liveViewButtonShown)
    }

    @Test
    fun liveViewButtonOpensAndClosesTheLivePicture() {
        // A server that accepts the RTSP connection and never answers keeps the viewer open.
        ServerSocket(0).use { camera ->
            val connections = mutableListOf<java.net.Socket>()
            val acceptor = Thread { runCatching { while (true) connections += camera.accept() } }.apply { isDaemon = true; start() }
            val config = FrameConfig(baseUrl).apply { liveViewUrl = "rtsp://127.0.0.1:${camera.localPort}/cam" }
            try {
                page(config) { page ->
                    eventually("live view button visible") { page.liveViewButtonShown }
                    assertFalse(page.liveViewPictureShown)
                    page.tapLiveView()
                    eventually("live picture visible") { page.liveViewPictureShown }
                    eventually("status reports active") { get("/action/live-view").getBoolean("active") }
                    page.tapLiveView()
                    eventually("live picture closed") { !page.liveViewPictureShown }
                    assertFalse(get("/action/live-view").getBoolean("active"))
                }
            } finally {
                connections.forEach { runCatching { it.close() } }
                acceptor.interrupt()
            }
        }
    }
}
