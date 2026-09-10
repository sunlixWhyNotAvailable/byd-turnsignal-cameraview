package com.byd.extend;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CameraSettingsTransferTest {
    private static final CameraSettingsTransfer.GeometryResolver GEOMETRY = target ->
            target == CameraDisplayTarget.CLUSTER
                    ? new CameraSettingsTransfer.DisplayGeometry(1920, 720, 0, 0, 0)
                    : new CameraSettingsTransfer.DisplayGeometry(1280, 800, 12, 30, 70);

    @Test
    public void defaultsRoundTripMaterializesEveryCameraScope() {
        TestSharedPreferences source = new TestSharedPreferences();
        String json = CameraSettingsTransfer.exportCameraPreset(source);
        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(json);
        TestSharedPreferences destination = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(destination, parsed);

        Map<?, ?> settings = (Map<?, ?>) parsed.get("settings");
        // 4 Blind profiles (20 values each), 8 Parking profiles (20 each),
        // Three Reverse panes plus the optional central-front calibration
        // fields, and shared/background/front values.
        // v2 adds the active independent Mirror group; its optional front
        // source state/calibration contributes another 16 fields. Blind
        // width/height remain optional without an explicitly saved placement.
        assertEquals(408, settings.size());
        for (CameraProfile profile : CameraProfile.values()) {
            assertTrue(settings.containsKey(BlindSpotOverlayController.positionKey(profile, false)));
            assertTrue(settings.containsKey(BlindSpotOverlayController.positionKey(profile, true)));
            assertTrue(settings.containsKey(BlindSpotOverlayController.scaleKey(profile)));
            assertTrue(settings.containsKey(BlindSpotOverlayController.targetKey(profile)));
            assertTrue(settings.containsKey(BlindSpotOverlayController.frameAspectKey(profile)));
            assertEquals(DirectCameraCrop.load(source, profile).left,
                    DirectCameraCrop.load(destination, profile).left, 0.0f);
            assertEquals(CameraDewarpConfig.loadForProfile(source, profile).fovDegrees,
                    CameraDewarpConfig.loadForProfile(destination, profile).fovDegrees);
        }
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            String prefix = "parking_camera_"
                    + profile.wireName.toLowerCase(java.util.Locale.US) + "_";
            assertTrue(settings.containsKey(prefix + "enabled"));
            assertTrue(settings.containsKey(prefix + "scale"));
            assertTrue(settings.containsKey(prefix + "x"));
            assertTrue(settings.containsKey(prefix + "y"));
            assertEquals(DirectCameraCrop.load(source, profile).width,
                    DirectCameraCrop.load(destination, profile).width, 0.0f);
            assertEquals(CameraDewarpConfig.loadForParking(source, profile).projection,
                    CameraDewarpConfig.loadForParking(destination, profile).projection);
        }
        Map<String, Object> reparsed = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(destination));
        assertEquivalentSettings((Map<?, ?>) parsed.get("settings"),
                (Map<?, ?>) reparsed.get("settings"));
    }

    @Test
    public void correctedFreeMarkerRoundTripsAndOldPresetClearsStaleMarker() {
        TestSharedPreferences source = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        DirectCameraCrop.saveCorrectedGeometryEdit(source, profile,
                DirectCameraCrop.requireUiGeometry(
                        0.21f, 0.18f, 0.37f, 0.41f, 11, CameraRotation.MODE_ALIGNED));
        String json = CameraSettingsTransfer.exportCameraPreset(source);
        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(json);
        Map<?, ?> values = (Map<?, ?>) parsed.get("settings");
        assertEquals(DirectCameraCrop.ASPECT_FREE,
                values.get(DirectCameraCrop.correctedAspectKey(profile)));

        TestSharedPreferences target = new TestSharedPreferences();
        target.putInt(DirectCameraCrop.correctedAspectKey(profile),
                DirectCameraCrop.ASPECT_FREE);
        CameraSettingsTransfer.applyCameraPreset(target, parsed);
        assertEquals(DirectCameraCrop.ASPECT_FREE,
                target.getInt(DirectCameraCrop.correctedAspectKey(profile), -1));

        TestSharedPreferences old = new TestSharedPreferences();
        String oldJson = CameraSettingsTransfer.exportCameraPreset(old);
        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(oldJson));
        assertFalse(target.contains(DirectCameraCrop.correctedAspectKey(profile)));
    }

    @Test
    public void cameraPresetScopeKeepsTriggerAndUnrelatedKeys() {
        TestSharedPreferences source = new TestSharedPreferences();
        source.putInt(BlindSpotOverlayController.PREF_LEFT_SCALE, 47);
        source.putFloat(DirectCameraCrop.PREF_LEFT_X, 0.1234567f);
        source.putFloat(BlindSpotOverlayController.PREF_FRONT_RIGHT_X, 1.0f);
        source.putFloat(BlindSpotOverlayController.PREF_FRONT_RIGHT_Y, 0.0f);
        source.putInt(BlindSpotOverlayController.PREF_MIN_SPEED, 77);
        source.putFloat("outward_deg", 123.0f);
        source.putString("unrelated", "keep");
        String json = CameraSettingsTransfer.exportCameraPreset(source);

        TestSharedPreferences target = new TestSharedPreferences();
        target.putInt(BlindSpotOverlayController.PREF_LEFT_SCALE, 5);
        target.putInt(BlindSpotOverlayController.PREF_MIN_SPEED, 19);
        target.putString("unrelated", "keep");
        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(json));
        assertEquals(47, target.getInt(BlindSpotOverlayController.PREF_LEFT_SCALE, -1));
        assertEquals(19, target.getInt(BlindSpotOverlayController.PREF_MIN_SPEED, -1));
        assertEquals("keep", target.getString("unrelated", ""));
        assertEquals(0.1234567f, target.getFloat(DirectCameraCrop.PREF_LEFT_X, -1), 0.000001f);
        assertEquals(1.0f, target.getFloat(BlindSpotOverlayController.PREF_FRONT_RIGHT_X, -1), 0.0f);
        assertEquals(0.0f, target.getFloat(BlindSpotOverlayController.PREF_FRONT_RIGHT_Y, -1), 0.0f);
    }

    @Test
    public void learnedReverseButtonStaysOutsideCameraPresetAndLegacyImportScope() {
        String key = ReverseSteeringButtonPreferences.KEY_CODE;
        TestSharedPreferences source = new TestSharedPreferences();
        source.putInt(key, 87);
        Map<String, Object> preset = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(source));
        assertEquals(2, preset.get("version"));
        assertFalse(((Map<?, ?>) preset.get("settings")).containsKey(key));

        TestSharedPreferences target = new TestSharedPreferences();
        target.putInt(key, 88);
        CameraSettingsTransfer.applyCameraPreset(target, preset);
        assertEquals(88, ReverseSteeringButtonPreferences.load(target));

        Map<String, Object> legacy = CameraSettingsTransfer.parseLegacySettings(
                "<map><int name=\"" + key + "\" value=\"87\"/>"
                        + "<boolean name=\"guard_enabled\" value=\"true\"/></map>");
        assertFalse(legacy.containsKey(key));
        CameraSettingsTransfer.applyLegacySettings(target, legacy);
        assertEquals(88, ReverseSteeringButtonPreferences.load(target));
        assertTrue(target.getBoolean("guard_enabled", false));
    }

    @Test
    public void legacyV1WithoutMirrorPreservesIndependentMirrorState() throws Exception {
        org.json.JSONObject preset = new org.json.JSONObject(
                CameraSettingsTransfer.exportCameraPreset(new TestSharedPreferences()));
        preset.put("version", 1);
        org.json.JSONObject settings = preset.getJSONObject("settings");
        java.util.ArrayList<String> mirrorKeys = new java.util.ArrayList<>();
        java.util.Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("mirror_")) mirrorKeys.add(key);
        }
        for (String key : mirrorKeys) settings.remove(key);

        TestSharedPreferences target = new TestSharedPreferences();
        target.putBoolean(RearviewMirrorSettings.PREF_ENABLED, true);
        target.putString(RearviewMirrorSettings.PREF_TARGET, "Cluster");
        target.putFloat(RearviewMirrorSettings.PREF_X, 12.0f);
        CameraPlacement tablet = CameraPlacement.of(0.11f, 0.12f, 0.23f, 0.24f);
        CameraPlacement cluster = CameraPlacement.of(0.31f, 0.32f, 0.25f, 0.26f);
        seedMirrorPlacement(target, CameraDisplayTarget.TABLET, tablet);
        seedMirrorPlacement(target, CameraDisplayTarget.CLUSTER, cluster);
        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(preset.toString()));

        assertTrue(target.getBoolean(RearviewMirrorSettings.PREF_ENABLED, false));
        assertEquals("Cluster", target.getString(RearviewMirrorSettings.PREF_TARGET, ""));
        assertEquals(12.0f, target.getFloat(RearviewMirrorSettings.PREF_X, -1.0f), 0.0f);
        assertPlacement(tablet,
                RearviewMirrorSettings.placement(target, CameraDisplayTarget.TABLET));
        assertPlacement(cluster,
                RearviewMirrorSettings.placement(target, CameraDisplayTarget.CLUSTER));
    }

    @Test
    public void olderPresetWithoutPanoramaOptionsPreservesCurrentValues() throws Exception {
        org.json.JSONObject preset = new org.json.JSONObject(
                CameraSettingsTransfer.exportCameraPreset(new TestSharedPreferences()));
        org.json.JSONObject values = preset.getJSONObject("settings");
        values.remove(BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA);
        values.remove(BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA);
        values.remove(RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA);

        TestSharedPreferences target = new TestSharedPreferences();
        target.putBoolean(
                BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, false);
        target.putBoolean(
                BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA, false);
        target.putBoolean(RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA, false);
        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(preset.toString()));

        assertFalse(BlindSpotOverlayController.readPanoramaSuppression(target, false));
        assertFalse(BlindSpotOverlayController.readPanoramaSuppression(target, true));
        assertFalse(RearviewMirrorSettings.suppressWhilePanorama(target));

        TestSharedPreferences explicit = new TestSharedPreferences();
        explicit.putBoolean(
                BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, false);
        explicit.putBoolean(
                BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA, false);
        explicit.putBoolean(RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA, false);
        TestSharedPreferences replaced = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(replaced,
                CameraSettingsTransfer.parseCameraPreset(
                        CameraSettingsTransfer.exportCameraPreset(explicit)));
        assertFalse(BlindSpotOverlayController.readPanoramaSuppression(replaced, false));
        assertFalse(BlindSpotOverlayController.readPanoramaSuppression(replaced, true));
        assertFalse(RearviewMirrorSettings.suppressWhilePanorama(replaced));
    }

    @Test
    public void importsPreserveLocalMirrorHideAndPreset() {
        String activePreset = CameraSettingsTransfer.exportCameraPreset(
                new TestSharedPreferences());
        TestSharedPreferences target = new TestSharedPreferences();
        target.putBoolean(RearviewMirrorSettings.PREF_MANUAL_HIDDEN, true);
        RearviewMirrorSettings.writePreset(target, new RearviewMirrorSettings.Calibration(
                CameraPlacement.of(0.10f, 0.10f, 0.40f, 0.40f),
                CameraPlacement.of(0.15f, 0.15f, 0.35f, 0.35f),
                true, 125, CameraDewarpConfig.PROJECTION_RECTILINEAR,
                false, 0, CameraRotation.MODE_FIT));
        RearviewMirrorSettings.writePreset(target, true,
                RearviewMirrorSettings.defaultCalibration(true));

        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(activePreset));

        assertTrue(target.getBoolean(RearviewMirrorSettings.PREF_MANUAL_HIDDEN, false));
        assertTrue(RearviewMirrorSettings.preset(target, false) != null);
        assertTrue(RearviewMirrorSettings.preset(target, true) != null);
    }

    @Test
    public void frontMirrorExportRoundTripsAndExcludesLocalState() {
        TestSharedPreferences source = new TestSharedPreferences();
        RearviewMirrorSettings.Calibration front = new RearviewMirrorSettings.Calibration(
                CameraPlacement.of(.1f, .2f, .7f, .6f),
                CameraPlacement.of(.2f, .1f, .6f, .7f), true, 145, 1,
                true, 30, CameraRotation.MODE_ALIGNED);
        RearviewMirrorSettings.writeCalibration(source, true, front);
        RearviewMirrorSettings.writePreset(source, true, front);
        RearviewMirrorSettings.writeSourceState(
                (android.content.SharedPreferences) source, true, true);
        source.putBoolean(RearviewMirrorSettings.PREF_MANUAL_HIDDEN, true);
        source.putInt("mirror_source_steering_key_code", 88);
        source.putInt("mirror_visibility_steering_key_code", 87);

        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(source));
        @SuppressWarnings("unchecked") Map<String, Object> values =
                (Map<String, Object>) parsed.get("settings");
        assertEquals(true, values.get(RearviewMirrorSettings.PREF_FRONT_INTEGRATED));
        assertEquals(true, values.get(RearviewMirrorSettings.PREF_SHOW_FRONT));
        assertEquals(10f, number(values, "mirror_front_original_x"), 0f);
        assertEquals(70f, number(values, "mirror_front_original_width"), 0f);
        assertFalse(values.containsKey(RearviewMirrorSettings.PREF_FRONT_PRESET_PRESENT));
        assertFalse(values.containsKey("mirror_front_preset_x"));
        assertFalse(values.containsKey(RearviewMirrorSettings.PREF_MANUAL_HIDDEN));
        assertFalse(values.containsKey("mirror_source_steering_key_code"));
        assertFalse(values.containsKey("mirror_visibility_steering_key_code"));

        TestSharedPreferences target = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(target, parsed);
        RearviewMirrorSettings.Settings imported = new RearviewMirrorSettings(target).load();
        assertTrue(imported.activeFront());
        assertEquals(front.raw, imported.frontCalibration.raw);
        assertEquals(front.corrected, imported.frontCalibration.corrected);
        assertTrue(imported.frontCalibration.mirrored);
    }

    @Test
    public void v1V2V3WithoutFrontMirrorFieldsPreserveSavedFrontState() throws Exception {
        org.json.JSONObject template = new org.json.JSONObject(
                CameraSettingsTransfer.exportCameraPreset(new TestSharedPreferences(), GEOMETRY));
        java.util.ArrayList<String> frontKeys = new java.util.ArrayList<>();
        java.util.Iterator<String> keys = template.getJSONObject("settings").keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("mirror_front_") || RearviewMirrorSettings.PREF_SHOW_FRONT.equals(key)) {
                frontKeys.add(key);
            }
        }
        for (String key : frontKeys) template.getJSONObject("settings").remove(key);

        RearviewMirrorSettings.Calibration front = new RearviewMirrorSettings.Calibration(
                CameraPlacement.of(.1f, .2f, .7f, .6f),
                CameraPlacement.of(.2f, .1f, .6f, .7f), true, 145, 1,
                true, 30, CameraRotation.MODE_ALIGNED);
        for (int version : new int[]{1, 2, 3}) {
            org.json.JSONObject old = new org.json.JSONObject(template.toString());
            old.put("version", version);
            TestSharedPreferences target = new TestSharedPreferences();
            RearviewMirrorSettings.writeCalibration(target, true, front);
            RearviewMirrorSettings.writeSourceState(
                    (android.content.SharedPreferences) target, true, true);

            CameraSettingsTransfer.applyCameraPreset(target,
                    CameraSettingsTransfer.parseCameraPreset(old.toString()), GEOMETRY);

            RearviewMirrorSettings.Settings saved = new RearviewMirrorSettings(target).load();
            assertTrue(saved.activeFront());
            assertEquals(front.raw, saved.frontCalibration.raw);
            assertEquals(front.corrected, saved.frontCalibration.corrected);
        }
    }

    @Test
    public void invalidJsonIsRejectedBeforeMutation() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putInt(BlindSpotOverlayController.PREF_LEFT_SCALE, 44);
        String valid = CameraSettingsTransfer.exportCameraPreset(preferences);
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.parseCameraPreset(valid.replace("\"version\":2", "\"version\":3")));
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.parseCameraPreset(valid.replace("\"version\":2", "\"version\":1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.parseCameraPreset(valid.replace("\"version\":2", "\"version\":\"2\"")));
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.parseCameraPreset(valid.replace("\"camera_enabled\":false", "\"camera_enabled\":NaN")));
        assertEquals(before, preferences.getAll());
    }

    @Test
    public void legacyXmlImportsAllowlistedSettingsAndRejectsEntity() {
        String xml = "<?xml version=\"1.0\"?><map>"
                + "<boolean name=\"guard_enabled\" value=\"true\"/>"
                + "<float name=\"outward_deg\" value=\"120\"/>"
                + "<int name=\"camera_min_speed_kph\" value=\"55\"/>"
                + "<float name=\"direct_crop_left_x\" value=\"0.1234567\"/>"
                + "<string name=\"helper_apk_marker\">secret</string>"
                + "<int name=\"selected_tab\" value=\"3\"/>"
                + "<boolean name=\"reverse_camera_parking_guidelines\" value=\"true\"/>"
                + "<string name=\"unknown\">ignored</string></map>";
        Map<String, Object> parsed = CameraSettingsTransfer.parseLegacySettings(xml);
        assertEquals(Boolean.TRUE, parsed.get("guard_enabled"));
        assertEquals(120.0f, parsed.get("outward_deg"));
        assertFalse(parsed.containsKey("helper_apk_marker"));
        assertFalse(parsed.containsKey("selected_tab"));
        assertFalse(parsed.containsKey("reverse_camera_parking_guidelines"));
        assertFalse(parsed.containsKey("unknown"));

        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putString("unrelated", "keep");
        CameraSettingsTransfer.applyLegacySettings(preferences, parsed);
        assertTrue(preferences.getBoolean("guard_enabled", false));
        assertEquals(55, preferences.getInt(BlindSpotOverlayController.PREF_MIN_SPEED, -1));
        assertEquals("keep", preferences.getString("unrelated", ""));

        String entity = "<!DOCTYPE map [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<map><string name=\"outward_deg\">&xxe;</string></map>";
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.parseLegacySettings(entity));
    }

    @Test
    public void legacyXmlKeepsCalibrationSlotTypesAndSkipsMigrationMarkers() {
        String xml = "<map>"
                + "<int name=\"camera_calibration_preset_v1_rear_left_version\" value=\"1\"/>"
                + "<int name=\"camera_calibration_preset_v1_rear_left_raw_mode\" value=\"2\"/>"
                + "<boolean name=\"camera_calibration_preset_v1_rear_left_correction\" value=\"true\"/>"
                + "<int name=\"camera_dewarp_left_zoom\" value=\"115\"/>"
                + "<int name=\"camera_dewarp_left_center_y\" value=\"50\"/>"
                + "<int name=\"camera_overlay_scale_percent\" value=\"30\"/>"
                + "<float name=\"camera_left_frame_aspect\" value=\"4.5\"/>"
                + "<boolean name=\"direct_crop_dewarp_v2_rear_left_seeded\" value=\"true\"/>"
                + "</map>";
        Map<String, Object> parsed = CameraSettingsTransfer.parseLegacySettings(xml);
        assertEquals(1, parsed.get("camera_calibration_preset_v1_rear_left_version"));
        assertEquals(2, parsed.get("camera_calibration_preset_v1_rear_left_raw_mode"));
        assertEquals(Boolean.TRUE,
                parsed.get("camera_calibration_preset_v1_rear_left_correction"));
        assertEquals(115, parsed.get("camera_dewarp_left_zoom"));
        assertEquals(50, parsed.get("camera_dewarp_left_center_y"));
        assertEquals(30, parsed.get("camera_overlay_scale_percent"));
        assertEquals(4.5f, parsed.get("camera_left_frame_aspect"));
        assertFalse(parsed.containsKey("direct_crop_dewarp_v2_rear_left_seeded"));
    }

    @Test
    public void invalidScaleAndReverseStackingAreRejectedBeforeMutation() throws Exception {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putInt(BlindSpotOverlayController.PREF_LEFT_SCALE, 44);
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        String valid = CameraSettingsTransfer.exportCameraPreset(new TestSharedPreferences());
        for (int scale : new int[]{4, 61, 999}) {
            org.json.JSONObject preset = new org.json.JSONObject(valid);
            preset.getJSONObject("settings").put(BlindSpotOverlayController.PREF_LEFT_SCALE, scale);
            assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.applyCameraPreset(
                    preferences, CameraSettingsTransfer.parseCameraPreset(preset.toString())));
            String xml = "<map><int name=\"camera_left_scale_percent\" value=\"" + scale + "\"/></map>";
            assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.applyLegacySettings(
                    preferences, CameraSettingsTransfer.parseLegacySettings(xml)));
        }
        for (String xml : new String[]{
                "<map><int name=\"reverse_camera_z_0\" value=\"99\"/></map>",
                "<map><int name=\"reverse_camera_z_0\" value=\"2\"/>"
                        + "<int name=\"reverse_camera_z_1\" value=\"2\"/></map>"}) {
            assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.applyLegacySettings(
                    preferences, CameraSettingsTransfer.parseLegacySettings(xml)));
        }
        assertEquals(before, preferences.getAll());
        assertEquals(3, CameraSettingsTransfer.parseLegacySettings(
                "<map><int name=\"reverse_camera_z_0\" value=\"3\"/>"
                        + "<int name=\"reverse_camera_z_1\" value=\"1\"/>"
                        + "<int name=\"reverse_camera_z_2\" value=\"2\"/></map>")
                .get("reverse_camera_z_0"));
    }

    @Test
    public void malformedLegacyValueNeverChangesDestination() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putBoolean("guard_enabled", false);
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        String malformed = "<map><boolean name=\"guard_enabled\" value=\"maybe\"/></map>";
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.applyLegacySettings(preferences,
                        CameraSettingsTransfer.parseLegacySettings(malformed)));
        assertEquals(before, preferences.getAll());
    }

    private static void assertEquivalentSettings(Map<?, ?> expected, Map<?, ?> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (Object key : expected.keySet()) {
            Object left = expected.get(key);
            Object right = actual.get(key);
            if (left instanceof Number && right instanceof Number) {
                assertEquals(((Number) left).doubleValue(), ((Number) right).doubleValue(), 0.000001);
            } else {
                assertEquals(left, right);
            }
        }
    }

    @Test
    public void importsWithoutAspectPinPriorDestinationAndExplicitAspectWins() throws Exception {
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        String key = BlindSpotOverlayController.frameAspectKey(profile);
        for (boolean legacy : new boolean[]{false, true}) {
            for (boolean explicit : new boolean[]{false, true}) {
                TestSharedPreferences target = new TestSharedPreferences();
                android.content.SharedPreferences.Editor seed = target.edit();
                DirectCameraCrop.write(seed, profile, DirectCameraCrop.of(
                        0.1f, 0.1f, 0.4f, 0.2f, DirectCameraCrop.ASPECT_FREE));
                seed.apply();
                float prior = DirectCameraCrop.load(target, profile).outputAspect();
                int transactions = target.transactions;
                if (legacy) {
                    Map<String, Object> values = new HashMap<>();
                    values.put(DirectCameraCrop.preferenceKey(profile, 2), 0.5f);
                    if (explicit) values.put(key, 2.25f);
                    CameraSettingsTransfer.applyLegacySettings(target, values);
                } else {
                    org.json.JSONObject preset = new org.json.JSONObject(
                            CameraSettingsTransfer.exportCameraPreset(new TestSharedPreferences()));
                    if (explicit) preset.getJSONObject("settings").put(key, 2.25f);
                    else preset.getJSONObject("settings").remove(key);
                    CameraSettingsTransfer.applyCameraPreset(target,
                            CameraSettingsTransfer.parseCameraPreset(preset.toString()));
                }
                assertEquals(transactions + 1, target.transactions);
                assertEquals(explicit ? 2.25f : prior, target.getFloat(key, -1), 0.00001f);
            }
        }
    }

    @Test
    public void invalidImportedFrameAspectCannotClearOrWritePreferences() throws Exception {
        TestSharedPreferences target = new TestSharedPreferences();
        String key = BlindSpotOverlayController.frameAspectKey(
                CameraProfile.of(CameraProfile.REAR_LEFT));
        target.putFloat(key, 1.65f);
        Map<String, ?> before = target.getAll();
        for (float invalid : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            Map<String, Object> legacy = new HashMap<>();
            legacy.put(key, invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> CameraSettingsTransfer.applyLegacySettings(target, legacy));
            Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(
                    CameraSettingsTransfer.exportCameraPreset(new TestSharedPreferences()));
            @SuppressWarnings("unchecked") Map<String, Object> values =
                    (Map<String, Object>) parsed.get("settings");
            values.put(key, invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> CameraSettingsTransfer.applyCameraPreset(target, parsed));
            assertEquals(before, target.getAll());
            assertEquals(0, target.transactions);
        }
    }

    @Test
    public void v4ExportsBothDisplayPlacementsForMirrorAndEveryBlindProfile() {
        TestSharedPreferences source = new TestSharedPreferences();
        for (CameraProfile profile : CameraProfile.values()) {
            seedBlindPlacement(source, profile, CameraDisplayTarget.TABLET,
                    CameraPlacement.of(0.10f, 0.11f, 0.20f, 0.21f));
            seedBlindPlacement(source, profile, CameraDisplayTarget.CLUSTER,
                    CameraPlacement.of(0.50f, 0.51f, 0.22f, 0.23f));
        }
        seedMirrorPlacement(source, CameraDisplayTarget.TABLET,
                CameraPlacement.of(0.12f, 0.13f, 0.24f, 0.25f));
        seedMirrorPlacement(source, CameraDisplayTarget.CLUSTER,
                CameraPlacement.of(0.52f, 0.53f, 0.26f, 0.27f));

        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(source, GEOMETRY));
        assertEquals(4, parsed.get("version"));
        @SuppressWarnings("unchecked") Map<String, Object> values =
                (Map<String, Object>) parsed.get("settings");
        for (CameraProfile profile : CameraProfile.values()) {
            assertEquals(0.10f, number(values, BlindSpotOverlayController.placementKey(
                    profile, CameraDisplayTarget.TABLET,
                    BlindSpotOverlayController.PLACEMENT_X)), 0.0f);
            assertEquals(0.50f, number(values, BlindSpotOverlayController.placementKey(
                    profile, CameraDisplayTarget.CLUSTER,
                    BlindSpotOverlayController.PLACEMENT_X)), 0.0f);
        }
        assertEquals(12.0f, number(values, RearviewMirrorSettings.placementKey(
                CameraDisplayTarget.TABLET, RearviewMirrorSettings.PLACEMENT_X)), 0.0f);
        assertEquals(52.0f, number(values, RearviewMirrorSettings.placementKey(
                CameraDisplayTarget.CLUSTER, RearviewMirrorSettings.PLACEMENT_X)), 0.0f);

        TestSharedPreferences destination = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(destination, parsed, GEOMETRY);
        for (CameraProfile profile : CameraProfile.values()) {
            assertPlacement(CameraPlacement.of(0.10f, 0.11f, 0.20f, 0.21f),
                    BlindSpotOverlayController.readPlacement(destination, profile,
                            CameraDisplayTarget.TABLET, 1280, 800, 12, 30, 70));
            assertPlacement(CameraPlacement.of(0.50f, 0.51f, 0.22f, 0.23f),
                    BlindSpotOverlayController.readPlacement(destination, profile,
                            CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
        }
        assertPlacement(CameraPlacement.of(0.12f, 0.13f, 0.24f, 0.25f),
                RearviewMirrorSettings.placement(destination, CameraDisplayTarget.TABLET));
        assertPlacement(CameraPlacement.of(0.52f, 0.53f, 0.26f, 0.27f),
                RearviewMirrorSettings.placement(destination, CameraDisplayTarget.CLUSTER));
        assertEquals(1, destination.transactions);
    }

    @Test
    public void contextlessV2ExportUsesTheSelectedExplicitBlindSlot() {
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_RIGHT);
        TestSharedPreferences source = new TestSharedPreferences();
        source.putInt(BlindSpotOverlayController.targetKey(profile),
                CameraDisplayTarget.CLUSTER);
        seedBlindPlacement(source, profile, CameraDisplayTarget.CLUSTER,
                CameraPlacement.of(0.41f, 0.42f, 0.23f, 0.24f));

        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(source));
        assertEquals(2, parsed.get("version"));
        @SuppressWarnings("unchecked") Map<String, Object> values =
                (Map<String, Object>) parsed.get("settings");
        assertEquals(0.41f, number(values,
                BlindSpotOverlayController.positionKey(profile, false)), 0.0f);
        assertEquals(0.42f, number(values,
                BlindSpotOverlayController.positionKey(profile, true)), 0.0f);
        assertEquals(0.23f, number(values,
                BlindSpotOverlayController.placementWidthKey(profile)), 0.0f);
        assertEquals(0.24f, number(values,
                BlindSpotOverlayController.placementHeightKey(profile)), 0.0f);
    }

    @Test
    public void v1AndV2ImportWriteOnlyDeclaredTargetAndPreserveInactivePlacement()
            throws Exception {
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        org.json.JSONObject template = new org.json.JSONObject(
                CameraSettingsTransfer.exportCameraPreset(new TestSharedPreferences()));
        org.json.JSONObject values = template.getJSONObject("settings");
        values.put(BlindSpotOverlayController.targetKey(profile), CameraDisplayTarget.CLUSTER);
        values.put(BlindSpotOverlayController.positionKey(profile, false), 0.61f);
        values.put(BlindSpotOverlayController.positionKey(profile, true), 0.62f);
        values.put(BlindSpotOverlayController.placementWidthKey(profile), 0.21f);
        values.put(BlindSpotOverlayController.placementHeightKey(profile), 0.22f);
        values.put(RearviewMirrorSettings.PREF_TARGET, "Cluster");
        values.put(RearviewMirrorSettings.PREF_X, 63.0f);
        values.put(RearviewMirrorSettings.PREF_Y, 64.0f);
        values.put(RearviewMirrorSettings.PREF_WIDTH, 20.0f);
        values.put(RearviewMirrorSettings.PREF_HEIGHT, 21.0f);

        CameraPlacement blindTablet = CameraPlacement.of(0.11f, 0.12f, 0.23f, 0.24f);
        CameraPlacement mirrorTablet = CameraPlacement.of(0.13f, 0.14f, 0.22f, 0.23f);
        for (int version : new int[]{1, 2}) {
            org.json.JSONObject preset = new org.json.JSONObject(template.toString());
            preset.put("version", version);
            TestSharedPreferences target = new TestSharedPreferences();
            seedBlindPlacement(target, profile, CameraDisplayTarget.TABLET, blindTablet);
            seedBlindPlacement(target, profile, CameraDisplayTarget.CLUSTER,
                    CameraPlacement.of(0.31f, 0.32f, 0.25f, 0.26f));
            seedMirrorPlacement(target, CameraDisplayTarget.TABLET, mirrorTablet);
            seedMirrorPlacement(target, CameraDisplayTarget.CLUSTER,
                    CameraPlacement.of(0.33f, 0.34f, 0.24f, 0.25f));

            CameraSettingsTransfer.applyCameraPreset(target,
                    CameraSettingsTransfer.parseCameraPreset(preset.toString()), GEOMETRY);

            assertPlacement(blindTablet, BlindSpotOverlayController.readPlacement(
                    target, profile, CameraDisplayTarget.TABLET,
                    1280, 800, 12, 30, 70));
            assertPlacement(CameraPlacement.of(0.61f, 0.62f, 0.21f, 0.22f),
                    BlindSpotOverlayController.readPlacement(
                            target, profile, CameraDisplayTarget.CLUSTER,
                            1920, 720, 0, 0, 0));
            assertPlacement(mirrorTablet,
                    RearviewMirrorSettings.placement(target, CameraDisplayTarget.TABLET));
            assertPlacement(CameraPlacement.of(0.63f, 0.64f, 0.20f, 0.21f),
                    RearviewMirrorSettings.placement(target, CameraDisplayTarget.CLUSTER));
            assertEquals(1, target.transactions);
        }
    }

    @Test
    public void v2MissingTargetUsesCurrentTargetAndMissingMirrorStaysUntouched() throws Exception {
        CameraProfile profile = CameraProfile.of(CameraProfile.FRONT_RIGHT);
        org.json.JSONObject preset = new org.json.JSONObject(
                CameraSettingsTransfer.exportCameraPreset(new TestSharedPreferences()));
        org.json.JSONObject values = preset.getJSONObject("settings");
        values.remove(BlindSpotOverlayController.targetKey(profile));
        values.put(BlindSpotOverlayController.positionKey(profile, false), 0.55f);
        values.put(BlindSpotOverlayController.positionKey(profile, true), 0.56f);
        values.put(BlindSpotOverlayController.placementWidthKey(profile), 0.20f);
        values.put(BlindSpotOverlayController.placementHeightKey(profile), 0.21f);
        values.remove(RearviewMirrorSettings.PREF_TARGET);
        values.put(RearviewMirrorSettings.PREF_X, 21.0f);
        values.put(RearviewMirrorSettings.PREF_Y, 22.0f);
        values.put(RearviewMirrorSettings.PREF_WIDTH, 23.0f);
        values.put(RearviewMirrorSettings.PREF_HEIGHT, 24.0f);

        TestSharedPreferences target = new TestSharedPreferences();
        target.putInt(BlindSpotOverlayController.targetKey(profile),
                CameraDisplayTarget.CLUSTER);
        target.putString(RearviewMirrorSettings.PREF_TARGET, "Tablet");
        CameraPlacement tablet = CameraPlacement.of(0.10f, 0.10f, 0.20f, 0.20f);
        CameraPlacement mirrorTablet = CameraPlacement.of(0.20f, 0.20f, 0.25f, 0.25f);
        CameraPlacement mirrorCluster = CameraPlacement.of(0.40f, 0.40f, 0.25f, 0.25f);
        seedBlindPlacement(target, profile, CameraDisplayTarget.TABLET, tablet);
        seedMirrorPlacement(target, CameraDisplayTarget.TABLET, mirrorTablet);
        seedMirrorPlacement(target, CameraDisplayTarget.CLUSTER, mirrorCluster);

        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(preset.toString()), GEOMETRY);

        assertEquals(CameraDisplayTarget.CLUSTER,
                BlindSpotOverlayController.readTarget(target, profile));
        assertPlacement(tablet, BlindSpotOverlayController.readPlacement(
                target, profile, CameraDisplayTarget.TABLET, 1280, 800, 12, 30, 70));
        assertPlacement(CameraPlacement.of(0.55f, 0.56f, 0.20f, 0.21f),
                BlindSpotOverlayController.readPlacement(
                        target, profile, CameraDisplayTarget.CLUSTER,
                        1920, 720, 0, 0, 0));
        assertPlacement(CameraPlacement.of(0.21f, 0.22f, 0.23f, 0.24f),
                RearviewMirrorSettings.placement(target, CameraDisplayTarget.TABLET));
        assertPlacement(mirrorCluster,
                RearviewMirrorSettings.placement(target, CameraDisplayTarget.CLUSTER));
    }

    @Test
    public void legacyXmlImportPreservesInactiveBlindPlacementInOneCommit() {
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        Map<String, Object> imported = CameraSettingsTransfer.parseLegacySettings(
                "<map><int name=\"camera_left_display_target\" value=\"1\"/>"
                        + "<float name=\"camera_left_x\" value=\"0.60\"/>"
                        + "<float name=\"camera_left_y\" value=\"0.61\"/>"
                        + "<float name=\"camera_left_width\" value=\"0.20\"/>"
                        + "<float name=\"camera_left_height\" value=\"0.21\"/></map>");
        TestSharedPreferences target = new TestSharedPreferences();
        CameraPlacement tablet = CameraPlacement.of(0.12f, 0.13f, 0.24f, 0.25f);
        seedBlindPlacement(target, profile, CameraDisplayTarget.TABLET, tablet);

        CameraSettingsTransfer.applyLegacySettings(target, imported, GEOMETRY);

        assertPlacement(tablet, BlindSpotOverlayController.readPlacement(
                target, profile, CameraDisplayTarget.TABLET, 1280, 800, 12, 30, 70));
        assertPlacement(CameraPlacement.of(0.60f, 0.61f, 0.20f, 0.21f),
                BlindSpotOverlayController.readPlacement(
                        target, profile, CameraDisplayTarget.CLUSTER,
                        1920, 720, 0, 0, 0));
        assertEquals(1, target.transactions);
    }

    @Test
    public void invalidV4PlacementIsRejectedBeforeEditorMutation() throws Exception {
        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(
                        new TestSharedPreferences(), GEOMETRY));
        @SuppressWarnings("unchecked") Map<String, Object> values =
                (Map<String, Object>) parsed.get("settings");
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        values.put(BlindSpotOverlayController.placementKey(
                profile, CameraDisplayTarget.CLUSTER,
                BlindSpotOverlayController.PLACEMENT_WIDTH), 2.0f);
        TestSharedPreferences target = new TestSharedPreferences();
        target.putString("unrelated", "keep");
        Map<String, ?> before = target.getAll();

        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.applyCameraPreset(target, parsed, GEOMETRY));
        assertEquals(before, target.getAll());
        assertEquals(0, target.transactions);
    }

    @Test
    public void v4RoundTripsIndependentBordersAndRejectsInvalidPairsBeforeWrite() {
        TestSharedPreferences source = new TestSharedPreferences();
        CameraBorderSettings.writeBlind(source, CameraProfile.FRONT_LEFT,
                new CameraBorderSettings.Border(3, 0xFF112233));
        CameraBorderSettings.writeParking(source, ParkingCameraProfile.RIGHT,
                new CameraBorderSettings.Border(4, 0xFF223344));
        CameraBorderSettings.writeReverse(source, 2, false,
                new CameraBorderSettings.Border(5, 0xFF334455));
        CameraBorderSettings.writeReverse(source, 2, true,
                new CameraBorderSettings.Border(6, 0xFF445566));
        CameraBorderSettings.writeReverseElement(source,
                ReverseCameraLayout.BACKGROUND_PANE_ID,
                new CameraBorderSettings.Border(7, 0xFF556677));
        CameraBorderSettings.writeReverseElement(source,
                ReverseCameraLayout.WIDGET_PANE_ID,
                new CameraBorderSettings.Border(8, 0xFF667788));
        CameraBorderSettings.writeMirror(source, false,
                new CameraBorderSettings.Border(9, 0xFF778899));
        CameraBorderSettings.writeMirror(source, true,
                new CameraBorderSettings.Border(10, 0xFF8899AA));

        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(source, GEOMETRY));
        assertEquals(4, parsed.get("version"));
        TestSharedPreferences target = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(target, parsed, GEOMETRY);
        assertEquals(3, CameraBorderSettings.forBlind(target, CameraProfile.FRONT_LEFT).borderDp);
        assertEquals(4, CameraBorderSettings.forParking(target, ParkingCameraProfile.RIGHT).borderDp);
        assertEquals(5, CameraBorderSettings.forReverse(target, 2, false).borderDp);
        assertEquals(6, CameraBorderSettings.forReverse(target, 2, true).borderDp);
        assertEquals(7, CameraBorderSettings.forReverseElement(target,
                ReverseCameraLayout.BACKGROUND_PANE_ID).borderDp);
        assertEquals(8, CameraBorderSettings.forReverseElement(target,
                ReverseCameraLayout.WIDGET_PANE_ID).borderDp);
        assertEquals(9, CameraBorderSettings.forMirror(target, false).borderDp);
        assertEquals(10, CameraBorderSettings.forMirror(target, true).borderDp);

        @SuppressWarnings("unchecked") Map<String, Object> values =
                (Map<String, Object>) parsed.get("settings");
        String colorKey = CameraBorderSettings.colorKey(
                CameraBorderSettings.blindPrefix(CameraProfile.of(CameraProfile.REAR_LEFT)));
        values.remove(colorKey);
        TestSharedPreferences unchanged = new TestSharedPreferences();
        unchanged.putString("unrelated", "keep");
        Map<String, ?> before = unchanged.getAll();
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.applyCameraPreset(unchanged, parsed, GEOMETRY));
        assertEquals(before, unchanged.getAll());
        assertEquals(0, unchanged.transactions);
    }

    @Test
    public void v3GeometryIsNotRemigratedAndSharedMirrorBorderAppliesToBothSources() {
        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(
                        new TestSharedPreferences(), GEOMETRY));
        parsed.put("version", 3);
        @SuppressWarnings("unchecked") Map<String, Object> values =
                (Map<String, Object>) parsed.get("settings");
        values.keySet().removeIf(key -> key.endsWith("_border_width")
                || key.endsWith("_border_color"));
        values.put(RearviewMirrorSettings.PREF_BORDER_DP, 11);
        values.put(RearviewMirrorSettings.PREF_BORDER_ARGB, 0xFFABCDEF);
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        seedBlindPlacementValue(values, profile, CameraDisplayTarget.TABLET,
                CameraPlacement.of(0.10f, 0.11f, 0.20f, 0.21f));
        seedBlindPlacementValue(values, profile, CameraDisplayTarget.CLUSTER,
                CameraPlacement.of(0.50f, 0.51f, 0.22f, 0.23f));
        values.put(BlindSpotOverlayController.positionKey(profile, false), 0.70f);
        values.put(BlindSpotOverlayController.positionKey(profile, true), 0.70f);

        TestSharedPreferences target = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(target, parsed, GEOMETRY);
        assertPlacement(CameraPlacement.of(0.10f, 0.11f, 0.20f, 0.21f),
                BlindSpotOverlayController.readPlacement(target, profile,
                        CameraDisplayTarget.TABLET, 1280, 800, 12, 30, 70));
        assertPlacement(CameraPlacement.of(0.50f, 0.51f, 0.22f, 0.23f),
                BlindSpotOverlayController.readPlacement(target, profile,
                        CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
        assertEquals(11, CameraBorderSettings.forMirror(target, false).borderDp);
        assertEquals(11, CameraBorderSettings.forMirror(target, true).borderDp);
    }

    private static void seedBlindPlacement(
            TestSharedPreferences preferences, CameraProfile profile,
            int target, CameraPlacement placement) {
        preferences.putFloat(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_X), placement.x);
        preferences.putFloat(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_Y), placement.y);
        preferences.putFloat(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_WIDTH), placement.width);
        preferences.putFloat(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_HEIGHT), placement.height);
    }

    private static void seedBlindPlacementValue(
            Map<String, Object> values, CameraProfile profile,
            int target, CameraPlacement placement) {
        values.put(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_X), placement.x);
        values.put(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_Y), placement.y);
        values.put(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_WIDTH), placement.width);
        values.put(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_HEIGHT), placement.height);
    }

    private static void seedMirrorPlacement(
            TestSharedPreferences preferences, int target, CameraPlacement placement) {
        preferences.putFloat(RearviewMirrorSettings.placementKey(
                target, RearviewMirrorSettings.PLACEMENT_X), placement.x * 100.0f);
        preferences.putFloat(RearviewMirrorSettings.placementKey(
                target, RearviewMirrorSettings.PLACEMENT_Y), placement.y * 100.0f);
        preferences.putFloat(RearviewMirrorSettings.placementKey(
                target, RearviewMirrorSettings.PLACEMENT_WIDTH), placement.width * 100.0f);
        preferences.putFloat(RearviewMirrorSettings.placementKey(
                target, RearviewMirrorSettings.PLACEMENT_HEIGHT), placement.height * 100.0f);
    }

    private static float number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).floatValue();
    }

    private static void assertPlacement(CameraPlacement expected, CameraPlacement actual) {
        assertEquals(expected.x, actual.x, 0.000001f);
        assertEquals(expected.y, actual.y, 0.000001f);
        assertEquals(expected.width, actual.width, 0.000001f);
        assertEquals(expected.height, actual.height, 0.000001f);
    }
}
