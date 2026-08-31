package com.byd.extend

import com.byd.extend.ui.CameraDisplayGeometry
import com.byd.extend.ui.CameraGroup
import com.byd.extend.ui.CameraProfileId
import com.byd.extend.ui.CameraSide
import com.byd.extend.ui.DisplayTarget
import com.byd.extend.ui.ParkingView
import com.byd.extend.ui.ProductionPlacementGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPlacementGeometryTest {
    @Test
    fun parkingGeometryUsesProductionScaleAndNormalizedRemainingSpace() {
        val display = CameraDisplayGeometry(1920, 1080, target = DisplayTarget.Tablet)
        val actual = productionPlacementGeometry(
            CameraProfileId.Parking(ParkingView.FrontLeft), display,
            sizePercent = 30f, frameAspect = 16f / 9f, x = 1f, y = 1f)
        val expected = ParkingCameraController.overlayGeometry(1920, 1080, 30, 1f, 1f)

        assertGeometryEquals(expected, actual)
        assertEquals(.30f, actual.width / actual.canvasWidth.toFloat(), .0001f)
        // Parking camera output is fixed 4:3, even on a 16:9 display.
        assertEquals(.40f, actual.height / actual.canvasHeight.toFloat(), .0001f)
        assertEquals(.70f, actual.left / actual.canvasWidth.toFloat(), .0001f)
        assertEquals(.60f, actual.top / actual.canvasHeight.toFloat(), .0001f)
    }

    @Test
    fun tallBlindFrameFitsDisplayAndCoordinatesStayBounded() {
        val display = CameraDisplayGeometry(1920, 1080, target = DisplayTarget.Tablet)
        val actual = productionPlacementGeometry(
            CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left), display,
            sizePercent = 60f, frameAspect = .5f, x = -2f, y = 4f)
        val expected = BlindSpotOverlayController.overlayGeometry(
            1920, 1080, 60, .5f, -2f, 4f, 0, 0, 0)

        assertGeometryEquals(expected, actual)
        assertEquals(0, actual.left)
        assertEquals(0, actual.top)
        assertEquals(1f, actual.height / actual.canvasHeight.toFloat(), .0001f)
        assertEquals(.28125f, actual.width / actual.canvasWidth.toFloat(), .0001f)
    }

    @Test
    fun blindPlacementMatchesProductionFitForTabletAndCluster() {
        assertBlindGeometryMatches(CameraDisplayGeometry(1920, 1080, target = DisplayTarget.Tablet))
        assertBlindGeometryMatches(CameraDisplayGeometry(1920, 720, target = DisplayTarget.Cluster))
    }

    @Test
    fun parkingPlacementStaysFourThreeWhenRawCropIsNotFourThree() {
        val preferences = TestSharedPreferences()
        val profile = ParkingCameraProfile.of(ParkingCameraProfile.FL)
        DirectCameraCrop.save(preferences, profile, DirectCameraCrop.of(
            0f, 0f, .8f, .2f, DirectCameraCrop.ASPECT_FREE))

        val display = CameraDisplayGeometry(1500, 1000, target = DisplayTarget.Tablet)
        val state = readProductionUiState(preferences, false, false) { display }
        val uiProfile = state.parking.views[ParkingView.FrontLeft]!!.profile
        assertEquals(display, uiProfile.displayGeometry)

        val actual = productionPlacementGeometry(
            CameraProfileId.Parking(ParkingView.FrontLeft), uiProfile.displayGeometry,
            uiProfile.size.toFloat(), uiProfile.frameAspect,
            uiProfile.x.toFloat() / 100f, uiProfile.y.toFloat() / 100f)
        val expected = ParkingCameraController.overlayGeometry(
            display.width, display.height, uiProfile.size.toInt(),
            uiProfile.x.toFloat() / 100f, uiProfile.y.toFloat() / 100f)
        assertGeometryEquals(expected, actual)
        assertEquals(expected[2].toFloat() / expected[3].coerceAtLeast(1), uiProfile.frameAspect, .0001f)

        // The output frame remains fixed 4:3 regardless of the persisted raw-crop aspect.
        val canonicalPreferences = TestSharedPreferences()
        DirectCameraCrop.save(canonicalPreferences, profile, DirectCameraCrop.of(
            0f, 0f, .75f, .25f, DirectCameraCrop.ASPECT_FOUR_THREE))
        val canonical = readProductionUiState(canonicalPreferences, false, false) { display }
            .parking.views[ParkingView.FrontLeft]!!.profile
        assertEquals(canonical.frameAspect, uiProfile.frameAspect, .0001f)
    }

    private fun assertBlindGeometryMatches(display: CameraDisplayGeometry) {
        val scale = 36
        val aspect = 1.4f
        val x = .65f
        val y = .2f
        val actual = productionPlacementGeometry(
            CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left), display,
            scale.toFloat(), aspect, x, y)
        val expected = BlindSpotOverlayController.overlayGeometry(
            display.width, display.height, scale, aspect, x, y,
            display.marginLeft, display.marginTop, display.marginBottom)
        assertGeometryEquals(expected, actual)
    }

    private fun assertGeometryEquals(expected: IntArray, actual: ProductionPlacementGeometry) {
        assertEquals(expected[0], actual.left)
        assertEquals(expected[1], actual.top)
        assertEquals(expected[2], actual.width)
        assertEquals(expected[3], actual.height)
    }
}
