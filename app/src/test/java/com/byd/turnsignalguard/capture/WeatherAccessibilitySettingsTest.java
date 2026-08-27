package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WeatherAccessibilitySettingsTest {
    @Test
    public void componentUsesExtendPackageAndLegacyJavaNamespace() {
        assertEquals(
                "com.byd.extend/com.byd.turnsignalguard.capture.WeatherRefreshAccessibilityService",
                WeatherAccessibilitySettings.SERVICE_COMPONENT);
        assertEquals(WeatherAccessibilitySettings.SERVICE_COMPONENT,
                WeatherAccessibilitySettings.SHORT_SERVICE_COMPONENT);
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
