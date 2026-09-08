package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraProfileTest {
    @Test
    public void frontAndRearPoliciesStayIndependent() {
        int rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT).bit();
        int frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT).bit();

        assertEquals(rearLeft | frontLeft, CameraProfile.desiredMask(
                true, 2, 10.0f, 10.0f,
                true, 10, 300, true, 0, 10, true, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                false, 2, 30.0f, 0.0f,
                true, 10, 300, true, 0, 10, false, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, Float.NaN, 0.0f,
                true, 10, 300, true, 0, 10, false, 10.0f));
    }

    @Test
    public void panoramaSuppressionIsIndependentAndRequiresKnownVisibleState() {
        assertFalse(BlindSpotOverlayController.panoramaSuppresses(false, true, true));
        assertFalse(BlindSpotOverlayController.panoramaSuppresses(true, false, true));
        assertFalse(BlindSpotOverlayController.panoramaSuppresses(true, true, false));
        assertTrue(BlindSpotOverlayController.panoramaSuppresses(true, true, true));

        TestSharedPreferences settings = new TestSharedPreferences();
        assertTrue(BlindSpotOverlayController.readPanoramaSuppression(settings, false));
        assertTrue(BlindSpotOverlayController.readPanoramaSuppression(settings, true));
        BlindSpotOverlayController.migrateOverlayPreferences(settings);
        assertTrue(settings.getBoolean(
                BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, false));
        assertTrue(settings.getBoolean(
                BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA, false));
        settings.putBoolean(
                BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, false);
        assertFalse(BlindSpotOverlayController.readPanoramaSuppression(settings, false));
        assertTrue(BlindSpotOverlayController.readPanoramaSuppression(settings, true));
    }

    @Test
    public void speedRangesAreInclusiveAndSharedBoundaryAllowsBothGroups() {
        int rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT).bit();
        int frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT).bit();
        assertEquals(rearLeft | frontLeft, CameraProfile.desiredMask(
                true, 2, 10.0f, 10.0f,
                true, 10, 300, true, 0, 10, true, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, 301.0f, 20.0f,
                true, 10, 300, true, 0, 10, true, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, 10.0f, 20.0f,
                true, 20, 10, true, 20, 10, true, 10.0f));
    }

    @Test
    public void frontCameraRequiresFreshSignedAngle() {
        int frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT).bit();
        int frontRight = CameraProfile.of(CameraProfile.FRONT_RIGHT).bit();
        assertEquals(frontLeft, CameraProfile.desiredMask(
                true, 1, 5.0f, 12.0f,
                false, 10, 300, true, 0, 10, false, 10.0f));
        assertEquals(frontRight, CameraProfile.desiredMask(
                true, 1, 5.0f, -12.0f,
                false, 10, 300, true, 0, 10, false, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, 5.0f, -12.0f,
                false, 10, 300, true, 0, 10, true, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, 5.0f, Float.NaN,
                false, 10, 300, true, 0, 10, true, 10.0f));
    }

    @Test
    public void cameraMappingsAndCropKeysAreFixed() {
        assertEquals(2, CameraProfile.of(CameraProfile.REAR_LEFT).previewIndex);
        assertEquals(2, CameraProfile.of(CameraProfile.FRONT_LEFT).previewIndex);
        assertEquals(3, CameraProfile.of(CameraProfile.REAR_RIGHT).previewIndex);
        assertEquals(3, CameraProfile.of(CameraProfile.FRONT_RIGHT).previewIndex);
        assertEquals(DirectCameraCrop.PREF_LEFT_X,
                DirectCameraCrop.preferenceKey(CameraProfile.of(CameraProfile.REAR_LEFT), 0));
        assertEquals(DirectCameraCrop.PREF_FRONT_LEFT_X,
                DirectCameraCrop.preferenceKey(CameraProfile.of(CameraProfile.FRONT_LEFT), 0));
        assertEquals(DirectCameraCrop.PREF_FRONT_LEFT_ROTATION,
                DirectCameraCrop.preferenceKey(CameraProfile.of(CameraProfile.FRONT_LEFT), 5));
        assertEquals(DirectCameraCrop.PREF_FRONT_LEFT_ROTATION_MODE,
                DirectCameraCrop.preferenceKey(CameraProfile.of(CameraProfile.FRONT_LEFT), 6));
    }
}
