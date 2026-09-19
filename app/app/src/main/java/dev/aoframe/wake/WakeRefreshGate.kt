package dev.aoframe.wake

// Default cooldown - long enough to absorb rapid repeated resumes
// (a flaky external trigger, or the Activity briefly cycling), short
// enough that a real fresh wake later the same session still refreshes.
private const val DEFAULT_MIN_INTERVAL_MS = 60_000L

/**
 * Debounces "the screen just turned back on" so a caller doesn't repeat
 * expensive work (this app's only current caller forces a fresh
 * webcam-gate capture) on every resume in quick succession.
 *
 * Framework-agnostic on purpose - plain Kotlin, no Android dependency -
 * so it's trivially unit-testable and isn't tied to any one detection
 * mechanism. MainActivity drives it from onResume(), which for a single
 * foreground kiosk app (nothing else able to steal focus) already
 * correlates with the real display turning back on regardless of what
 * caused it - a scheduled timer, a manual sleep/wake action, a
 * home-automation trigger, or anything else a future caller adds. Any
 * other "did we just wake up" signal could drive the same gate; it's
 * meant as a general reusable hook, not something specific to the
 * webcam-refresh use it currently backs.
 */
class WakeRefreshGate(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    // Overridable only by tests - production always uses the real clock.
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var lastFiredAtMs = 0L

    // True the first call, and again only once minIntervalMs has passed
    // since the last true result - false ("too soon, skip") otherwise.
    fun shouldFire(): Boolean {
        val now = nowMs()
        if (now - lastFiredAtMs < minIntervalMs) return false
        lastFiredAtMs = now
        return true
    }
}
