package dev.aoframe.nightmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class NightModeTimingTest {
    @Test
    fun minutesOfDayComputesCorrectly() {
        assertEquals(0, NightModeTiming.minutesOfDay(LocalTime.of(0, 0)))
        assertEquals(60, NightModeTiming.minutesOfDay(LocalTime.of(1, 0)))
        assertEquals(1439, NightModeTiming.minutesOfDay(LocalTime.of(23, 59)))
    }

    @Test
    fun parseMinutesOfDayParsesHhMm() {
        assertEquals(60, NightModeTiming.parseMinutesOfDay("01:00"))
        assertEquals(485, NightModeTiming.parseMinutesOfDay("08:05"))
    }

    @Test
    fun triggersWhenMinuteMatchesAndNotYetFiredToday() {
        val today = LocalDate.of(2026, 8, 29)
        assertTrue(NightModeTiming.shouldTrigger(nowMinutes = 60, targetMinutes = 60, today = today, lastTriggeredDate = null))
    }

    @Test
    fun doesNotTriggerTwiceOnTheSameDay() {
        val today = LocalDate.of(2026, 8, 29)
        assertFalse(NightModeTiming.shouldTrigger(nowMinutes = 60, targetMinutes = 60, today = today, lastTriggeredDate = today))
    }

    @Test
    fun triggersAgainOnANewDay() {
        val today = LocalDate.of(2026, 8, 29)
        val yesterday = today.minusDays(1)
        assertTrue(NightModeTiming.shouldTrigger(nowMinutes = 60, targetMinutes = 60, today = today, lastTriggeredDate = yesterday))
    }

    @Test
    fun doesNotTriggerWhenMinuteDoesNotMatch() {
        val today = LocalDate.of(2026, 8, 29)
        assertFalse(NightModeTiming.shouldTrigger(nowMinutes = 61, targetMinutes = 60, today = today, lastTriggeredDate = null))
    }

    @Test
    fun isWithinWindowHandlesSameDayRange() {
        // sleep 01:00 (60), wake 10:00 (600)
        assertTrue(NightModeTiming.isWithinWindow(nowMinutes = 60, sleepMinutes = 60, wakeMinutes = 600))
        assertTrue(NightModeTiming.isWithinWindow(nowMinutes = 300, sleepMinutes = 60, wakeMinutes = 600))
        assertFalse(NightModeTiming.isWithinWindow(nowMinutes = 600, sleepMinutes = 60, wakeMinutes = 600))
        assertFalse(NightModeTiming.isWithinWindow(nowMinutes = 30, sleepMinutes = 60, wakeMinutes = 600))
    }

    @Test
    fun isWithinWindowHandlesMidnightCrossing() {
        // sleep 23:00 (1380), wake 07:00 (420)
        assertTrue(NightModeTiming.isWithinWindow(nowMinutes = 1380, sleepMinutes = 1380, wakeMinutes = 420))
        assertTrue(NightModeTiming.isWithinWindow(nowMinutes = 0, sleepMinutes = 1380, wakeMinutes = 420))
        assertTrue(NightModeTiming.isWithinWindow(nowMinutes = 419, sleepMinutes = 1380, wakeMinutes = 420))
        assertFalse(NightModeTiming.isWithinWindow(nowMinutes = 420, sleepMinutes = 1380, wakeMinutes = 420))
        assertFalse(NightModeTiming.isWithinWindow(nowMinutes = 720, sleepMinutes = 1380, wakeMinutes = 420))
    }

    @Test
    fun isWithinWindowIsFalseWhenSleepAndWakeAreEqual() {
        assertFalse(NightModeTiming.isWithinWindow(nowMinutes = 300, sleepMinutes = 60, wakeMinutes = 60))
    }
}
