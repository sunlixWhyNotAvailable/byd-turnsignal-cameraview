package com.byd.extend

import com.byd.extend.ui.CameraDisplayGeometry
import com.byd.extend.ui.CameraGroup
import com.byd.extend.ui.CameraProfileId
import com.byd.extend.ui.CameraSide
import com.byd.extend.ui.NumericDraftPolicy
import com.byd.extend.ui.ParkingView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeHotfixUiPolicyTest {
    @Test
    fun numericDraftRejectsAndResetsBothFields() {
        val rejected = NumericDraftPolicy.resolve("999", "12", 0f..100f)
        assertFalse(rejected.valid)
        assertEquals("12", rejected.draft)
        assertEquals(12f, rejected.slider)

        // Locally valid can still fail paired-limit/crop validation in the synchronous backend.
        val submitted = NumericDraftPolicy.resolve("12.5", "12", 0f..100f)
        assertTrue(submitted.valid)
        assertEquals("12", submitted.draft)
        assertEquals(12f, submitted.slider)

        val reloaded = NumericDraftPolicy.resolve("12.5", "12.5", 0f..100f)
        assertTrue(reloaded.valid)
        assertEquals("12.5", reloaded.draft)
        assertEquals(12.5f, reloaded.slider)
    }

    @Test
    fun numericCanonicalizationAndMalformedInputUseTheAcceptedValue() {
        val canonicalized = NumericDraftPolicy.resolve("12.9", "13", 0f..100f)
        assertTrue(canonicalized.valid)
        assertEquals("13", canonicalized.draft)
        assertEquals(13f, canonicalized.slider)
        for (raw in listOf("", "-", "NaN", "Infinity", "-1")) {
            val rejected = NumericDraftPolicy.resolve(raw, "13", 0f..100f)
            assertFalse(raw, rejected.valid)
            assertEquals(raw, "13", rejected.draft)
            assertEquals(raw, 13f, rejected.slider, 0f)
        }
    }

    @Test
    fun blindGeometryUsesRealCanvasAspectAndChromeMargins() {
        val display = CameraDisplayGeometry(2000, 1000, 20, 30, 20, 70)
        val actual = productionPlacementGeometry(
            CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left),
            display, 40f, 1.5f, 1f, 1f)
        val expected = BlindSpotOverlayController.overlayGeometry(
            2000, 1000, 40, 1.5f, 1f, 1f, 20, 30, 70)
        assertEquals(expected[0], actual.left)
        assertEquals(expected[1], actual.top)
        assertEquals(expected[2], actual.width)
        assertEquals(expected[3], actual.height)
    }

    @Test
    fun parkingGeometryRemainsFourThreeOnNonSixteenNineCanvas() {
        val display = CameraDisplayGeometry(1500, 1000)
        val actual = productionPlacementGeometry(
            CameraProfileId.Parking(ParkingView.FrontLeft),
            display, 25f, 4f / 3f, .5f, .5f)
        val expected = ParkingCameraController.overlayGeometry(1500, 1000, 25, .5f, .5f)
        assertEquals(expected[0], actual.left)
        assertEquals(expected[1], actual.top)
        assertEquals(expected[2], actual.width)
        assertEquals(expected[3], actual.height)
    }
}
