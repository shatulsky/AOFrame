package dev.aoframe.render

/**
 * Pure timing thresholds for video playback (stall watchdog / late-preload
 * trigger - VIDEO_STALL_TIMEOUT_MS, VIDEO_MAX_DURATION_MS,
 * LATE_PRELOAD_LEAD_MS), already tuned against real playback issues on
 * this class of hardware. Plain longs/booleans, no ExoPlayer/Android
 * classes - genuine plain-JVM-testable unit, same reasoning as
 * BitmapSampling/KenBurnsMath.
 */
object VideoTiming {
    fun hasStalled(elapsedSincePlayRequestedMs: Long, currentPositionMs: Long, stallTimeoutMs: Long): Boolean =
        currentPositionMs == 0L && elapsedSincePlayRequestedMs > stallTimeoutMs

    fun hasExceededMaxDuration(elapsedSincePlayRequestedMs: Long, maxDurationMs: Long): Boolean =
        elapsedSincePlayRequestedMs > maxDurationMs

    fun shouldTriggerLatePreload(currentPositionMs: Long, durationMs: Long, leadMs: Long): Boolean =
        durationMs > 0 && (durationMs - currentPositionMs) < leadMs
}
