package dev.aoframe.nightmode

import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure trigger-decision logic - plain-JVM testable, same reasoning as
 * ClockFormatter/VideoTiming (only java.time, no Android-SDK-stubbed
 * classes). The once-per-day dedup (lastTriggeredDate != today) is what
 * keeps a periodic poll loop from firing the same sleep/wake action
 * repeatedly across every check that lands within the target minute.
 */
object NightModeTiming {
    fun minutesOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

    fun parseMinutesOfDay(hhmm: String): Int {
        val parts = hhmm.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    fun shouldTrigger(
        nowMinutes: Int,
        targetMinutes: Int,
        today: LocalDate,
        lastTriggeredDate: LocalDate?
    ): Boolean = nowMinutes == targetMinutes && lastTriggeredDate != today

    // Range check, not an edge trigger - mirrors pi-video-gate/server.js's
    // own isWithinSleepWindow() (same webcam-capture-skip use case on that
    // side), needed here for the same reason: unlike shouldTrigger() above
    // (which only fires once at the instant sleep/wake happens), this asks
    // "is right now inside the window" from a caller that isn't itself a
    // once-a-day scheduler. Handles a window crossing midnight (e.g. sleep
    // 23:00/wake 07:00).
    fun isWithinWindow(nowMinutes: Int, sleepMinutes: Int, wakeMinutes: Int): Boolean {
        if (sleepMinutes == wakeMinutes) return false
        return if (sleepMinutes < wakeMinutes) {
            nowMinutes in sleepMinutes until wakeMinutes
        } else {
            nowMinutes >= sleepMinutes || nowMinutes < wakeMinutes
        }
    }
}
