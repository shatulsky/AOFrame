package dev.aoframe.countdown

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
 * CountdownConfig/CountdownStore. Instrumented (androidTest) - see
 * ImmichClientTest's header comment for why org.json needs a real Android
 * runtime here.
 *
 * CountdownStore caches its loaded config in a process-lifetime @Volatile
 * field (unlike NightModeStore), so these tests read the persisted file
 * directly rather than through CountdownStore.load() - the cache would
 * otherwise leak state between tests sharing this instrumentation process.
 */
class CountdownStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configFile = File(context.filesDir, "countdown.json")
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
    fun toJsonFromJsonRoundTripsThroughAConfig() {
        val config = CountdownConfig(enabled = true, targetDate = "2027-01-01T00:00", label = "New Year", precision = "daysOnly")

        assertEquals(config, CountdownConfig.fromJson(config.toJson()))
    }

    @Test
    fun fromJsonAppliesDefaultsForMissingFields() {
        assertEquals(CountdownConfig(), CountdownConfig.fromJson(JSONObject()))
    }

    @Test
    fun saveWritesTheConfigToDiskAsJson() {
        val config = CountdownConfig(enabled = true, targetDate = "2026-12-25T09:00", label = "Xmas", precision = "daysHours")

        CountdownStore.save(context, config)

        val onDisk = CountdownConfig.fromJson(JSONObject(configFile.readText()))
        assertEquals(config, onDisk)
    }

    @Test
    fun restoreFromPiIfMissingLeavesTheFileAbsentWhenNoPiIsConfigured() = runBlocking {
        // No immich-secrets.json at all -> ConfigBackupClient.fetchBackup
        // returns null immediately, so this must be a safe no-op.
        CountdownStore.restoreFromPiIfMissing(context)

        assertFalse(configFile.exists())
    }

    @Test
    fun restoreFromPiIfMissingIsANoOpWhenTheConfigFileAlreadyExists() = runBlocking {
        val existing = CountdownConfig(enabled = true, targetDate = "2025-06-01T00:00", label = "already here")
        configFile.writeText(existing.toJson().toString())

        CountdownStore.restoreFromPiIfMissing(context)

        val onDisk = CountdownConfig.fromJson(JSONObject(configFile.readText()))
        assertEquals(existing, onDisk)
    }
}
