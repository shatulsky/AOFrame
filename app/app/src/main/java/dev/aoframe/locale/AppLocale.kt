package dev.aoframe.locale

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Centralizes the app's few pieces of display text that aren't purely
 * numeric/date-pattern formatting (java.time's own locale-aware
 * DateTimeFormatter already covers weekday/month names for any language).
 * English is the default and the fallback for any language without a
 * dedicated entry below - add a case per function to support another
 * language properly, rather than leaving it silently falling back.
 */
object AppLocale {
    fun resolve(languageTag: String?): Locale {
        val locale = languageTag?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) }
        return if (locale == null || locale.language.isBlank()) Locale.ENGLISH else locale
    }

    // Countdown unit words, in the three plural forms CountdownTiming's
    // shared pluralize() rule expects (singular/few/many - see that
    // function's own comment). Languages with only two plural forms
    // repeat the same word for forms[1]/forms[2], which the shared rule
    // already handles correctly for every n != 1.
    data class CountdownWords(
        val day: Array<String>,
        val hour: Array<String>,
        val minute: Array<String>,
        val second: Array<String>,
        val arrived: String
    )

    private val ENGLISH_COUNTDOWN = CountdownWords(
        day = arrayOf("day", "days", "days"),
        hour = arrayOf("hour", "hours", "hours"),
        minute = arrayOf("minute", "minutes", "minutes"),
        second = arrayOf("second", "seconds", "seconds"),
        arrived = "Time's up!"
    )

    private val UKRAINIAN_COUNTDOWN = CountdownWords(
        day = arrayOf("день", "дні", "днів"),
        hour = arrayOf("година", "години", "годин"),
        minute = arrayOf("хвилина", "хвилини", "хвилин"),
        second = arrayOf("секунда", "секунди", "секунд"),
        arrived = "Настав час!"
    )

    fun countdownWords(locale: Locale): CountdownWords = when (locale.language) {
        "uk" -> UKRAINIAN_COUNTDOWN
        else -> ENGLISH_COUNTDOWN
    }

    fun today(locale: Locale): String = when (locale.language) {
        "uk" -> "Сьогодні"
        else -> "Today"
    }

    fun tomorrow(locale: Locale): String = when (locale.language) {
        "uk" -> "Завтра"
        else -> "Tomorrow"
    }

    // Ukrainian's 2-letter weekday convention (пн/вт/.../нд) doesn't
    // reliably come out of java.time's own locale data for "uk" (its
    // short/narrow forms don't match this exact, already-confirmed
    // convention) - hardcoded for that one language; every other language
    // uses java.time's own standard short form, which is generally
    // correct without needing an override.
    private val UKRAINIAN_SHORT_DAYS = mapOf(
        DayOfWeek.MONDAY to "пн",
        DayOfWeek.TUESDAY to "вт",
        DayOfWeek.WEDNESDAY to "ср",
        DayOfWeek.THURSDAY to "чт",
        DayOfWeek.FRIDAY to "пт",
        DayOfWeek.SATURDAY to "сб",
        DayOfWeek.SUNDAY to "нд"
    )

    fun shortWeekdayName(dayOfWeek: DayOfWeek, locale: Locale): String =
        if (locale.language == "uk") {
            UKRAINIAN_SHORT_DAYS.getValue(dayOfWeek)
        } else {
            dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        }

    // Trailing marker some languages append after a numeric date
    // (Ukrainian's "28 вересня 2026 р." - short for "року", "of the
    // year"). Most languages' own locale-formatted date already reads
    // correctly without one.
    fun dateSuffix(locale: Locale): String = when (locale.language) {
        "uk" -> " р."
        else -> ""
    }
}
