package com.byd.turnsignalguard.capture;

import android.content.SharedPreferences;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CameraDewarpConfigTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void clampsFovAndRoutesPhysicalLenses() {
        CameraDewarpConfig value = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 200);
        assertEquals(CameraDewarpConfig.MAX_FOV_DEGREES, value.fovDegrees);
        assertTrue(value.usesGpu());
        assertFalse(CameraDewarpConfig.disabled(
                CameraDewarpConfig.LENS_LEFT).usesGpu());
        assertEquals(CameraDewarpConfig.PROJECTION_RECTILINEAR, value.projection);
        assertThrows(IllegalArgumentException.class,
                () -> CameraDewarpConfig.of(0, true, 100));
        assertThrows(IllegalArgumentException.class,
                () -> CameraDewarpConfig.of(
                        CameraDewarpConfig.LENS_LEFT, true, 100, 2));

        assertEquals(CameraDewarpConfig.LENS_LEFT,
                CameraDewarpConfig.lensFor(CameraProfile.of(CameraProfile.REAR_LEFT)));
        assertEquals(CameraDewarpConfig.LENS_LEFT,
                CameraDewarpConfig.lensFor(CameraProfile.of(CameraProfile.FRONT_LEFT)));
        assertEquals(CameraDewarpConfig.LENS_RIGHT,
                CameraDewarpConfig.lensFor(CameraProfile.of(CameraProfile.REAR_RIGHT)));
        assertEquals(CameraDewarpConfig.LENS_REAR,
                CameraDewarpConfig.lensForReverseCamera(
                        ReverseCameraLayout.REAR_CAMERA_INDEX));
        assertEquals(CameraDewarpConfig.LENS_FRONT,
                CameraDewarpConfig.lensForDirectCamera(4));
    }

    @Test
    public void oldOrInvalidPreferencesCannotEnableNewCorrection() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit()
                .putBoolean("camera_dewarp_left_enabled", true)
                .putInt("camera_dewarp_left_strength", 100)
                .apply();
        CameraDewarpConfig migrated = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.LENS_LEFT);
        assertFalse(migrated.enabled);
        assertEquals(CameraDewarpConfig.DEFAULT_FOV_DEGREES, migrated.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_RECTILINEAR, migrated.projection);

        preferences.edit()
                .putBoolean("camera_dewarp_v2_left_enabled", true)
                .putInt("camera_dewarp_v2_left_fov", 112)
                .apply();
        CameraDewarpConfig restored = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.LENS_LEFT);
        assertTrue(restored.enabled);
        assertEquals(112, restored.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_RECTILINEAR, restored.projection);

        CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 165,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL));
        restored = CameraDewarpConfig.load(preferences, CameraDewarpConfig.LENS_LEFT);
        assertTrue(restored.enabled);
        assertEquals(165, restored.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_CYLINDRICAL, restored.projection);

        preferences.edit().putInt("camera_dewarp_v2_left_projection", 7).apply();
        CameraDewarpConfig invalid = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.LENS_LEFT);
        assertFalse(invalid.enabled);
        assertEquals(CameraDewarpConfig.DEFAULT_FOV_DEGREES, invalid.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_RECTILINEAR, invalid.projection);
    }

    @Test
    public void turnCropSeedsOnceAndThenRemainsIndependent() {
        SharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        DirectCameraCrop raw = DirectCameraCrop.of(
                0.10f, 0.12f, 0.55f, 0.60f, DirectCameraCrop.ASPECT_FREE,
                7, CameraRotation.MODE_FILL);
        DirectCameraCrop.save(preferences, profile, false, raw);

        DirectCameraCrop seeded = DirectCameraCrop.load(preferences, profile, true);
        assertCropEquals(raw, seeded);
        DirectCameraCrop corrected = DirectCameraCrop.of(
                0.20f, 0.18f, 0.48f, 0.52f, DirectCameraCrop.ASPECT_FREE,
                -4, CameraRotation.MODE_FIT);
        DirectCameraCrop.save(preferences, profile, true, corrected);

        assertCropEquals(raw, DirectCameraCrop.load(preferences, profile, false));
        assertCropEquals(corrected, DirectCameraCrop.load(preferences, profile, true));
        assertNotEquals(DirectCameraCrop.preferenceKey(profile, 0, false),
                DirectCameraCrop.preferenceKey(profile, 0, true));
    }

    @Test
    public void reverseCropSwitchesWithoutChangingSharedLayout() {
        SharedPreferences preferences = new TestSharedPreferences();
        ReverseCameraLayout defaults = ReverseCameraLayout.defaults();
        ReverseCameraLayout raw = defaults;
        for (int index = 1; index <= 3; index++) {
            ReverseCameraLayout.Pane pane = raw.pane(index);
            raw = ReverseCameraLayout.withPane(raw, index, pane.destination,
                    ReverseCameraLayout.sourceCrop(
                            0.03f * index, 0.04f * index, 0.70f, 0.68f), index * 4);
        }
        ReverseCameraController.saveLayout(preferences, raw);

        for (int index = 1; index <= 3; index++) {
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                    CameraDewarpConfig.lensForReverseCamera(index), true, 100));
        }
        ReverseCameraLayout seeded = ReverseCameraController.loadLayout(preferences);
        ReverseCameraLayout corrected = seeded;
        for (int index = 1; index <= 3; index++) {
            ReverseCameraLayout.Pane rawPane = raw.pane(index);
            ReverseCameraLayout.Pane seededPane = seeded.pane(index);
            assertRectEquals(rawPane.sourceCrop, seededPane.sourceCrop);
            assertRectEquals(rawPane.destination, seededPane.destination);
            assertEquals(index * 4, seededPane.rotationDegrees);
            corrected = ReverseCameraLayout.withPane(
                    corrected, index, seededPane.destination,
                    ReverseCameraLayout.sourceCrop(
                            0.05f * index, 0.06f * index, 0.60f, 0.58f),
                    seededPane.rotationDegrees);
        }
        ReverseCameraController.saveLayout(preferences, corrected);
        ReverseCameraLayout persistedRaw = ReverseCameraController.loadRawLayout(preferences);
        for (int index = 1; index <= 3; index++) {
            assertRectEquals(raw.pane(index).sourceCrop,
                    persistedRaw.pane(index).sourceCrop);
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.disabled(
                    CameraDewarpConfig.lensForReverseCamera(index)));
        }
        ReverseCameraLayout disabled = ReverseCameraController.loadLayout(preferences);
        for (int index = 1; index <= 3; index++) {
            assertRectEquals(raw.pane(index).sourceCrop, disabled.pane(index).sourceCrop);
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                    CameraDewarpConfig.lensForReverseCamera(index), true, 100));
        }
        ReverseCameraLayout enabled = ReverseCameraController.loadLayout(preferences);
        for (int index = 1; index <= 3; index++) {
            assertRectEquals(corrected.pane(index).sourceCrop,
                    enabled.pane(index).sourceCrop);
        }
    }

    @Test
    public void rendererFailurePolicyDistinguishesFallbackFromFatalMismatch() {
        CameraDewarpConfig rawLeft = CameraDewarpConfig.disabled(
                CameraDewarpConfig.LENS_LEFT);
        CameraDewarpConfig correctedLeft = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 100);
        assertEquals("dewarp_fallback_raw",
                CameraDewarpRenderer.mappingFailureKind(rawLeft, correctedLeft));
        assertEquals("dewarp_pipeline_failed",
                CameraDewarpRenderer.mappingFailureKind(correctedLeft, rawLeft));
        assertEquals("dewarp_pipeline_failed",
                CameraDewarpRenderer.mappingFailureKind(correctedLeft,
                        CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, true, 100)));
        assertEquals("dewarp_pipeline_failed",
                CameraDewarpRenderer.mappingFailureKind(correctedLeft,
                        correctedLeft.withFov(110)));
        assertEquals("dewarp_pipeline_failed",
                CameraDewarpRenderer.mappingFailureKind(correctedLeft,
                        correctedLeft.withProjection(
                                CameraDewarpConfig.PROJECTION_CYLINDRICAL)));
        assertTrue(CameraDewarpRenderer.isFatalEventKind("dewarp_frame_failed"));
        assertTrue(CameraDewarpRenderer.isFatalEventKind("dewarp_pipeline_failed"));
        assertFalse(CameraDewarpRenderer.isFatalEventKind("dewarp_fallback_raw"));
    }

    private static void assertCropEquals(DirectCameraCrop expected, DirectCameraCrop actual) {
        assertEquals(expected.left, actual.left, EPSILON);
        assertEquals(expected.top, actual.top, EPSILON);
        assertEquals(expected.width, actual.width, EPSILON);
        assertEquals(expected.height, actual.height, EPSILON);
        assertEquals(expected.aspectMode, actual.aspectMode);
        assertEquals(expected.rotationDegrees, actual.rotationDegrees);
        assertEquals(expected.rotationMode, actual.rotationMode);
    }

    private static void assertRectEquals(
            ReverseCameraLayout.Rect expected, ReverseCameraLayout.Rect actual) {
        assertEquals(expected.left, actual.left, EPSILON);
        assertEquals(expected.top, actual.top, EPSILON);
        assertEquals(expected.width, actual.width, EPSILON);
        assertEquals(expected.height, actual.height, EPSILON);
    }
}
