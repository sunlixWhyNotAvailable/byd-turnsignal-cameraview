package com.byd.extend.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugCameraGeometryTest {
    @Test
    fun directPreviewUsesKnownPanoSourceAspect() {
        assertEquals(1920f / 1300f,
            diagnosticCameraFrameAspect(true, CameraDisplayGeometry(1280, 720)), .0001f)
    }

    @Test
    fun avmPreviewUsesResolvedDisplayAspectWithoutInventingSdkDimensions() {
        assertEquals(1500f / 1000f,
            diagnosticCameraFrameAspect(false, CameraDisplayGeometry(1500, 1000)), .0001f)
    }

    @Test
    fun invalidAvmDisplayFallsBackToKnownSafeAspect() {
        assertEquals(1920f / 1300f,
            diagnosticCameraFrameAspect(false, CameraDisplayGeometry(0, 0)), .0001f)
    }

    @Test
    fun avmHeadingTracksSelectedOrientation() {
        assertEquals("VIEW_GROUP_H", diagnosticGroupTitle(false, AvmOrientation.Horizontal))
        assertEquals("VIEW_GROUP_V", diagnosticGroupTitle(false, AvmOrientation.Vertical))
        assertEquals("pano_h", diagnosticGroupTitle(true, AvmOrientation.Vertical))
    }
}
