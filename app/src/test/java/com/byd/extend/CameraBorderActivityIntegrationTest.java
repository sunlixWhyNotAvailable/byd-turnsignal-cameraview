package com.byd.extend;

import com.byd.extend.ui.CameraGroup;
import com.byd.extend.ui.CameraProfileId;
import com.byd.extend.ui.CameraSide;
import com.byd.extend.ui.ReverseElement;
import com.byd.extend.ui.ReverseSource;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraBorderActivityIntegrationTest {
    @Test
    public void typedBorderWritesOnlyRequestedFieldAndProfile() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putFloat("direct_crop_left_x", .37f);
        preferences.putInt("camera_calibration_preset_v1_rear_left_version", 1);
        CameraProfileId.Blind blind = new CameraProfileId.Blind(
                CameraGroup.Rear, CameraSide.Left);

        CameraProbeActivity.writeProductionProfileBorder(
                preferences, blind, "7", null, null);
        assertEquals(7, CameraBorderSettings.forBlind(
                preferences, CameraProfile.REAR_LEFT).borderDp);
        assertEquals(CameraBorderSettings.DEFAULT_ARGB, CameraBorderSettings.forBlind(
                preferences, CameraProfile.REAR_LEFT).borderArgb);
        assertEquals(.37f, preferences.getFloat("direct_crop_left_x", -1f), 0f);
        assertEquals(1, preferences.getInt(
                "camera_calibration_preset_v1_rear_left_version", -1));

        CameraProbeActivity.writeProductionProfileBorder(
                preferences, blind, null, 0xFF123456, null);
        assertEquals(7, CameraBorderSettings.forBlind(
                preferences, CameraProfile.REAR_LEFT).borderDp);
        assertEquals(0xFF123456, CameraBorderSettings.forBlind(
                preferences, CameraProfile.REAR_LEFT).borderArgb);
    }

    @Test
    public void reverseElementsAndMirrorSourcesStayIndependentAndValidate() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfileId.Reverse background = new CameraProfileId.Reverse(
                ReverseElement.Background, ReverseSource.Rear);
        CameraProbeActivity.writeProductionProfileBorder(
                preferences, background, "9", 0xFF334455, null);
        assertEquals(9, CameraBorderSettings.forReverseElement(
                preferences, ReverseCameraLayout.BACKGROUND_PANE_ID).borderDp);
        assertEquals(0, CameraBorderSettings.forReverseElement(
                preferences, ReverseCameraLayout.WIDGET_PANE_ID).borderDp);

        CameraProbeActivity.writeProductionProfileBorder(preferences,
                CameraProfileId.Mirror.INSTANCE, "5", 0xFF778899, false);
        assertEquals(5, CameraBorderSettings.forMirror(preferences, false).borderDp);
        assertEquals(0, CameraBorderSettings.forMirror(preferences, true).borderDp);

        Map<String, ?> before = preferences.getAll();
        assertRejected(() -> CameraProbeActivity.writeProductionProfileBorder(
                preferences, background, "7.5", null, null));
        assertRejected(() -> CameraProbeActivity.writeProductionProfileBorder(
                preferences, background, "17", null, null));
        assertRejected(() -> CameraProbeActivity.writeProductionProfileBorder(
                preferences, background, null, 0x7F000000, null));
        assertEquals(before, preferences.getAll());
    }

    @Test
    public void activityDrawsFramesOnlyOnPlacementOutputAndReverseComposition() throws Exception {
        Path path = Path.of("app/src/main/java/com/byd/extend/CameraProbeActivity.java");
        if (!Files.exists(path)) path = Path.of("src/main/java/com/byd/extend/CameraProbeActivity.java");
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(source.contains("productionPlacementBorder = cameraBorderOverlay()"));
        assertTrue(source.contains("productionCalibrationBorder = cameraBorderOverlay()"));
        assertTrue(source.contains("ReverseCameraController.applyBorders(preferences, reverseCameraPreview)"));
        assertFalse(source.contains("productionCalibrationRawBorder"));
        assertFalse(source.contains("productionCalibrationCorrectedBorder"));
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid border accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
