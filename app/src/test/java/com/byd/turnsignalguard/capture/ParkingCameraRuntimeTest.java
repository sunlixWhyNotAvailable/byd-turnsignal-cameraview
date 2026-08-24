package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.fail;

import org.junit.Test;

/** Focused contract checks for the shared parking overlay runtime identity. */
public final class ParkingCameraRuntimeTest {
    @Test
    public void parkingOverlayIdsAreSeparateAndOrdered() {
        assertEquals(12, CameraOverlayProfile.COUNT);
        assertEquals(CameraOverlayProfile.PARKING_OFFSET,
                CameraOverlayProfile.overlayIdForParking(ParkingCameraProfile.FL));
        assertEquals("FL", CameraOverlayProfile.of(4).wireName);
        assertEquals("Front", CameraOverlayProfile.of(5).wireName);
        assertEquals("FR", CameraOverlayProfile.of(6).wireName);
        assertEquals("RR", CameraOverlayProfile.of(7).wireName);
        assertEquals("Rear", CameraOverlayProfile.of(8).wireName);
        assertEquals("RL", CameraOverlayProfile.of(9).wireName);
        assertEquals(10, CameraOverlayProfile.overlayIdForParking(ParkingCameraProfile.LEFT));
        assertEquals(11, CameraOverlayProfile.overlayIdForParking(ParkingCameraProfile.RIGHT));
        assertEquals("Left", CameraOverlayProfile.of(10).wireName);
        assertEquals("Right", CameraOverlayProfile.of(11).wireName);
    }

    @Test
    public void parkingOverlaySpecUsesGenericIdAndPhysicalCrop() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit().putInt(CameraBufferQuality.PREF_QUALITY,
                CameraBufferQuality.ORIGINAL).apply();
        int[] x = {0, 720, 1440, 1440, 720, 0, 0, 1440};
        int[] y = {0, 0, 0, 720, 720, 720, 360, 360};
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
    public void parkingOverlayScaleUsesSharedFivePercentMinimum() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit().putInt("parking_camera_fl_scale", 5).apply();
        CameraShellProtocol.OverlaySpec minimum = ParkingCameraController.buildOverlaySpec(
                ParkingCameraProfile.of(ParkingCameraProfile.FL), 1, CameraDisplayTarget.TABLET,
                1920, 1080, preferences);
        assertEquals(96, minimum.width);
        assertEquals(72, minimum.height);

        preferences.edit().putInt("parking_camera_fl_scale", 0).apply();
        CameraShellProtocol.OverlaySpec clampedMinimum = ParkingCameraController.buildOverlaySpec(
                ParkingCameraProfile.of(ParkingCameraProfile.FL), 1, CameraDisplayTarget.TABLET,
                1920, 1080, preferences);
        assertEquals(96, clampedMinimum.width);
        assertEquals(72, clampedMinimum.height);

        preferences.edit().putInt("parking_camera_fl_scale", 100).apply();
        CameraShellProtocol.OverlaySpec clampedMaximum = ParkingCameraController.buildOverlaySpec(
                ParkingCameraProfile.of(ParkingCameraProfile.FL), 1, CameraDisplayTarget.TABLET,
                1920, 1080, preferences);
        assertEquals(1152, clampedMaximum.width);
        assertEquals(864, clampedMaximum.height);
    }

    @Test
    public void parkingOverlayGeometryScalesProportionallyAndMapsAnchors() {
        assertArrayEquals(new int[]{0, 0, 96, 72},
                ParkingCameraController.overlayGeometry(1920, 1080, 5, 0.0f, 0.0f));
        assertArrayEquals(new int[]{864, 468, 192, 144},
                ParkingCameraController.overlayGeometry(1920, 1080, 10, 0.5f, 0.5f));
        assertArrayEquals(new int[]{1632, 864, 288, 216},
                ParkingCameraController.overlayGeometry(1920, 1080, 15, 1.0f, 1.0f));
        assertArrayEquals(new int[]{384, 108, 1152, 864},
                ParkingCameraController.overlayGeometry(1920, 1080, 60, 0.5f, 0.5f));
        assertArrayEquals(new int[]{0, 1008, 96, 72},
                ParkingCameraController.overlayGeometry(1920, 1080, 0, -1.0f, 2.0f));
    }

    @Test
    public void parkingOverlaySpecMatchesSharedGeometry() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit()
                .putInt("parking_camera_fl_scale", 15)
                .putFloat("parking_camera_fl_x", 0.5f)
                .putFloat("parking_camera_fl_y", 0.5f)
                .apply();
        CameraShellProtocol.OverlaySpec spec = ParkingCameraController.buildOverlaySpec(
                ParkingCameraProfile.of(ParkingCameraProfile.FL), 1,
                CameraDisplayTarget.TABLET, 1920, 1080, preferences);
        assertArrayEquals(ParkingCameraController.overlayGeometry(
                        1920, 1080, 15, 0.5f, 0.5f),
                new int[]{spec.x, spec.y, spec.width, spec.height});
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

    @Test
    public void reversePriorityCanBeAllowedWithoutBypassingParkingRules() {
        assertTrue(ParkingCameraController.isHardBlocked(false, false, true, false));
        assertFalse(ParkingCameraController.isHardBlocked(false, false, true, true));
        assertTrue(ParkingCameraController.isHardBlocked(true, false, true, true));
        assertTrue(ParkingCameraController.isHardBlocked(false, true, true, true));
    }

    @Test
    public void activityExclusiveBlocksBothParkingAndBlindButReverseOnlyBlocksBlind() {
        assertTrue(CameraHelperMain.HelperBinder.persistentAttachBlocked(
                false, true, false, true));
        assertTrue(CameraHelperMain.HelperBinder.persistentAttachBlocked(
                true, false, false, true));
        assertTrue(CameraHelperMain.HelperBinder.persistentAttachBlocked(
                true, false, true, false));
        assertFalse(CameraHelperMain.HelperBinder.persistentAttachBlocked(
                false, true, true, false));
    }

    @Test
    public void closeRetryFailureSuccessAndStaleTokenStaySafe() {
        ParkingCameraController.CloseRetryState state =
                new ParkingCameraController.CloseRetryState();
        int[] calls = {0};
        state.schedule(7);
        assertTrue(state.blocksEvaluation());
        assertEquals(ParkingCameraController.CloseRetryState.Result.FAILED,
                state.retry(7, requestId -> {
                    assertEquals(7, requestId);
                    calls[0]++;
                    return false;
                }));
        assertTrue(state.blocksEvaluation());
        assertEquals(ParkingCameraController.CloseRetryState.Result.SUCCEEDED,
                state.retry(7, requestId -> {
                    assertEquals(7, requestId);
                    calls[0]++;
                    return true;
                }));
        assertFalse(state.blocksEvaluation());
        state.schedule(7);
        assertEquals(ParkingCameraController.CloseRetryState.Result.STALE,
                state.retry(14, requestId -> {
                    calls[0]++;
                    return true;
                }));
        assertFalse(state.blocksEvaluation());
        assertEquals(2, calls[0]);
    }
}
