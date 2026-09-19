package dev.aoframe.webcam

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * File-backed JSON round-trip for WebcamTestModeConfig/Store. Instrumented
 * (androidTest) - see ImmichClientTest's header comment for why org.json
 * needs a real Android runtime here.
 */
class WebcamTestModeStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configFile = File(context.filesDir, "webcam-test-mode.json")

    @Before
    fun setUp() {
        configFile.delete()
    }

    @After
    fun tearDown() {
        configFile.delete()
    }

    @Test
    fun loadWithNoFileReturnsDisabledByDefault() {
        assertEquals(WebcamTestModeConfig(enabled = false), WebcamTestModeStore.load(context))
    }

    @Test
    fun saveThenLoadRoundTripsTheEnabledFlag() {
        WebcamTestModeStore.save(context, WebcamTestModeConfig(enabled = true))

        assertEquals(WebcamTestModeConfig(enabled = true), WebcamTestModeStore.load(context))
    }

    @Test
    fun loadWithMalformedJsonFallsBackToDisabled() {
        configFile.writeText("not json")

        assertEquals(WebcamTestModeConfig(enabled = false), WebcamTestModeStore.load(context))
    }

    @Test
    fun toJsonFromJsonRoundTripsThroughAConfig() {
        val config = WebcamTestModeConfig(enabled = true)

        assertEquals(config, WebcamTestModeConfig.fromJson(config.toJson()))
    }

    @Test
    fun fromJsonDefaultsToDisabledWhenTheFlagIsMissing() {
        assertEquals(WebcamTestModeConfig(enabled = false), WebcamTestModeConfig.fromJson(JSONObject()))
    }
}
