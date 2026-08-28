package com.byd.extend;

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
    public void capturedDefaultsFillMissingBlindAndReverseContexts() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile[] profiles = CameraProfile.values();
        float[][] raw = {
                {0.07071858f, 0.1937456f, 0.6358514f, 0.61250883f},
                {0.29343003f, 0.1937456f, 0.6358514f, 0.61250883f},
                {0.4807051f, 0.32135904f, 0.46608025f, 0.5074271f},
                {0.053414617f, 0.32259566f, 0.46438393f, 0.5072246f}
        };
        float[][] corrected = {
                {0.10472285f, 0.17163458f, 0.53600174f, 0.54995716f},
                {0.35927543f, 0.17163458f, 0.53600174f, 0.54995716f},
                {0.29754817f, 0.29489756f, 0.41666844f, 0.4414549f},
                {0.2795728f, 0.29326266f, 0.41497213f, 0.44125235f}
        };
        int[] rawRotation = {-30, 30, 45, -45};
        int[] fov = {165, 165, 130, 130};
        int[] projection = {1, 1, 0, 0};
        for (int i = 0; i < profiles.length; i++) {
            CameraProfile profile = profiles[i];
            DirectCameraCrop actualRaw = DirectCameraCrop.load(preferences, profile);
            DirectCameraCrop actualCorrected = DirectCameraCrop.loadCorrected(
                    preferences, profile, actualRaw);
            assertGeometry(raw[i], actualRaw);
            assertGeometry(corrected[i], actualCorrected);
            assertEquals(DirectCameraCrop.ASPECT_FREE, actualRaw.aspectMode);
            assertEquals(rawRotation[i], actualRaw.rotationDegrees);
            assertEquals(CameraRotation.MODE_ALIGNED, actualRaw.rotationMode);
            assertEquals(profile.rear(), actualRaw.mirrorHorizontally);
            assertConfig(CameraDewarpConfig.loadForProfile(preferences, profile),
                    true, fov[i], projection[i]);
            float[] positionX = {0.0f, 1.0f, 0.0f, 1.0f};
            float[] positionY = {0.08281444f, 0.072115384f, 1.0f, 1.0f};
            float[] frameAspect = {1.6173527f, 1.6154981f, 1.393998f, 1.3889601f};
            assertEquals(positionX[i],
                    BlindSpotOverlayController.readPosition(preferences, profile, false),
                    EPSILON);
            assertEquals(positionY[i],
                    BlindSpotOverlayController.readPosition(preferences, profile, true),
                    EPSILON);
            assertEquals(30, BlindSpotOverlayController.readScale(preferences, profile));
            assertEquals(CameraDisplayTarget.TABLET,
                    BlindSpotOverlayController.readTarget(preferences, profile));
            assertEquals(frameAspect[i],
                    BlindSpotOverlayController.readFrameAspect(
                            preferences, profile, actualRaw.outputAspect()), EPSILON);
        }

        ReverseCameraLayout rawLayout = ReverseCameraController.loadRawLayout(preferences);
        assertRect(ReverseCameraLayout.destination(
                        0.42398763f, 0.0f, 0.5745265f, 1.0f), rawLayout.background);
        int[] indexes = {1, 2, 3};
        int[] rotations = {0, 42, -42};
        float[][] destinations = {
                {0.43216026f, 0.0015433729f, 0.564868f, 0.7758869f},
                {0.43366212f, 0.7960598f, 0.25351316f, 0.20394021f},
                {0.74648684f, 0.7960598f, 0.25351316f, 0.20394021f}
        };
        float[][] rawCrops = {
                {0.0f, 0.0f, 1.0f, 0.8169013f},
                {0.0f, 0.25f, 0.384127f, 0.55f},
                {0.615873f, 0.25f, 0.384127f, 0.55f}
        };
        for (int i = 0; i < indexes.length; i++) {
            ReverseCameraLayout.Pane pane = rawLayout.pane(indexes[i]);
            assertRect(ReverseCameraLayout.destination(
                            destinations[i][0], destinations[i][1],
                            destinations[i][2], destinations[i][3]), pane.destination);
            assertRect(ReverseCameraLayout.sourceCrop(
                            rawCrops[i][0], rawCrops[i][1],
                            rawCrops[i][2], rawCrops[i][3]), pane.sourceCrop);
            assertEquals(rotations[i], pane.rotationDegrees);
            assertEquals(ReverseCameraLayout.DISPLAY_MODE_FILL, pane.displayMode);
            assertTrue(pane.mirrorHorizontally);
            assertRect(ReverseCameraController.defaultCorrectedSourceCrop(indexes[i]),
                    ReverseCameraController.loadCorrectedSourceCrop(
                            preferences, indexes[i], pane.sourceCrop));
            assertConfig(CameraDewarpConfig.loadForReverse(preferences, indexes[i]),
                    i != 0, i == 0 ? 170 : 163, 1);
        }
    }

    @Test
    public void selectedResetsUseCapturedDefaultsWithoutTouchingSiblingsOrSlots() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile selected = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraProfile sibling = CameraProfile.of(CameraProfile.REAR_RIGHT);
        DirectCameraCrop siblingRaw = DirectCameraCrop.of(
                0.20f, 0.10f, 0.30f, 0.40f, DirectCameraCrop.ASPECT_FREE, 17,
                CameraRotation.MODE_FIT);
        DirectCameraCrop.save(preferences, sibling, siblingRaw);
        DirectCameraCrop.saveCorrected(preferences, sibling, siblingRaw);
        CameraDewarpConfig.saveForProfile(preferences, sibling,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, false, 91));
        preferences.putFloat("camera_min_speed_kph", 37.0f);
        DirectCameraCrop.save(preferences, selected, siblingRaw);
        DirectCameraCrop.saveCorrected(preferences, selected, siblingRaw);
        CameraDewarpConfig.saveForProfile(preferences, selected,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT));
        CameraCalibrationPreset.saveCamera(preferences, selected);
        Map<String, ?> slotBefore = new HashMap<>(preferences.getAll());
        CameraCalibrationPreset.resetCameraToDefault(preferences, selected);

        assertGeometry(new float[]{0.07071858f, 0.1937456f, 0.6358514f, 0.61250883f},
                DirectCameraCrop.load(preferences, selected));
        assertConfig(CameraDewarpConfig.loadForProfile(preferences, selected),
                true, 165, CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        assertGeometry(new float[]{0.20f, 0.10f, 0.30f, 0.40f},
                DirectCameraCrop.load(preferences, sibling));
        assertFalse(CameraDewarpConfig.loadForProfile(preferences, sibling).enabled);
        assertEquals(37.0f, preferences.getFloat("camera_min_speed_kph", -1.0f), 0.0f);
        for (Map.Entry<String, ?> entry : slotBefore.entrySet()) {
            if (entry.getKey().startsWith("camera_calibration_preset_v1_")) {
                assertEquals(entry.getValue(), preferences.getAll().get(entry.getKey()));
            }
        }

        ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(preferences);
        ReverseCameraLayout.Rect siblingDestination = ReverseCameraLayout.destination(
                0.10f, 0.10f, 0.20f, 0.20f);
        layout = ReverseCameraLayout.withPane(layout,
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX,
                siblingDestination, layout.rearRight.sourceCrop);
        ReverseCameraController.saveLayout(preferences, layout);
        ReverseCameraController.saveVisibility(
                preferences, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, false);
        CameraCalibrationPreset.saveReverse(
                preferences, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        Map<String, ?> reverseSlotBefore = new HashMap<>(preferences.getAll());
        CameraCalibrationPreset.resetReverseToDefault(
                preferences, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        assertRect(ReverseCameraLayout.defaults().rearLeft.destination,
                ReverseCameraController.loadRawLayout(preferences).rearLeft.destination);
        assertTrue(ReverseCameraController.loadVisibility(
                preferences, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
        assertRect(siblingDestination,
                ReverseCameraController.loadRawLayout(preferences).rearRight.destination);
        for (Map.Entry<String, ?> entry : reverseSlotBefore.entrySet()) {
            if (entry.getKey().startsWith("reverse_calibration_preset_v1_")) {
                assertEquals(entry.getValue(), preferences.getAll().get(entry.getKey()));
            }
        }
    }

    @Test
    public void fullReverseResetUsesCapturedCompositionAndPreservesGlobalSettingsAndSlots() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putBoolean(ReverseCameraController.PREF_ENABLED, true);
        preferences.putBoolean("reverse_camera_parking_guidelines", false);
        CameraCalibrationPreset.saveReverse(
                preferences, ReverseCameraLayout.REAR_CAMERA_INDEX);
        preferences.putFloat("reverse_camera_background_left", 0.1f);
        CameraDewarpConfig.saveForReverse(preferences,
                ReverseCameraLayout.REAR_CAMERA_INDEX,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_REAR, true, 120));
        ReverseCameraController.resetLayout(preferences);

        ReverseCameraLayout actual = ReverseCameraController.loadRawLayout(preferences);
        ReverseCameraLayout expected = ReverseCameraLayout.defaults();
        assertRect(expected.background, actual.background);
        for (ReverseCameraLayout.Pane pane : expected.panes()) {
            ReverseCameraLayout.Pane restored = actual.pane(pane.cameraIndex);
            assertRect(pane.destination, restored.destination);
            assertRect(pane.sourceCrop, restored.sourceCrop);
            assertEquals(pane.rotationDegrees, restored.rotationDegrees);
            assertEquals(pane.displayMode, restored.displayMode);
            assertEquals(pane.zOrder, restored.zOrder);
            assertTrue(restored.mirrorHorizontally);
            assertTrue(ReverseCameraController.loadVisibility(
                    preferences, pane.cameraIndex));
        }
        assertRect(ReverseCameraController.defaultCorrectedSourceCrop(
                        ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX),
                ReverseCameraController.loadCorrectedSourceCrop(preferences,
                        ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, expected.rearLeft.sourceCrop));
        assertConfig(CameraDewarpConfig.loadForReverse(preferences,
                        ReverseCameraLayout.REAR_CAMERA_INDEX), false, 170, 1);
        assertTrue(preferences.getBoolean(ReverseCameraController.PREF_ENABLED, false));
        assertFalse(preferences.getBoolean("reverse_camera_parking_guidelines", true));
        assertTrue(CameraCalibrationPreset.hasReverse(
                preferences, ReverseCameraLayout.REAR_CAMERA_INDEX));
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

            DirectCameraCrop raw = crop(0.12f, 0.20f, 0.31f, 0.42f, 37)
                    .withMirrorHorizontally(true);
            DirectCameraCrop corrected = crop(0.27f, 0.11f, 0.25f, 0.33f, 37)
                    .withMirrorHorizontally(true);
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
            assertTrue(mirrored.mirrorHorizontally);
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
                CameraDewarpConfig.saveForReverse(preferences, index,
                        CameraDewarpConfig.disabled(
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
            layout = ReverseCameraLayout.withMirrorHorizontally(
                    layout, sourceIndex, false);
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
            CameraDewarpConfig.saveForReverse(preferences, sourceIndex,
                    CameraDewarpConfig.of(
                            CameraDewarpConfig.lensForReverseCamera(sourceIndex), true, 144,
                            CameraDewarpConfig.PROJECTION_CYLINDRICAL));
            CameraDewarpConfig.saveForReverse(preferences, targetIndex,
                    CameraDewarpConfig.of(
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
            assertFalse(rawTarget.mirrorHorizontally);
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
            assertTrue(restored.mirrorHorizontally);
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

    @Test
    public void parkingScopesHaveIndependentDefaultsAndTransferOnlyCalibration() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        ParkingCameraProfile rear = ParkingCameraProfile.of(ParkingCameraProfile.REAR);
        ParkingCameraProfile front = ParkingCameraProfile.of(ParkingCameraProfile.FRONT);
        ParkingCameraProfile frontLeft = ParkingCameraProfile.of(ParkingCameraProfile.FL);
        ParkingCameraProfile frontRight = ParkingCameraProfile.of(ParkingCameraProfile.FR);
        ParkingCameraProfile left = ParkingCameraProfile.of(ParkingCameraProfile.LEFT);
        ParkingCameraProfile right = ParkingCameraProfile.of(ParkingCameraProfile.RIGHT);
        DirectCameraCrop rearDefault = DirectCameraCrop.load(preferences, rear);
        DirectCameraCrop frontDefault = DirectCameraCrop.load(preferences, front);
        DirectCameraCrop frontLeftDefault = DirectCameraCrop.load(preferences, frontLeft);
        DirectCameraCrop frontRightDefault = DirectCameraCrop.load(preferences, frontRight);
        DirectCameraCrop leftDefault = DirectCameraCrop.load(preferences, left);
        DirectCameraCrop rightDefault = DirectCameraCrop.load(preferences, right);
        assertTrue(rearDefault.mirrorHorizontally);
        assertFalse(frontDefault.mirrorHorizontally);
        assertEquals(1.0f, rearDefault.width, EPSILON);
        assertEquals(1.0f, frontDefault.width, EPSILON);
        assertEquals(0.35f, frontLeftDefault.left, EPSILON);
        assertEquals(0.0f, frontRightDefault.left, EPSILON);
        assertEquals(0.0f, leftDefault.left, EPSILON);
        assertEquals(0.0f, leftDefault.top, EPSILON);
        assertEquals(1.0f, leftDefault.width, EPSILON);
        assertEquals(1.0f, leftDefault.height, EPSILON);
        assertEquals(0.0f, rightDefault.left, EPSILON);
        assertEquals(0.0f, rightDefault.top, EPSILON);
        assertEquals(1.0f, rightDefault.width, EPSILON);
        assertEquals(1.0f, rightDefault.height, EPSILON);
        assertEquals(DirectCameraCrop.ASPECT_FREE, leftDefault.aspectMode);
        assertEquals(DirectCameraCrop.ASPECT_FREE, rightDefault.aspectMode);
        assertFalse(leftDefault.mirrorHorizontally);
        assertFalse(rightDefault.mirrorHorizontally);

        DirectCameraCrop source = crop(0.14f, 0.18f, 0.36f, 0.42f, 31)
                .withMirrorHorizontally(false);
        DirectCameraCrop corrected = crop(0.24f, 0.16f, 0.25f, 0.31f, 31);
        DirectCameraCrop.save(preferences, rear, source);
        DirectCameraCrop.saveCorrected(preferences, rear, corrected);
        CameraDewarpConfig.saveForParking(preferences, rear,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_REAR, true, 143,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL));
        ParkingCameraSettings settings = new ParkingCameraSettings(preferences);
        settings.setRule(rear, new ParkingCameraSettings.Rule(true, 77, true));
        settings.setRule(front, new ParkingCameraSettings.Rule(true, 33, false));
        preferences.putInt("parking_camera_rear_scale", 42);
        preferences.putInt("parking_camera_front_scale", 28);

        assertTrue(CameraCalibrationPreset.mirrorParking(preferences, rear));
        ParkingCameraProfile target = ParkingCameraProfile.of(ParkingCameraProfile.FRONT);
        DirectCameraCrop mirrored = DirectCameraCrop.load(preferences, target);
        assertEquals(1.0f - source.left - source.width, mirrored.left, EPSILON);
        assertEquals(-source.rotationDegrees, mirrored.rotationDegrees);
        assertFalse(mirrored.mirrorHorizontally);
        assertTrue(CameraDewarpConfig.loadForParking(preferences, target).enabled);
        assertEquals(143, CameraDewarpConfig.loadForParking(preferences, target).fovDegrees);
        assertTrue(ParkingCameraSettings.readRule(preferences, rear).enabled);
        assertEquals(77, ParkingCameraSettings.readRule(preferences, rear).distanceCm);
        assertTrue(ParkingCameraSettings.readRule(preferences, front).enabled);
        assertEquals(33, ParkingCameraSettings.readRule(preferences, front).distanceCm);
        assertEquals(42, preferences.getInt("parking_camera_rear_scale", -1));
        assertEquals(28, preferences.getInt("parking_camera_front_scale", -1));
    }

    @Test
    public void parkingLeftRightTransferCopiesCalibrationOnly() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        ParkingCameraProfile left = ParkingCameraProfile.of(ParkingCameraProfile.LEFT);
        ParkingCameraProfile right = ParkingCameraProfile.of(ParkingCameraProfile.RIGHT);
        DirectCameraCrop source = crop(0.17f, 0.14f, 0.33f, 0.41f, 27)
                .withMirrorHorizontally(false);
        DirectCameraCrop corrected = crop(0.21f, 0.18f, 0.25f, 0.29f, 27);
        DirectCameraCrop.save(preferences, left, source);
        DirectCameraCrop.saveCorrected(preferences, left, corrected);
        CameraDewarpConfig.saveForParking(preferences, left,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 141,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL));
        ParkingCameraSettings settings = new ParkingCameraSettings(preferences);
        settings.setRule(left, new ParkingCameraSettings.Rule(true, 79, false));
        settings.setRule(right, new ParkingCameraSettings.Rule(false, 23, false));
        preferences.putInt("parking_camera_right_scale", 39);

        assertEquals(ParkingCameraProfile.RIGHT,
                CameraCalibrationPreset.parkingMirrorTarget(left));
        assertEquals(ParkingCameraProfile.LEFT,
                CameraCalibrationPreset.parkingMirrorTarget(right));
        assertTrue(CameraCalibrationPreset.mirrorParking(preferences, left));

        DirectCameraCrop transferred = DirectCameraCrop.load(preferences, right);
        assertEquals(1.0f - source.left - source.width, transferred.left, EPSILON);
        assertEquals(-source.rotationDegrees, transferred.rotationDegrees);
        assertEquals(141, CameraDewarpConfig.loadForParking(preferences, right).fovDegrees);
        assertTrue(CameraDewarpConfig.loadForParking(preferences, right).enabled);
        assertFalse(ParkingCameraSettings.readRule(preferences, right).enabled);
        assertEquals(23, ParkingCameraSettings.readRule(preferences, right).distanceCm);
        assertEquals(39, preferences.getInt("parking_camera_right_scale", -1));
    }

    @Test
    public void parkingTabAndCalibrationMigrationKeepLogicalOrigins() {
        assertEquals(1, CameraProbeActivity.migrateStoredTab(4));
        assertEquals(1, CameraProbeActivity.migrateStoredTab(1));
        assertEquals("Перенести →", CameraProbeActivity.parkingCalibrationTransferLabel(
                ParkingCameraProfile.of(ParkingCameraProfile.FL)));
        assertEquals("← Перенести", CameraProbeActivity.parkingCalibrationTransferLabel(
                ParkingCameraProfile.of(ParkingCameraProfile.FR)));
        assertEquals("← Перенести", CameraProbeActivity.parkingCalibrationTransferLabel(
                ParkingCameraProfile.of(ParkingCameraProfile.REAR)));
        assertEquals("Перенести →", CameraProbeActivity.parkingCalibrationTransferLabel(
                ParkingCameraProfile.of(ParkingCameraProfile.LEFT)));
        assertEquals("← Перенести", CameraProbeActivity.parkingCalibrationTransferLabel(
                ParkingCameraProfile.of(ParkingCameraProfile.RIGHT)));
    }

    @Test
    public void reverseFrontPresetRestoresOnlyIndependentSourceCalibration() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        int left = ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX;
        int right = ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
        ReverseCameraLayout.Rect raw = ReverseCameraLayout.sourceCrop(
                0.41f, 0.22f, 0.37f, 0.46f);
        ReverseCameraLayout.Rect corrected = ReverseCameraLayout.sourceCrop(
                0.28f, 0.18f, 0.42f, 0.51f);
        ReverseCameraController.saveFrontSourceCrop(preferences, left, raw, false);
        ReverseCameraController.saveFrontSourceCrop(preferences, left, corrected, true);
        ReverseCameraController.saveFrontPaneTransform(preferences, left, 37,
                ReverseCameraLayout.DISPLAY_MODE_STRETCH, true);
        CameraDewarpConfig.saveForReverseFront(preferences, left,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 147,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL));
        ReverseCameraController.saveFrontIntegrated(preferences, left, true);
        ReverseCameraController.saveVisibility(preferences, left, false);
        ReverseCameraLayout.Rect sharedDestination = ReverseCameraController
                .loadRawLayout(preferences).pane(left).destination;

        CameraCalibrationPreset.saveReverseFront(preferences, left);
        ReverseCameraController.saveFrontSourceCrop(preferences, left,
                ReverseCameraLayout.sourceCrop(0.02f, 0.03f, 0.2f, 0.2f), false);
        ReverseCameraController.saveFrontPaneTransform(preferences, left, -5,
                ReverseCameraLayout.DISPLAY_MODE_FILL, false);
        CameraDewarpConfig.saveForReverseFront(preferences, left,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT));

        assertTrue(CameraCalibrationPreset.loadReverseFront(preferences, left));
        ReverseCameraLayout.Pane restored = ReverseCameraController
                .loadFrontRawLayout(preferences).pane(left);
        assertEquals(raw.left, restored.sourceCrop.left, EPSILON);
        assertEquals(raw.top, restored.sourceCrop.top, EPSILON);
        assertEquals(37, restored.rotationDegrees);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_STRETCH, restored.displayMode);
        assertTrue(restored.mirrorHorizontally);
        assertConfig(CameraDewarpConfig.loadForReverseFront(preferences, left),
                true, 147, CameraDewarpConfig.PROJECTION_CYLINDRICAL);
        assertTrue(ReverseCameraController.loadFrontIntegrated(preferences, left));
        assertFalse(ReverseCameraController.loadVisibility(preferences, left));
        assertEquals(sharedDestination.left, ReverseCameraController
                .loadRawLayout(preferences).pane(left).destination.left, EPSILON);

        ReverseCameraLayout.Rect targetDestination = ReverseCameraController
                .loadRawLayout(preferences).pane(right).destination;
        assertTrue(CameraCalibrationPreset.mirrorReverseFront(preferences, left));
        ReverseCameraLayout.Pane mirrored = ReverseCameraController
                .loadFrontRawLayout(preferences).pane(right);
        assertEquals(1.0f - raw.left - raw.width,
                mirrored.sourceCrop.left, EPSILON);
        assertEquals(-37, mirrored.rotationDegrees);
        assertFalse(ReverseCameraController.loadFrontIntegrated(preferences, right));
        assertEquals(targetDestination.left, ReverseCameraController
                .loadRawLayout(preferences).pane(right).destination.left, EPSILON);
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

    private static void assertGeometry(float[] expected, DirectCameraCrop actual) {
        assertEquals(expected[0], actual.left, EPSILON);
        assertEquals(expected[1], actual.top, EPSILON);
        assertEquals(expected[2], actual.width, EPSILON);
        assertEquals(expected[3], actual.height, EPSILON);
    }

    private static void assertConfig(
            CameraDewarpConfig actual, boolean enabled, int fov, int projection) {
        assertEquals(enabled, actual.enabled);
        assertEquals(fov, actual.fovDegrees);
        assertEquals(projection, actual.projection);
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
