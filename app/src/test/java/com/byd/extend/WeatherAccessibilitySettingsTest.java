package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WeatherAccessibilitySettingsTest {
    @Test
    public void componentUsesExtendPackageAndNamespace() {
        assertEquals(
                "com.byd.extend/com.byd.extend.WeatherRefreshAccessibilityService",
                WeatherAccessibilitySettings.SERVICE_COMPONENT);
        assertEquals("com.byd.extend/.WeatherRefreshAccessibilityService",
                WeatherAccessibilitySettings.SHORT_SERVICE_COMPONENT);
    }

    @Test
    public void migratesExtendComponentWithoutTouchingLegacyApplication() {
        String oldApp = "com.byd.turnsignalguard.capture/.WeatherRefreshAccessibilityService";
        String before = "com.example.first/.One:"
                + WeatherAccessibilitySettings.LEGACY_SERVICE_COMPONENT + ":" + oldApp;
        assertTrue(WeatherAccessibilitySettings.hasLegacyService(before));
        assertFalse(WeatherAccessibilitySettings.hasOwnService(before));
        assertEquals("com.example.first/.One:" + oldApp + ":"
                        + WeatherAccessibilitySettings.SERVICE_COMPONENT,
                WeatherAccessibilitySettings.transformEnabledAccessibilityServices(before, true));
        assertEquals("com.example.first/.One:" + oldApp,
                WeatherAccessibilitySettings.transformEnabledAccessibilityServices(before, false));
        assertFalse(WeatherAccessibilitySettings.hasLegacyService(oldApp));
    }

    @Test
    public void enablesCanonicalComponentWithoutLeadingEmptyEntry() {
        assertEquals(
                "com.example.first/.One:com.example.second/.Two:"
                        + WeatherAccessibilitySettings.SERVICE_COMPONENT,
                WeatherAccessibilitySettings.transformEnabledAccessibilityServices(
                        ":com.example.first/.One::com.example.second/.Two:", true));
    }

    @Test
    public void removesCanonicalAndShortAliasesButPreservesOtherOrder() {
        assertEquals(
                "com.example.first/.One:com.example.second/.Two",
                WeatherAccessibilitySettings.transformEnabledAccessibilityServices(
                        WeatherAccessibilitySettings.SHORT_SERVICE_COMPONENT
                                + ":com.example.first/.One:"
                                + WeatherAccessibilitySettings.SERVICE_COMPONENT
                                + ":com.example.second/.Two",
                        false));
    }

    @Test
    public void nullAndDuplicateInputAreSafe() {
        assertEquals(
                WeatherAccessibilitySettings.SERVICE_COMPONENT,
                WeatherAccessibilitySettings.transformEnabledAccessibilityServices(null, true));
        assertEquals(
                "com.example.first/.One:" + WeatherAccessibilitySettings.SERVICE_COMPONENT,
                WeatherAccessibilitySettings.transformEnabledAccessibilityServices(
                        "com.example.first/.One:com.example.first/.One", true));
    }

    @Test
    public void malformedEntriesAreDroppedBeforeReturningSetting() {
        assertEquals(
                "com.example.good/.Service",
                WeatherAccessibilitySettings.transformEnabledAccessibilityServices(
                        "com.example.good/.Service:bad;settings put secure:com.example.bad:/.Nope",
                        false));
    }

    @Test
    public void detectsBothOwnComponentSpellingsOnly() {
        assertTrue(WeatherAccessibilitySettings.hasOwnService(
                "com.example.first/.One:" + WeatherAccessibilitySettings.SERVICE_COMPONENT));
        assertTrue(WeatherAccessibilitySettings.hasOwnService(
                WeatherAccessibilitySettings.SHORT_SERVICE_COMPONENT));
        assertFalse(WeatherAccessibilitySettings.hasOwnService(
                "com.example.first/.One:com.example.second/.Two"));
    }
}
