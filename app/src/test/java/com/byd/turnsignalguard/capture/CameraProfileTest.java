package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CameraProfileTest {
    @Test
    public void frontAndRearPoliciesStayIndependent() {
        int rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT).bit();
        int frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT).bit();
        int frontRight = CameraProfile.of(CameraProfile.FRONT_RIGHT).bit();

        assertEquals(rearLeft | frontLeft, CameraProfile.desiredMask(
                true, 2, 20.0f, 0.0f,
                true, 20, true, 0, true));
        assertEquals(frontLeft | frontRight, CameraProfile.desiredMask(
                true, 1, 0.0f, 0.0f,
                false, 20, true, 0, false));
        assertEquals(0, CameraProfile.desiredMask(
                false, 2, 30.0f, 0.0f,
                true, 20, true, 0, false));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, Float.NaN, 0.0f,
                true, 20, true, 0, false));
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
    }
}
