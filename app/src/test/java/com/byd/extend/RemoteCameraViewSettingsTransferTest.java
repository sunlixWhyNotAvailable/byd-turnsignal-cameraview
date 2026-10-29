package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
    @Test public void cellularSettingExportsAndImportsWithoutCopyingTheLocalRetryGeneration() throws Exception {
        assertFalse(new JSONObject(CompatibilityBundleExporter.sanitizedPreferences(settings))
                .getBoolean(MotionConfig.CELLULAR_PREF));
        settings.edit().putLong(MotionConfig.CELLULAR_GENERATION_PREF, 4).commit();
        Map<String, Object> values = CameraSettingsTransfer.parseLegacySettings(
                "<map><boolean name=\"camera_restore_cellular\" value=\"true\"/>"
                + "<long name=\"camera_cellular_generation\" value=\"99\"/></map>");
        CameraSettingsTransfer.applyLegacySettings(settings, values);
        assertTrue(settings.getBoolean(MotionConfig.CELLULAR_PREF, false));
        assertEquals(5, settings.getLong(MotionConfig.CELLULAR_GENERATION_PREF, 0));
        assertTrue(new JSONObject(CompatibilityBundleExporter.sanitizedPreferences(settings))
                .getBoolean(MotionConfig.CELLULAR_PREF));
        CameraSettingsTransfer.applyLegacySettings(settings, CameraSettingsTransfer.parseLegacySettings("<map/>"));
        assertTrue(settings.getBoolean(MotionConfig.CELLULAR_PREF, false));
        assertEquals(5, settings.getLong(MotionConfig.CELLULAR_GENERATION_PREF, 0));
    }
    private SharedPreferences settings;

    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        settings.edit().clear().commit();
    }

    @Test public void legacyImportRestoresValidMaskAndPreservesItWhenOlderFileOmitsIt() {
        String xml = "<map><int name=\"cameras_viewing_interior\" value=\"3\" />"
                + "<boolean name=\"" + RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY
                + "\" value=\"true\" />"
                + "<boolean name=\"guard_enabled\" value=\"false\" /></map>";
        Map<String, Object> values = CameraSettingsTransfer.parseLegacySettings(xml);
        CameraSettingsTransfer.applyLegacySettings(settings, values);
        assertEquals(RemoteCameraView.BOTH,
                RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getInt(RemoteCameraView.PREFERENCE_KEY, RemoteCameraView.NONE));
        assertTrue(settings.getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, false));
        assertFalse(settings.getBoolean("guard_enabled", true));

        Map<String, Object> olderValues = CameraSettingsTransfer.parseLegacySettings(
                "<map><boolean name=\"guard_enabled\" value=\"true\" /></map>");
        CameraSettingsTransfer.applyLegacySettings(settings, olderValues);
        assertEquals(RemoteCameraView.BOTH,
                RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getInt(RemoteCameraView.PREFERENCE_KEY, RemoteCameraView.NONE));
        assertTrue(settings.getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, false));
    }

    @Test public void legacyImportRejectsMasksOutsideTheFourChoices() {
        for (int invalid : new int[]{-1, 4}) {
            String xml = "<map><int name=\"cameras_viewing_interior\" value=\"" + invalid + "\" /></map>";
            assertThrows(IllegalArgumentException.class,
                    () -> CameraSettingsTransfer.parseLegacySettings(xml));
        }
    }

    @Test public void legacyImportRestoresHazardThanksAndOldFilesGetSafeDefaults() {
        String xml = "<map><boolean name=\"hazard_thanks_enabled\" value=\"true\" />"
                + "<int name=\"hazard_thanks_flashes\" value=\"3\" />"
                + "<int name=\"hazard_thanks_steering_key_code\" value=\"305\" />"
                + "<string name=\"hazard_thanks_steering_press\">Double</string>"
                + "<string name=\"hazard_thanks_steering_press_android\">Hold</string>"
                + "<string name=\"hazard_thanks_steering_source\">Sos</string></map>";
        CameraSettingsTransfer.applyLegacySettings(settings,
                CameraSettingsTransfer.parseLegacySettings(xml));
        assertTrue(HazardThanksSettings.enabled(settings));
        assertEquals(3, HazardThanksSettings.flashes(settings));
        assertEquals(new CameraButtonBindings.Binding(CameraButtonBindings.Source.Sos,
                        CameraButtonBindings.Press.Double),
                CameraButtonBindings.load(settings, CameraButtonBindings.Action.HazardThanks));
        assertEquals(new CameraButtonBindings.Binding(305, CameraButtonBindings.Press.Hold),
                CameraButtonBindings.loadForSource(settings,
                        CameraButtonBindings.Action.HazardThanks,
                        CameraButtonBindings.Source.AndroidKey));

        Map<String, Object> olderValues = CameraSettingsTransfer.parseLegacySettings(
                "<map><boolean name=\"guard_enabled\" value=\"true\" /></map>");
        CameraSettingsTransfer.applyLegacySettings(settings, olderValues);
        assertFalse(HazardThanksSettings.enabled(settings));
        assertEquals(2, HazardThanksSettings.flashes(settings));
        assertEquals(new CameraButtonBindings.Binding(CameraButtonBindings.UNASSIGNED,
                        CameraButtonBindings.Press.Single),
                CameraButtonBindings.load(settings, CameraButtonBindings.Action.HazardThanks));
    }

    @Test public void legacyImportValidatesHazardThanksSettingsAndBindings() {
        assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.parseLegacySettings(
                "<map><int name=\"hazard_thanks_flashes\" value=\"4\" /></map>"));
        assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.parseLegacySettings(
                "<map><string name=\"hazard_thanks_steering_source\">Sos</string>"
                        + "<string name=\"hazard_thanks_steering_press\">Hold</string></map>"));
    }

    @Test public void configurationExportIncludesTheDefaultMaskWhenUnset() throws Exception {
        JSONObject configuration = new JSONObject(CompatibilityBundleExporter.sanitizedPreferences(settings));
        assertEquals(RemoteCameraView.NONE, configuration.getInt(RemoteCameraView.PREFERENCE_KEY));
        assertFalse(configuration.getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY));
        JSONObject preset = new JSONObject(CameraSettingsTransfer.exportCameraPreset(
                RuntimeEnvironment.getApplication(), settings));
        assertFalse(preset.getJSONObject("settings")
                .getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY));
    }

    @Test public void configurationExportIncludesHazardThanksButCameraPresetDoesNot() throws Exception {
        settings.edit().putInt(RemoteCameraView.PREFERENCE_KEY, RemoteCameraView.BOTH)
                .putBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, true)
                .putString("cameras_viewing_token", "private-token")
                .putBoolean(HazardThanksSettings.ENABLED, true)
                .putInt(HazardThanksSettings.FLASH_COUNT, 3)
                .putInt(CameraButtonBindings.HAZARD_THANKS_KEY_CODE, 305)
                .putString(CameraButtonBindings.HAZARD_THANKS_PRESS,
                        CameraButtonBindings.Press.Double.name())
                .putString(CameraButtonBindings.HAZARD_THANKS_PRESS + "_android",
                        CameraButtonBindings.Press.Hold.name())
                .putString(CameraButtonBindings.HAZARD_THANKS_SOURCE,
                        CameraButtonBindings.Source.Sos.name()).commit();

        JSONObject configuration = new JSONObject(CompatibilityBundleExporter.sanitizedPreferences(settings));
        assertEquals(RemoteCameraView.BOTH, configuration.getInt(RemoteCameraView.PREFERENCE_KEY));
        assertTrue(configuration.getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY));
        assertTrue(configuration.getBoolean(HazardThanksSettings.ENABLED));
        assertEquals(3, configuration.getInt(HazardThanksSettings.FLASH_COUNT));
        assertEquals(305, configuration.getInt(CameraButtonBindings.HAZARD_THANKS_KEY_CODE));
        assertEquals("Double", configuration.getString(CameraButtonBindings.HAZARD_THANKS_PRESS));
        assertEquals("Hold", configuration.getString(
                CameraButtonBindings.HAZARD_THANKS_PRESS + "_android"));
        assertEquals("Sos", configuration.getString(CameraButtonBindings.HAZARD_THANKS_SOURCE));
        assertFalse(configuration.has("cameras_viewing_token"));

        JSONObject preset = new JSONObject(CameraSettingsTransfer.exportCameraPreset(settings));
        assertFalse(preset.getJSONObject("settings").has(RemoteCameraView.PREFERENCE_KEY));
        assertFalse(preset.getJSONObject("settings").has(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY));
        assertFalse(preset.getJSONObject("settings").has(HazardThanksSettings.ENABLED));
        assertFalse(preset.getJSONObject("settings").has(HazardThanksSettings.FLASH_COUNT));
        assertFalse(preset.getJSONObject("settings").has(CameraButtonBindings.HAZARD_THANKS_SOURCE));
    }

    @Test public void currentPresetTransfersCabinMirrorAndOldPresetPreservesIt() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        settings.edit().putBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, true).commit();
        JSONObject exported = new JSONObject(CameraSettingsTransfer.exportCameraPreset(app, settings));
        assertTrue(exported.getJSONObject("settings")
                .getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY));
        settings.edit().putBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, false).commit();
        CameraSettingsTransfer.applyCameraPreset(settings,
                CameraSettingsTransfer.parseCameraPreset(exported.toString()));
        assertTrue(settings.getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, false));

        JSONObject old = new JSONObject(exported.toString()).put("version", 6);
        old.getJSONObject("settings").remove(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY);
        settings.edit().putBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, true).commit();
        CameraSettingsTransfer.applyCameraPreset(settings,
                CameraSettingsTransfer.parseCameraPreset(old.toString()));
        assertTrue(settings.getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, false));
    }
}
