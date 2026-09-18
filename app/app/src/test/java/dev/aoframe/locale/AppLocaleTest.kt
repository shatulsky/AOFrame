package dev.aoframe.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.DayOfWeek
import java.util.Locale

class AppLocaleTest {
    @Test
    fun resolveFallsBackToEnglishWhenUnsetOrUnrecognized() {
        assertEquals(Locale.ENGLISH, AppLocale.resolve(null))
        assertEquals(Locale.ENGLISH, AppLocale.resolve(""))
    }

    @Test
    fun resolveHonorsAKnownLanguageTag() {
        assertEquals("uk", AppLocale.resolve("uk").language)
    }

    @Test
    fun ukrainianShortWeekdaysMatchTheConfirmedConvention() {
        assertEquals("нд", AppLocale.shortWeekdayName(DayOfWeek.SUNDAY, Locale.forLanguageTag("uk")))
        assertEquals("пн", AppLocale.shortWeekdayName(DayOfWeek.MONDAY, Locale.forLanguageTag("uk")))
    }

    @Test
    fun everyUkrainianShortWeekdayIsADistinctTwoLetterAbbreviation() {
        val names = DayOfWeek.entries.map { AppLocale.shortWeekdayName(it, Locale.forLanguageTag("uk")) }
        assertEquals(7, names.toSet().size)
        assertEquals(7, names.count { it.length == 2 })
    }

    @Test
    fun otherLocalesFallBackToJavaTimesOwnShortForm() {
        assertEquals("Mon", AppLocale.shortWeekdayName(DayOfWeek.MONDAY, Locale.ENGLISH))
    }

    @Test
    fun countdownWordsFallBackToEnglishForAnUnlistedLanguage() {
        val words = AppLocale.countdownWords(Locale.forLanguageTag("es"))
        assertEquals(AppLocale.countdownWords(Locale.ENGLISH), words)
    }

    @Test
    fun ukrainianCountdownWordsDifferFromEnglish() {
        assertNotEquals(
            AppLocale.countdownWords(Locale.ENGLISH).day.toList(),
            AppLocale.countdownWords(Locale.forLanguageTag("uk")).day.toList()
        )
    }
}
