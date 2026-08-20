package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.fail;

import org.junit.Test;

/** Focused contract checks for the shared parking overlay runtime identity. */
public final class ParkingCameraRuntimeTest {
    @Test
    public void parkingOverlayIdsAreSeparateAndOrdered() {
        assertEquals(10, CameraOverlayProfile.COUNT);
        assertEquals(CameraOverlayProfile.PARKING_OFFSET,
                CameraOverlayProfile.overlayIdForParking(ParkingCameraProfile.FL));
        assertEquals("FL", CameraOverlayProfile.of(4).wireName);
        assertEquals("Front", CameraOverlayProfile.of(5).wireName);
        assertEquals("FR", CameraOverlayProfile.of(6).wireName);
        assertEquals("RR", CameraOverlayProfile.of(7).wireName);
        assertEquals("Rear", CameraOverlayProfile.of(8).wireName);
        assertEquals("RL", CameraOverlayProfile.of(9).wireName);
    }

    @Test
    public void parkingOverlaySpecUsesGenericIdAndPhysicalCrop() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit().putInt(CameraBufferQuality.PREF_QUALITY,
                CameraBufferQuality.ORIGINAL).apply();
        int[] x = {0, 720, 1440, 1440, 720, 0};
        int[] y = {0, 0, 0, 720, 720, 720};
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            CameraShellProtocol.OverlaySpec spec = ParkingCameraController.buildOverlaySpec(
                    profile, profile.id + 1, CameraDisplayTarget.TABLET,
                    1920, 1080, preferences);
            assertEquals(CameraOverlayProfile.overlayIdForParking(profile.id), spec.cameraId);
            assertEquals(480, spec.width);
            assertEquals(360, spec.height);
            assertEquals(x[profile.id], spec.x);
            assertEquals(y[profile.id], spec.y);
            assertEquals(CameraDewarpConfig.lensFor(profile), spec.dewarp.lens);
            assertEquals(CameraBufferQuality.ORIGINAL, spec.bufferQuality);
            assertEquals(profile.id == ParkingCameraProfile.REAR,
                    spec.mirrorHorizontally);
            spec.validate(1920, 1080);
        }
    }

    @Test
    public void parkingProtocolAcceptsGenericIdsButWarningStaysBlindOnly() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            CameraShellProtocol.OverlaySpec spec = ParkingCameraController.buildOverlaySpec(
                    profile, profile.id + 1, CameraDisplayTarget.TABLET,
                    1920, 1080, preferences);
            spec.validate(1920, 1080);
        }
        try {
            CameraShellProtocol.validateWarning(4, 1, 1,
                    CameraShellProtocol.WARNING_EDGE_LEFT,
                    CameraShellProtocol.WARNING_MODE_CONSTANT);
            fail("parking warning must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected: BSD warning is a blind-spot-only protocol operation
        }
    }

    @Test
    public void parkingConsumerIdentityCoexistsWithBlindGroup() {
        CameraHelperMain.HelperBinder.ConsumerGroup overlay =
                new CameraHelperMain.HelperBinder.ConsumerGroup(
                        CameraHelperMain.CAMERA_OWNER_OVERLAY);
        CameraHelperMain.HelperBinder.ConsumerGroup parking =
                new CameraHelperMain.HelperBinder.ConsumerGroup(
                        CameraHelperMain.CAMERA_OWNER_PARKING);
        overlay.set(new android.view.Surface[]{null}, new int[]{2}, 1,
                "blind", false, false, true);
        parking.set(new android.view.Surface[]{null, null}, new int[]{2, 4}, 2,
                "parking", false, false, true);
        assertTrue(overlay.has());
        assertTrue(parking.has());
        assertEquals(2, parking.indexes[0]);
        assertEquals(4, parking.indexes[1]);
    }

    @Test
    public void parkingOverlayRejectsClusterTarget() {
        CameraShellProtocol.OverlaySpec spec = ParkingCameraController.buildOverlaySpec(
                ParkingCameraProfile.of(ParkingCameraProfile.FL), 1,
                CameraDisplayTarget.CLUSTER, 1920, 1080,
                new TestSharedPreferences());
        try {
            spec.validate(1920, 1080);
            fail("parking overlay must stay on tablet");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void firstFrameMustMatchCurrentEpochAndActivationIsNotRearmed() {
        OverlayFrameArm current = OverlayFrameArm.create(
                CameraOverlayProfile.overlayIdForParking(ParkingCameraProfile.FL),
                11, 12, 13);
        assertTrue(ParkingCameraController.matchesFirstFrame(current, 11, 12, 13, true));
        assertFalse(ParkingCameraController.matchesFirstFrame(current, 11, 12, 12, true));
        assertFalse(ParkingCameraController.matchesFirstFrame(current, 11, 12, 13, false));
        assertTrue(ParkingCameraController.shouldArmFirstFrame(
                true, true, false, false, false));
        assertFalse(ParkingCameraController.shouldArmFirstFrame(
                true, true, false, false, true));
        assertFalse(ParkingCameraController.shouldArmFirstFrame(
                true, true, false, true, false));
    }

    @Test
    public void activityPreviewAndReverseEachPreemptParking() {
        assertFalse(ParkingCameraController.isHardBlocked(false, false, false));
        assertTrue(ParkingCameraController.isHardBlocked(true, false, false));
        assertTrue(ParkingCameraController.isHardBlocked(false, true, false));
        assertTrue(ParkingCameraController.isHardBlocked(false, false, true));
    }
}
