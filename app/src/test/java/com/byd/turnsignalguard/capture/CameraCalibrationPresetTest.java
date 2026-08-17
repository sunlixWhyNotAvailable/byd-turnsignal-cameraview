package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CameraCalibrationPresetTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void percentEntryAcceptsCommaDotAndValidatesBoundsAndAspect() {
        DirectCameraCrop free = DirectCameraCrop.parsePercent(
                "10.25", "20,50", "30", "40,00",
                DirectCameraCrop.ASPECT_FREE, 17, CameraRotation.MODE_FILL);
        assertEquals(0.1025f, free.left, EPSILON);
        assertEquals(0.205f, free.top, EPSILON);
        assertEquals(0.30f, free.width, EPSILON);
        assertEquals(0.40f, free.height, EPSILON);
        assertEquals(17, free.rotationDegrees);

        for (int aspect = DirectCameraCrop.ASPECT_FOUR_THREE;
                aspect <= DirectCameraCrop.ASPECT_ONE_ONE; aspect++) {
            DirectCameraCrop shaped = DirectCameraCrop.of(
                    0.0f, 0.0f, 0.40f, 0.10f, aspect);
            DirectCameraCrop parsed = DirectCameraCrop.parsePercent(
                    "0", "0", "40.00",
                    String.format(Locale.US, "%.2f", shaped.height * 100.0f),
                    aspect, 0, CameraRotation.MODE_FIT);
            assertEquals(Float.parseFloat(String.format(
                    Locale.US, "%.2f", shaped.height * 100.0f)) / 100.0f,
                    parsed.height, 0.0f);
        }

        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "-1", "0", "20", "20", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT));
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "90", "0", "20", "20", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT));
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "0", "0", "0", "20", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT));
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "0", "0", "0.99", "1.00", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT));
        DirectCameraCrop minimum = DirectCameraCrop.parsePercent(
                "0", "0", "1,00", "1.00", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT);
        assertEquals(0.01f, minimum.width, 0.0f);
        assertEquals(0.01f, minimum.height, 0.0f);
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "0", "0", "40", "44.50", DirectCameraCrop.ASPECT_FOUR_THREE,
                0, CameraRotation.MODE_FIT));
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "NaN", "0", "20", "20", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT));
        DirectCameraCrop shaped = DirectCameraCrop.of(
                0.0f, 0.0f, 0.40f, 0.10f, DirectCameraCrop.ASPECT_FOUR_THREE);
        float displayedHeight = Float.parseFloat(String.format(
                Locale.US, "%.2f", shaped.height * 100.0f));
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "0", "0", "40.00",
                String.format(Locale.US, "%.2f", displayedHeight + 0.01f),
                DirectCameraCrop.ASPECT_FOUR_THREE, 0, CameraRotation.MODE_FIT));
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "0", "0", "1e-40", "20", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT));
        assertThrows(IllegalArgumentException.class, () ->
                ReverseCameraLayout.sourceCrop(
                        0.0f, 0.0f, Float.MIN_VALUE, 0.25f));
        assertThrows(IllegalArgumentException.class, () ->
                ReverseCameraLayout.sourceCrop(0.0f, 0.0f, 0.0099f, 0.25f));
    }

    @Test
    public void rawAndCorrectedCoordinatesRemainIndependent() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        DirectCameraCrop raw = crop(0.10f, 0.12f, 0.50f, 0.55f, 21);
        DirectCameraCrop corrected = crop(0.24f, 0.18f, 0.34f, 0.41f, 21);
        DirectCameraCrop.save(preferences, profile, raw);
        DirectCameraCrop.saveCorrected(preferences, profile, corrected);

        DirectCameraCrop movedRaw = crop(0.30f, 0.06f, 0.44f, 0.62f, 21);
        DirectCameraCrop.save(preferences, profile, movedRaw);

        assertCrop(movedRaw, DirectCameraCrop.load(preferences, profile));
        assertGeometry(corrected,
                DirectCameraCrop.loadCorrected(preferences, profile, movedRaw));
    }

    @Test
    public void cameraPresetRoundTripsAndInvalidLoadChangesNothing() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        DirectCameraCrop raw = crop(0.12f, 0.16f, 0.48f, 0.52f, 33);
        DirectCameraCrop corrected = crop(0.27f, 0.21f, 0.31f, 0.39f, 33);
        DirectCameraCrop.save(preferences, profile, raw);
        DirectCameraCrop.saveCorrected(preferences, profile, corrected);
        CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 137,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL));
        CameraCalibrationPreset.saveCamera(preferences, profile);

        DirectCameraCrop active = crop(0.02f, 0.03f, 0.20f, 0.22f, -9);
        DirectCameraCrop.save(preferences, profile, active);
        DirectCameraCrop.saveCorrected(preferences, profile, active);
        CameraDewarpConfig.saveForProfile(preferences, profile,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT));
        assertTrue(CameraCalibrationPreset.loadCamera(preferences, profile));
        assertCrop(raw, DirectCameraCrop.load(preferences, profile));
        assertGeometry(corrected,
                DirectCameraCrop.loadCorrected(preferences, profile, raw));
        CameraDewarpConfig restored = CameraDewarpConfig.loadForProfile(
                preferences, profile);
        assertTrue(restored.enabled);
        assertEquals(137, restored.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_CYLINDRICAL, restored.projection);

        DirectCameraCrop unchanged = crop(0.05f, 0.08f, 0.26f, 0.30f, -15);
        DirectCameraCrop.save(preferences, profile, unchanged);
        DirectCameraCrop.saveCorrected(preferences, profile, unchanged);
        CameraDewarpConfig.saveForProfile(preferences, profile,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT));
        preferences.putFloat(
                "camera_calibration_preset_v1_rear_left_corrected_w", Float.NaN);
        assertFalse(CameraCalibrationPreset.loadCamera(preferences, profile));
        assertCrop(unchanged, DirectCameraCrop.load(preferences, profile));
        assertGeometry(unchanged,
                DirectCameraCrop.loadCorrected(preferences, profile, unchanged));
        assertFalse(CameraDewarpConfig.loadForProfile(
                preferences, profile).enabled);

        String presetPrefix = "camera_calibration_preset_v1_rear_left_";
        preferences.putFloat(presetPrefix + "corrected_w", corrected.width);
        preferences.putFloat(presetPrefix + "raw_w", 0.0099f);
        Map<String, ?> beforeRawFloorRejection = new HashMap<>(preferences.getAll());
        assertFalse(CameraCalibrationPreset.loadCamera(preferences, profile));
        assertEquals(beforeRawFloorRejection, preferences.getAll());
        assertCrop(unchanged, DirectCameraCrop.load(preferences, profile));

        preferences.putFloat(presetPrefix + "raw_w", raw.width);
        preferences.putFloat(presetPrefix + "corrected_h", 0.0099f);
        Map<String, ?> beforeCorrectedFloorRejection =
                new HashMap<>(preferences.getAll());
        assertFalse(CameraCalibrationPreset.loadCamera(preferences, profile));
        assertEquals(beforeCorrectedFloorRejection, preferences.getAll());
        assertCrop(unchanged, DirectCameraCrop.load(preferences, profile));
    }

    @Test
    public void cameraMirrorCoversEveryPairAndKeepsTargetPresetAndLens() {
        for (CameraProfile source : CameraProfile.values()) {
            TestSharedPreferences preferences = new TestSharedPreferences();
            CameraProfile target = CameraProfile.of(
                    CameraCalibrationPreset.cameraMirrorTarget(source));
            DirectCameraCrop targetBackup = crop(
                    0.04f, 0.06f, 0.24f, 0.28f, -12);
            DirectCameraCrop.save(preferences, target, targetBackup);
            DirectCameraCrop.saveCorrected(preferences, target, targetBackup);
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                    CameraDewarpConfig.lensFor(target), false, 91));
            CameraCalibrationPreset.saveCamera(preferences, target);

            DirectCameraCrop raw = crop(0.12f, 0.20f, 0.31f, 0.42f, 37);
            DirectCameraCrop corrected = crop(0.27f, 0.11f, 0.25f, 0.33f, 37);
            DirectCameraCrop.save(preferences, source, raw);
            DirectCameraCrop.saveCorrected(preferences, source, corrected);
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                    CameraDewarpConfig.lensFor(source), true, 149,
                    CameraDewarpConfig.PROJECTION_CYLINDRICAL));

            CameraCalibrationPreset.mirrorCamera(preferences, source);

            DirectCameraCrop mirrored = DirectCameraCrop.load(preferences, target);
            assertEquals(1.0f - raw.left - raw.width, mirrored.left, EPSILON);
            assertEquals(raw.top, mirrored.top, EPSILON);
            assertEquals(raw.width, mirrored.width, EPSILON);
            assertEquals(raw.height, mirrored.height, EPSILON);
            assertEquals(-raw.rotationDegrees, mirrored.rotationDegrees);
            assertEquals(raw.rotationMode, mirrored.rotationMode);
            DirectCameraCrop mirroredCorrected = DirectCameraCrop.loadCorrected(
                    preferences, target, mirrored);
            assertEquals(1.0f - corrected.left - corrected.width,
                    mirroredCorrected.left, EPSILON);
            assertEquals(corrected.top, mirroredCorrected.top, EPSILON);
            CameraDewarpConfig targetDewarp = CameraDewarpConfig.loadForProfile(
                    preferences, target);
            assertEquals(CameraDewarpConfig.lensFor(target), targetDewarp.lens);
            assertTrue(targetDewarp.enabled);
            assertEquals(149, targetDewarp.fovDegrees);

            assertTrue(CameraCalibrationPreset.loadCamera(preferences, target));
            assertCrop(targetBackup, DirectCameraCrop.load(preferences, target));
        }
    }

    @Test
    public void reverseMirrorCopiesAllGeometryButKeepsPresetLensAndZOrder() {
        int[] sources = {ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX};
        for (int sourceIndex : sources) {
            TestSharedPreferences preferences = new TestSharedPreferences();
            int targetIndex = CameraCalibrationPreset.reverseMirrorTarget(sourceIndex);
            for (int index = 1; index <= 3; index++) {
                CameraDewarpConfig.save(preferences, CameraDewarpConfig.disabled(
                        CameraDewarpConfig.lensForReverseCamera(index)));
            }
            ReverseCameraLayout.Rect sourceDestination = ReverseCameraLayout.destination(
                    0.08f, 0.14f, 0.36f, 0.40f);
            ReverseCameraLayout.Rect sourceRaw = ReverseCameraLayout.sourceCrop(
                    0.11f, 0.17f, 0.43f, 0.51f);
            ReverseCameraLayout.Rect sourceCorrected = ReverseCameraLayout.sourceCrop(
                    0.23f, 0.09f, 0.32f, 0.44f);
            ReverseCameraLayout.Rect targetDestination = ReverseCameraLayout.destination(
                    0.55f, 0.22f, 0.30f, 0.34f);
            ReverseCameraLayout.Rect targetRaw = ReverseCameraLayout.sourceCrop(
                    0.61f, 0.05f, 0.26f, 0.30f);
            ReverseCameraLayout.Rect targetCorrected = ReverseCameraLayout.sourceCrop(
                    0.52f, 0.12f, 0.28f, 0.36f);
            ReverseCameraLayout layout = ReverseCameraLayout.defaults();
            layout = ReverseCameraLayout.withPane(layout, sourceIndex,
                    sourceDestination, sourceRaw, 42);
            layout = ReverseCameraLayout.withDisplayMode(layout, sourceIndex,
                    ReverseCameraLayout.DISPLAY_MODE_STRETCH);
            layout = ReverseCameraLayout.withPane(layout, targetIndex,
                    targetDestination, targetRaw, -16);
            layout = ReverseCameraLayout.withDisplayMode(layout, targetIndex,
                    ReverseCameraLayout.DISPLAY_MODE_FILL);
            layout = ReverseCameraLayout.bringToFront(layout, targetIndex);
            ReverseCameraController.saveLayout(preferences, layout);
            ReverseCameraController.saveSourceCrop(
                    preferences, sourceIndex, sourceCorrected, true);
            ReverseCameraController.saveSourceCrop(
                    preferences, targetIndex, targetCorrected, true);
            ReverseCameraController.saveVisibility(preferences, sourceIndex, false);
            ReverseCameraController.saveVisibility(preferences, targetIndex, true);
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                    CameraDewarpConfig.lensForReverseCamera(sourceIndex), true, 144,
                    CameraDewarpConfig.PROJECTION_CYLINDRICAL));
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                    CameraDewarpConfig.lensForReverseCamera(targetIndex), false, 93));
            CameraCalibrationPreset.saveReverse(preferences, targetIndex);
            int targetZ = ReverseCameraController.loadRawLayout(preferences)
                    .pane(targetIndex).zOrder;

            assertTrue(CameraCalibrationPreset.mirrorReverse(preferences, sourceIndex));

            ReverseCameraLayout.Pane rawTarget = ReverseCameraController
                    .loadRawLayout(preferences).pane(targetIndex);
            assertRectMirrored(sourceDestination, rawTarget.destination);
            assertRectMirrored(sourceRaw, rawTarget.sourceCrop);
            assertEquals(-42, rawTarget.rotationDegrees);
            assertEquals(ReverseCameraLayout.DISPLAY_MODE_STRETCH,
                    rawTarget.displayMode);
            assertEquals(targetZ, rawTarget.zOrder);
            ReverseCameraLayout.Pane correctedTarget = ReverseCameraController
                    .loadLayout(preferences).pane(targetIndex);
            assertRectMirrored(sourceCorrected, correctedTarget.sourceCrop);
            CameraDewarpConfig targetDewarp = CameraDewarpConfig.loadForReverse(
                    preferences, targetIndex);
            assertEquals(CameraDewarpConfig.lensForReverseCamera(targetIndex),
                    targetDewarp.lens);
            assertTrue(targetDewarp.enabled);
            assertEquals(144, targetDewarp.fovDegrees);
            assertEquals(CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                    targetDewarp.projection);
            assertTrue(ReverseCameraController.loadVisibility(preferences, targetIndex));

            assertTrue(CameraCalibrationPreset.loadReverse(preferences, targetIndex));
            ReverseCameraLayout.Pane restored = ReverseCameraController
                    .loadRawLayout(preferences).pane(targetIndex);
            assertRect(targetDestination, restored.destination);
            assertRect(targetRaw, restored.sourceCrop);
            assertEquals(-16, restored.rotationDegrees);
            assertEquals(ReverseCameraLayout.DISPLAY_MODE_FILL, restored.displayMode);
            assertEquals(targetZ, restored.zOrder);
            assertRect(targetCorrected, ReverseCameraController
                    .loadCorrectedSourceCrop(preferences, targetIndex, targetRaw));
            assertTrue(ReverseCameraController.loadVisibility(preferences, targetIndex));
        }
        assertFalse(CameraCalibrationPreset.mirrorReverse(
                new TestSharedPreferences(), ReverseCameraLayout.REAR_CAMERA_INDEX));
    }

    @Test
    public void presetsAndMirrorsOnlyChangeTheirTargetActivationScope() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraProfile frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT);
        CameraProfile rearRight = CameraProfile.of(CameraProfile.REAR_RIGHT);
        CameraProfile frontRight = CameraProfile.of(CameraProfile.FRONT_RIGHT);
        int reverseLeft = ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX;
        int reverseRight = ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;

        CameraDewarpConfig.saveForProfile(preferences, rearLeft,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 130));
        CameraDewarpConfig.saveForProfile(preferences, frontLeft,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, false, 130));
        CameraDewarpConfig.saveForReverse(preferences, reverseLeft,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, false, 130));
        CameraCalibrationPreset.saveCamera(preferences, rearLeft);
        CameraDewarpConfig.saveForProfile(preferences, rearLeft,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, false, 131));

        assertTrue(CameraCalibrationPreset.loadCamera(preferences, rearLeft));
        assertTrue(CameraDewarpConfig.loadForProfile(preferences, rearLeft).enabled);
        assertFalse(CameraDewarpConfig.loadForProfile(preferences, frontLeft).enabled);
        assertFalse(CameraDewarpConfig.loadForReverse(preferences, reverseLeft).enabled);

        CameraDewarpConfig.saveForProfile(preferences, frontRight,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, false, 90));
        CameraDewarpConfig.saveForReverse(preferences, reverseRight,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, false, 90));
        CameraCalibrationPreset.mirrorCamera(preferences, rearLeft);
        assertTrue(CameraDewarpConfig.loadForProfile(preferences, rearRight).enabled);
        assertFalse(CameraDewarpConfig.loadForProfile(preferences, frontRight).enabled);
        assertFalse(CameraDewarpConfig.loadForReverse(preferences, reverseRight).enabled);

        CameraDewarpConfig.saveForReverse(preferences, reverseLeft,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 145));
        CameraCalibrationPreset.saveReverse(preferences, reverseLeft);
        CameraDewarpConfig.saveForReverse(preferences, reverseLeft,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, false, 146));
        CameraDewarpConfig.saveForProfile(preferences, rearLeft,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, false, 146));
        assertTrue(CameraCalibrationPreset.loadReverse(preferences, reverseLeft));
        assertTrue(CameraDewarpConfig.loadForReverse(preferences, reverseLeft).enabled);
        assertFalse(CameraDewarpConfig.loadForProfile(preferences, rearLeft).enabled);
        assertFalse(CameraDewarpConfig.loadForProfile(preferences, frontLeft).enabled);

        CameraDewarpConfig.saveForProfile(preferences, rearRight,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, false, 91));
        CameraDewarpConfig.saveForProfile(preferences, frontRight,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, true, 91));
        assertTrue(CameraCalibrationPreset.mirrorReverse(preferences, reverseLeft));
        assertTrue(CameraDewarpConfig.loadForReverse(preferences, reverseRight).enabled);
        assertFalse(CameraDewarpConfig.loadForProfile(preferences, rearRight).enabled);
        assertTrue(CameraDewarpConfig.loadForProfile(preferences, frontRight).enabled);
    }

    @Test
    public void legacyReversePresetWithoutVisibilityFieldLoadsVisibleByDefault() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        int cameraIndex = ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX;
        CameraDewarpConfig.saveForReverse(preferences, cameraIndex,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT));
        ReverseCameraController.saveVisibility(preferences, cameraIndex, false);
        CameraCalibrationPreset.saveReverse(preferences, cameraIndex);
        String prefix = "reverse_calibration_preset_v1_" + cameraIndex + "_";
        preferences.remove(prefix + "visible");

        assertTrue(CameraCalibrationPreset.hasReverse(preferences, cameraIndex));
        assertTrue(CameraCalibrationPreset.loadReverse(preferences, cameraIndex));
        assertTrue(ReverseCameraController.loadVisibility(preferences, cameraIndex));
        assertEquals(1, preferences.getInt(prefix + "version", -1));
    }

    @Test
    public void malformedReversePresetVisibilityFallsBackOnWithoutInvalidatingPreset() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        int cameraIndex = ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
        CameraDewarpConfig.saveForReverse(preferences, cameraIndex,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_RIGHT));
        CameraCalibrationPreset.saveReverse(preferences, cameraIndex);
        String key = "reverse_calibration_preset_v1_" + cameraIndex + "_visible";
        preferences.putString(key, "invalid");

        assertTrue(CameraCalibrationPreset.loadReverse(preferences, cameraIndex));
        assertTrue(ReverseCameraController.loadVisibility(preferences, cameraIndex));
    }

    @Test
    public void invalidReversePresetNeverChangesActiveSettings() {
        assertInvalidReversePreset("version", 2);
        assertInvalidReversePreset("destination_x", Float.NaN);
        assertInvalidReversePreset("raw_w", 0.0099f);
        assertInvalidReversePreset("corrected_h", 0.0099f);
        assertInvalidReversePreset("rotation", 181);
        assertInvalidReversePreset("mode", 99);
        assertInvalidReversePreset("correction", null);
        assertInvalidReversePreset("fov", 0);
        assertInvalidReversePreset("projection", 99);
    }

    private static void assertInvalidReversePreset(String suffix, Object invalidValue) {
        int cameraIndex = ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX;
        TestSharedPreferences preferences = new TestSharedPreferences();
        ReverseCameraLayout.Rect savedDestination = ReverseCameraLayout.destination(
                0.08f, 0.14f, 0.36f, 0.40f);
        ReverseCameraLayout.Rect savedRaw = ReverseCameraLayout.sourceCrop(
                0.11f, 0.17f, 0.43f, 0.51f);
        ReverseCameraLayout layout = ReverseCameraLayout.withPane(
                ReverseCameraLayout.defaults(), cameraIndex,
                savedDestination, savedRaw, 42);
        layout = ReverseCameraLayout.withDisplayMode(
                layout, cameraIndex, ReverseCameraLayout.DISPLAY_MODE_STRETCH);
        ReverseCameraController.saveLayout(preferences, layout);
        ReverseCameraController.saveSourceCrop(preferences, cameraIndex,
                ReverseCameraLayout.sourceCrop(0.23f, 0.09f, 0.32f, 0.44f), true);
        CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                CameraDewarpConfig.lensForReverseCamera(cameraIndex), true, 144,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL));
        CameraCalibrationPreset.saveReverse(preferences, cameraIndex);

        ReverseCameraLayout active = ReverseCameraLayout.withPane(
                ReverseCameraController.loadRawLayout(preferences), cameraIndex,
                ReverseCameraLayout.destination(0.54f, 0.21f, 0.28f, 0.31f),
                ReverseCameraLayout.sourceCrop(0.57f, 0.05f, 0.24f, 0.29f), -16);
        active = ReverseCameraLayout.withDisplayMode(
                active, cameraIndex, ReverseCameraLayout.DISPLAY_MODE_FILL);
        ReverseCameraController.saveLayout(preferences, active);
        ReverseCameraController.saveSourceCrop(preferences, cameraIndex,
                ReverseCameraLayout.sourceCrop(0.51f, 0.12f, 0.27f, 0.35f), true);
        CameraDewarpConfig.save(preferences, CameraDewarpConfig.disabled(
                CameraDewarpConfig.lensForReverseCamera(cameraIndex)));

        String key = "reverse_calibration_preset_v1_" + cameraIndex + "_" + suffix;
        if (invalidValue == null) {
            preferences.remove(key);
        } else if (invalidValue instanceof Integer) {
            preferences.putInt(key, (Integer) invalidValue);
        } else {
            preferences.putFloat(key, (Float) invalidValue);
        }
        Map<String, ?> before = new HashMap<>(preferences.getAll());

        assertFalse(CameraCalibrationPreset.loadReverse(preferences, cameraIndex));
        assertEquals(before, preferences.getAll());
    }

    private static DirectCameraCrop crop(
            float x, float y, float width, float height, int rotation) {
        return DirectCameraCrop.of(x, y, width, height,
                DirectCameraCrop.ASPECT_FREE, rotation, CameraRotation.MODE_FILL);
    }

    private static void assertCrop(DirectCameraCrop expected, DirectCameraCrop actual) {
        assertGeometry(expected, actual);
        assertEquals(expected.aspectMode, actual.aspectMode);
        assertEquals(expected.rotationDegrees, actual.rotationDegrees);
        assertEquals(expected.rotationMode, actual.rotationMode);
    }

    private static void assertGeometry(
            DirectCameraCrop expected, DirectCameraCrop actual) {
        assertEquals(expected.left, actual.left, EPSILON);
        assertEquals(expected.top, actual.top, EPSILON);
        assertEquals(expected.width, actual.width, EPSILON);
        assertEquals(expected.height, actual.height, EPSILON);
    }

    private static void assertRectMirrored(
            ReverseCameraLayout.Rect source, ReverseCameraLayout.Rect actual) {
        assertEquals(1.0f - source.left - source.width, actual.left, EPSILON);
        assertEquals(source.top, actual.top, EPSILON);
        assertEquals(source.width, actual.width, EPSILON);
        assertEquals(source.height, actual.height, EPSILON);
    }

    private static void assertRect(
            ReverseCameraLayout.Rect expected, ReverseCameraLayout.Rect actual) {
        assertEquals(expected.left, actual.left, EPSILON);
        assertEquals(expected.top, actual.top, EPSILON);
        assertEquals(expected.width, actual.width, EPSILON);
        assertEquals(expected.height, actual.height, EPSILON);
    }
}
