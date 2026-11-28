package dev.aoframe.backup

import androidx.test.platform.app.InstrumentationRegistry
import dev.aoframe.countdown.CountdownConfig
import dev.aoframe.countdown.CountdownStore
import dev.aoframe.nightmode.NightModeConfig
import dev.aoframe.nightmode.NightModeStore
import dev.aoframe.slideshowsettings.SlideshowSettingsConfig
import dev.aoframe.slideshowsettings.SlideshowSettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ConfigBackupClient and the restore-on-missing-file path of the three
 * stores, against a MockWebServer that stands in for the backup server.
 * Instrumented because org.json needs the Android runtime (see
 * ImmichClientTest). Never run on the real frame: it deletes stored settings.
 */
class ConfigBackupClientTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer()
    private val files = listOf("immich-secrets.json", "countdown.json", "night-mode.json", "slideshow-settings.json")
        .map { File(context.filesDir, it) }

    @Before
    fun setUp() {
        files.forEach { it.delete() }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        files.forEach { it.delete() }
    }

    private fun configurePi(url: String = server.url("").toString().removeSuffix("/")) {
        File(context.filesDir, "immich-secrets.json").writeText(JSONObject().put("piBaseUrl", url).toString())
    }

    private fun answer(code: Int, body: String = "") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun noBackupServerConfiguredMeansNoRequestAndNull() = runBlocking {
        assertNull(ConfigBackupClient.fetchBackup(context, "/x/backup", retryDelayMs = 0))
        configurePi(url = "   ")
        assertNull(ConfigBackupClient.fetchBackup(context, "/x/backup", retryDelayMs = 0))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aBackupIsReturnedAsJsonFromTheGivenPath() = runBlocking {
        configurePi()
        answer(200, """{"enabled":true}""")
        val json = ConfigBackupClient.fetchBackup(context, "/frameo/countdown/backup", retryDelayMs = 0)
        assertEquals(true, json?.getBoolean("enabled"))
        assertEquals("/frameo/countdown/backup", server.takeRequest().path)
    }

    @Test
    fun aMissingBackup404IsNullAndNotRetried() = runBlocking {
        configurePi()
        answer(404)
        assertNull(ConfigBackupClient.fetchBackup(context, "/x/backup", retryDelayMs = 0))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aServerErrorIsRetriedAndTheSecondAnswerIsUsed() = runBlocking {
        configurePi()
        answer(500)
        answer(200, """{"ok":1}""")
        assertEquals(1, ConfigBackupClient.fetchBackup(context, "/x/backup", retryDelayMs = 0)?.getInt("ok"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun threeFailuresInARowGiveNullNotAnException() = runBlocking {
        configurePi()
        answer(500)
        answer(200, "not json")
        answer(503)
        assertNull(ConfigBackupClient.fetchBackup(context, "/x/backup", retryDelayMs = 0))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun anUnreachableServerGivesNull() = runBlocking {
        val url = server.url("").toString().removeSuffix("/")
        server.shutdown()
        configurePi(url)
        assertNull(ConfigBackupClient.fetchBackup(context, "/x/backup", retryDelayMs = 0))
    }

    @Test
    fun aMissingCountdownFileIsRestoredFromTheBackup() = runBlocking {
        configurePi()
        val backup = CountdownConfig(enabled = true, targetDate = "2030-01-01T00:00", label = "Trip", precision = "daysOnly")
        answer(200, backup.toJson().toString())
        CountdownStore.restoreFromPiIfMissing(context)
        assertEquals("/frameo/countdown/backup", server.takeRequest().path)
        assertEquals(backup, CountdownConfig.fromJson(JSONObject(File(context.filesDir, "countdown.json").readText())))
    }

    @Test
    fun aMissingNightModeFileIsRestoredFromTheBackup() = runBlocking {
        configurePi()
        val backup = NightModeConfig(enabled = true, sleepTime = "22:15", wakeTime = "06:45")
        answer(200, backup.toJson().toString())
        NightModeStore.restoreFromPiIfMissing(context)
        assertEquals("/frameo/nightmode/backup", server.takeRequest().path)
        assertEquals(backup, NightModeStore.load(context))
    }

    @Test
    fun aMissingSlideshowSettingsFileIsRestoredFromTheBackup() = runBlocking {
        configurePi()
        answer(200, """{"durationMs":12000,"endZoom":1.2}""")
        SlideshowSettingsStore.restoreFromPiIfMissing(context)
        assertEquals("/frameo/settings/slideshow-settings/backup", server.takeRequest().path)
        assertEquals(12_000L, SlideshowSettingsStore.load(context).durationMs)
    }

    @Test
    fun anExistingSlideshowSettingsFileIsNeverOverwritten() = runBlocking {
        configurePi()
        SlideshowSettingsStore.save(context, SlideshowSettingsConfig(durationMs = 7_000L))
        SlideshowSettingsStore.restoreFromPiIfMissing(context)
        assertEquals(0, server.requestCount)
        assertEquals(7_000L, SlideshowSettingsStore.load(context).durationMs)
    }

    @Test
    fun noBackupOnTheServerLeavesTheFilesMissing() = runBlocking {
        configurePi()
        repeat(3) { answer(404) }
        CountdownStore.restoreFromPiIfMissing(context)
        NightModeStore.restoreFromPiIfMissing(context)
        SlideshowSettingsStore.restoreFromPiIfMissing(context)
        assertFalse(File(context.filesDir, "countdown.json").exists())
        assertFalse(File(context.filesDir, "night-mode.json").exists())
        assertFalse(File(context.filesDir, "slideshow-settings.json").exists())
    }
}
