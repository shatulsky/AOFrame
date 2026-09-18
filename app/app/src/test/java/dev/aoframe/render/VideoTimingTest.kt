package dev.aoframe.render

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
}
