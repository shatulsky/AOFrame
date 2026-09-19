package dev.aoframe.webcam

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * File-backed per-camera sync-timestamp map for WebcamSyncState.
 * Instrumented (androidTest) - see ImmichClientTest's header comment for
 * why org.json needs a real Android runtime here.
 */
class WebcamSyncStateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stateFile = File(context.filesDir, "webcam-sync-state.json")

    @Before
    fun setUp() {
        stateFile.delete()
    }

    @After
    fun tearDown() {
        stateFile.delete()
    }

    @Test
    fun loadWithNoFileReturnsAnEmptyMap() {
        assertTrue(WebcamSyncState.load(context).isEmpty())
    }

    @Test
    fun loadWithMalformedJsonReturnsAnEmptyMap() {
        stateFile.writeText("not json")

        assertTrue(WebcamSyncState.load(context).isEmpty())
    }

    @Test
    fun recordSyncedAddsANewCameraWithoutDisturbingOthers() {
        WebcamSyncState.recordSynced(context, "front")
        val afterFirst = WebcamSyncState.load(context)["front"]

        WebcamSyncState.recordSynced(context, "back")
        val loaded = WebcamSyncState.load(context)

        assertEquals(afterFirst, loaded["front"])
        assertTrue(loaded.containsKey("back"))
    }

    @Test
    fun recordSyncedOverwritesTheTimestampForTheSameCamera() {
        WebcamSyncState.recordSynced(context, "front")
        val first = WebcamSyncState.load(context).getValue("front")

        Thread.sleep(5)
        WebcamSyncState.recordSynced(context, "front")
        val second = WebcamSyncState.load(context).getValue("front")

        assertTrue(second >= first)
        assertEquals(1, WebcamSyncState.load(context).size)
    }
}
