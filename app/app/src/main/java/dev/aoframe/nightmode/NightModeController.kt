package dev.aoframe.nightmode

import android.content.Context
import android.os.PowerManager
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
    private val scope: CoroutineScope,
    // Overridable only by tests - production always uses the real clock/
    // root-shell mechanism (see this class's own header comment for why
    // root-shell). Defaulted rather than a separate test subclass/
    // interface, so MainActivity's call site stays untouched.
    private val today: () -> LocalDate = { LocalDate.now() },
    private val nowMinutes: () -> Int = { NightModeTiming.minutesOfDay(LocalTime.now()) },
    private val runRootShell: (String) -> Unit = ::runRealRootShell,
    private val isScreenAwake: () -> Boolean = { realIsScreenAwake(context) }
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

    // Internal, not private - lets tests drive a single check deterministically
    // instead of waiting on the real 30s loop/wall clock (see NightModeControllerTest).
    internal fun checkOnce() {
        try {
            val config = NightModeStore.load(context)
            if (!config.enabled) return

            val today = today()
            val nowMinutes = nowMinutes()
            // Real screen-power state (android.os.PowerManager), not just
            // the once-per-day date dedup below - an external actor
            // (pi-dashboard's manual sleep/wake buttons, or the
            // presence-based HA automation, both hitting this same device
            // via ADB root keyevents) can already have put the screen in
            // the target state before this scheduled tick runs. Checking
            // first avoids firing a redundant keyevent - harmless since
            // KEYCODE_SLEEP/WAKEUP are idempotent, but pointless work, and
            // the whole point of this refactor (2026-09-20, see
            // homeassistant/docs/automations.md's presence-based
            // auto-sleep automation) is to stop doing pointless work.
            val screenAwake = isScreenAwake()

            if (NightModeTiming.shouldTrigger(
                    nowMinutes, NightModeTiming.parseMinutesOfDay(config.sleepTime), today, lastSleepTriggeredDate
                )
            ) {
                lastSleepTriggeredDate = today
                if (screenAwake) runRootShell("input keyevent KEYCODE_SLEEP")
            }

            if (NightModeTiming.shouldTrigger(
                    nowMinutes, NightModeTiming.parseMinutesOfDay(config.wakeTime), today, lastWakeTriggeredDate
                )
            ) {
                lastWakeTriggeredDate = today
                if (!screenAwake) runRootShell("input keyevent KEYCODE_WAKEUP")
            }
        } catch (error: Exception) {
            // A malformed config (e.g. a bad time string from a future
            // FE bug) should never take the whole coordinator loop down -
            // skip this tick, try again next one.
            Log.e(TAG, "Night-mode check failed", error)
        }
    }
}

private fun runRealRootShell(command: String) {
    try {
        val process = ProcessBuilder("su", "-c", command).start()
        process.waitFor()
    } catch (error: Exception) {
        Log.e(TAG, "Night-mode root command failed: $command", error)
    }
}

// Same PowerManager.isInteractive() check MainActivity.buildStatusJson()
// exposes as /status's "screenAwake" field - kept as a free function
// (not a Context extension) so it's trivially swappable via the
// isScreenAwake constructor param above, same pattern as runRootShell.
private fun realIsScreenAwake(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
