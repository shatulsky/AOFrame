package dev.aoframe.e2e

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import dev.aoframe.MainActivity
import dev.aoframe.R

/**
 * Page Object of the frame's one screen. Tests say what they see
 * ("weather widget is visible"), never which view id holds it.
 * Waiting is a poll with a deadline ([eventually]), never a fixed sleep.
 */
class FramePage(private val scenario: ActivityScenario<MainActivity>) {
    private fun <T> read(block: (MainActivity) -> T): T {
        var result: T? = null
        scenario.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun visibility(id: Int): Int = read { it.findViewById<View>(id).visibility }
    private fun text(id: Int): String = read { it.findViewById<TextView>(id).text.toString() }

    val loadingOverlayShown: Boolean get() = visibility(R.id.loadingOverlay) == View.VISIBLE
    val weatherWidgetShown: Boolean get() = visibility(R.id.weatherWidgetContainer) == View.VISIBLE
    val weatherWidgetGone: Boolean get() = visibility(R.id.weatherWidgetContainer) == View.GONE
    val liveViewButtonShown: Boolean get() = visibility(R.id.controlLiveViewButton) == View.VISIBLE
    val liveViewPictureShown: Boolean get() = visibility(R.id.playerViewLive) == View.VISIBLE

    val clockTime: String get() = text(R.id.clockTimeText)
    val clockDate: String get() = text(R.id.clockDateText)
    val currentTemp: String get() = text(R.id.weatherCurrentTempText)
    val feelsLike: String get() = text(R.id.weatherFeelsLikeText)
    val precipitation: String get() = text(R.id.weatherPrecipText)
    val seaTemp: String get() = text(R.id.weatherSeaTempText)
    val seaTempShown: Boolean get() = visibility(R.id.weatherSeaTempText) == View.VISIBLE
    val day0Label: String get() = text(R.id.weatherDay0Label)
    val day0Max: String get() = text(R.id.weatherDay0MaxTemp)
    val day2Min: String get() = text(R.id.weatherDay2MinTemp)

    val photoShown: Boolean
        get() = read { it.findViewById<ImageView>(R.id.photoView).drawable != null }

    fun tapLiveView() {
        onView(withId(R.id.controlLiveViewButton)).perform(click())
    }
}

/** Polls [condition] until it holds or [timeoutMs] passes. No fixed sleep: it returns at once when true. */
fun eventually(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(50)
    }
    throw AssertionError("Timed out after $timeoutMs ms waiting for: $what")
}
