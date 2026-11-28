package dev.aoframe.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimingTest {
    @Test
    fun hasStalledWhenNeverStartedAndTimeoutExceeded() {
        assertTrue(VideoTiming.hasStalled(elapsedSincePlayRequestedMs = 9_000, currentPositionMs = 0, stallTimeoutMs = 8_000))
    }

    @Test
    fun hasNotStalledIfPlaybackAlreadyAdvanced() {
        assertFalse(VideoTiming.hasStalled(elapsedSincePlayRequestedMs = 9_000, currentPositionMs = 500, stallTimeoutMs = 8_000))
    }

    @Test
    fun hasNotStalledBeforeTimeoutElapses() {
        assertFalse(VideoTiming.hasStalled(elapsedSincePlayRequestedMs = 2_000, currentPositionMs = 0, stallTimeoutMs = 8_000))
    }

    @Test
    fun hasExceededMaxDurationPastTheCeiling() {
        assertTrue(VideoTiming.hasExceededMaxDuration(elapsedSincePlayRequestedMs = 200_000, maxDurationMs = 180_000))
    }

    @Test
    fun hasNotExceededMaxDurationBeforeTheCeiling() {
        assertFalse(VideoTiming.hasExceededMaxDuration(elapsedSincePlayRequestedMs = 100_000, maxDurationMs = 180_000))
    }

    @Test
    fun triggersLatePreloadOnceWithinTheLeadWindow() {
        assertTrue(VideoTiming.shouldTriggerLatePreload(currentPositionMs = 13_500, durationMs = 15_000, leadMs = 2_000))
    }

    @Test
    fun doesNotTriggerLatePreloadOutsideTheLeadWindow() {
        assertFalse(VideoTiming.shouldTriggerLatePreload(currentPositionMs = 10_000, durationMs = 15_000, leadMs = 2_000))
    }

    @Test
    fun doesNotTriggerLatePreloadWhenDurationIsUnknown() {
        assertFalse(VideoTiming.shouldTriggerLatePreload(currentPositionMs = 0, durationMs = 0, leadMs = 2_000))
    }

    private fun action(
        ended: Boolean = false,
        elapsed: Long = 1_000,
        position: Long = 500,
        duration: Long = 20_000,
        preloaded: Boolean = false
    ) = VideoTiming.watchdogAction(ended, elapsed, position, duration, preloaded,
        stallTimeoutMs = 8_000, maxDurationMs = 180_000, leadMs = 2_000)

    @Test
    fun watchdogWaitsWhilePlayingNormally() {
        assertEquals(VideoTiming.WatchdogAction.WAIT, action())
    }

    @Test
    fun watchdogAdvancesOnEndStallOrCeiling() {
        assertEquals(VideoTiming.WatchdogAction.ADVANCE, action(ended = true))
        assertEquals(VideoTiming.WatchdogAction.ADVANCE, action(elapsed = 8_001, position = 0))
        assertEquals(VideoTiming.WatchdogAction.ADVANCE, action(elapsed = 180_001, position = 90_000))
    }

    @Test
    fun watchdogAdvanceWinsOverPreloadNearTheEnd() {
        assertEquals(VideoTiming.WatchdogAction.ADVANCE, action(ended = true, position = 19_500))
    }

    @Test
    fun watchdogPreloadsOnceWithinTheLeadOfTheEnd() {
        assertEquals(VideoTiming.WatchdogAction.PRELOAD_NEXT, action(position = 18_500))
        assertEquals(VideoTiming.WatchdogAction.WAIT, action(position = 18_500, preloaded = true))
        assertEquals(VideoTiming.WatchdogAction.WAIT, action(position = 18_000))
    }

    @Test
    fun watchdogNeverPreloadsWhileTheDurationIsUnknown() {
        assertEquals(VideoTiming.WatchdogAction.WAIT, action(position = 0, duration = -9_223_372_036_854_775_807))
    }
}
