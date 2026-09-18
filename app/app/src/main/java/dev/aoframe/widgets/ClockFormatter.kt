package dev.aoframe.widgets

import dev.aoframe.locale.AppLocale
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Clock/date display: weekday line ("П'ятниця," / "Friday,"), full date
 * line ("28 вересня 2026 р." / "September 28 2026"), then a large time
 * with small superscript seconds ("16:06" + raised "58") - seconds
 * formatting/spanning is built in MainActivity (needs android.text spans,
 * not plain-JVM-testable), this is just the pure string formatting for
 * each piece. Locale-driven (see AppLocale) rather than hardcoded to one
 * language.
 */
object ClockFormatter {
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    private val SECONDS_FORMAT = DateTimeFormatter.ofPattern("ss")

    fun formatTime(now: LocalDateTime): String = now.format(TIME_FORMAT)

    fun formatSeconds(now: LocalDateTime): String = now.format(SECONDS_FORMAT)

    // Capitalizing the leading weekday is done directly here in Kotlin
    // rather than relying on the formatted string already being
    // capitalized, since not every locale's "EEEE" output is.
    fun formatWeekday(now: LocalDateTime, locale: Locale): String =
        now.format(DateTimeFormatter.ofPattern("EEEE", locale)).replaceFirstChar { it.titlecase(locale) } + ","

    fun formatFullDate(now: LocalDateTime, locale: Locale): String =
        now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale)) + AppLocale.dateSuffix(locale)
}
