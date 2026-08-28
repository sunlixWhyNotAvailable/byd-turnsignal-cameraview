package com.byd.extend;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class FrameAspectPersistenceTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void frameAspectKeysStayIndependentAcrossAllProfiles() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile[] profiles = CameraProfile.values();
        float[] aspects = {4.0f / 3.0f, 16.0f / 9.0f, 1.0f, 2.0f};

        for (int i = 0; i < profiles.length; i++) {
            DirectCameraCrop.save(settings, profiles[i], DirectCameraCrop.defaultFor(profiles[i]));
            assertEquals(aspects[i], BlindSpotOverlayController.readFrameAspect(
                    settings, profiles[i], aspects[i]), EPSILON);
            assertEquals(aspects[i], settings.getFloat(
                    BlindSpotOverlayController.frameAspectKey(profiles[i]), -1.0f), EPSILON);
        }
    }

    @Test
    public void firstReadMigratesFallbackAndSecondReadIsIdempotent() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        float fallback = 16.0f / 9.0f;
        DirectCameraCrop.save(settings, profile, DirectCameraCrop.defaultFor(profile));

        assertEquals(fallback, BlindSpotOverlayController.readFrameAspect(
                settings, profile, fallback), EPSILON);
        assertEquals(fallback, settings.getFloat(
                BlindSpotOverlayController.frameAspectKey(profile), -1.0f), EPSILON);

        Map<String, ?> afterFirstRead = new HashMap<>(settings.getAll());
        assertEquals(fallback, BlindSpotOverlayController.readFrameAspect(
                settings, profile, 1.0f), EPSILON);
        assertEquals(afterFirstRead, settings.getAll());
    }

    @Test
    public void validStoredFrameAspectWinsOverCurrentCropFallback() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_RIGHT);
        String key = BlindSpotOverlayController.frameAspectKey(profile);
        settings.putFloat(key, 1.85f);
        Map<String, ?> beforeRead = new HashMap<>(settings.getAll());

        assertEquals(1.85f, BlindSpotOverlayController.readFrameAspect(
                settings, profile, DirectCameraCrop.OUTPUT_ASPECT), EPSILON);
        assertEquals(beforeRead, settings.getAll());
    }

    @Test
    public void invalidStoredFrameAspectFallsBackAndRepairs() {
        float[] invalid = {0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY};
        for (float value : invalid) {
            TestSharedPreferences settings = new TestSharedPreferences();
            CameraProfile profile = CameraProfile.of(CameraProfile.FRONT_LEFT);
            String key = BlindSpotOverlayController.frameAspectKey(profile);
            DirectCameraCrop.save(settings, profile, DirectCameraCrop.defaultFor(profile));
            settings.putFloat(key, value);

            float fallback = 1.6f;
            assertEquals(fallback, BlindSpotOverlayController.readFrameAspect(
                    settings, profile, fallback), EPSILON);
            assertEquals(fallback, settings.getFloat(key, -1.0f), EPSILON);
        }
    }

    @Test
    public void cropChangesDoNotResizeMigratedOverlayFrame() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        DirectCameraCrop initialCrop = DirectCameraCrop.defaultFor(profile);
        float persistedFrameAspect = BlindSpotOverlayController.readFrameAspect(
                settings, profile, initialCrop.outputAspect());
        int[] initialGeometry = BlindSpotOverlayController.overlayGeometry(
                1280, 800, 30, persistedFrameAspect, 0.5f, 0.5f,
                16, 36, 88);

        DirectCameraCrop changedCrop = DirectCameraCrop.defaultFor(
                profile.right(), DirectCameraCrop.ASPECT_SIXTEEN_NINE);
        assertNotEquals(initialCrop.outputAspect(), changedCrop.outputAspect(), EPSILON);
        DirectCameraCrop.save(settings, profile, changedCrop);
        DirectCameraCrop loaded = DirectCameraCrop.load(settings, profile);
        DirectCameraCrop.save(settings, profile, loaded.mirrored());

        assertEquals(persistedFrameAspect, BlindSpotOverlayController.readFrameAspect(
                settings, profile, loaded.outputAspect()), EPSILON);
        int[] geometryAfterCropChange = BlindSpotOverlayController.overlayGeometry(
                1280, 800, 30, persistedFrameAspect, 0.5f, 0.5f,
                16, 36, 88);
        assertArrayEquals(initialGeometry, geometryAfterCropChange);
        assertEquals(persistedFrameAspect, settings.getFloat(
                BlindSpotOverlayController.frameAspectKey(profile), -1.0f), EPSILON);
    }

    @Test
    public void calibrationLiveUsesProductionAspectForEveryBlindAndParkingProfile() {
        TestSharedPreferences settings = new TestSharedPreferences();
        float[] blindAspects = {1.6173527f, 1.61f, 1.47f, 1.46f};
        CameraProfile[] blindProfiles = CameraProfile.values();
        for (int i = 0; i < blindProfiles.length; i++) {
            settings.putFloat(
                    BlindSpotOverlayController.frameAspectKey(blindProfiles[i]),
                    blindAspects[i]);
            assertEquals(blindAspects[i], CameraProbeActivity.calibrationLiveAspect(
                    settings, false, blindProfiles[i].id, 1.1f), EPSILON);
        }
        for (ParkingCameraProfile parking : ParkingCameraProfile.values()) {
            assertEquals(4.0f / 3.0f, CameraProbeActivity.calibrationLiveAspect(
                    settings, true, parking.id, 1.9f), EPSILON);
        }
    }
}
