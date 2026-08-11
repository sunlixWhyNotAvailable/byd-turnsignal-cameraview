package com.byd.turnsignalguard.capture;

import android.content.SharedPreferences;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertEquals(0.5f, restored.roiCenterX, 0.0f);
        assertEquals(0.5f, restored.roiCenterY, 0.0f);

        CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 165,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL)
                .withRoiCenter(0.2f, 0.8f));
        restored = CameraDewarpConfig.load(preferences, CameraDewarpConfig.LENS_LEFT);
        assertTrue(restored.enabled);
        assertEquals(165, restored.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_CYLINDRICAL, restored.projection);
        assertEquals(0.5f, restored.roiCenterX, 0.0f);
        assertEquals(0.5f, restored.roiCenterY, 0.0f);

        preferences.edit().putInt("camera_dewarp_v2_left_projection", 7).apply();
        CameraDewarpConfig invalid = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.LENS_LEFT);
        assertFalse(invalid.enabled);
        assertEquals(CameraDewarpConfig.DEFAULT_FOV_DEGREES, invalid.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_RECTILINEAR, invalid.projection);
    }

    @Test
    public void runtimeRoiIsValidatedAndPreservedByMappingChanges() {
        CameraDewarpConfig centered = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 100);
        CameraDewarpConfig aimed = centered.withRoiCenter(0.25f, 0.75f);
        assertFalse(centered.sameMapping(aimed));
        assertEquals(0.25f, aimed.withEnabled(false).roiCenterX, 0.0f);
        assertEquals(0.75f, aimed.withFov(120).roiCenterY, 0.0f);
        assertEquals(0.25f, aimed.withProjection(
                CameraDewarpConfig.PROJECTION_CYLINDRICAL).roiCenterX, 0.0f);
        assertThrows(IllegalArgumentException.class,
                () -> centered.withRoiCenter(Float.NaN, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> centered.withRoiCenter(-0.01f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> centered.withRoiCenter(0.5f, 1.01f));
    }

    @Test
    public void disabledMappingIgnoresParametersUnusedByIdentityPipeline() {
        CameraDewarpConfig disabled = CameraDewarpConfig.disabled(
                CameraDewarpConfig.LENS_LEFT);
        CameraDewarpConfig differentUnusedParameters = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, false, 165,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL)
                .withRoiCenter(0.2f, 0.8f);

        assertTrue(disabled.sameMapping(differentUnusedParameters));
        assertFalse(disabled.sameMapping(CameraDewarpConfig.disabled(
                CameraDewarpConfig.LENS_RIGHT)));
        assertFalse(disabled.sameMapping(disabled.withEnabled(true)));
    }

    @Test
    public void coalescedAbaReturnGetsOnlyCurrentPostSwapAcknowledgement() {
        CameraDewarpConfig a = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 100);
        CameraDewarpRenderer.MappingRequest a1 = new CameraDewarpRenderer.MappingRequest(a, 1);
        CameraDewarpRenderer.MappingRequest b2 = new CameraDewarpRenderer.MappingRequest(
                a.withFov(120), 2);
        CameraDewarpRenderer.MappingRequest a3 = new CameraDewarpRenderer.MappingRequest(
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 100), 3);

        assertEquals(2L, CameraDewarpRenderer.nextRequestToken(
                a1.token, a1.config, b2.config));
        assertEquals(3L, CameraDewarpRenderer.nextRequestToken(
                b2.token, b2.config, a3.config));
        assertTrue(CameraDewarpRenderer.canReuseAppliedMapping(a1, a3));
        assertFalse(CameraDewarpRenderer.shouldEmitAppliedMesh(
                false, a3, a1, a1, a3));
        assertFalse(CameraDewarpRenderer.shouldEmitAppliedMesh(
                true, null, a1, a1, a3));
        assertFalse(CameraDewarpRenderer.shouldEmitAppliedMesh(
                true, a1, a1, a1, a3));
        assertFalse(CameraDewarpRenderer.shouldEmitAppliedMesh(
                true, a3, a1, b2, a3));
        assertTrue(CameraDewarpRenderer.shouldEmitAppliedMesh(
                true, a3, a1, a1, a3));
        assertFalse(CameraDewarpRenderer.shouldHandleEvent(
                "dewarp_mesh_applied", a1.token, a3.token));
        assertTrue(CameraDewarpRenderer.shouldHandleEvent(
                "dewarp_mesh_applied", a3.token, a3.token));
    }

    @Test
    public void domainFallbackWaitsForIdentitySwapAndCanRestoreCorrection() {
        CameraDewarpRenderer.MappingRequest supportedA =
                new CameraDewarpRenderer.MappingRequest(CameraDewarpConfig.of(
                        CameraDewarpConfig.LENS_LEFT, true, 120), 1);
        CameraDewarpRenderer.MappingRequest unsupportedB =
                new CameraDewarpRenderer.MappingRequest(CameraDewarpConfig.of(
                        CameraDewarpConfig.LENS_LEFT, true, 170)
                        .withRoiCenter(0.005f, 0.005f), 2);
        CameraDewarpRenderer.MappingRequest rawIdentity =
                new CameraDewarpRenderer.MappingRequest(CameraDewarpConfig.disabled(
                        CameraDewarpConfig.LENS_LEFT), 2);

        assertFalse(CameraDewarpRenderer.canReuseAppliedMapping(
                supportedA, unsupportedB));
        assertFalse(CameraDewarpRenderer.shouldEmitAppliedMesh(
                false, unsupportedB, rawIdentity, rawIdentity, unsupportedB));
        assertTrue(CameraDewarpRenderer.shouldEmitAppliedMesh(
                true, unsupportedB, rawIdentity, rawIdentity, unsupportedB));
        CameraDewarpRenderer.Event fallback = new CameraDewarpRenderer.Event(
                "dewarp_fallback_raw", unsupportedB, 4, 0, null);
        assertEquals(unsupportedB.token, fallback.requestToken);
        assertTrue(unsupportedB.config.sameMapping(fallback.mapping));
        assertFalse(CameraDewarpRenderer.shouldHandleEvent(
                fallback.kind, fallback.requestToken, 3));

        CameraDewarpRenderer.MappingRequest supportedC =
                new CameraDewarpRenderer.MappingRequest(supportedA.config, 3);
        assertFalse(CameraDewarpRenderer.canReuseAppliedMapping(
                rawIdentity, supportedC));
        assertTrue(CameraDewarpRenderer.shouldEmitAppliedMesh(
                true, supportedC, supportedC, supportedC, supportedC));
    }

    @Test
    public void disabledRoiEditKeepsRequestTokenAndDoesNotNeedNewMapping() {
        CameraDewarpConfig disabled = CameraDewarpConfig.disabled(
                CameraDewarpConfig.LENS_LEFT);
        CameraDewarpConfig roiEdit = disabled.withRoiCenter(0.2f, 0.8f);
        assertEquals(7L, CameraDewarpRenderer.nextRequestToken(
                7L, disabled, roiEdit));
        assertEquals(8L, CameraDewarpRenderer.nextRequestToken(
                7L, disabled, disabled.withEnabled(true)));
    }

    @Test
    public void turnCropPersistsIndependentCorrectedGeometryAndIgnoresLegacyKeys() {
        SharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        DirectCameraCrop raw = DirectCameraCrop.of(
                0.10f, 0.12f, 0.55f, 0.60f, DirectCameraCrop.ASPECT_FREE,
                37, CameraRotation.MODE_ALIGNED);
        DirectCameraCrop.save(preferences, profile, raw);
        String legacyX = "direct_crop_dewarp_v2_" + profile.wireName + "_x";
        preferences.edit().putFloat(legacyX, 0.77f).apply();

        CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 120));
        assertCropEquals(raw, DirectCameraCrop.load(preferences, profile));
        DirectCameraCrop centered = raw.centered();
        assertCropEquals(centered,
                DirectCameraCrop.loadCorrected(preferences, profile, raw));

        DirectCameraCrop corrected = raw.withGeometry(DirectCameraCrop.of(
                0.22f, 0.18f, 0.42f, 0.46f, DirectCameraCrop.ASPECT_FREE));
        DirectCameraCrop.saveCorrected(preferences, profile, corrected);
        assertCropEquals(corrected,
                DirectCameraCrop.loadCorrected(preferences, profile, raw));

        DirectCameraCrop replacement = DirectCameraCrop.of(
                0.2f, 0.2f, 0.4f, 0.45f, DirectCameraCrop.ASPECT_FOUR_THREE,
                11, CameraRotation.MODE_FILL);
        DirectCameraCrop.save(preferences, profile, replacement);
        assertCropEquals(replacement, DirectCameraCrop.load(preferences, profile));
        DirectCameraCrop reframed = DirectCameraCrop.preserveCenterAndAspect(
                corrected, replacement);
        assertEquals(corrected.left + corrected.width / 2.0f,
                reframed.left + reframed.width / 2.0f, 0.0001f);
        assertEquals(corrected.top + corrected.height / 2.0f,
                reframed.top + reframed.height / 2.0f, 0.0001f);
        assertEquals(replacement.aspectMode, reframed.aspectMode);
        assertEquals(replacement.rotationDegrees, reframed.rotationDegrees);
        assertEquals(replacement.rotationMode, reframed.rotationMode);
        assertEquals(0.77f, preferences.getFloat(legacyX, -1.0f), 0.0f);
    }

    @Test
    public void reverseCropUsesV3CorrectedGeometryAndKeepsLegacyKeysUntouched() {
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
        float[] legacyLeft = {0.71f, 0.72f, 0.73f};
        for (int index = 1; index <= 3; index++) {
            preferences.edit().putFloat(ReverseCameraController.sourceCropKey(
                    index, "left", true), legacyLeft[index - 1]).apply();
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                    CameraDewarpConfig.lensForReverseCamera(index), true, 100));
        }
        ReverseCameraLayout enabled = ReverseCameraController.loadLayout(preferences);
        for (int index = 1; index <= 3; index++) {
            assertRectEquals(ReverseCameraLayout.centeredSourceCrop(
                    raw.pane(index).sourceCrop), enabled.pane(index).sourceCrop);
            ReverseCameraLayout.Rect corrected = ReverseCameraLayout.sourceCrop(
                    0.1f * index, 0.05f * index, 0.4f, 0.5f);
            ReverseCameraController.saveSourceCrop(
                    preferences, index, corrected, true);
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.disabled(
                    CameraDewarpConfig.lensForReverseCamera(index)));
        }
        ReverseCameraLayout disabled = ReverseCameraController.loadLayout(preferences);
        for (int index = 1; index <= 3; index++) {
            assertRectEquals(raw.pane(index).sourceCrop, disabled.pane(index).sourceCrop);
            CameraDewarpConfig.save(preferences, CameraDewarpConfig.of(
                    CameraDewarpConfig.lensForReverseCamera(index), true, 100));
        }
        enabled = ReverseCameraController.loadLayout(preferences);
        for (int index = 1; index <= 3; index++) {
            assertEquals(0.1f * index, enabled.pane(index).sourceCrop.left, 0.0001f);
            assertEquals(legacyLeft[index - 1], preferences.getFloat(
                    ReverseCameraController.sourceCropKey(index, "left", true), -1.0f), 0.0f);
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

    @Test
    public void staleMappingEventsCannotAffectNewerMappingButFrameFailureCan() {
        assertFalse(CameraDewarpRenderer.shouldHandleEvent(
                "dewarp_pipeline_failed", 2, 3));
        assertFalse(CameraDewarpRenderer.shouldHandleEvent(
                "dewarp_fallback_raw", 2, 3));
        assertFalse(CameraDewarpRenderer.shouldHandleEvent(
                "dewarp_mesh_applied", 2, 3));
        assertTrue(CameraDewarpRenderer.shouldHandleEvent(
                "dewarp_pipeline_failed", 3, 3));
        assertTrue(CameraDewarpRenderer.shouldHandleEvent(
                "dewarp_frame_failed", 2, 3));
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
