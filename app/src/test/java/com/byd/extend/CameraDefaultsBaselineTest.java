package com.byd.extend;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

import static org.junit.Assert.*;

/** Independent visual-only fixture extracted from the owner's parking-derived JSON. */
public final class CameraDefaultsBaselineTest {
    @Test
    public void allApprovedVisualDefaultsMatchTheOwnerPreset() throws Exception {
        assertBaseline(new TestSharedPreferences(), false);
    }

    @Test
    public void scopedResetsRestoreTheSameBaselineWithoutReplacingSavedSlots() throws Exception {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putBoolean("guard_enabled", false);
        preferences.putInt("outward_deg", 7);
        preferences.putInt("camera_calibration_preset_v1_rear_left_version", 1);
        for (CameraProfile profile : CameraProfile.values()) {
            DirectCameraCrop.save(preferences, profile, DirectCameraCrop.of(.1f, .1f, .5f, .5f, 3));
            BlindSpotOverlayController.writePlacement(preferences, profile, CameraPlacement.bounded(.1f, .2f, .3f, .4f));
            CameraCalibrationPreset.resetCameraToDefault(preferences, profile,
                    CameraDisplayTarget.TABLET, 1920, 1080, 16, 36, 88);
        }
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            DirectCameraCrop.save(preferences, profile, DirectCameraCrop.of(.1f, .1f, .5f, .5f, 3));
            for (CameraCalibrationPreset.Stage stage : CameraCalibrationPreset.Stage.values()) {
                CameraCalibrationPreset.resetParkingStage(preferences, profile, stage);
            }
        }
        for (int index = 1; index <= 3; index++) {
            CameraCalibrationPreset.resetReverseToDefault(preferences, index);
            CameraCalibrationPreset.resetReverseFrontToDefault(preferences, index);
        }
        for (CameraProfile profile : CameraProfile.values()) {
            assertEquals(BlindSpotOverlayController.defaultPlacement(profile,
                            CameraDisplayTarget.TABLET, 1920, 1080, 16, 36, 88),
                    BlindSpotOverlayController.readPlacement(preferences, profile,
                            CameraDisplayTarget.TABLET, 1920, 1080, 16, 36, 88));
        }
        // The explicit whole-display slot is pixel-equivalent to the accepted legacy anchors;
        // its normalized x/y wire values intentionally differ by the tablet chrome insets.
        assertBaseline(preferences, true);
        assertFalse(preferences.getBoolean("guard_enabled", true));
        assertEquals(7, preferences.getInt("outward_deg", -1));
        assertEquals(1, preferences.getInt("camera_calibration_preset_v1_rear_left_version", -1));
    }

    private static void assertBaseline(
            TestSharedPreferences preferences, boolean skipBlindPosition) throws Exception {
        JSONObject expected;
        try (InputStream input = CameraDefaultsBaselineTest.class.getResourceAsStream("/approved-camera-visual-defaults.json")) {
            assertNotNull(input);
            expected = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        Map<?, ?> actual = (Map<?, ?>) CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(preferences)).get("settings");
        assertEquals(339, expected.length());
        for (Iterator<String> keys = expected.keys(); keys.hasNext();) {
            String key = keys.next();
            if (skipBlindPosition && isBlindPositionKey(key)) continue;
            Object wanted = expected.get(key);
            Object found = actual.get(key);
            // A free corrected-aspect marker is optional on disk when RAW already has that
            // aspect. Compare the effective renderer value, not the presence of a redundant key.
            if (found == null && key.endsWith("_corrected_aspect")) {
                for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
                    if (key.equals("parking_direct_crop_v1_" + profile.wireName.toLowerCase(java.util.Locale.US)
                            + "_corrected_aspect")) {
                        DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
                        found = DirectCameraCrop.loadCorrected(preferences, profile, raw).aspectMode;
                    }
                }
            }
            assertNotNull("missing " + key, found);
            if (wanted instanceof Number) {
                assertTrue(key, found instanceof Number);
                assertEquals(key, ((Number) wanted).doubleValue(), ((Number) found).doubleValue(), .000001);
            } else {
                assertEquals(key, wanted, found);
            }
        }
    }

    private static boolean isBlindPositionKey(String key) {
        for (CameraProfile profile : CameraProfile.values()) {
            if (BlindSpotOverlayController.positionKey(profile, false).equals(key)
                    || BlindSpotOverlayController.positionKey(profile, true).equals(key)) return true;
        }
        return false;
    }
}
