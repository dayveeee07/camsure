package com.camsure.profiler

import org.junit.Assert.*
import org.junit.Test

class PreviewGeometryTest {
    @Test fun portraitPreviewStaysUprightAndCropsToFillTallPhone() {
        val t = PreviewGeometry.centerCrop(1080, 2400, 1280, 720, 90, 0)
        assertEquals(0f, t.rotationDegrees, 0f) // No second sensor rotation.
        assertEquals(1.25f, t.scaleX, 0.0001f)
        assertEquals(1f, t.scaleY, 0.0001f)
        assertEquals(1350f, 1080 * t.scaleX, 0.001f)
        assertEquals(2400f, 2400 * t.scaleY, 0.001f)
    }

    @Test fun bothLandscapeDirectionsCompensateDisplayWithoutFlipping() {
        for (display in listOf(90, 270)) {
            val t = PreviewGeometry.centerCrop(2400, 1080, 1280, 720, 90, display)
            assertEquals(-display.toFloat(), t.rotationDegrees, 0f)
            assertEquals(2400f, 1080 * t.scaleY, 0.001f)
            assertEquals(1350f, 2400 * t.scaleX, 0.001f)
            assertTrue(t.scaleX > 0 && t.scaleY > 0)
        }
    }

    @Test fun nativeLandscapeAndReversePortraitHaveNoExtraSensorRotation() {
        val tablet = PreviewGeometry.centerCrop(1920, 1080, 1280, 720, 0, 0)
        assertEquals(1f, tablet.scaleX, 0f)
        assertEquals(1f, tablet.scaleY, 0f)
        assertEquals(0f, tablet.rotationDegrees, 0f)
        val reverse = PreviewGeometry.centerCrop(1080, 2400, 1280, 720, 270, 180)
        assertEquals(-180f, reverse.rotationDegrees, 0f)
        assertEquals(1.25f, reverse.scaleX, 0.0001f)
        assertEquals(1f, reverse.scaleY, 0.0001f)
    }

    @Test fun everySensorAndDisplayRotationFillsViewportWithUniformImageScale() {
        for (sensor in listOf(0, 90, 180, 270)) for (display in listOf(0, 90, 180, 270)) {
            for ((w, h) in listOf(1080 to 2400, 2400 to 1080, 800 to 800, 1200 to 1920)) {
                val t = PreviewGeometry.centerCrop(w, h, 1280, 720, sensor, display)
                val naturalW = if (sensor % 180 == 0) 1280f else 720f
                val naturalH = if (sensor % 180 == 0) 720f else 1280f
                assertEquals(w * t.scaleX / naturalW, h * t.scaleY / naturalH, 0.0001f)
                val rotatedW = if (display % 180 == 0) w * t.scaleX else h * t.scaleY
                val rotatedH = if (display % 180 == 0) h * t.scaleY else w * t.scaleX
                assertTrue(rotatedW >= w - 0.01f && rotatedH >= h - 0.01f)
                assertTrue(kotlin.math.abs(rotatedW - w) < 0.01f || kotlin.math.abs(rotatedH - h) < 0.01f)
            }
        }
    }
}
