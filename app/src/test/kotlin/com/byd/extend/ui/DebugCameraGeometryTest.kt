package com.byd.extend.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun cameraHeaderKeepsOnlyOpeningAndErrorLifecycleStates() {
        val ukrainian = UiStrings(UiLanguage.Ukrainian)
        val english = UiStrings(UiLanguage.English)
        assertEquals("Відкриття...", cameraStatusForDisplay(
            StatusUiState("Opening camera", StatusTone.Warning, true), ukrainian).text)
        assertEquals("Error", cameraStatusForDisplay(
            StatusUiState("Camera error", StatusTone.Error, true), english).text)
        assertFalse(cameraStatusForDisplay(
            StatusUiState("First frame ready", StatusTone.Ok, true), ukrainian).visible)
        assertEquals("Opening...", cameraStatusForDisplay(
            StatusUiState("Waiting for first frame", StatusTone.Warning, true), english).text)
        assertFalse(cameraStatusForDisplay(
            StatusUiState("Camera closed", StatusTone.Neutral, true), english).visible)
    }

    @Test
    fun reverseSectionUsesLayoutInEnglish() {
        assertEquals("Композиція", UiStrings(UiLanguage.Ukrainian).reverseSections[1])
        assertEquals("Layout", UiStrings(UiLanguage.English).reverseSections[1])
    }

    @Test
    fun debugHeadingUsesTheDebugTabAfterMirrorInsertion() {
        val source = java.io.File("src/main/kotlin/com/byd/extend/ui/DebugScreen.kt").readText()
        assertTrue(source.contains("PageTitle(strings.tabs[6]"))
        assertEquals("Відладка", UiStrings(UiLanguage.Ukrainian).tabs[6])
        assertEquals("Debug", UiStrings(UiLanguage.English).tabs[6])
        assertEquals("调试", UiStrings(UiLanguage.Chinese).tabs[6])
    }
}
