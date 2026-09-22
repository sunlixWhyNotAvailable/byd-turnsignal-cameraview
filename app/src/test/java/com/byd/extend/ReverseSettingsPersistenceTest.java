package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ReverseSettingsPersistenceTest {
    @Test
    public void everyRearPaneOutputTransformChangesOnlyItsSelectedField() {
        for (int cameraIndex = ReverseCameraLayout.REAR_CAMERA_INDEX;
                cameraIndex <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
                cameraIndex++) {
            TestSharedPreferences settings = seededSettings();
            ReverseCameraLayout.Pane pane = ReverseCameraController
                    .loadRawLayout(settings).pane(cameraIndex);
            Map<String, ?> before = settings.getAll();
            int rotation = 40 + cameraIndex;
            ReverseCameraController.saveRearPaneTransform(settings, cameraIndex,
                    rotation, pane.displayMode, pane.mirrorHorizontally);
            assertOnlyChanged(before, settings,
                    "reverse_camera_" + cameraIndex + "_rotation_degrees", rotation);

            settings = seededSettings();
            pane = ReverseCameraController.loadRawLayout(settings).pane(cameraIndex);
            before = settings.getAll();
            ReverseCameraController.saveRearPaneTransform(settings, cameraIndex,
                    pane.rotationDegrees, ReverseCameraLayout.DISPLAY_MODE_STRETCH,
                    pane.mirrorHorizontally);
            assertOnlyChanged(before, settings,
                    ReverseCameraController.displayModeKey(cameraIndex),
                    ReverseCameraLayout.DISPLAY_MODE_STRETCH);

            settings = seededSettings();
            pane = ReverseCameraController.loadRawLayout(settings).pane(cameraIndex);
            before = settings.getAll();
            ReverseCameraController.saveRearPaneTransform(settings, cameraIndex,
                    pane.rotationDegrees, pane.displayMode, true);
            assertOnlyChanged(before, settings,
                    ReverseCameraController.mirrorKey(cameraIndex), true);
        }
    }

    @Test
    public void compositionPersistenceChangesOnlyGeometryAndLayerOrder() {
        TestSharedPreferences settings = seededSettings();
        Map<String, Object> protectedBefore = withoutComposition(settings.getAll());
        ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(settings);
        layout = ReverseCameraLayout.withBackground(layout,
                ReverseCameraLayout.destination(0.04f, 0.05f, 0.90f, 0.88f));
        layout = ReverseCameraLayout.withWidget(layout,
                ReverseCameraLayout.widgetDestination(0.62f, 0.68f, 0.11f, 0.22f));
        layout = ReverseCameraLayout.move(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 0.04f, -0.03f);
        layout = ReverseCameraLayout.bringToFront(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);

        ReverseCameraController.saveCompositionLayout(settings, layout);

        assertEquals(protectedBefore, withoutComposition(settings.getAll()));
        ReverseCameraLayout restored = ReverseCameraController.loadRawLayout(settings);
        assertRect(layout.background, restored.background);
        assertRect(layout.widget, restored.widget);
        for (ReverseCameraLayout.Pane pane : layout.panes()) {
            assertRect(pane.destination, restored.pane(pane.cameraIndex).destination);
            assertEquals(pane.zOrder, restored.pane(pane.cameraIndex).zOrder);
        }
        assertEquals(17, settings.getInt("reverse_current_setting", -1));
        assertEquals(3, settings.getInt(
                "reverse_calibration_preset_v1_2_version", -1));
    }

    @Test
    public void explicitCalibrationWritesOnlyTheRequestedCropStage() {
        for (int cameraIndex = ReverseCameraLayout.REAR_CAMERA_INDEX;
                cameraIndex <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
                cameraIndex++) {
            TestSharedPreferences settings = seededSettings();
            Map<String, ?> before = settings.getAll();
            ReverseCameraLayout.Rect raw = ReverseCameraLayout.sourceCrop(
                    0.05f * cameraIndex, 0.04f * cameraIndex,
                    0.45f, 0.46f);
            ReverseCameraController.saveSourceCrop(settings, cameraIndex, raw, false);
            assertCropOnlyChanged(before, settings, cameraIndex, raw, false);
            assertRect(raw, ReverseCameraController.loadRawLayout(settings)
                    .pane(cameraIndex).sourceCrop);

            before = settings.getAll();
            ReverseCameraLayout.Rect corrected = ReverseCameraLayout.sourceCrop(
                    0.16f + 0.01f * cameraIndex, 0.17f + 0.01f * cameraIndex,
                    0.36f, 0.37f);
            ReverseCameraController.saveSourceCrop(
                    settings, cameraIndex, corrected, true);
            assertCropOnlyChanged(before, settings, cameraIndex, corrected, true);
            assertRect(raw, ReverseCameraController.loadRawLayout(settings)
                    .pane(cameraIndex).sourceCrop);
            assertRect(corrected, ReverseCameraController.loadCorrectedSourceCrop(
                    settings, cameraIndex, raw));
            assertEquals(3, settings.getInt(
                    "reverse_calibration_preset_v1_2_version", -1));
        }
    }

    @Test
    public void activityRoutesAllRearPersistenceThroughScopedWriters() throws Exception {
        Path source = Paths.get(
                "app/src/main/java/com/byd/extend/CameraProbeActivity.java");
        if (!Files.exists(source)) {
            source = Paths.get("src/main/java/com/byd/extend/CameraProbeActivity.java");
        }
        String activity = new String(
                Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertFalse(activity.contains("ReverseCameraController.saveLayout("));
    }

    private static TestSharedPreferences seededSettings() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraController.saveCompositionLayout(
                settings, ReverseCameraLayout.defaults());
        for (int cameraIndex = ReverseCameraLayout.REAR_CAMERA_INDEX;
                cameraIndex <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
                cameraIndex++) {
            ReverseCameraController.saveSourceCrop(settings, cameraIndex,
                    ReverseCameraLayout.sourceCrop(
                            0.01f * cameraIndex, 0.02f * cameraIndex,
                            0.50f + 0.01f * cameraIndex,
                            0.60f + 0.01f * cameraIndex), false);
            ReverseCameraController.saveSourceCrop(settings, cameraIndex,
                    ReverseCameraLayout.sourceCrop(
                            0.10f + 0.01f * cameraIndex,
                            0.20f + 0.01f * cameraIndex,
                            0.30f + 0.01f * cameraIndex,
                            0.40f + 0.01f * cameraIndex), true);
            ReverseCameraController.saveRearPaneTransform(settings, cameraIndex,
                    cameraIndex * 10, ReverseCameraLayout.DISPLAY_MODE_FILL, false);
            CameraDewarpConfig.saveForReverse(settings, cameraIndex,
                    CameraDewarpConfig.of(
                            CameraDewarpConfig.lensForReverseCamera(cameraIndex),
                            cameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                            100 + cameraIndex));
            ReverseCameraController.saveFrontSourceCrop(settings, cameraIndex,
                    ReverseCameraLayout.sourceCrop(
                            0.03f * cameraIndex, 0.04f * cameraIndex,
                            0.40f, 0.50f), false);
            ReverseCameraController.saveFrontSourceCrop(settings, cameraIndex,
                    ReverseCameraLayout.sourceCrop(
                            0.13f + 0.01f * cameraIndex,
                            0.14f + 0.01f * cameraIndex,
                            0.34f, 0.35f), true);
            ReverseCameraController.saveFrontPaneTransform(settings, cameraIndex,
                    -cameraIndex * 10, ReverseCameraLayout.DISPLAY_MODE_FIT, true);
        }
        settings.putBoolean(ReverseCameraController.PREF_BACKGROUND_VISIBLE, false);
        settings.putBoolean(ReverseCameraController.PREF_WIDGET_VISIBLE, true);
        settings.putBoolean(ReverseCameraController.visibilityKey(
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX), false);
        settings.putInt("reverse_current_setting", 17);
        settings.putInt("reverse_calibration_preset_v1_2_version", 3);
        settings.putFloat("reverse_calibration_preset_v1_2_raw_x", 0.27f);
        settings.putFloat("reverse_front_calibration_preset_v1_3_corrected_y", 0.38f);
        settings.putString("unrelated_camera_setting", "keep");
        return settings;
    }

    private static void assertOnlyChanged(
            Map<String, ?> before, TestSharedPreferences settings,
            String key, Object value) {
        Map<String, Object> expected = new HashMap<>();
        expected.putAll(before);
        expected.put(key, value);
        assertEquals(expected, settings.getAll());
    }

    private static void assertCropOnlyChanged(
            Map<String, ?> before, TestSharedPreferences settings,
            int cameraIndex, ReverseCameraLayout.Rect crop, boolean corrected) {
        Map<String, Object> expected = new HashMap<>();
        expected.putAll(before);
        String prefix = corrected
                ? "reverse_camera_" + cameraIndex + "_corrected_v3_crop_"
                : null;
        expected.put(corrected ? prefix + "left"
                        : ReverseCameraController.sourceCropKey(cameraIndex, "left", false),
                crop.left);
        expected.put(corrected ? prefix + "top"
                        : ReverseCameraController.sourceCropKey(cameraIndex, "top", false),
                crop.top);
        expected.put(corrected ? prefix + "width"
                        : ReverseCameraController.sourceCropKey(cameraIndex, "width", false),
                crop.width);
        expected.put(corrected ? prefix + "height"
                        : ReverseCameraController.sourceCropKey(cameraIndex, "height", false),
                crop.height);
        assertEquals(expected, settings.getAll());
    }

    private static Map<String, Object> withoutComposition(Map<String, ?> values) {
        Set<String> composition = compositionKeys();
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!composition.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static Set<String> compositionKeys() {
        Set<String> keys = new HashSet<>();
        addRectKeys(keys, "reverse_camera_background_");
        addRectKeys(keys, "reverse_camera_widget_");
        for (int cameraIndex = ReverseCameraLayout.REAR_CAMERA_INDEX;
                cameraIndex <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
                cameraIndex++) {
            addRectKeys(keys, "reverse_camera_" + cameraIndex + "_");
            keys.add("reverse_camera_z_" + (cameraIndex - 1));
        }
        return keys;
    }

    private static void addRectKeys(Set<String> keys, String prefix) {
        keys.add(prefix + "left");
        keys.add(prefix + "top");
        keys.add(prefix + "width");
        keys.add(prefix + "height");
    }

    private static void assertRect(
            ReverseCameraLayout.Rect expected, ReverseCameraLayout.Rect actual) {
        assertEquals(expected.left, actual.left, 0.0f);
        assertEquals(expected.top, actual.top, 0.0f);
        assertEquals(expected.width, actual.width, 0.0f);
        assertEquals(expected.height, actual.height, 0.0f);
    }

}
