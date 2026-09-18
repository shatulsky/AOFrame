package dev.aoframe.countdown

import dev.aoframe.locale.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class CountdownTimingTest {
    private val uk = Locale.forLanguageTag("uk")

    @Test
    fun pluralizesOneDayCorrectly() {
        assertEquals("день", CountdownTiming.pluralize(1, arrayOf("день", "дні", "днів")))
    }

    @Test
    fun pluralizesFewDaysCorrectly() {
        assertEquals("дні", CountdownTiming.pluralize(3, arrayOf("день", "дні", "днів")))
    }

    @Test
    fun pluralizesManyDaysCorrectly() {
        assertEquals("днів", CountdownTiming.pluralize(5, arrayOf("день", "дні", "днів")))
    }

    @Test
    fun pluralizesElevenAsManyNotOne() {
        // The 11-14 exception - mod10 == 1 but mod100 == 11 still falls to forms[2].
        assertEquals("днів", CountdownTiming.pluralize(11, arrayOf("день", "дні", "днів")))
    }

    @Test
    fun formatRemainingTrimsTrailingZeroUnit() {
        // Exactly 167 days, 0 hours - should read "167 днів", not
        // "167 днів 0 годин".
        val remainingMs = 167L * 86400 * 1000
        assertEquals("167 днів", CountdownTiming.formatRemaining(remainingMs, "full", uk))
    }

    @Test
    fun formatRemainingShowsTwoUnitsWhenSecondUnitNonZero() {
        val remainingMs = (2L * 86400 + 3 * 3600) * 1000
        assertEquals("2 дні 3 години", CountdownTiming.formatRemaining(remainingMs, "full", uk))
    }

    @Test
    fun formatRemainingRespectsDaysOnlyPrecision() {
        val remainingMs = (2L * 86400 + 3 * 3600 + 30 * 60) * 1000
        assertEquals("2 дні", CountdownTiming.formatRemaining(remainingMs, "daysOnly", uk))
    }

    @Test
    fun formatRemainingRespectsDaysHoursPrecision() {
        val remainingMs = (2L * 86400 + 3 * 3600 + 30 * 60) * 1000
        assertEquals("2 дні 3 години", CountdownTiming.formatRemaining(remainingMs, "daysHours", uk))
    }

    @Test
    fun formatRemainingFallsBackToSmallestUnitWhenAllZero() {
        assertEquals("0 секунд", CountdownTiming.formatRemaining(0, "full", uk))
    }

    @Test
    fun formatRemainingWorksInEnglishToo() {
        val remainingMs = (2L * 86400 + 3 * 3600) * 1000
        assertEquals("2 days 3 hours", CountdownTiming.formatRemaining(remainingMs, "full", Locale.ENGLISH))
    }

    @Test
    fun displayIsNullWhenDisabled() {
        val config = CountdownConfig(enabled = false, targetDate = "2027-10-02T14:30")
        assertNull(CountdownTiming.display(config, 0, uk))
    }

    @Test
    fun displayIsNullWhenTargetDateBlank() {
        val config = CountdownConfig(enabled = true, targetDate = "")
        assertNull(CountdownTiming.display(config, 0, uk))
    }

    @Test
    fun displayShowsArrivedWhenTargetIsInThePast() {
        val target = LocalDateTime.of(2020, 1, 1, 0, 0)
        val nowMs = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1000
        val config = CountdownConfig(enabled = true, targetDate = "2020-01-01T00:00", label = "Test")
        val arrived = AppLocale.countdownWords(uk).arrived
        assertEquals(CountdownDisplay("Test", arrived), CountdownTiming.display(config, nowMs, uk))
    }

    @Test
    fun displayComputesRemainingWhenTargetIsInTheFuture() {
        val target = LocalDateTime.of(2030, 1, 1, 0, 0)
        val nowMs = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - 60_000
        val config = CountdownConfig(enabled = true, targetDate = "2030-01-01T00:00", label = "", precision = "full")
        assertEquals(CountdownDisplay("", "1 хвилина"), CountdownTiming.display(config, nowMs, uk))
    }
}
