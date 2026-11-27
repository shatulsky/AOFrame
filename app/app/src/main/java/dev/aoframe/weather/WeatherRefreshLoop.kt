package dev.aoframe.weather

import kotlinx.coroutines.CancellationException

/**
 * The retry and backoff rule of the weather loop, without Android and
 * without a real delay. MainActivity gives it the fetch, the render and
 * the real `delay`; a test gives it fakes.
 *
 * A good fetch renders the result and waits the normal interval. A failed
 * fetch keeps the old weather on screen and waits a shorter time: the
 * retry delay starts at [retryInitialMs], doubles after each failure and
 * stops at [refreshIntervalMs]. A good fetch resets it.
 */
class WeatherRefreshLoop<T>(
    private val fetch: suspend () -> T,
    private val render: (T) -> Unit,
    private val delayMs: suspend (Long) -> Unit,
    private val isActive: () -> Boolean,
    private val onFailure: (Exception, Long) -> Unit = { _, _ -> },
    private val refreshIntervalMs: Long = 30 * 60 * 1_000L,
    private val retryInitialMs: Long = 60 * 1_000L
) {
    suspend fun run() {
        var retryDelayMs = retryInitialMs
        while (isActive()) {
            var waitMs = refreshIntervalMs
            try {
                render(fetch())
                retryDelayMs = retryInitialMs
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error, retryDelayMs)
                waitMs = retryDelayMs
                retryDelayMs = minOf(retryDelayMs * 2, refreshIntervalMs)
            }
            delayMs(waitMs)
        }
    }
}
