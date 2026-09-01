package com.byd.extend;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public final class FrameAspectPersistenceTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void outputOnlyEditsAndResetDoNotMaterializeOrChangeDefaultGeometry() {
        for (CameraProfile profile : CameraProfile.values()) {
            TestSharedPreferences settings = new TestSharedPreferences();
            DirectCameraCrop raw = DirectCameraCrop.load(settings, profile);
            DirectCameraCrop corrected = DirectCameraCrop.loadCorrected(settings, profile, raw);
            float aspect = BlindSpotOverlayController.readFrameAspect(settings, profile);
            DirectCameraCrop.saveOutputTransform(settings, profile,
                    raw.withOutputTransformPreservingGeometry(90, CameraRotation.MODE_FIT,
                            !raw.mirrorHorizontally));
            assertEquals(3, settings.getAll().size());
            assertEquals(1, settings.transactions);
            DirectCameraCrop changed = DirectCameraCrop.load(settings, profile);
            assertEquals(90, changed.rotationDegrees);
            assertEquals(raw.width, changed.width, 0.0f);
            assertEquals(raw.height, changed.height, 0.0f);
            assertEquals(corrected.left,
                    DirectCameraCrop.loadCorrected(settings, profile, changed).left, 0.0f);
            assertEquals(corrected.width,
                    DirectCameraCrop.loadCorrected(settings, profile, changed).width, 0.0f);
            assertEquals(aspect, BlindSpotOverlayController.readFrameAspect(settings, profile), 0.0f);
            CameraCalibrationPreset.resetCameraStage(settings, profile,
                    CameraCalibrationPreset.Stage.OUTPUT);
            assertEquals(3, settings.getAll().size());
            assertEquals(raw.rotationDegrees, DirectCameraCrop.load(settings, profile).rotationDegrees);
            assertEquals(corrected.left, DirectCameraCrop.loadCorrected(settings, profile,
                    DirectCameraCrop.load(settings, profile)).left, 0.0f);
            assertEquals(aspect, BlindSpotOverlayController.readFrameAspect(settings, profile), 0.0f);
        }
    }

    @Test
    public void frameAspectKeysStayIndependentAcrossAllProfiles() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile[] profiles = CameraProfile.values();
        float[] aspects = {4.0f / 3.0f, 16.0f / 9.0f, 1.0f, 2.0f};

        for (int i = 0; i < profiles.length; i++) {
            settings.putFloat(BlindSpotOverlayController.frameAspectKey(profiles[i]), aspects[i]);
            assertEquals(aspects[i], BlindSpotOverlayController.readFrameAspect(
                    settings, profiles[i], aspects[i]), EPSILON);
            assertEquals(aspects[i], settings.getFloat(
                    BlindSpotOverlayController.frameAspectKey(profiles[i]), -1.0f), EPSILON);
        }
    }

    @Test
    public void absentKeyReadsCurrentFallbackWithoutCreatingPreference() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        float fallback = 16.0f / 9.0f;
        DirectCameraCrop.save(settings, profile, DirectCameraCrop.defaultFor(profile));

        assertEquals(fallback, BlindSpotOverlayController.readFrameAspect(
                settings, profile, fallback), EPSILON);
        assertEquals(-1.0f, settings.getFloat(
                BlindSpotOverlayController.frameAspectKey(profile), -1.0f), EPSILON);

        Map<String, ?> afterFirstRead = new HashMap<>(settings.getAll());
        assertEquals(1.0f, BlindSpotOverlayController.readFrameAspect(
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
    public void invalidStoredFrameAspectFallsBackWithoutRepairOnRead() {
        float[] invalid = {0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY};
        for (float value : invalid) {
            TestSharedPreferences settings = new TestSharedPreferences();
            CameraProfile profile = CameraProfile.of(CameraProfile.FRONT_LEFT);
            String key = BlindSpotOverlayController.frameAspectKey(profile);
            DirectCameraCrop.save(settings, profile, DirectCameraCrop.defaultFor(profile));
            settings.putFloat(key, value);
            Map<String, ?> before = settings.getAll();

            float fallback = 1.6f;
            assertEquals(fallback, BlindSpotOverlayController.readFrameAspect(
                    settings, profile, fallback), EPSILON);
            assertEquals(before, settings.getAll());
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

    @Test
    public void explicitGeometryPinsPriorAspectWithCropAndMetadataInOneTransaction() {
        for (boolean corrected : new boolean[]{false, true}) {
            TestSharedPreferences settings = seededLegacyGeometry();
            CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
            DirectCameraCrop prior = DirectCameraCrop.load(settings, profile);
            String aspectKey = BlindSpotOverlayController.frameAspectKey(profile);
            Map<String, ?> before = settings.getAll();
            assertFalse(settings.contains(aspectKey));
            assertThrows(IllegalArgumentException.class, () ->
                    DirectCameraCrop.saveRawGeometryEdit(settings, profile,
                            prior.withIndependentGeometry(-0.1f, 0, 0.4f, 0.4f)));
            assertEquals(before, settings.getAll());
            int transactions = settings.transactions;
            DirectCameraCrop edit = prior.withIndependentGeometry(0.1f, 0.1f, 0.5f, 0.3f);
            if (corrected) DirectCameraCrop.saveCorrectedGeometryEdit(settings, profile, edit);
            else DirectCameraCrop.saveRawGeometryEdit(settings, profile, edit);
            assertEquals(transactions + 1, settings.transactions);
            assertEquals(prior.outputAspect(), settings.getFloat(aspectKey, -1), EPSILON);
            assertEquals(DirectCameraCrop.ASPECT_FREE,
                    settings.getInt(DirectCameraCrop.correctedAspectKey(profile), -1));
        }
    }

    @Test
    public void originalResetPresetLoadAndTransferPinDestinationNotNewCropAspect() {
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        for (int operation = 0; operation < 3; operation++) {
            TestSharedPreferences settings = seededLegacyGeometry();
            DirectCameraCrop prior = DirectCameraCrop.load(settings, profile);
            if (operation == 1) {
                CameraCalibrationPreset.saveCamera(settings, profile);
                settings.putFloat(DirectCameraCrop.preferenceKey(profile, 2), 0.55f);
                prior = DirectCameraCrop.load(settings, profile);
            }
            int transactions = settings.transactions;
            if (operation == 0) CameraCalibrationPreset.resetCameraStage(
                    settings, profile, CameraCalibrationPreset.Stage.ORIGINAL);
            if (operation == 1) org.junit.Assert.assertTrue(
                    CameraCalibrationPreset.loadCamera(settings, profile));
            if (operation == 2) CameraCalibrationPreset.mirrorCamera(settings,
                    CameraProfile.of(CameraProfile.REAR_RIGHT));
            assertEquals(transactions + 1, settings.transactions);
            assertEquals(prior.outputAspect(), settings.getFloat(
                    BlindSpotOverlayController.frameAspectKey(profile), -1), EPSILON);
        }
    }

    @Test
    public void exportIsReadOnlyIncludingLegacyCorrectionAndCropFallbacks() {
        TestSharedPreferences settings = seededLegacyGeometry();
        settings.putInt("camera_dewarp_v2_left_fov", 121);
        settings.putBoolean("camera_dewarp_v2_left_enabled", true);
        settings.putFloat("reverse_camera_2_corrected_v3_crop_left", 0.2f);
        settings.putFloat("reverse_camera_2_corrected_v3_crop_width", 0.005f);
        settings.putFloat("reverse_camera_2_corrected_v3_crop_height", 0.005f);
        Map<String, ?> before = settings.getAll();
        int transactions = settings.transactions;
        String exported = CameraSettingsTransfer.exportCameraPreset(settings);
        org.junit.Assert.assertTrue(exported.contains("frame_aspect"));
        assertEquals(before, settings.getAll());
        assertEquals(transactions, settings.transactions);
    }

    @Test
    public void correctedFallbackIsSharedAndTogglePinsItBeforeChangingStage() {
        TestSharedPreferences settings = seededLegacyGeometry();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        android.content.SharedPreferences.Editor editor = settings.edit();
        DirectCameraCrop.writeCorrected(editor, profile, DirectCameraCrop.of(
                0.2f, 0.2f, 0.3f, 0.4f, DirectCameraCrop.ASPECT_FREE));
        editor.apply();
        float expected = 0.3f * DirectCameraCrop.SOURCE_WIDTH
                / (0.4f * DirectCameraCrop.SOURCE_HEIGHT);
        Map<String, ?> before = settings.getAll();
        assertEquals(expected, BlindSpotOverlayController.readFrameAspect(settings, profile), EPSILON);
        assertEquals(before, settings.getAll());
        CameraDewarpConfig config = CameraDewarpConfig.loadForProfile(settings, profile);
        int transactions = settings.transactions;
        CameraDewarpConfig.saveForProfile(settings, profile, config.withEnabled(false));
        assertEquals(transactions + 1, settings.transactions);
        assertEquals(expected, BlindSpotOverlayController.readFrameAspect(settings, profile), EPSILON);
        assertEquals(expected, settings.getFloat(
                BlindSpotOverlayController.frameAspectKey(profile), -1), EPSILON);
    }

    private static TestSharedPreferences seededLegacyGeometry() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        android.content.SharedPreferences.Editor editor = settings.edit();
        DirectCameraCrop.write(editor, profile, DirectCameraCrop.of(
                0.1f, 0.1f, 0.4f, 0.2f, DirectCameraCrop.ASPECT_FOUR_THREE,
                30, CameraRotation.MODE_ALIGNED));
        editor.apply();
        return settings;
    }
}
