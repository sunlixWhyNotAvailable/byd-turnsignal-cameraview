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
        TestSharedPreferences preferences = new TestSharedPreferences();
        RearviewMirrorSettings.Settings settings = RearviewMirrorSettings.defaults();
        assertFalse(settings.enabled);
        assertEquals(RearviewMirrorSettings.TARGET_TABLET, settings.target);
        assertEquals(CameraPlacement.mirrorDemo(), settings.placement);
        assertFalse(settings.calibration.enabled);
        assertEquals(CameraRotation.MODE_FIT, settings.calibration.rotationMode);
        assertFalse(settings.frontIntegrated);
        assertFalse(settings.showFront);
        assertFalse(settings.activeFront());
        assertEquals(CameraPlacement.of(0f, 0f, 1f, .85f),
                settings.frontCalibration.raw);
        assertEquals(CameraPlacement.of(0f, 0f, 1f, 1f),
                settings.frontCalibration.corrected);
        assertFalse(settings.frontCalibration.enabled);
        assertEquals(100, settings.frontCalibration.fovDegrees);
        assertEquals(0, settings.frontCalibration.projection);
        assertFalse(settings.frontCalibration.mirrored);
        assertEquals(0, settings.frontCalibration.rotationDegrees);
        assertEquals(CameraRotation.MODE_FILL, settings.frontCalibration.rotationMode);
        assertEquals(0, settings.borderDp);
        assertEquals(RearviewMirrorSettings.DEFAULT_BORDER_ARGB, settings.borderArgb);
        assertTrue(RearviewMirrorSettings.suppressWhilePanorama(preferences));
        preferences.putBoolean(RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA, false);
        assertFalse(RearviewMirrorSettings.suppressWhilePanorama(preferences));
    }

    @Test
    public void returnOnOpenDefaultsOnAndPersistsOff() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        assertTrue(RearviewMirrorSettings.returnOnAppOpen(preferences));
        assertFalse(RearviewMirrorSettings.hiddenByButton(preferences));

        preferences.edit().putBoolean(
                RearviewMirrorSettings.PREF_RETURN_ON_APP_OPEN, false).apply();

        assertFalse(RearviewMirrorSettings.returnOnAppOpen(preferences));
    }

    @Test
    public void appOpenRestorationPolicyCoversEveryOriginAndPreferenceCombination() {
        for (int mask = 0; mask < 8; mask++) {
            boolean hidden = (mask & 1) != 0;
            boolean byButton = (mask & 2) != 0;
            boolean returnOnOpen = (mask & 4) != 0;
            assertEquals(hidden && (!byButton || returnOnOpen),
                    RearviewMirrorSettings.shouldRestoreOnAppOpen(
                            hidden, byButton, returnOnOpen));
        }
    }

    @Test
    public void hideAndShowWritesKeepOriginAtomicAndNonButtonActionsClearIt() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        int transactions = preferences.transactions;
        android.content.SharedPreferences.Editor buttonHide = preferences.edit();
        RearviewMirrorSettings.writeHidden(buttonHide, true, true);
        buttonHide.apply();
        assertEquals(transactions + 1, preferences.transactions);
        assertTrue(RearviewMirrorSettings.hidden(preferences));
        assertTrue(RearviewMirrorSettings.hiddenByButton(preferences));

        android.content.SharedPreferences.Editor buttonShow = preferences.edit();
        RearviewMirrorSettings.writeHidden(buttonShow, false, false);
        buttonShow.apply();
        assertFalse(RearviewMirrorSettings.hidden(preferences));
        assertFalse(RearviewMirrorSettings.hiddenByButton(preferences));

        preferences.edit().putBoolean(RearviewMirrorSettings.PREF_HIDDEN_BY_BUTTON, true).apply();
        RearviewMirrorSettings.setHidden(preferences, true);
        assertTrue(RearviewMirrorSettings.hidden(preferences));
        assertFalse(RearviewMirrorSettings.hiddenByButton(preferences));
        RearviewMirrorSettings.setHidden(preferences, false);
        assertFalse(RearviewMirrorSettings.hidden(preferences));
        assertFalse(RearviewMirrorSettings.hiddenByButton(preferences));
    }

    @Test
    public void unrelatedAndSourceOnlySavesRetainReturnAndOriginFlags() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit()
                .putBoolean(RearviewMirrorSettings.PREF_RETURN_ON_APP_OPEN, false)
                .putBoolean(RearviewMirrorSettings.PREF_HIDDEN_BY_BUTTON, true)
                .putBoolean(RearviewMirrorSettings.PREF_MANUAL_HIDDEN, true)
                .apply();

        RearviewMirrorSettings.writeSourceState(
                (android.content.SharedPreferences) preferences, true, true);
        RearviewMirrorSettings.Settings loaded = new RearviewMirrorSettings(preferences).load();
        new RearviewMirrorSettings(preferences).save(loaded.withEnabled(true));

        assertFalse(RearviewMirrorSettings.returnOnAppOpen(preferences));
        assertTrue(RearviewMirrorSettings.hiddenByButton(preferences));
        assertTrue(RearviewMirrorSettings.hidden(preferences));
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

    @Test
    public void displaySlotsFallBackToLegacyAndOnlyExplicitTargetChanges() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit()
                .putFloat(RearviewMirrorSettings.PREF_X, 10f)
                .putFloat(RearviewMirrorSettings.PREF_Y, 20f)
                .putFloat(RearviewMirrorSettings.PREF_WIDTH, 30f)
                .putFloat(RearviewMirrorSettings.PREF_HEIGHT, 40f).apply();
        assertEquals(RearviewMirrorSettings.placement(preferences,
                RearviewMirrorSettings.TARGET_TABLET),
                RearviewMirrorSettings.placement(preferences,
                        RearviewMirrorSettings.TARGET_CLUSTER));

        RearviewMirrorSettings.writePlacement(preferences,
                RearviewMirrorSettings.TARGET_CLUSTER, .2f, .3f, .4f, .5f);
        assertEquals(CameraPlacement.of(.1f, .2f, .3f, .4f),
                RearviewMirrorSettings.placement(preferences,
                        RearviewMirrorSettings.TARGET_TABLET));
        assertEquals(CameraPlacement.of(.2f, .3f, .4f, .5f),
                RearviewMirrorSettings.placement(preferences,
                        RearviewMirrorSettings.TARGET_CLUSTER));
        assertEquals(10f, preferences.getFloat(RearviewMirrorSettings.PREF_X, -1f), 0f);
    }

    @Test
    public void clusterFactoryPlacementCentersWholeTabletFactoryRectangle() {
        CameraPlacement tablet = RearviewMirrorSettings.defaultPlacement(
                RearviewMirrorSettings.TARGET_TABLET);
        CameraPlacement cluster = RearviewMirrorSettings.defaultPlacement(
                RearviewMirrorSettings.TARGET_CLUSTER);
        assertEquals(tablet.width, cluster.width, 0f);
        assertEquals(tablet.height, cluster.height, 0f);
        assertEquals((1f - cluster.width) / 2f, cluster.x, 0f);
        assertEquals((1f - cluster.height) / 2f, cluster.y, 0f);
    }

    @Test
    public void fullSaveWritesPlacementOnlyToValueTarget() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        RearviewMirrorSettings.Settings base = RearviewMirrorSettings.defaults();
        CameraPlacement cluster = CameraPlacement.of(.2f, .3f, .4f, .5f);
        RearviewMirrorSettings.Settings value = new RearviewMirrorSettings.Settings(
                base.enabled, RearviewMirrorSettings.TARGET_CLUSTER, cluster,
                base.calibration, base.preset, base.borderDp, base.borderArgb,
                base.manualHidden);
        new RearviewMirrorSettings(preferences).save(value);

        assertEquals(cluster, RearviewMirrorSettings.placement(preferences,
                RearviewMirrorSettings.TARGET_CLUSTER));
        assertEquals(CameraPlacement.mirrorDemo(), RearviewMirrorSettings.placement(preferences,
                RearviewMirrorSettings.TARGET_TABLET));
        assertFalse(preferences.contains(RearviewMirrorSettings.placementKey(
                RearviewMirrorSettings.TARGET_TABLET,
                RearviewMirrorSettings.PLACEMENT_X)));
    }

    @Test
    public void sourceReadsNeverMigrateOrResetOldPreferences() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putFloat(RearviewMirrorSettings.PREF_ORIGINAL_X, 17f);
        preferences.putFloat(RearviewMirrorSettings.PREF_ORIGINAL_WIDTH, 50f);
        java.util.Map<String, ?> before = new java.util.HashMap<>(preferences.getAll());
        int transactions = preferences.transactions;

        RearviewMirrorSettings.Settings value = new RearviewMirrorSettings(preferences).load();

        assertEquals(before, preferences.getAll());
        assertEquals(transactions, preferences.transactions);
        assertEquals(.17f, value.calibration.raw.x, .000001f);
        assertEquals(.85f, value.frontCalibration.raw.height, 0f);
    }

    @Test
    public void oldConstructorSavePreservesAddedSourceFields() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        RearviewMirrorSettings.Calibration front = new RearviewMirrorSettings.Calibration(
                CameraPlacement.of(.1f, .2f, .7f, .6f),
                CameraPlacement.of(.2f, .1f, .6f, .7f), true, 140, 1,
                true, 45, CameraRotation.MODE_ALIGNED);
        RearviewMirrorSettings.writeCalibration(preferences, true, front);
        RearviewMirrorSettings.writePreset(preferences, true, front);
        RearviewMirrorSettings.writeSourceState(
                (android.content.SharedPreferences) preferences, true, true);
        RearviewMirrorSettings.Settings base = new RearviewMirrorSettings(preferences).load();

        new RearviewMirrorSettings(preferences).save(new RearviewMirrorSettings.Settings(
                true, base.target, base.placement, base.calibration, base.preset,
                base.borderDp, base.borderArgb, base.manualHidden));

        RearviewMirrorSettings.Settings saved = new RearviewMirrorSettings(preferences).load();
        assertTrue(saved.activeFront());
        assertEquals(front.raw, saved.frontCalibration.raw);
        assertEquals(front.corrected, saved.frontPreset.corrected);
    }

    @Test
    public void sourceCalibrationPresetAndRuntimeGettersStayIsolated() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        RearviewMirrorSettings.Calibration rear = new RearviewMirrorSettings.Calibration(
                CameraPlacement.of(.1f, .1f, .8f, .8f),
                CameraPlacement.of(.2f, .2f, .7f, .7f), false, 110, 0,
                false, -10, CameraRotation.MODE_FIT);
        RearviewMirrorSettings.Calibration front = new RearviewMirrorSettings.Calibration(
                CameraPlacement.of(.3f, .2f, .6f, .7f),
                CameraPlacement.of(.2f, .3f, .7f, .6f), true, 150, 1,
                true, 20, CameraRotation.MODE_FILL);
        RearviewMirrorSettings.writeCalibration(preferences, false, rear);
        RearviewMirrorSettings.writeCalibration(preferences, true, front);
        RearviewMirrorSettings.writePreset(preferences, false, rear);
        RearviewMirrorSettings.writePreset(preferences, true, front);

        assertEquals(rear.raw, RearviewMirrorSettings.calibration(preferences, false).raw);
        assertEquals(front.raw, RearviewMirrorSettings.calibration(preferences, true).raw);
        assertEquals(rear.corrected, RearviewMirrorSettings.preset(preferences, false).corrected);
        assertEquals(front.corrected, RearviewMirrorSettings.preset(preferences, true).corrected);
        assertEquals(RearviewMirrorSettings.REAR_CAMERA_INDEX,
                RearviewMirrorSettings.cameraIndex(preferences));
        RearviewMirrorSettings.writeSourceState(
                (android.content.SharedPreferences) preferences, true, true);
        assertEquals(RearviewMirrorSettings.FRONT_CAMERA_INDEX,
                RearviewMirrorSettings.cameraIndex(preferences));
        DirectCameraCrop frontRaw = RearviewMirrorSettings.raw(preferences, true);
        assertEquals(front.raw.x, frontRaw.left, 0f);
        assertEquals(front.raw.y, frontRaw.top, 0f);
        assertEquals(front.raw.width, frontRaw.width, 0f);
        assertEquals(front.raw.height, frontRaw.height, 0f);
        assertEquals(CameraDewarpConfig.LENS_FRONT,
                RearviewMirrorSettings.dewarp(preferences).lens);

        RearviewMirrorSettings.copyCalibration(preferences, true, false);
        RearviewMirrorSettings.Calibration copied =
                RearviewMirrorSettings.calibration(preferences, false);
        assertEquals(front.raw, copied.raw);
        assertEquals(front.corrected, copied.corrected);
        assertTrue(copied.mirrored);

        RearviewMirrorSettings.writeSourceState(
                (android.content.SharedPreferences) preferences, false, true);
        RearviewMirrorSettings.Settings disabled = new RearviewMirrorSettings(preferences).load();
        assertFalse(disabled.showFront);
        assertFalse(disabled.activeFront());
        assertEquals(front.raw, disabled.frontCalibration.raw);
        assertEquals(front.corrected, disabled.frontPreset.corrected);
    }
}
