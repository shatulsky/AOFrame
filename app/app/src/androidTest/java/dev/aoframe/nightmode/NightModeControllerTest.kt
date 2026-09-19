package dev.aoframe.nightmode

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Deterministic tests for NightModeController's trigger-once-per-day
 * scheduling logic, using injected clock/root-shell-action/screen-state
 * seams (added specifically so this can be tested - see the class's own
 * comment) to drive checkOnce() directly instead of waiting on the real
 * 30s loop/wall clock or the emulator's actual (indeterminate at test
 * time) screen-power state. NightModeTiming's own pure logic
 * (shouldTrigger/isWithinWindow) is already covered by
 * NightModeTimingTest - these tests focus on the controller's own state
 * (once-per-day dedup, config-disabled short circuit, malformed-config
 * resilience, and skipping a keyevent when the screen's already in the
 * target state - added 2026-09-20 alongside the isScreenAwake seam).
 */
class NightModeControllerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configFile = File(context.filesDir, "night-mode.json")
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val shellCommands = mutableListOf<String>()
    private val day = LocalDate.of(2026, 1, 15)

    // screenAwake defaults to true - most existing tests assert a SLEEP
    // trigger, which only fires when the screen starts out awake.
    private fun controllerAt(sleepTime: String, wakeTime: String, minute: Int, screenAwake: Boolean = true) = NightModeController(
        context = context,
        scope = scope,
        today = { day },
        nowMinutes = { minute },
        runRootShell = { shellCommands.add(it) },
        isScreenAwake = { screenAwake }
    ).also {
        NightModeStore.save(context, NightModeConfig(enabled = true, sleepTime = sleepTime, wakeTime = wakeTime))
    }

    @Before
    fun setUp() {
        configFile.delete()
        shellCommands.clear()
    }

    @After
    fun tearDown() {
        configFile.delete()
    }

    @Test
    fun checkOnceDoesNothingWhenDisabled() {
        NightModeStore.save(context, NightModeConfig(enabled = false, sleepTime = "01:00", wakeTime = "08:00"))
        val controller = NightModeController(
            context = context,
            scope = scope,
            today = { day },
            nowMinutes = { NightModeTiming.parseMinutesOfDay("01:00") },
            runRootShell = { shellCommands.add(it) }
        )

        controller.checkOnce()

        assertTrue(shellCommands.isEmpty())
    }

    @Test
    fun checkOnceTriggersSleepAtTheConfiguredMinute() {
        val controller = controllerAt(sleepTime = "01:00", wakeTime = "08:00", minute = NightModeTiming.parseMinutesOfDay("01:00"))

        controller.checkOnce()

        assertEquals(listOf("input keyevent KEYCODE_SLEEP"), shellCommands)
    }

    @Test
    fun checkOnceTriggersWakeAtTheConfiguredMinute() {
        val controller = controllerAt(
            sleepTime = "01:00", wakeTime = "08:00", minute = NightModeTiming.parseMinutesOfDay("08:00"), screenAwake = false
        )

        controller.checkOnce()

        assertEquals(listOf("input keyevent KEYCODE_WAKEUP"), shellCommands)
    }

    @Test
    fun checkOnceDoesNotRetriggerSleepTwiceOnTheSameDay() {
        val controller = controllerAt(sleepTime = "01:00", wakeTime = "08:00", minute = NightModeTiming.parseMinutesOfDay("01:00"))

        controller.checkOnce()
        controller.checkOnce()

        assertEquals(listOf("input keyevent KEYCODE_SLEEP"), shellCommands)
    }

    @Test
    fun checkOnceSkipsSleepKeyeventWhenScreenIsAlreadyAsleep() {
        // Simulates an external actor (pi-dashboard's manual sleep-now
        // button, or the presence-based HA automation) having already put
        // the screen to sleep before this scheduled tick runs - the whole
        // point of the isScreenAwake seam added 2026-09-20.
        val controller = controllerAt(
            sleepTime = "01:00", wakeTime = "08:00", minute = NightModeTiming.parseMinutesOfDay("01:00"), screenAwake = false
        )

        controller.checkOnce()

        assertTrue(shellCommands.isEmpty())
    }

    @Test
    fun checkOnceSkipsWakeKeyeventWhenScreenIsAlreadyAwake() {
        val controller = controllerAt(
            sleepTime = "01:00", wakeTime = "08:00", minute = NightModeTiming.parseMinutesOfDay("08:00"), screenAwake = true
        )

        controller.checkOnce()

        assertTrue(shellCommands.isEmpty())
    }

    @Test
    fun checkOnceDoesNothingOutsideEitherTriggerMinute() {
        val controller = controllerAt(sleepTime = "01:00", wakeTime = "08:00", minute = NightModeTiming.parseMinutesOfDay("14:30"))

        controller.checkOnce()

        assertTrue(shellCommands.isEmpty())
    }

    @Test
    fun checkOnceSwallowsAMalformedConfigWithoutThrowingOrTriggering() {
        configFile.writeText("""{"enabled": true, "sleepTime": "not-a-time", "wakeTime": "08:00"}""")
        val controller = NightModeController(
            context = context,
            scope = scope,
            today = { day },
            nowMinutes = { 60 },
            runRootShell = { shellCommands.add(it) }
        )

        controller.checkOnce() // must not throw

        assertTrue(shellCommands.isEmpty())
    }
}
