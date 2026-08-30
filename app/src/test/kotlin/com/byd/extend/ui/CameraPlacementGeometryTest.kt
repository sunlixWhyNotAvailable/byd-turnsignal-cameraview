package com.byd.extend

import com.byd.extend.ui.PLACEMENT_CLUSTER_ASPECT
import com.byd.extend.ui.PLACEMENT_PARKING_FRAME_ASPECT
import com.byd.extend.ui.PLACEMENT_TABLET_ASPECT
import com.byd.extend.ui.ParkingView
import com.byd.extend.ui.calculatePlacementFractions
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class CameraPlacementGeometryTest {
    @Test
    fun geometryUsesScaleAspectAndNormalizedRemainingSpace() {
        val result = calculatePlacementFractions(
            sizePercent = 30f,
            frameAspect = PLACEMENT_TABLET_ASPECT,
            canvasAspect = PLACEMENT_TABLET_ASPECT,
            x = 1f,
            y = 1f,
        )

        assertEquals(.30f, result.width, .0001f)
        assertEquals(.30f, result.height, .0001f)
        assertEquals(.70f, result.left, .0001f)
        assertEquals(.70f, result.top, .0001f)
    }

    @Test
    fun tallFrameFitsTabletAndCoordinatesStayBounded() {
        val result = calculatePlacementFractions(
            sizePercent = 60f,
            frameAspect = .5f,
            canvasAspect = PLACEMENT_TABLET_ASPECT,
            x = -2f,
            y = 4f,
        )

        assertEquals(1f, result.height, .0001f)
        assertEquals(0f, result.left, .0001f)
        assertEquals(0f, result.top, .0001f)
        assertEquals(.28125f, result.width, .0001f)
    }

    @Test
    fun blindPlacementMatchesProductionFitForTabletAndCluster() {
        assertBlindGeometryMatches(1920, 1080, PLACEMENT_TABLET_ASPECT)
        assertBlindGeometryMatches(1920, 720, PLACEMENT_CLUSTER_ASPECT)
    }

    @Test
    fun parkingPlacementStaysFourThreeWhenRawCropIsNotFourThree() {
        val preferences = TestSharedPreferences()
        val profile = ParkingCameraProfile.of(ParkingCameraProfile.FL)
        DirectCameraCrop.save(preferences, profile, DirectCameraCrop.of(
            0f, 0f, .8f, .2f, DirectCameraCrop.ASPECT_FREE))

        val state = readProductionUiState(preferences, false, false)
        val uiProfile = state.parking.views[ParkingView.FrontLeft]!!.profile
        assertEquals(PLACEMENT_PARKING_FRAME_ASPECT, uiProfile.frameAspect, .0001f)

        val actual = calculatePlacementFractions(
            25f, uiProfile.frameAspect, PLACEMENT_TABLET_ASPECT, .6f, .4f)
        val expected = ParkingCameraController.overlayGeometry(1920, 1080, 25, .6f, .4f)
        assertEquals(expected[0], (actual.left * 1920).roundToInt())
        assertEquals(expected[1], (actual.top * 1080).roundToInt())
        assertEquals(expected[2], (actual.width * 1920).roundToInt())
        assertEquals(expected[3], (actual.height * 1080).roundToInt())
    }

    private fun assertBlindGeometryMatches(width: Int, height: Int, canvasAspect: Float) {
        val scale = 36
        val aspect = 1.4f
        val x = .65f
        val y = .2f
        val actual = calculatePlacementFractions(scale.toFloat(), aspect, canvasAspect, x, y)
        val expectedSize = BlindSpotOverlayController.fitAspect(
            width * scale / 100, width, height, aspect)
        assertEquals(expectedSize[0], (actual.width * width).roundToInt())
        assertEquals(expectedSize[1], (actual.height * height).roundToInt())
        assertEquals((x * (width - expectedSize[0])).roundToInt(),
            (actual.left * width).roundToInt())
        assertEquals((y * (height - expectedSize[1])).roundToInt(),
            (actual.top * height).roundToInt())
    }
}
