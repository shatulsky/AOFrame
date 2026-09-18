package dev.aoframe.render

/**
 * Standard Android downsampling calculation - the largest power-of-two
 * inSampleSize such that the decoded bitmap is still >= the requested
 * target size. Avoids decoding a cached thumbnail at its full source
 * resolution just to immediately downscale it for display - a real
 * native-heap memory cost, especially on lower-RAM devices.
 *
 * Pure ints in, pure int out - no BitmapFactory.Options/Android SDK
 * classes touched here, so this is a genuine plain-JVM-testable unit
 * (unlike the JSON/SQLite code - see those tests' header comments for
 * why they need a real Android runtime and this doesn't).
 */
object BitmapSampling {
    fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        var inSampleSize = 1
        if (sourceHeight > targetHeight || sourceWidth > targetWidth) {
            val halfHeight = sourceHeight / 2
            val halfWidth = sourceWidth / 2
            while ((halfHeight / inSampleSize) >= targetHeight && (halfWidth / inSampleSize) >= targetWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
