package dev.aoframe.nightmode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

private const val TAG = "NightModeController"
private const val CHECK_INTERVAL_MS = 30_000L

/**
 * In-process sleep/wake scheduler - one more coalesced timer alongside
 * the clock/weather/sync coordinators, not a separate service or
 * AlarmManager job - this app is meant to be alive indefinitely (kiosk,
 * boot-launched, foreground).
 *
 * Root-shell mechanism (`su -c "input keyevent KEYCODE_SLEEP/WAKEUP"`),
 * not KEYCODE_POWER (a toggle - unreliable for a fixed schedule, since
 * firing it twice in a row just undoes itself). Confirmed empirically on
 * a real device, not assumed: `adb shell run-as <applicationId> su -c id`
 * returning uid=0(root) means a rooted device's su grants root to the
 * app's own process with no interactive prompt, and both keyevents
 * genuinely change `dumpsys power`'s real Wakefulness/Display-Power
 * state.
 */
class NightModeController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var lastSleepTriggeredDate: LocalDate? = null
    private var lastWakeTriggeredDate: LocalDate? = null

    fun start() {
        scope.launch {
            while (isActive) {
                checkOnce()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun checkOnce() {
        try {
            val config = NightModeStore.load(context)
            if (!config.enabled) return

            val today = LocalDate.now()
            val nowMinutes = NightModeTiming.minutesOfDay(LocalTime.now())

            if (NightModeTiming.shouldTrigger(
                    nowMinutes, NightModeTiming.parseMinutesOfDay(config.sleepTime), today, lastSleepTriggeredDate
                )
            ) {
                lastSleepTriggeredDate = today
                runRootShell("input keyevent KEYCODE_SLEEP")
            }

            if (NightModeTiming.shouldTrigger(
                    nowMinutes, NightModeTiming.parseMinutesOfDay(config.wakeTime), today, lastWakeTriggeredDate
                )
            ) {
                lastWakeTriggeredDate = today
                runRootShell("input keyevent KEYCODE_WAKEUP")
            }
        } catch (error: Exception) {
            // A malformed config (e.g. a bad time string from a future
            // FE bug) should never take the whole coordinator loop down -
            // skip this tick, try again next one.
            Log.e(TAG, "Night-mode check failed", error)
        }
    }

    private fun runRootShell(command: String) {
        try {
            val process = ProcessBuilder("su", "-c", command).start()
            process.waitFor()
        } catch (error: Exception) {
            Log.e(TAG, "Night-mode root command failed: $command", error)
        }
    }
}
