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
        // v2 adds the active independent Mirror group (23 fields) while
        // Blind width/height remain optional when no explicit v2 placement
        // has been saved.
        assertEquals(392, settings.size());
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
        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(preset.toString()));

        assertTrue(target.getBoolean(RearviewMirrorSettings.PREF_ENABLED, false));
        assertEquals("Cluster", target.getString(RearviewMirrorSettings.PREF_TARGET, ""));
        assertEquals(12.0f, target.getFloat(RearviewMirrorSettings.PREF_X, -1.0f), 0.0f);
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

        CameraSettingsTransfer.applyCameraPreset(target,
                CameraSettingsTransfer.parseCameraPreset(activePreset));

        assertTrue(target.getBoolean(RearviewMirrorSettings.PREF_MANUAL_HIDDEN, false));
        assertTrue(RearviewMirrorSettings.preset(target) != null);
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
}
