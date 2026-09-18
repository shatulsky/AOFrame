package dev.aoframe.widgets

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

/**
 * A genuine plain-JVM unit test, no emulator/Robolectric needed - unlike
 * ImmichClient/AssetRepository/AssetCacheDatabase (all Android-SDK-stub
 * dependent, see their androidTest header comments), ClockFormatter only
 * touches java.time/java.util, real JDK classes available directly in
 * a `./gradlew test` run.
 */
class ClockFormatterTest {
    private val uk = Locale.forLanguageTag("uk")

    @Test
    fun formatsTimeAs24Hour() {
        val time = ClockFormatter.formatTime(LocalDateTime.of(2026, 8, 28, 14, 5, 9))
        assertEquals("14:05", time)
    }

    @Test
    fun formatsSecondsWithLeadingZero() {
        val seconds = ClockFormatter.formatSeconds(LocalDateTime.of(2026, 8, 28, 14, 5, 9))
        assertEquals("09", seconds)
    }

    @Test
    fun formatsWeekdayCapitalizedWithTrailingComma() {
        // 2026-08-28 is a Friday. U+02BC (Ukrainian modifier-letter
        // apostrophe), not ASCII ' - confirmed via the real JDK's ICU
        // locale data, not assumed.
        val weekday = ClockFormatter.formatWeekday(LocalDateTime.of(2026, 8, 28, 0, 0), uk)
        assertEquals("Пʼятниця,", weekday)
    }

    @Test
    fun formatsFullDateWithYearSuffix() {
        val date = ClockFormatter.formatFullDate(LocalDateTime.of(2026, 8, 28, 0, 0), uk)
        assertEquals("28 серпня 2026 р.", date)
    }

    @Test
    fun formatsWeekdayInEnglishWithNoSuffix() {
        val weekday = ClockFormatter.formatWeekday(LocalDateTime.of(2026, 8, 28, 0, 0), Locale.ENGLISH)
        assertEquals("Friday,", weekday)
    }

    @Test
    fun formatsFullDateInEnglishWithNoYearSuffix() {
        val date = ClockFormatter.formatFullDate(LocalDateTime.of(2026, 8, 28, 0, 0), Locale.ENGLISH)
        assertEquals("28 August 2026", date)
    }
}
