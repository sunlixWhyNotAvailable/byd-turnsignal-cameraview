package com.byd.extend;

import org.junit.Test;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for the optional central Front source in Reverse. */
public final class CentralFrontIntegrationTest {
    private static final int CENTRAL = ReverseCameraLayout.REAR_CAMERA_INDEX;
    private static final float EPSILON = 0.0001f;

    @Test
    public void centralIntegrationDefaultsOffAndPersistsIndependently() {
        TestSharedPreferences settings = new TestSharedPreferences();

        assertFalse(ReverseCameraController.loadCentralFrontIntegrated(settings));
        assertFalse(ReverseCameraController.DEFAULT_FRONT_INTEGRATED);

        ReverseCameraController.saveCentralFrontIntegrated(settings, true);

        assertTrue(ReverseCameraController.loadCentralFrontIntegrated(settings));
        assertTrue(settings.getBoolean(
                ReverseCameraController.PREF_CENTRAL_FRONT_INTEGRATED, false));
    }

    @Test
    public void centralFrontUsesSharedDestinationAndIndependentCalibration() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraLayout rear = ReverseCameraLayout.withPane(
                ReverseCameraLayout.defaults(), CENTRAL,
                ReverseCameraLayout.destination(0.12f, 0.18f, 0.42f, 0.53f),
                ReverseCameraLayout.sourceCrop(0.14f, 0.16f, 0.51f, 0.62f), 21);
        rear = ReverseCameraLayout.withDisplayMode(
                rear, CENTRAL, ReverseCameraLayout.DISPLAY_MODE_FILL);
        rear = ReverseCameraLayout.withMirrorHorizontally(rear, CENTRAL, true);
        ReverseCameraController.saveLayout(settings, rear);
        ReverseCameraController.saveVisibility(settings, CENTRAL, false);
        CameraDewarpConfig.saveForReverse(settings, CENTRAL,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_REAR, true, 161,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL));

        ReverseCameraLayout.Rect frontRaw = ReverseCameraLayout.sourceCrop(
                0.23f, 0.27f, 0.44f, 0.52f);
        ReverseCameraLayout.Rect frontCorrected = ReverseCameraLayout.sourceCrop(
                0.31f, 0.19f, 0.36f, 0.47f);
        ReverseCameraController.saveFrontSourceCrop(settings, CENTRAL, frontRaw, false);
        ReverseCameraController.saveFrontSourceCrop(
                settings, CENTRAL, frontCorrected, true);
        ReverseCameraController.saveFrontPaneTransform(settings, CENTRAL, -37,
                ReverseCameraLayout.DISPLAY_MODE_STRETCH, false);
        CameraDewarpConfig.saveForReverseFront(settings, CENTRAL,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_FRONT, true, 149,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL));

        ReverseCameraLayout.Pane rearPane = ReverseCameraController
                .loadRawLayout(settings).pane(CENTRAL);
        ReverseCameraLayout.Pane frontRawPane = ReverseCameraController
                .loadFrontRawLayout(settings).pane(CENTRAL);
        ReverseCameraLayout.Pane frontPane = ReverseCameraController
                .loadFrontLayout(settings).pane(CENTRAL);

        assertEquals(rear.pane(CENTRAL).destination.left,
                frontRawPane.destination.left, EPSILON);
        assertEquals(rear.pane(CENTRAL).destination.top,
                frontRawPane.destination.top, EPSILON);
        assertEquals(frontRaw.left, frontRawPane.sourceCrop.left, EPSILON);
        assertEquals(frontRaw.top, frontRawPane.sourceCrop.top, EPSILON);
        assertEquals(frontCorrected.left, frontPane.sourceCrop.left, EPSILON);
        assertEquals(-37, frontPane.rotationDegrees);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_STRETCH, frontPane.displayMode);
        assertFalse(frontPane.mirrorHorizontally);
        assertFalse(ReverseCameraController.loadVisibility(settings, CENTRAL));
        assertEquals(CameraDewarpConfig.LENS_REAR,
                CameraDewarpConfig.loadForReverse(settings, CENTRAL).lens);
        assertEquals(161, CameraDewarpConfig.loadForReverse(settings, CENTRAL).fovDegrees);
        assertEquals(CameraDewarpConfig.LENS_FRONT,
                CameraDewarpConfig.loadForReverseFront(settings, CENTRAL).lens);
        assertEquals(149,
                CameraDewarpConfig.loadForReverseFront(settings, CENTRAL).fovDegrees);
    }

    @Test
    public void centralFrontPresetRoundTripDoesNotOverwriteRearDestination() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraLayout rear = ReverseCameraLayout.withPane(
                ReverseCameraLayout.defaults(), CENTRAL,
                ReverseCameraLayout.destination(0.08f, 0.11f, 0.37f, 0.43f),
                ReverseCameraLayout.defaults().pane(CENTRAL).sourceCrop, 7);
        ReverseCameraController.saveLayout(settings, rear);
        ReverseCameraLayout.Rect destination = rear.pane(CENTRAL).destination;
        ReverseCameraLayout.Rect raw = ReverseCameraLayout.sourceCrop(
                0.17f, 0.22f, 0.55f, 0.63f);
        ReverseCameraLayout.Rect corrected = ReverseCameraLayout.sourceCrop(
                0.21f, 0.28f, 0.41f, 0.49f);
        ReverseCameraController.saveFrontSourceCrop(settings, CENTRAL, raw, false);
        ReverseCameraController.saveFrontSourceCrop(settings, CENTRAL, corrected, true);
        ReverseCameraController.saveFrontPaneTransform(settings, CENTRAL, 43,
                ReverseCameraLayout.DISPLAY_MODE_FILL, true);
        CameraDewarpConfig.saveForReverseFront(settings, CENTRAL,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_FRONT, true, 152,
                        CameraDewarpConfig.PROJECTION_RECTILINEAR));

        CameraCalibrationPreset.saveReverseFront(settings, CENTRAL);
        ReverseCameraController.saveFrontSourceCrop(settings, CENTRAL,
                ReverseCameraLayout.sourceCrop(0.01f, 0.02f, 0.2f, 0.2f), false);
        ReverseCameraController.saveFrontPaneTransform(settings, CENTRAL, -5,
                ReverseCameraLayout.DISPLAY_MODE_STRETCH, false);
        CameraDewarpConfig.saveForReverseFront(settings, CENTRAL,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_FRONT));

        assertTrue(CameraCalibrationPreset.loadReverseFront(settings, CENTRAL));
        ReverseCameraLayout.Pane restored = ReverseCameraController
                .loadFrontRawLayout(settings).pane(CENTRAL);
        assertEquals(raw.left, restored.sourceCrop.left, EPSILON);
        assertEquals(raw.top, restored.sourceCrop.top, EPSILON);
        assertEquals(43, restored.rotationDegrees);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FILL, restored.displayMode);
        assertTrue(restored.mirrorHorizontally);
        assertTrue(CameraDewarpConfig.loadForReverseFront(settings, CENTRAL).enabled);
        assertEquals(152,
                CameraDewarpConfig.loadForReverseFront(settings, CENTRAL).fovDegrees);
        ReverseCameraLayout.Pane rearRestored = ReverseCameraController
                .loadRawLayout(settings).pane(CENTRAL);
        assertEquals(destination.left, rearRestored.destination.left, EPSILON);
        assertEquals(destination.top, rearRestored.destination.top, EPSILON);
    }

    @Test
    public void overlaySpecCarriesCentralFrontScopeAndToggle() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraShellProtocol.ReverseOverlaySpec defaults =
                ReverseCameraController.buildOverlaySpec(settings, 101);
        assertFalse(defaults.centralFrontIntegrated);
        assertFalse(defaults.requiresCentralFrontSource());
        assertEquals(CameraDewarpConfig.LENS_FRONT, defaults.centralFrontDewarp.lens);

        ReverseCameraController.saveCentralFrontIntegrated(settings, true);
        CameraDewarpConfig.saveForReverseFront(settings, CENTRAL,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_FRONT, true, 143,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL));
        CameraShellProtocol.ReverseOverlaySpec enabled =
                ReverseCameraController.buildOverlaySpec(settings, 102);
        assertTrue(enabled.centralFrontIntegrated);
        assertFalse(enabled.requiresCentralFrontSource());
        assertEquals(143, enabled.centralFrontDewarp.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                enabled.centralFrontDewarp.projection);
        enabled.validate(1920, 990);

        ReverseCameraController.saveWidgetVisible(settings, true);
        CameraShellProtocol.ReverseOverlaySpec visible =
                ReverseCameraController.buildOverlaySpec(settings, 103);
        assertTrue(visible.requiresCentralFrontSource());
    }

    @Test
    public void centralCalibrationIdentityChangesWhenSwitchingRearAndFrontSources() {
        // The logical center pane remains index 1 in both modes, but its live
        // producer changes from Rear pano_h=1 to Front pano_h=4.  That change
        // must force a mirror/copy rebind; an unchanged identity must not.
        assertFalse(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, CENTRAL, 1,
                false, CENTRAL, 1));
        assertTrue(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, CENTRAL, 1,
                false, CENTRAL, 4));
        assertTrue(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, CENTRAL, 4,
                false, CENTRAL, 1));
        // Switching the logical center to a side pane also rebinds, while the
        // side pane's own source identity remains stable across its modes.
        assertTrue(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, CENTRAL, 4,
                false, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 2));
        assertFalse(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 2,
                false, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 2));
    }

    @Test
    public void cameraPresetTransferIncludesCentralFrontFieldsAndLegacyFallbackIsOff()
            throws Exception {
        TestSharedPreferences source = new TestSharedPreferences();
        ReverseCameraController.saveCentralFrontIntegrated(source, true);
        ReverseCameraController.saveFrontSourceCrop(source, CENTRAL,
                ReverseCameraLayout.sourceCrop(0.11f, 0.12f, 0.63f, 0.67f), false);
        ReverseCameraController.saveFrontPaneTransform(source, CENTRAL, 33,
                ReverseCameraLayout.DISPLAY_MODE_STRETCH, true);
        CameraDewarpConfig.saveForReverseFront(source, CENTRAL,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_FRONT, true, 148,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL));

        Map<?, ?> values = (Map<?, ?>) CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(source)).get("settings");
        assertTrue(values.containsKey("reverse_camera_front_1_integrated"));
        assertTrue(values.containsKey("reverse_camera_front_1_crop_left"));
        assertTrue(values.containsKey("camera_dewarp_v3_reverse_front_1_fov"));

        TestSharedPreferences target = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(
                        CameraSettingsTransfer.exportCameraPreset(source)));
        assertTrue(ReverseCameraController.loadCentralFrontIntegrated(target));
        assertEquals(33, ReverseCameraController.loadFrontRawLayout(target)
                .pane(CENTRAL).rotationDegrees);
        assertEquals(148, CameraDewarpConfig.loadForReverseFront(target, CENTRAL).fovDegrees);

        // A preset exported before central Front existed has none of the
        // central fields.  It must still import and materialize neutral
        // defaults rather than failing validation or inheriting Rear state.
        JSONObject legacyRoot = new JSONObject(CameraSettingsTransfer.exportCameraPreset(source));
        JSONObject legacySettings = legacyRoot.getJSONObject("settings");
        Set<String> centralKeys = new LinkedHashSet<>();
        Iterator<String> keys = legacySettings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("reverse_camera_front_1_")
                    || key.startsWith("camera_dewarp_v3_reverse_front_1_")) {
                centralKeys.add(key);
            }
        }
        assertEquals(15, centralKeys.size());
        for (String key : centralKeys) legacySettings.remove(key);

        Map<String, Object> legacyParsed = CameraSettingsTransfer.parseCameraPreset(
                legacyRoot.toString());
        TestSharedPreferences legacy = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(legacy, legacyParsed);
        assertFalse(ReverseCameraController.loadCentralFrontIntegrated(legacy));
        assertFalse(CameraCalibrationPreset.hasReverseFront(legacy, CENTRAL));
        ReverseCameraLayout.Pane neutral = ReverseCameraController
                .loadFrontRawLayout(legacy).pane(CENTRAL);
        assertEquals(0.0f, neutral.sourceCrop.left, EPSILON);
        assertEquals(0.0f, neutral.sourceCrop.top, EPSILON);
        assertEquals(1.0f, neutral.sourceCrop.width, EPSILON);
        assertEquals(1.0f, neutral.sourceCrop.height, EPSILON);
        CameraDewarpConfig neutralDewarp = CameraDewarpConfig
                .loadForReverseFront(legacy, CENTRAL);
        assertFalse(neutralDewarp.enabled);
        assertEquals(CameraDewarpConfig.LENS_FRONT, neutralDewarp.lens);
    }
}
