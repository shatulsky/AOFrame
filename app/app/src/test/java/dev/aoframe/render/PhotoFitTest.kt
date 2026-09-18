package dev.aoframe.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFitTest {
    // Panel is the frame's actual portrait resolution, 1080x1920 (9:16).
    private val panelWidth = 1080
    private val panelHeight = 1920

    @Test
    fun coversWhenAssetRatioMatchesPanelExactly() {
        assertTrue(PhotoFit.shouldCover(1080, 1920, panelWidth, panelHeight))
    }

    @Test
    fun coversA3x4PortraitPhoto() {
        // Common phone-photo portrait ratio, doesn't match the panel's
        // 9:16 exactly but still reads fine cropped - letterboxing this
        // looks worse, not better.
        assertTrue(PhotoFit.shouldCover(1080, 1440, panelWidth, panelHeight))
    }

    @Test
    fun coversA4x5PortraitPhoto() {
        assertTrue(PhotoFit.shouldCover(1080, 1350, panelWidth, panelHeight))
    }

    @Test
    fun doesNotCoverALandscapePhoto() {
        assertFalse(PhotoFit.shouldCover(1920, 1080, panelWidth, panelHeight))
    }

    @Test
    fun coversASquarePhoto() {
        // height >= width counts as "portrait" here, so a square photo
        // covers same as any other portrait ratio - just crops equally
        // top/bottom, not a case that needs letterboxing on its own.
        assertTrue(PhotoFit.shouldCover(1080, 1080, panelWidth, panelHeight))
    }

    @Test
    fun invalidDimensionsDefaultToCover() {
        assertTrue(PhotoFit.shouldCover(0, 0, panelWidth, panelHeight))
    }
}
