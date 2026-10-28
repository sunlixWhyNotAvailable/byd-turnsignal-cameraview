package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

/** Exercises the real app SharedPreferences read/commit path without faking permissions or Binder. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
public final class CameraModeSettingsPreferencesTest {
    @Test public void sharedSettingsPersistIndependentlyWithoutChangingModes() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences screen = app.getSharedPreferences(MotionConfig.SCREEN_PREFS, Context.MODE_PRIVATE);
        SharedPreferences settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        screen.edit().clear().putBoolean("enabled", false).putInt("lock", 0).commit();
        settings.edit().remove(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY)
                .putInt(RemoteCameraView.PREFERENCE_KEY, RemoteCameraView.ALLOW_DRIVER).commit();

        assertTrue(CameraModeSettings.persistSettingPreference(app, "mirrorCabin", true));
        assertTrue(CameraModeSettings.persistSettingPreference(app, "recordingSensitivity", 0));
        assertFalse(CameraModeSettings.persistSettingPreference(app, "recordingSensitivity", 0));
        assertFalse(CameraModeSettings.persistSettingPreference(app, "mirrorCabin", true));

        Context reopened = RuntimeEnvironment.getApplication();
        assertTrue(reopened.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, false));
        MotionConfig config = MotionConfig.read(reopened);
        assertEquals(0, config.recordingSensitivity);
        assertFalse(config.warningEnabled);
        assertEquals(0, config.lockCondition);
        assertEquals(RemoteCameraView.ALLOW_DRIVER, settings.getInt(RemoteCameraView.PREFERENCE_KEY, 0));
    }

    @Test public void settingValidationRejectsWrongTypesRangesAndUnknownKeysWithoutWrites() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        CameraModeSettings.persistSettingPreference(app, "recordingSensitivity", 2);
        for (Object value : new Object[] { -1, 3, 1.0, "1", true, null }) {
            CameraModeClient.ModeException failure = assertThrows(CameraModeClient.ModeException.class,
                    () -> CameraModeSettings.persistSettingPreference(app, "recordingSensitivity", value));
            assertEquals("invalid_setting", failure.code());
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
        }
        assertThrows(IllegalArgumentException.class, () -> CameraModeSettings.validateSetting("mirrorCabin", 1));
        assertThrows(IllegalArgumentException.class, () -> CameraModeSettings.validateSetting("mirrorDriver", true));
        assertEquals(2, MotionConfig.read(app).recordingSensitivity);
    }

    @Test public void recordingSensitivityUsesSharedScreenPreferenceAndDefaultsToMiddle() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences screen = app.getSharedPreferences(MotionConfig.SCREEN_PREFS, Context.MODE_PRIVATE);
        screen.edit().remove(MotionConfig.KEY_RECORDING_SENSITIVITY).commit();
        assertEquals(MotionConfig.DEFAULT_RECORDING_SENSITIVITY,
                MotionConfig.read(app).recordingSensitivity);

        screen.edit().putInt(MotionConfig.KEY_RECORDING_SENSITIVITY, 0).commit();
        assertEquals(0, MotionConfig.read(RuntimeEnvironment.getApplication()).recordingSensitivity);
    }

    @Test public void modeCommitSurvivesFreshReadAndPreservesEveryOtherModeField() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(MotionConfig.SCREEN_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", false)
                .putInt("lock", 0)
                .commit();
        app.getSharedPreferences(MotionConfig.RECORDING_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", false)
                .putBoolean("audio", true)
                .putInt("before", 7)
                .commit();

        assertTrue(CameraModeSettings.persistModePreference(app,
                CameraModeSettings.MODE_TABLET, true));
        assertTrue(CameraModeSettings.persistModePreference(app,
                CameraModeSettings.MODE_RECORDING, true));

        SharedPreferences freshScreenRead = RuntimeEnvironment.getApplication()
                .getSharedPreferences(MotionConfig.SCREEN_PREFS, Context.MODE_PRIVATE);
        SharedPreferences freshRecordingRead = RuntimeEnvironment.getApplication()
                .getSharedPreferences(MotionConfig.RECORDING_PREFS, Context.MODE_PRIVATE);
        assertTrue(freshScreenRead.getBoolean("enabled", false));
        assertEquals(0, freshScreenRead.getInt("lock", -1));
        assertTrue(freshRecordingRead.getBoolean("enabled", false));
        assertTrue(freshRecordingRead.getBoolean("audio", false));
        assertEquals(7, freshRecordingRead.getInt("before", -1));

        MotionConfig config = MotionConfig.read(RuntimeEnvironment.getApplication());
        assertTrue(config.warningEnabled);
        assertTrue(config.recordingEnabled);
        assertTrue(config.audioEnabled);
        assertEquals(0, config.lockCondition);
    }
}
