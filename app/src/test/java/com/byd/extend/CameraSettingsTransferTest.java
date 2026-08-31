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
        assertEquals(367, settings.size());
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
    public void invalidJsonIsRejectedBeforeMutation() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putInt(BlindSpotOverlayController.PREF_LEFT_SCALE, 44);
        String valid = CameraSettingsTransfer.exportCameraPreset(preferences);
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.parseCameraPreset(valid.replace("\"version\":1", "\"version\":2")));
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.parseCameraPreset(valid.replace("\"version\":1", "\"version\":1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.parseCameraPreset(valid.replace("\"version\":1", "\"version\":\"1\"")));
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
}
