package com.byd.extend;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public final class ReverseDisplayPlacementTest {
    private static final int[] ELEMENTS = {-1, -2, 1, 2, 3};
    private static final float EPSILON = 0.00001f;

    @Test public void editorClusterDragNeverOverwritesTabletPlacement() {
        for (int id : ELEMENTS) {
            ReverseCameraLayout before = ReverseCameraLayout.withTarget(configuredLayout(), id, 1);
            ReverseCameraLayout.Rect moved = ReverseCameraLayout.destination(.12f, .24f, .3f, .4f);
            assertTrue(ReverseCameraEditorView.belongsToDisplay(before, id, 1));
            assertFalse(ReverseCameraEditorView.belongsToDisplay(before, id, 0));
            ReverseCameraLayout after = ReverseCameraEditorView.applyPlacement(before, id, 1, moved);
            assertRect(moved, after.rectFor(id, 1));
            assertRect(before.rectFor(id, 0), after.rectFor(id, 0));
            for (int other : ELEMENTS) if (other != id) {
                assertRect(before.rectFor(other, 0), after.rectFor(other, 0));
                assertRect(before.rectFor(other, 1), after.rectFor(other, 1));
            }
        }
    }
    private static final CameraSettingsTransfer.GeometryResolver GEOMETRY = target ->
            target == CameraDisplayTarget.CLUSTER
                    ? new CameraSettingsTransfer.DisplayGeometry(1920, 720, 0, 0, 0)
                    : new CameraSettingsTransfer.DisplayGeometry(1280, 800, 0, 0, 0);

    @Test public void changingTargetAndGeometryPreservesOtherDisplayForEveryElement() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraLayout layout = configuredLayout();
        ReverseCameraController.saveCompositionLayout(settings, layout);
        ReverseCameraLayout loaded = ReverseCameraController.loadRawLayout(settings);
        assertPlacements(layout, loaded);
        for (int id : ELEMENTS) {
            ReverseCameraLayout changed = ReverseCameraLayout.withTarget(loaded, id, 0);
            changed = ReverseCameraLayout.withRect(changed, id, 0,
                    ReverseCameraLayout.destination(.95f, -1f, .4f, .4f));
            assertRect(layout.rectFor(id, 1), changed.rectFor(id, 1));
            assertEquals(.6f, changed.rectFor(id, 0).left, EPSILON);
            assertEquals(0f, changed.rectFor(id, 0).top, EPSILON);
            assertEquals(0, changed.targetFor(id));
            for (int other : ELEMENTS) if (other != id) {
                assertRect(loaded.rectFor(other, 0), changed.rectFor(other, 0));
                assertRect(loaded.rectFor(other, 1), changed.rectFor(other, 1));
                assertEquals(loaded.targetFor(other), changed.targetFor(other));
            }
        }
    }

    @Test public void missingClusterPlacementUsesCenteredDefaultsWithoutRewritingTablet() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraLayout custom = configuredLayout();
        ReverseCameraController.saveCompositionLayout(settings, custom);
        for (String key : settings.getAll().keySet()) {
            if (key.startsWith("reverse_camera_element_") || key.startsWith("reverse_camera_cluster_z_")) {
                settings.remove(key);
            }
        }
        Map<String, ?> before = settings.getAll();
        ReverseCameraLayout loaded = ReverseCameraController.loadRawLayout(settings);
        ReverseCameraLayout defaults = ReverseCameraLayout.defaults();
        for (int id : ELEMENTS) {
            assertRect(custom.rectFor(id, 0), loaded.rectFor(id, 0));
            ReverseCameraLayout.Rect cluster = loaded.rectFor(id, 1);
            assertEquals(defaults.rectFor(id, 0).width, cluster.width, EPSILON);
            assertEquals(defaults.rectFor(id, 0).height, cluster.height, EPSILON);
            assertEquals((1f - cluster.width) / 2f, cluster.left, EPSILON);
            assertEquals((1f - cluster.height) / 2f, cluster.top, EPSILON);
            assertEquals(0, loaded.targetFor(id));
        }
        assertEquals(before, settings.getAll());
        settings.putInt("reverse_camera_cluster_z_0", 99);
        loaded = ReverseCameraController.loadRawLayout(settings);
        for (int id : ELEMENTS) assertRect(custom.rectFor(id, 0), loaded.rectFor(id, 0));
    }

    @Test public void localPresetsRoundTripAllElementsAndOldSlotsKeepClusterState() {
        for (int id : ELEMENTS) {
            TestSharedPreferences settings = new TestSharedPreferences();
            ReverseCameraLayout saved = configuredLayout();
            ReverseCameraController.saveCompositionLayout(settings, saved);
            if (id < 0) CameraCalibrationPreset.saveReverseElement(settings, id);
            else CameraCalibrationPreset.saveReverse(settings, id);
            ReverseCameraController.saveCompositionLayout(settings, ReverseCameraLayout.defaults());
            assertTrue(id < 0 ? CameraCalibrationPreset.loadReverseElement(settings, id)
                    : CameraCalibrationPreset.loadReverse(settings, id));
            ReverseCameraLayout loaded = ReverseCameraController.loadRawLayout(settings);
            assertRect(saved.rectFor(id, 0), loaded.rectFor(id, 0));
            assertRect(saved.rectFor(id, 1), loaded.rectFor(id, 1));
            assertEquals(saved.targetFor(id), loaded.targetFor(id));

            for (String key : settings.getAll().keySet()) {
                if (key.startsWith("reverse_calibration_preset_v1_")
                        && (key.contains("cluster_destination_") || key.endsWith("display_target"))) {
                    settings.remove(key);
                }
            }
            ReverseCameraLayout current = ReverseCameraLayout.withRect(loaded, id, 1,
                    ReverseCameraLayout.destination(.1f, .2f, .2f, .3f));
            current = ReverseCameraLayout.withTarget(current, id, 0);
            ReverseCameraController.saveCompositionLayout(settings, current);
            assertTrue(id < 0 ? CameraCalibrationPreset.loadReverseElement(settings, id)
                    : CameraCalibrationPreset.loadReverse(settings, id));
            loaded = ReverseCameraController.loadRawLayout(settings);
            assertRect(current.rectFor(id, 1), loaded.rectFor(id, 1));
            assertEquals(0, loaded.targetFor(id));
        }
    }

    @Test public void oppositeCameraTransferMirrorsBothPlacementsAndCopiesTarget() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraLayout source = configuredLayout();
        source = ReverseCameraLayout.withTarget(source, 3, 0);
        ReverseCameraController.saveCompositionLayout(settings, source);
        assertTrue(CameraCalibrationPreset.mirrorReverse(settings, 2));
        ReverseCameraLayout result = ReverseCameraController.loadRawLayout(settings);
        assertEquals(1, result.targetFor(3));
        for (int display = 0; display <= 1; display++) {
            ReverseCameraLayout.Rect left = source.rectFor(2, display);
            ReverseCameraLayout.Rect right = result.rectFor(3, display);
            assertEquals(1f - left.left - left.width, right.left, EPSILON);
            assertEquals(left.top, right.top, EPSILON);
            assertEquals(left.width, right.width, EPSILON);
            assertEquals(left.height, right.height, EPSILON);
            assertRect(left, result.rectFor(2, display));
        }
    }

    @Test public void layerStepSkipsPanesOnTheOtherDisplay() {
        ReverseCameraLayout original = ReverseCameraLayout.withTarget(
                ReverseCameraLayout.defaults(), 2, 1);
        ReverseCameraLayout raised = ReverseCameraLayout.raise(original, 1, 0);
        assertTrue(raised.zOrderFor(1, 0) > raised.zOrderFor(3, 0));
        for (int id = 1; id <= 3; id++) {
            assertEquals(original.zOrderFor(id, 1), raised.zOrderFor(id, 1));
        }
        ReverseCameraLayout singleCluster = ReverseCameraLayout.raise(raised, 2, 1);
        for (int id = 1; id <= 3; id++) {
            assertEquals(raised.zOrderFor(id, 1), singleCluster.zOrderFor(id, 1));
        }
        ReverseCameraLayout reset = ReverseCameraLayout.resetLayer(raised, 1, 0);
        assertTrue(reset.zOrderFor(1, 0) < reset.zOrderFor(3, 0));
        for (int id = 1; id <= 3; id++) {
            assertEquals(raised.zOrderFor(id, 1), reset.zOrderFor(id, 1));
        }
    }

    @Test(timeout = 1000) public void resettingOnlyClusterPaneDoesNotWaitForUnavailableLayer() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraLayout layout = ReverseCameraLayout.withTarget(
                ReverseCameraLayout.defaults(), 2, 1);
        layout = ReverseCameraLayout.withClusterZOrders(layout, new int[]{1, 2, 0});
        ReverseCameraController.saveCompositionLayout(settings, layout);
        ReverseCameraController.resetSelectedLayout(settings, "RearLeft", 1);
        ReverseCameraLayout reset = ReverseCameraController.loadRawLayout(settings);
        assertEquals(2, reset.zOrderFor(2, 1));
        for (int id : ELEMENTS) assertRect(layout.rectFor(id, 0), reset.rectFor(id, 0));
    }

    @Test public void exportedPresetPreservesBothDisplaysAndCalibrationCopyDoesNotMoveThem() {
        TestSharedPreferences source = new TestSharedPreferences();
        ReverseCameraLayout expected = configuredLayout();
        ReverseCameraController.saveCompositionLayout(source, expected);
        TestSharedPreferences target = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(target, CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(source, GEOMETRY)), GEOMETRY);
        assertPlacements(expected, ReverseCameraController.loadRawLayout(target));
        assertTrue(CameraCalibrationPreset.copyCentralReverseRearToFront(target));
        assertPlacements(expected, ReverseCameraController.loadRawLayout(target));
        assertTrue(CameraCalibrationPreset.copyCentralReverseFrontToRear(target));
        assertPlacements(expected, ReverseCameraController.loadRawLayout(target));
    }

    @Test public void oldExportWithoutNewFieldsPreservesCurrentClusterAndHoldDurations() throws Exception {
        TestSharedPreferences target = new TestSharedPreferences();
        ReverseCameraLayout current = configuredLayout();
        ReverseCameraController.saveCompositionLayout(target, current);
        target.putInt(BlindSpotOverlayController.PREF_REAR_HOLD_DURATION_SECONDS, 1);
        target.putInt(BlindSpotOverlayController.PREF_FRONT_HOLD_DURATION_SECONDS, 5);
        org.json.JSONObject legacy = new org.json.JSONObject(CameraSettingsTransfer.exportCameraPreset(
                new TestSharedPreferences(), GEOMETRY));
        org.json.JSONObject values = legacy.getJSONObject("settings");
        java.util.List<String> removed = new java.util.ArrayList<>();
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("reverse_camera_element_") || key.startsWith("reverse_camera_cluster_z_")
                    || key.equals(BlindSpotOverlayController.PREF_REAR_HOLD_DURATION_SECONDS)
                    || key.equals(BlindSpotOverlayController.PREF_FRONT_HOLD_DURATION_SECONDS)) removed.add(key);
        }
        for (String key : removed) values.remove(key);
        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(legacy.toString()), GEOMETRY);
        ReverseCameraLayout loaded = ReverseCameraController.loadRawLayout(target);
        for (int id : ELEMENTS) {
            assertRect(current.rectFor(id, 1), loaded.rectFor(id, 1));
            assertEquals(current.targetFor(id), loaded.targetFor(id));
        }
        assertEquals(1, BlindSpotOverlayController.readHoldDurationSeconds(target, CameraProfile.REAR_LEFT));
        assertEquals(5, BlindSpotOverlayController.readHoldDurationSeconds(target, CameraProfile.FRONT_RIGHT));
    }

    @Test public void incompleteImportedDisplayGroupCannotPartiallyOverwriteSettings() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraController.saveCompositionLayout(settings, configuredLayout());
        Map<String, ?> before = settings.getAll();
        Map<String, Object> parsed = new java.util.HashMap<>(CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(settings, GEOMETRY)));
        ((Map<?, ?>) parsed.get("settings")).remove(
                ReverseCameraController.clusterGeometryPrefix(2) + "width");
        try {
            CameraSettingsTransfer.applyCameraPreset(settings, parsed, GEOMETRY);
            fail("Partial Cluster geometry must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(before, settings.getAll());
        }
    }

    private static ReverseCameraLayout configuredLayout() {
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        for (int i = 0; i < ELEMENTS.length; i++) {
            int id = ELEMENTS[i];
            layout = ReverseCameraLayout.withRect(layout, id, 0,
                    ReverseCameraLayout.destination(.05f * i, .04f * i, .3f, .4f));
            layout = ReverseCameraLayout.withRect(layout, id, 1,
                    ReverseCameraLayout.destination(.04f * i, .03f * i, .2f, .3f));
            layout = ReverseCameraLayout.withTarget(layout, id, 1);
        }
        return ReverseCameraLayout.withClusterZOrders(layout, new int[]{2, 0, 1});
    }

    private static void assertPlacements(ReverseCameraLayout expected, ReverseCameraLayout actual) {
        for (int id : ELEMENTS) {
            assertRect(expected.rectFor(id, 0), actual.rectFor(id, 0));
            assertRect(expected.rectFor(id, 1), actual.rectFor(id, 1));
            assertEquals("target for " + id, expected.targetFor(id), actual.targetFor(id));
            if (id > 0) for (int display = 0; display <= 1; display++) {
                assertEquals(expected.zOrderFor(id, display), actual.zOrderFor(id, display));
            }
        }
    }

    private static void assertRect(ReverseCameraLayout.Rect expected, ReverseCameraLayout.Rect actual) {
        assertArrayEquals(new float[]{expected.left, expected.top, expected.width, expected.height},
                new float[]{actual.left, actual.top, actual.width, actual.height}, EPSILON);
    }
}
