package dev.aoframe.nightmode

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
 * File-backed JSON round-trip and restore-on-missing-file behavior for
 * NightModeConfig/NightModeStore. Instrumented (androidTest), not a plain
 * JVM unit test - see ImmichClientTest's header comment for why org.json
 * needs a real Android runtime here.
 */
class NightModeStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configFile = File(context.filesDir, "night-mode.json")
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
        val config = NightModeStore.load(context)
        assertEquals(NightModeConfig(), config)
    }

    @Test
    fun saveThenLoadRoundTripsAllFields() {
        val config = NightModeConfig(enabled = true, sleepTime = "23:30", wakeTime = "07:15")
        NightModeStore.save(context, config)

        assertEquals(config, NightModeStore.load(context))
    }

    @Test
    fun loadWithMalformedJsonFallsBackToDefaults() {
        configFile.writeText("not json")

        assertEquals(NightModeConfig(), NightModeStore.load(context))
    }

    @Test
    fun toJsonFromJsonRoundTripsThroughAConfig() {
        val config = NightModeConfig(enabled = true, sleepTime = "22:00", wakeTime = "06:45")

        assertEquals(config, NightModeConfig.fromJson(config.toJson()))
    }

    @Test
    fun fromJsonAppliesDefaultsForMissingFields() {
        val parsed = NightModeConfig.fromJson(JSONObject())

        assertEquals(NightModeConfig(), parsed)
    }

    @Test
    fun restoreFromPiIfMissingIsANoOpWhenTheConfigFileAlreadyExists() = runBlocking {
        val config = NightModeConfig(enabled = true, sleepTime = "20:00", wakeTime = "05:00")
        NightModeStore.save(context, config)

        NightModeStore.restoreFromPiIfMissing(context)

        assertEquals(config, NightModeStore.load(context))
    }

    @Test
    fun restoreFromPiIfMissingLeavesDefaultsWhenNoPiIsConfigured() = runBlocking {
        // No immich-secrets.json at all -> ConfigBackupClient.fetchBackup
        // returns null immediately (no piBaseUrl to ask), so this must be
        // a safe no-op rather than throwing.
        NightModeStore.restoreFromPiIfMissing(context)

        assertEquals(NightModeConfig(), NightModeStore.load(context))
        assertFalse(configFile.exists())
    }
}
