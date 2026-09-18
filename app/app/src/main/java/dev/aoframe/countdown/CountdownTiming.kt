package dev.aoframe.countdown

import dev.aoframe.locale.AppLocale
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

data class CountdownDisplay(val label: String, val value: String)

/**
 * Countdown display formatting - trailing-zero-unit trimming, precision
 * capping, locale-aware pluralization (see AppLocale for the unit words
 * per language). Plain-JVM testable, same reasoning as
 * ClockFormatter/VideoTiming/NightModeTiming (only java.time, no
 * Android-SDK-stubbed classes).
 */
object CountdownTiming {
    // Slavic-style plural rules (also correct for any language whose
    // "few"/"many" forms are identical, e.g. English): 1/21/31... ->
    // forms[0], 2-4/22-24... -> forms[1] (excluding the 11-14 exception),
    // everything else -> forms[2].
    fun pluralize(n: Long, forms: Array<String>): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1L && mod100 != 11L -> forms[0]
            mod10 in 2..4 && (mod100 < 12 || mod100 > 14) -> forms[1]
            else -> forms[2]
        }
    }

    // Shows the largest non-zero unit plus the next one down, skipping a
    // trailing zero unit entirely (e.g. "167 days" instead of "167 days
    // 0 hours"). precision caps how far the breakdown drills down.
    fun formatRemaining(remainingMs: Long, precision: String, locale: Locale): String {
        val totalSeconds = remainingMs / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val words = AppLocale.countdownWords(locale)
        val allUnits = listOf(days to words.day, hours to words.hour, minutes to words.minute, seconds to words.second)
        val units = when (precision) {
            "daysOnly" -> allUnits.subList(0, 1)
            "daysHours" -> allUnits.subList(0, 2)
            else -> allUnits
        }

        val leadingIndex = units.indexOfFirst { it.first > 0 }
        val visible = if (leadingIndex == -1) {
            listOf(units.last())
        } else {
            units.subList(leadingIndex, minOf(leadingIndex + 2, units.size))
                .filterIndexed { index, (value, _) -> index == 0 || value > 0 }
        }

        return visible.joinToString(" ") { (value, forms) -> "$value ${pluralize(value, forms)}" }
    }

    private fun parseTargetMillis(targetDate: String): Long? = try {
        LocalDateTime.parse(targetDate)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (error: DateTimeParseException) {
        null
    }

    // null when disabled, unset, or an unparsable target date - the
    // widget hides itself entirely in that case.
    fun display(config: CountdownConfig, nowMs: Long, locale: Locale): CountdownDisplay? {
        if (!config.enabled || config.targetDate.isBlank()) return null
        val targetMs = parseTargetMillis(config.targetDate) ?: return null
        val remainingMs = targetMs - nowMs
        val value = if (remainingMs > 0) {
            formatRemaining(remainingMs, config.precision, locale)
        } else {
            AppLocale.countdownWords(locale).arrived
        }
        return CountdownDisplay(config.label, value)
    }
}
