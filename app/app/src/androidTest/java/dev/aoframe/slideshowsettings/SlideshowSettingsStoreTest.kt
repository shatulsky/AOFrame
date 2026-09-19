package dev.aoframe.slideshowsettings

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * File-backed JSON round-trip and out-of-range clamping for
 * SlideshowSettingsConfig/Store. Instrumented (androidTest) - see
 * ImmichClientTest's header comment for why org.json needs a real Android
 * runtime here.
 */
class SlideshowSettingsStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configFile = File(context.filesDir, "slideshow-settings.json")
    private val secretsFile = File(context.filesDir, "immich-secrets.json")

    @Before
    fun setUp() {
        configFile.delete()
        secretsFile.delete()
    }

    @After
    fun tearDown() {
        configFile.delete()
        secretsFile.delete()
    }

    @Test
    fun loadWithNoFileReturnsDefaults() {
        assertEquals(SlideshowSettingsConfig(), SlideshowSettingsStore.load(context))
    }

    @Test
    fun saveThenLoadRoundTripsBothFields() {
        val config = SlideshowSettingsConfig(durationMs = 8_000L, endZoom = 1.75f)
        SlideshowSettingsStore.save(context, config)

        assertEquals(config, SlideshowSettingsStore.load(context))
    }

    @Test
    fun loadWithMalformedJsonFallsBackToDefaults() {
        configFile.writeText("not json")

        assertEquals(SlideshowSettingsConfig(), SlideshowSettingsStore.load(context))
    }

    @Test
    fun fromJsonClampsAnOutOfRangeDurationToTheAllowedBounds() {
        val tooShort = JSONObject().put("durationMs", 1L).put("endZoom", 1.5)
        val tooLong = JSONObject().put("durationMs", 999_999L).put("endZoom", 1.5)

        assertEquals(1_000L, SlideshowSettingsConfig.fromJson(tooShort).durationMs)
        assertEquals(60_000L, SlideshowSettingsConfig.fromJson(tooLong).durationMs)
    }

    @Test
    fun fromJsonClampsAnOutOfRangeEndZoomToTheAllowedBounds() {
        val tooSmall = JSONObject().put("durationMs", 6000L).put("endZoom", 0.1)
        val tooLarge = JSONObject().put("durationMs", 6000L).put("endZoom", 10.0)

        assertEquals(1.0f, SlideshowSettingsConfig.fromJson(tooSmall).endZoom)
        assertEquals(3.0f, SlideshowSettingsConfig.fromJson(tooLarge).endZoom)
    }

    @Test
    fun toJsonRoundsEndZoomToThreeDecimalPlaces() {
        val config = SlideshowSettingsConfig(durationMs = 6000L, endZoom = 1.4550000429153442f)

        val json = config.toJson()

        assertEquals(1.455, json.getDouble("endZoom"), 0.0001)
    }

    @Test
    fun restoreFromPiIfMissingLeavesTheFileAbsentWhenNoPiIsConfigured() = runBlocking {
        SlideshowSettingsStore.restoreFromPiIfMissing(context)

        assertFalse(configFile.exists())
    }
}
