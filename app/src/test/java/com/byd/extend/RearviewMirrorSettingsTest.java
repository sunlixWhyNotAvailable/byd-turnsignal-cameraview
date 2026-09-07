package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RearviewMirrorSettingsTest {
    @Test
    public void independentDestinationFieldsNeverRoundTheOtherDimensions() {
        CameraPlacement before = CameraPlacement.of(.123456f, .234567f, .345678f, .456789f);
        CameraPlacement moved = CameraProbeActivity.editMirrorPlacement(before,
                com.byd.extend.ui.MirrorNumber.X, "50");
        assertEquals(.5f, moved.x, 0f);
        assertEquals(before.y, moved.y, 0f);
        assertEquals(before.width, moved.width, 0f);
        assertEquals(before.height, moved.height, 0f);
        CameraPlacement resized = CameraProbeActivity.editMirrorPlacement(moved,
                com.byd.extend.ui.MirrorNumber.Width, "70");
        assertEquals(.3f, resized.x, .000001f);
        assertEquals(.7f, resized.width, .000001f);
        assertEquals(before.y, resized.y, 0f);
        assertEquals(before.height, resized.height, 0f);
    }

    @Test
    public void typedCalibrationEditsPreserveUntouchedPrecisionAndIndependentStages() {
        CameraPlacement raw = CameraPlacement.source(.98f, .012345f, .01f, .876543f);
        CameraPlacement corrected = CameraPlacement.source(.234567f, .345678f, .54321f, .4321f);
        RearviewMirrorSettings.Calibration initial = new RearviewMirrorSettings.Calibration(
                raw, corrected, false, 100, 0, false, 0, CameraRotation.MODE_FIT);
        RearviewMirrorSettings.Calibration fov = CameraProbeActivity.mergeMirrorCalibration(initial,
                com.byd.extend.ui.ProfileNumber.Fov, null, "145");
        assertEquals(145, fov.fovDegrees);
        assertEquals(raw, fov.raw);
        assertEquals(corrected, fov.corrected);
        RearviewMirrorSettings.Calibration toggled = CameraProbeActivity.mergeMirrorCalibration(fov,
                null, com.byd.extend.ui.MirrorCalibrationField.CorrectionEnabled, "true");
        assertTrue(toggled.enabled);
        assertEquals(raw, toggled.raw);
        assertEquals(corrected, toggled.corrected);
        RearviewMirrorSettings.Calibration resized = CameraProbeActivity.mergeMirrorCalibration(toggled,
                com.byd.extend.ui.ProfileNumber.OriginalWidth, null, "5");
        assertEquals(.95f, resized.raw.x, .000001f);
        assertEquals(.05f, resized.raw.width, 0f);
        assertEquals(raw.y, resized.raw.y, 0f);
        assertEquals(raw.height, resized.raw.height, 0f);
        assertEquals(corrected, resized.corrected);
        assertEquals(145, resized.fovDegrees);
    }

    @Test
    public void targetReadsStringAndTemporaryIntegerWithoutMovingClusterToTablet() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putInt(RearviewMirrorSettings.PREF_TARGET, RearviewMirrorSettings.TARGET_CLUSTER);
        assertEquals(RearviewMirrorSettings.TARGET_CLUSTER, RearviewMirrorSettings.target(preferences));
        preferences.edit().putString(RearviewMirrorSettings.PREF_TARGET, "Cluster").apply();
        assertEquals(RearviewMirrorSettings.TARGET_CLUSTER, RearviewMirrorSettings.target(preferences));
        preferences.edit().putString(RearviewMirrorSettings.PREF_TARGET, "Tablet").apply();
        assertEquals(RearviewMirrorSettings.TARGET_TABLET, RearviewMirrorSettings.target(preferences));
    }

    @Test
    public void defaultsMatchPreviewMirrorState() {
        RearviewMirrorSettings.Settings settings = RearviewMirrorSettings.defaults();
        assertFalse(settings.enabled);
        assertEquals(RearviewMirrorSettings.TARGET_TABLET, settings.target);
        assertEquals(CameraPlacement.mirrorDemo(), settings.placement);
        assertFalse(settings.calibration.enabled);
        assertEquals(CameraRotation.MODE_FIT, settings.calibration.rotationMode);
        assertEquals(0, settings.borderDp);
        assertEquals(RearviewMirrorSettings.DEFAULT_BORDER_ARGB, settings.borderArgb);
    }

    @Test
    public void placementAndTransformedRawPersistIndependently() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        RearviewMirrorSettings.writePlacement(preferences,
                0.101f, 0.202f, 0.303f, 0.404f);
        RearviewMirrorSettings.Settings stored = new RearviewMirrorSettings(preferences).load();
        assertEquals(0.101f, stored.placement.x, 0.00001f);
        assertEquals(0.202f, stored.placement.y, 0.00001f);
        assertEquals(0.303f, stored.placement.width, 0.00001f);
        assertEquals(0.404f, stored.placement.height, 0.00001f);

        RearviewMirrorSettings.Calibration calibration = new RearviewMirrorSettings.Calibration(
                CameraPlacement.of(0.1f, 0.2f, 0.3f, 0.4f),
                CameraPlacement.of(0.2f, 0.3f, 0.4f, 0.5f),
                true, 130, CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                true, 35, CameraRotation.MODE_FILL);
        new RearviewMirrorSettings(preferences).save(
                RearviewMirrorSettings.defaults().withEnabled(true));
        RearviewMirrorSettings.writePreset(preferences, calibration);
        preferences.edit().putBoolean(RearviewMirrorSettings.PREF_ENABLED, true)
                .putFloat("mirror_original_x", calibration.raw.x * 100.0f)
                .putFloat("mirror_original_y", calibration.raw.y * 100.0f)
                .putFloat("mirror_original_width", calibration.raw.width * 100.0f)
                .putFloat("mirror_original_height", calibration.raw.height * 100.0f)
                .putFloat("mirror_corrected_x", calibration.corrected.x * 100.0f)
                .putFloat("mirror_corrected_y", calibration.corrected.y * 100.0f)
                .putFloat("mirror_corrected_width", calibration.corrected.width * 100.0f)
                .putFloat("mirror_corrected_height", calibration.corrected.height * 100.0f)
                .putBoolean("mirror_correction", true)
                .putInt("mirror_fov", 130)
                .putInt("mirror_projection", 1)
                .putBoolean("mirror_mirrored", true)
                .putInt("mirror_rotation", 35)
                .putInt("mirror_output_mode", CameraRotation.MODE_FILL).apply();
        assertTrue(RearviewMirrorSettings.raw(preferences).mirrorHorizontally);
        assertEquals(35, RearviewMirrorSettings.raw(preferences).rotationDegrees);
        assertEquals(CameraRotation.MODE_FILL, RearviewMirrorSettings.raw(preferences).rotationMode);
        assertEquals(130, RearviewMirrorSettings.dewarp(preferences).fovDegrees);
        assertEquals(1, RearviewMirrorSettings.dewarp(preferences).projection);
        assertTrue(RearviewMirrorSettings.preset(preferences) != null);
    }
}
