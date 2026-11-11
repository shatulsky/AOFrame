package dev.aoframe.liveview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewTimingTest {
    @Test
    fun rtspUrlIsUsable() {
        assertTrue(LiveViewTiming.isUsableUrl("rtsp://host:8554/stream"))
        assertTrue(LiveViewTiming.isUsableUrl("RTSP://host/stream"))
    }

    @Test
    fun missingOrOtherSchemeIsNotUsable() {
        assertFalse(LiveViewTiming.isUsableUrl(null))
        assertFalse(LiveViewTiming.isUsableUrl(""))
        assertFalse(LiveViewTiming.isUsableUrl("rtsp://"))
        assertFalse(LiveViewTiming.isUsableUrl("http://host/stream"))
    }

    @Test
    fun maxDurationIsFiveMinutes() {
        assertEquals(300_000L, LiveViewTiming.MAX_DURATION_MS)
    }
}
