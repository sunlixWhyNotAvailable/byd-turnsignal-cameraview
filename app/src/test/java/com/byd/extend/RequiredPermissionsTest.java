package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class RequiredPermissionsTest {
    @Test public void disabledFeaturesAndSavedBindingsDoNotReportErrors() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        CameraButtonBindings.save(prefs, CameraButtonBindings.Action.MirrorVisibility,
                new CameraButtonBindings.Binding(305, CameraButtonBindings.Press.Single));
        CameraButtonBindings.save(prefs, CameraButtonBindings.Action.ReverseSource,
                new CameraButtonBindings.Binding(88, CameraButtonBindings.Press.Single));
        assertTrue(RequiredPermissions.read(prefs, false, false, false)
                .satisfied(false, false, false, false));
    }

    @Test public void enabledMirrorRequiresCameraOverlayAndOnlyAssignedEligibleGestures() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        prefs.edit().putBoolean(RearviewMirrorSettings.PREF_ENABLED, true).apply();
        RequiredPermissions required = RequiredPermissions.read(prefs, false, false, false);
        assertFalse(required.satisfied(false, true, true, true));
        assertFalse(required.satisfied(true, false, true, true));
        assertTrue(required.satisfied(true, true, false, false));
        CameraButtonBindings.save(prefs, CameraButtonBindings.Action.MirrorSource,
                new CameraButtonBindings.Binding(305, CameraButtonBindings.Press.Double));
        assertTrue(RequiredPermissions.read(prefs, false, false, false).satisfied(true, true, false, false));
        prefs.edit().putBoolean(RearviewMirrorSettings.PREF_FRONT_INTEGRATED, true).apply();
        assertFalse(RequiredPermissions.read(prefs, false, false, false).satisfied(true, true, false, true));
    }

    @Test public void explicitOperationsRequireOnlyTheirOwnPermission() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        assertFalse(RequiredPermissions.read(prefs, true, false, false).satisfied(false, true, true, true));
        assertFalse(RequiredPermissions.read(prefs, false, true, false).satisfied(true, true, false, true));
        assertFalse(RequiredPermissions.read(prefs, false, false, true).satisfied(true, true, true, false));
        assertTrue(RequiredPermissions.read(prefs, false, false, false).satisfied(false, false, false, false));
    }

    @Test public void enabledWeatherRequiresLiveAccessibilityButNotCameraOrLocationHere() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        prefs.edit().putBoolean(WeatherRuntime.PREF_ENABLED, true).apply();
        RequiredPermissions required = RequiredPermissions.read(prefs, false, false, false);
        assertFalse(required.satisfied(true, true, false, true));
        assertTrue(required.satisfied(false, false, true, false));
    }
}
