package dev.aoframe.render

import org.junit.Assert.assertEquals
import org.junit.Test

class KenBurnsMathTest {
    @Test
    fun startFrameIsAlwaysWideAndCentered() {
        val frame = KenBurnsMath.startFrame()
        assertEquals(1.0f, frame.scale)
        assertEquals(0.5f, frame.focusX)
        assertEquals(0.5f, frame.focusY)
    }

    @Test
    fun endFrameStaysCenteredWhenNoFaceTarget() {
        val frame = KenBurnsMath.endFrame(null, null)
        assertEquals(0.5f, frame.focusX)
        assertEquals(0.5f, frame.focusY)
    }

    @Test
    fun endFrameZoomsTowardTheDetectedFace() {
        val frame = KenBurnsMath.endFrame(faceXPercent = 30f, faceYPercent = 70f)
        assertEquals(0.30f, frame.focusX, 0.001f)
        assertEquals(0.70f, frame.focusY, 0.001f)
    }

    @Test
    fun endFrameClampsOutOfRangeFaceCoordinates() {
        val frame = KenBurnsMath.endFrame(faceXPercent = 150f, faceYPercent = -20f)
        assertEquals(1.0f, frame.focusX)
        assertEquals(0.0f, frame.focusY)
    }

    @Test
    fun endFrameDefaultsToTheStandardEndScaleWhenNoneIsPassed() {
        val frame = KenBurnsMath.endFrame(null, null)
        assertEquals(KenBurnsMath.DEFAULT_END_SCALE, frame.scale)
    }

    @Test
    fun endFrameUsesAConfiguredEndScaleInstead() {
        val frame = KenBurnsMath.endFrame(faceXPercent = null, faceYPercent = null, endScale = 2.0f)
        assertEquals(2.0f, frame.scale)
    }
}
