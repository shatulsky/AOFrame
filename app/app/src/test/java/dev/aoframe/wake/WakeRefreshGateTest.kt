package dev.aoframe.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRefreshGateTest {
    @Test
    fun shouldFireIsTrueOnTheFirstCall() {
        val gate = WakeRefreshGate(minIntervalMs = 1000, nowMs = { 5000L })

        assertTrue(gate.shouldFire())
    }

    @Test
    fun shouldFireIsFalseWithinTheMinimumInterval() {
        var now = 5000L
        val gate = WakeRefreshGate(minIntervalMs = 1000, nowMs = { now })

        assertTrue(gate.shouldFire())
        now += 500
        assertFalse(gate.shouldFire())
    }

    @Test
    fun shouldFireIsTrueAgainOnceTheIntervalHasPassed() {
        var now = 5000L
        val gate = WakeRefreshGate(minIntervalMs = 1000, nowMs = { now })

        assertTrue(gate.shouldFire())
        now += 1000
        assertTrue(gate.shouldFire())
    }

    @Test
    fun shouldFireIsFalseExactlyAtTheBoundary() {
        var now = 5000L
        val gate = WakeRefreshGate(minIntervalMs = 1000, nowMs = { now })

        assertTrue(gate.shouldFire())
        now += 999
        assertFalse(gate.shouldFire())
    }
}
