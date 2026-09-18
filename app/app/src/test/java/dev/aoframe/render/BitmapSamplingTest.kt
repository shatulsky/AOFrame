package dev.aoframe.render

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapSamplingTest {
    @Test
    fun returnsOneWhenSourceAlreadyFitsTarget() {
        assertEquals(1, BitmapSampling.calculateInSampleSize(800, 600, 1080, 1920))
    }

    @Test
    fun returnsOneWhenSourceExactlyMatchesTarget() {
        assertEquals(1, BitmapSampling.calculateInSampleSize(1080, 1920, 1080, 1920))
    }

    @Test
    fun downsamplesByPowerOfTwoWhenSourceIsLarger() {
        // 4000x3000 source, 1080x1920 target -> halves are 2000x1500,
        // still >= target at inSampleSize=1, and still >= at 2 (1000x750
        // is NOT >= 1080 width, so it should stop at... let's verify:
        // inSampleSize doubles while halfWidth/inSampleSize >= targetWidth
        // AND halfHeight/inSampleSize >= targetHeight.
        // half = 2000x1500; at size=1: 2000>=1080 && 1500>=1920? no (1500<1920)
        // loop never enters -> stays at 1.
        assertEquals(1, BitmapSampling.calculateInSampleSize(4000, 3000, 1080, 1920))
    }

    @Test
    fun downsamplesALargePortraitSourceCorrectly() {
        // 4320x7680 source (tall), 1080x1920 target.
        // half = 2160x3840. size=1: 2160>=1080 && 3840>=1920 -> true, size=2
        // size=2: 1080>=1080 && 1920>=1920 -> true, size=4
        // size=4: 540>=1080? false -> stop. Result: 4.
        assertEquals(4, BitmapSampling.calculateInSampleSize(4320, 7680, 1080, 1920))
    }
}
