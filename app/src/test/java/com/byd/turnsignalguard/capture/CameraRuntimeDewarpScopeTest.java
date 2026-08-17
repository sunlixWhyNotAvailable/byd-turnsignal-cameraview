package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraRuntimeDewarpScopeTest {
    @Test
    public void runtimeSpecsKeepAllSevenActivationScopesIndependent() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean("camera_dewarp_v2_left_enabled", false);
        settings.putBoolean("camera_dewarp_v2_right_enabled", true);
        settings.putBoolean("camera_dewarp_v2_rear_enabled", false);
        settings.putInt(CameraBufferQuality.PREF_QUALITY, CameraBufferQuality.QUALITY);

        saveProfile(settings, CameraProfile.REAR_LEFT, true,
                CameraDewarpConfig.LENS_LEFT, 133,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        saveProfile(settings, CameraProfile.FRONT_LEFT, false,
                CameraDewarpConfig.LENS_LEFT, 133,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        saveReverse(settings, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, true,
                CameraDewarpConfig.LENS_LEFT, 133,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);

        saveProfile(settings, CameraProfile.REAR_RIGHT, false,
                CameraDewarpConfig.LENS_RIGHT, 147,
                CameraDewarpConfig.PROJECTION_RECTILINEAR);
        saveProfile(settings, CameraProfile.FRONT_RIGHT, true,
                CameraDewarpConfig.LENS_RIGHT, 147,
                CameraDewarpConfig.PROJECTION_RECTILINEAR);
        saveReverse(settings, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX, false,
                CameraDewarpConfig.LENS_RIGHT, 147,
                CameraDewarpConfig.PROJECTION_RECTILINEAR);
        saveReverse(settings, ReverseCameraLayout.REAR_CAMERA_INDEX, true,
                CameraDewarpConfig.LENS_REAR, 158,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);

        CameraShellProtocol.OverlaySpec rearLeft = overlaySpec(
                settings, CameraProfile.REAR_LEFT);
        CameraShellProtocol.OverlaySpec frontLeft = overlaySpec(
                settings, CameraProfile.FRONT_LEFT);
        CameraShellProtocol.OverlaySpec rearRight = overlaySpec(
                settings, CameraProfile.REAR_RIGHT);
        CameraShellProtocol.OverlaySpec frontRight = overlaySpec(
                settings, CameraProfile.FRONT_RIGHT);
        CameraShellProtocol.ReverseOverlaySpec reverse =
                ReverseCameraController.buildOverlaySpec(settings, 30);

        assertDewarp(rearLeft.dewarp, true, CameraDewarpConfig.LENS_LEFT, 133,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        assertDewarp(frontLeft.dewarp, false, CameraDewarpConfig.LENS_LEFT, 133,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        assertDewarp(reverse.leftDewarp, true, CameraDewarpConfig.LENS_LEFT, 133,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        assertDewarp(rearRight.dewarp, false, CameraDewarpConfig.LENS_RIGHT, 147,
                CameraDewarpConfig.PROJECTION_RECTILINEAR);
        assertDewarp(frontRight.dewarp, true, CameraDewarpConfig.LENS_RIGHT, 147,
                CameraDewarpConfig.PROJECTION_RECTILINEAR);
        assertDewarp(reverse.rightDewarp, false, CameraDewarpConfig.LENS_RIGHT, 147,
                CameraDewarpConfig.PROJECTION_RECTILINEAR);
        assertDewarp(reverse.rearDewarp, true, CameraDewarpConfig.LENS_REAR, 158,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        assertEquals(CameraBufferQuality.QUALITY, rearLeft.bufferQuality);
        assertEquals(CameraBufferQuality.QUALITY, frontLeft.bufferQuality);
        assertEquals(CameraBufferQuality.QUALITY, rearRight.bufferQuality);
        assertEquals(CameraBufferQuality.QUALITY, frontRight.bufferQuality);
        assertEquals(CameraBufferQuality.QUALITY, reverse.bufferQuality);

        assertFalse(settings.getBoolean("camera_dewarp_v2_left_enabled", true));
        assertTrue(settings.getBoolean("camera_dewarp_v2_right_enabled", false));
        assertFalse(settings.getBoolean("camera_dewarp_v2_rear_enabled", true));
    }

    @Test
    public void malformedRuntimeScopeOrSharedOpticsFailsClosed() {
        TestSharedPreferences settings = new TestSharedPreferences();
        saveProfile(settings, CameraProfile.REAR_LEFT, true,
                CameraDewarpConfig.LENS_LEFT, 130,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        saveReverse(settings, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, true,
                CameraDewarpConfig.LENS_LEFT, 130,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        saveProfile(settings, CameraProfile.FRONT_RIGHT, true,
                CameraDewarpConfig.LENS_RIGHT, 140,
                CameraDewarpConfig.PROJECTION_RECTILINEAR);
        saveReverse(settings, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX, true,
                CameraDewarpConfig.LENS_RIGHT, 140,
                CameraDewarpConfig.PROJECTION_RECTILINEAR);
        saveReverse(settings, ReverseCameraLayout.REAR_CAMERA_INDEX, true,
                CameraDewarpConfig.LENS_REAR, 150,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL);

        settings.putString("camera_dewarp_v3_overlay_rear_left_enabled", "invalid");
        settings.putString("camera_dewarp_v3_reverse_2_enabled", "invalid");
        settings.putString("camera_dewarp_v2_right_fov", "invalid");

        assertFalse(overlaySpec(settings, CameraProfile.REAR_LEFT).dewarp.enabled);
        assertFalse(overlaySpec(settings, CameraProfile.FRONT_RIGHT).dewarp.enabled);
        CameraShellProtocol.ReverseOverlaySpec reverse =
                ReverseCameraController.buildOverlaySpec(settings, 31);
        assertFalse(reverse.leftDewarp.enabled);
        assertFalse(reverse.rightDewarp.enabled);
        assertTrue(reverse.rearDewarp.enabled);
    }

    private static CameraShellProtocol.OverlaySpec overlaySpec(
            TestSharedPreferences settings, int cameraId) {
        return BlindSpotOverlayController.buildOverlaySpec(
                settings, CameraProfile.of(cameraId), cameraId + 1,
                CameraDisplayTarget.TABLET, 1920, 990, 16, 36, 88);
    }

    private static void saveProfile(
            TestSharedPreferences settings, int cameraId, boolean enabled,
            int lens, int fov, int projection) {
        CameraDewarpConfig.saveForProfile(settings, CameraProfile.of(cameraId),
                CameraDewarpConfig.of(lens, enabled, fov, projection));
    }

    private static void saveReverse(
            TestSharedPreferences settings, int cameraIndex, boolean enabled,
            int lens, int fov, int projection) {
        CameraDewarpConfig.saveForReverse(settings, cameraIndex,
                CameraDewarpConfig.of(lens, enabled, fov, projection));
    }

    private static void assertDewarp(
            CameraDewarpConfig actual, boolean enabled, int lens,
            int fov, int projection) {
        assertEquals(enabled, actual.enabled);
        assertEquals(lens, actual.lens);
        assertEquals(fov, actual.fovDegrees);
        assertEquals(projection, actual.projection);
    }
}
