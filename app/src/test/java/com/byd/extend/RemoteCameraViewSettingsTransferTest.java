package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Map;

/** Exercises the real legacy settings import and configuration sanitizer with SharedPreferences. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
public final class RemoteCameraViewSettingsTransferTest {
    private SharedPreferences settings;

    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        settings.edit().clear().commit();
    }

    @Test public void legacyImportRestoresValidMaskAndPreservesItWhenOlderFileOmitsIt() {
        String xml = "<map><int name=\"cameras_viewing_interior\" value=\"3\" />"
                + "<boolean name=\"guard_enabled\" value=\"false\" /></map>";
        Map<String, Object> values = CameraSettingsTransfer.parseLegacySettings(xml);
        CameraSettingsTransfer.applyLegacySettings(settings, values);
        assertEquals(RemoteCameraView.BOTH,
                RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getInt(RemoteCameraView.PREFERENCE_KEY, RemoteCameraView.NONE));
        assertFalse(settings.getBoolean("guard_enabled", true));

        Map<String, Object> olderValues = CameraSettingsTransfer.parseLegacySettings(
                "<map><boolean name=\"guard_enabled\" value=\"true\" /></map>");
        CameraSettingsTransfer.applyLegacySettings(settings, olderValues);
        assertEquals(RemoteCameraView.BOTH,
                RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getInt(RemoteCameraView.PREFERENCE_KEY, RemoteCameraView.NONE));
    }

    @Test public void legacyImportRejectsMasksOutsideTheFourChoices() {
        for (int invalid : new int[]{-1, 4}) {
            String xml = "<map><int name=\"cameras_viewing_interior\" value=\"" + invalid + "\" /></map>";
            assertThrows(IllegalArgumentException.class,
                    () -> CameraSettingsTransfer.parseLegacySettings(xml));
        }
    }

    @Test public void configurationExportIncludesTheDefaultMaskWhenUnset() throws Exception {
        JSONObject configuration = new JSONObject(CompatibilityBundleExporter.sanitizedPreferences(settings));
        assertEquals(RemoteCameraView.NONE, configuration.getInt(RemoteCameraView.PREFERENCE_KEY));
    }

    @Test public void configurationExportIncludesTheMaskButCameraPresetDoesNot() throws Exception {
        settings.edit().putInt(RemoteCameraView.PREFERENCE_KEY, RemoteCameraView.BOTH)
                .putString("cameras_viewing_token", "private-token").commit();

        JSONObject configuration = new JSONObject(CompatibilityBundleExporter.sanitizedPreferences(settings));
        assertEquals(RemoteCameraView.BOTH, configuration.getInt(RemoteCameraView.PREFERENCE_KEY));
        assertFalse(configuration.has("cameras_viewing_token"));

        JSONObject preset = new JSONObject(CameraSettingsTransfer.exportCameraPreset(settings));
        assertFalse(preset.getJSONObject("settings").has(RemoteCameraView.PREFERENCE_KEY));
    }
}
