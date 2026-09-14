package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class RequiredPermissionsTest {
    @Test public void coreProvisionedRightsRemainRequiredWithFeaturesDisabled() {
        TestSharedPreferences prefs = withoutUpdateHint();
        CameraButtonBindings.save(prefs, CameraButtonBindings.Action.MirrorVisibility,
                new CameraButtonBindings.Binding(305, CameraButtonBindings.Press.Single));
        CameraButtonBindings.save(prefs, CameraButtonBindings.Action.ReverseSource,
                new CameraButtonBindings.Binding(88, CameraButtonBindings.Press.Single));
        RequiredPermissions required = RequiredPermissions.read(prefs, false, false, false);
        assertTrue(required.accessibility);
        assertTrue(required.notificationAccess);
        assertTrue(required.writeSecureSettings);
        assertFalse(required.satisfied(false, false, false, false, true, true));
        assertTrue(required.satisfied(false, false, true, false, true, true));
    }

    @Test public void enabledMirrorRequiresCameraAndOverlayAlongsideCoreRights() {
        TestSharedPreferences prefs = withoutUpdateHint();
        prefs.edit().putBoolean(RearviewMirrorSettings.PREF_ENABLED, true).apply();
        RequiredPermissions required = RequiredPermissions.read(prefs, false, false, false);
        assertFalse(required.satisfied(false, true, true, true));
        assertFalse(required.satisfied(true, false, true, true));
        assertTrue(required.satisfied(true, true, true, false));
        CameraButtonBindings.save(prefs, CameraButtonBindings.Action.MirrorSource,
                new CameraButtonBindings.Binding(305, CameraButtonBindings.Press.Double));
        assertTrue(RequiredPermissions.read(prefs, false, false, false).satisfied(true, true, true, false));
        prefs.edit().putBoolean(RearviewMirrorSettings.PREF_FRONT_INTEGRATED, true).apply();
        assertFalse(RequiredPermissions.read(prefs, false, false, false).satisfied(true, true, false, true));
    }

    @Test public void explicitOperationsRequireOnlyTheirOwnPermission() {
        TestSharedPreferences prefs = withoutUpdateHint();
        assertFalse(RequiredPermissions.read(prefs, true, false, false).satisfied(false, true, true, true));
        assertFalse(RequiredPermissions.read(prefs, false, true, false).satisfied(true, true, false, true));
        assertFalse(RequiredPermissions.read(prefs, false, false, true).satisfied(true, true, true, false));
        assertTrue(RequiredPermissions.read(prefs, false, false, false).satisfied(false, false, true, false));
    }

    @Test public void accessibilityReadbackIsRequiredRegardlessOfWeatherSwitch() {
        TestSharedPreferences prefs = withoutUpdateHint();
        prefs.edit().putBoolean(WeatherRuntime.PREF_ENABLED, true).apply();
        RequiredPermissions required = RequiredPermissions.read(prefs, false, false, false);
        assertFalse(required.satisfied(true, true, false, true));
        assertTrue(required.satisfied(false, false, true, false));
        prefs.edit().putBoolean(WeatherRuntime.PREF_ENABLED, false).apply();
        assertTrue(RequiredPermissions.read(prefs, false, false, false).accessibility);
    }

    @Test public void enabledByDefaultHintRequiresOnlyOverlayPermission() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        prefs.edit().putBoolean("adb_recovery_enabled", false).apply();
        RequiredPermissions required = RequiredPermissions.read(prefs, false, false, false);
        assertFalse(required.satisfied(false, false, false, false));
        assertTrue(required.satisfied(false, true, true, false));
        prefs.edit().putBoolean(UpdateHintRuntime.PREF_ENABLED, false).apply();
        assertTrue(RequiredPermissions.read(prefs, false, false, false)
                .satisfied(false, false, true, false));
    }

    @Test public void notificationReadbackRemainsRequiredWhenAutoStartTurnsOff() {
        TestSharedPreferences prefs = withoutUpdateHint();
        AvasConfig config = AvasConfig.empty();
        AvasConfig.Profile lock = config.profile("lock");
        prefs.edit().putString(AvasAudioLibrary.PREF_CONFIG,
                config.withProfile(lock.withSettings(true, false, 15, "")).toJson()).apply();
        RequiredPermissions required = RequiredPermissions.read(prefs, false, false, false);
        assertFalse(required.satisfied(true, true, true, true, false));
        assertTrue(required.satisfied(true, true, true, true, true));

        prefs.edit().putBoolean(GuardRecovery.KEY_AUTO_START, false).apply();
        RequiredPermissions autoStartOff = RequiredPermissions.read(
                prefs, false, false, false);
        assertTrue(autoStartOff.notificationAccess);
        assertFalse(autoStartOff.satisfied(false, false, true, false, false));
        assertTrue(autoStartOff.satisfied(false, false, true, false, true));
    }

    @Test public void defaultAdbRecoveryRequiresWssNotificationAndReminderOverlay() {
        TestSharedPreferences prefs = withoutUpdateHint();
        prefs.edit().putBoolean("adb_recovery_enabled", true)
                .putBoolean("adb_reminder_enabled", true).apply();
        RequiredPermissions required = RequiredPermissions.read(prefs, false, false, false);
        assertTrue(required.writeSecureSettings);
        assertTrue(required.notificationAccess);
        assertTrue(required.overlay);
        assertFalse(required.satisfied(true, true, true, true, true, false));
        assertFalse(required.satisfied(true, true, true, true, false, true));
        assertTrue(required.satisfied(true, true, true, true, true, true));
    }

    @Test public void reminderAndAutoStartDoNotGateCoreProvisionedRights() {
        TestSharedPreferences prefs = withoutUpdateHint();
        prefs.edit().putBoolean("adb_recovery_enabled", true)
                .putBoolean("adb_reminder_enabled", false)
                .putBoolean(GuardRecovery.KEY_AUTO_START, false).apply();
        RequiredPermissions required = RequiredPermissions.read(prefs, false, false, false);
        assertTrue(required.writeSecureSettings);
        assertTrue(required.notificationAccess);
        assertTrue(required.accessibility);
        assertFalse(required.overlay);
    }

    @Test public void disablingAdbRecoveryDoesNotHideProvisionedRightsReadback() {
        TestSharedPreferences prefs = withoutUpdateHint();
        prefs.edit().putBoolean("adb_recovery_enabled", true).apply();
        RequiredPermissions defaults = RequiredPermissions.read(prefs, false, false, false);
        assertTrue(defaults.writeSecureSettings);
        assertTrue(defaults.notificationAccess);

        prefs.edit().putBoolean("adb_recovery_enabled", false).apply();
        RequiredPermissions disabled = RequiredPermissions.read(prefs, false, false, false);
        assertTrue(disabled.writeSecureSettings);
        assertTrue(disabled.notificationAccess);
        assertTrue(disabled.accessibility);
        assertFalse(disabled.overlay);
    }

    private static TestSharedPreferences withoutUpdateHint() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        prefs.edit().putBoolean(UpdateHintRuntime.PREF_ENABLED, false)
                .putBoolean("adb_recovery_enabled", false).apply();
        return prefs;
    }
}
