package dev.aoframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reboot-tested pattern for taking over the launcher role on boot:
 * Frameo (the stock launcher this app replaces) auto-launches on its own
 * boot trigger too, so force-stopping it here (harmless whether it's
 * running yet or not) then launching this app is a reliable fallback even
 * if `set-home-activity` doesn't stick on a given device/Android build.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        runAsRoot("am force-stop net.frameo.frame")

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
    }

    // Best-effort: if su is unavailable or fails, HOME takeover (if it
    // stuck) or the existing "Відкрити ..." control actions remain as
    // fallbacks - never worth crashing boot over.
    private fun runAsRoot(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
        } catch (error: Exception) {
            // Root unavailable or su failed - non-fatal, see above.
        }
    }
}
