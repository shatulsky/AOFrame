package dev.aoframe.render

/**
 * scale: view scale factor. focusX/focusY: pivot point as a 0..1
 * fraction of the view, used as the ObjectAnimator pivotX/pivotY target
 * (in view pixels, computed by the caller) - ObjectAnimator rather than
 * a CSS-style transition, since this is a native View hierarchy.
 */
data class KenBurnsFrame(val scale: Float, val focusX: Float, val focusY: Float)

/**
 * Plain floats in and out - no Android graphics classes touched, so
 * this is a genuine plain-JVM-testable unit, same reasoning as
 * BitmapSampling.
 *
 * Opens wide/centered regardless of face data (classic Ken Burns starts
 * on the fuller scene), then slowly zooms toward the detected face when
 * one exists, or stays centered otherwise.
 */
object KenBurnsMath {
    const val START_SCALE = 1.0f

    // 1.455 default end scale - noticeably more zoom than a subtle 1.15
    // or 1.35, since a barely-perceptible zoom reads as a bug rather than
    // an effect, especially on horizontal photos. Just the *default* -
    // configurable via SlideshowSettingsConfig.endZoom; START_SCALE stays
    // fixed (only the end zoom is meant to be user-adjustable).
    const val DEFAULT_END_SCALE = 1.455f
    private const val CENTER = 0.5f

    fun startFrame(): KenBurnsFrame = KenBurnsFrame(START_SCALE, CENTER, CENTER)

    fun endFrame(faceXPercent: Float?, faceYPercent: Float?, endScale: Float = DEFAULT_END_SCALE): KenBurnsFrame {
        val focusX = faceXPercent?.let { (it / 100f).coerceIn(0f, 1f) } ?: CENTER
        val focusY = faceYPercent?.let { (it / 100f).coerceIn(0f, 1f) } ?: CENTER
        return KenBurnsFrame(endScale, focusX, focusY)
    }
}
