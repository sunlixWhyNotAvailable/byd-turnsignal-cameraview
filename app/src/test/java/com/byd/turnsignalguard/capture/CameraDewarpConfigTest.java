package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraDewarpConfigTest {
    @Test
    public void clampsPhysicalLensCalibrationAndUsesGpuOnlyWhenEnabled() {
        CameraDewarpConfig value = CameraDewarpConfig.of(true, 140, -5, 120, 90);
        assertEquals(100, value.strength);
        assertEquals(0, value.centerX);
        assertEquals(100, value.centerY);
        assertEquals(100, value.zoom);
        assertTrue(value.usesGpu());
        assertTrue(CameraDewarpConfig.of(true, 0, 50, 50, 115).usesGpu());
        assertFalse(CameraDewarpConfig.disabled().usesGpu());
    }

    @Test
    public void frontAndRearProfilesShareTheirPhysicalSideLens() {
        assertEquals(CameraDewarpConfig.LENS_LEFT,
                CameraDewarpConfig.lensFor(CameraProfile.of(CameraProfile.REAR_LEFT)));
        assertEquals(CameraDewarpConfig.LENS_LEFT,
                CameraDewarpConfig.lensFor(CameraProfile.of(CameraProfile.FRONT_LEFT)));
        assertEquals(CameraDewarpConfig.LENS_RIGHT,
                CameraDewarpConfig.lensFor(CameraProfile.of(CameraProfile.REAR_RIGHT)));
        assertEquals(CameraDewarpConfig.LENS_REAR,
                CameraDewarpConfig.lensForReverseCamera(
                        ReverseCameraLayout.REAR_CAMERA_INDEX));
    }
}
