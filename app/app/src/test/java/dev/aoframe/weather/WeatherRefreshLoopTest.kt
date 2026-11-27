package dev.aoframe.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WeatherRefreshLoopTest {
    private val interval = 1_800_000L
    private val retry = 60_000L

    private class Outcome(val waits: List<Long>, val rendered: List<String>, val failures: List<Long>)

    /** Runs the loop with scripted fetch results; stops after the script ends. */
    private fun run(script: List<Result<String>>): Outcome {
        val waits = mutableListOf<Long>()
        val rendered = mutableListOf<String>()
        val failures = mutableListOf<Long>()
        var calls = 0
        val loop = WeatherRefreshLoop<String>(
            fetch = { script[calls++].getOrThrow() },
            render = { rendered += it },
            delayMs = { waits += it },
            isActive = { calls < script.size },
            onFailure = { _, next -> failures += next },
            refreshIntervalMs = interval,
            retryInitialMs = retry
        )
        runBlocking { loop.run() }
        return Outcome(waits, rendered, failures)
    }

    private fun fail() = Result.failure<String>(IOException("offline"))

    @Test
    fun goodFetchRendersAndWaitsTheNormalInterval() {
        val out = run(listOf(Result.success("a")))
        assertEquals(listOf("a"), out.rendered)
        assertEquals(listOf(interval), out.waits)
    }

    @Test
    fun failureRendersNothingAndRetriesAfterOneMinute() {
        val out = run(listOf(fail()))
        assertTrue(out.rendered.isEmpty())
        assertEquals(listOf(retry), out.waits)
    }

    @Test
    fun retryDelayDoublesAndStopsAtTheNormalInterval() {
        val out = run(List(8) { fail() })
        assertEquals(
            listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, interval, interval, interval),
            out.waits
        )
    }

    @Test
    fun goodFetchResetsTheBackoff() {
        val out = run(listOf(fail(), fail(), Result.success("ok"), fail()))
        assertEquals(listOf(60_000L, 120_000L, interval, 60_000L), out.waits)
        assertEquals(listOf("ok"), out.rendered)
    }

    @Test
    fun failureReportsTheDelayUsedForTheRetry() {
        val out = run(listOf(fail(), fail()))
        assertEquals(listOf(60_000L, 120_000L), out.failures)
    }

    @Test
    fun failureAfterRenderKeepsTheOldWeather() {
        // The old snapshot stays on screen: a failed fetch never calls render.
        val out = run(listOf(Result.success("a"), fail()))
        assertEquals(listOf("a"), out.rendered)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotSwallowed() {
        val loop = WeatherRefreshLoop<String>(
            fetch = { throw CancellationException("stop") },
            render = {},
            delayMs = {},
            isActive = { true }
        )
        runBlocking { loop.run() }
    }

    @Test
    fun stopsWhenNotActive() {
        val loop = WeatherRefreshLoop<String>(
            fetch = { error("must not run") },
            render = {},
            delayMs = {},
            isActive = { false }
        )
        runBlocking { loop.run() }
    }
}
