package dev.aoframe.cache

import kotlinx.coroutines.CancellationException

/**
 * Runs [attempt] up to [maxAttempts] times, waiting [retryDelayMs] between
 * tries (no wait after the last one). Returns the first good result, or
 * null when every try failed. Plain Kotlin: the delay is injected, so a
 * test never really waits. Cancellation is rethrown, never retried.
 */
suspend fun <T> retryUpTo(
    maxAttempts: Int,
    retryDelayMs: Long,
    delayMs: suspend (Long) -> Unit,
    onFailure: (Int, Exception) -> Unit = { _, _ -> },
    attempt: suspend () -> T
): T? {
    for (number in 1..maxAttempts) {
        try {
            return attempt()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(number, error)
            if (number < maxAttempts) delayMs(retryDelayMs)
        }
    }
    return null
}
