package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
