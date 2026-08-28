package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ParkingCameraSettingsTest {
    @Test
    public void defaultsAndValidationStayWithinContract() {
        ParkingCameraSettings.Rule rule = ParkingCameraSettings.defaults(
                ParkingCameraProfile.of(ParkingCameraProfile.FR));
        assertFalse(rule.enabled);
        assertEquals(30, rule.distanceCm);
        assertFalse(rule.addCentral);
        assertEquals(0, ParkingCameraSettings.clampDistanceCm(-1));
        assertEquals(150, ParkingCameraSettings.clampDistanceCm(999));
        assertEquals(0, ParkingCameraSettings.clampSpeed(-1));
        assertEquals(300, ParkingCameraSettings.clampSpeed(999));
        assertEquals("parking_camera_fl_distance_cm",
                ParkingCameraSettings.distanceKey(ParkingCameraProfile.of(ParkingCameraProfile.FL)));
        assertEquals("parking_camera_max_speed_kph", ParkingCameraSettings.PREF_MAX_SPEED_KPH);
        assertEquals("parking_camera_allow_during_reverse",
                ParkingCameraSettings.PREF_ALLOW_DURING_REVERSE);
    }

    @Test
    public void eightRulesPersistIndependentlyWithGlobalSpeed() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        ParkingCameraSettings settings = new ParkingCameraSettings(preferences);
        assertFalse(CameraHelperService.anyParkingEnabled(preferences));
        assertFalse(settings.allowDuringReverse());
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            ParkingCameraSettings.Rule rule = settings.rule(profile);
            assertFalse(rule.enabled);
            assertEquals(30, rule.distanceCm);
            assertFalse(rule.addCentral);
        }
        ParkingCameraProfile fl = ParkingCameraProfile.of(ParkingCameraProfile.FL);
        settings.setRule(fl, new ParkingCameraSettings.Rule(true, 0, true));
        settings.setMaxSpeedKph(300);

        assertTrue(settings.rule(fl).enabled);
        assertTrue(CameraHelperService.anyParkingEnabled(preferences));
        assertEquals(0, settings.rule(fl).distanceCm);
        assertFalse(settings.rule(ParkingCameraProfile.FR).enabled);
        assertEquals(30, settings.rule(ParkingCameraProfile.FR).distanceCm);
        assertEquals(300, settings.maxSpeedKph());
        ParkingCameraProfile left = ParkingCameraProfile.of(ParkingCameraProfile.LEFT);
        settings.setRule(left, new ParkingCameraSettings.Rule(true, 150, false));
        assertTrue(settings.rule(left).enabled);
        assertEquals(150, settings.rule(left).distanceCm);
        assertEquals("parking_camera_left_distance_cm",
                ParkingCameraSettings.distanceKey(left));
        settings.setRule(left, settings.rule(left).withEnabled(false));
        settings.setRule(fl, settings.rule(fl).withEnabled(false));
        assertFalse(CameraHelperService.anyParkingEnabled(preferences));
    }

    @Test
    public void bulkEnablePreservesPerCameraRuleValuesAndReversePermissionIsIndependent() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        ParkingCameraSettings settings = new ParkingCameraSettings(preferences);
        ParkingCameraProfile fl = ParkingCameraProfile.of(ParkingCameraProfile.FL);
        ParkingCameraProfile rr = ParkingCameraProfile.of(ParkingCameraProfile.RR);
        settings.setRule(fl, new ParkingCameraSettings.Rule(false, 7, true));
        settings.setRule(rr, new ParkingCameraSettings.Rule(true, 123, false));
        settings.setMaxSpeedKph(42);
        settings.setAllowDuringReverse(true);

        settings.setAllEnabled(true);
        assertTrue(settings.rule(fl).enabled);
        assertEquals(7, settings.rule(fl).distanceCm);
        assertTrue(settings.rule(fl).addCentral);
        assertTrue(settings.rule(rr).enabled);
        assertEquals(123, settings.rule(rr).distanceCm);
        assertFalse(settings.rule(rr).addCentral);
        assertEquals(42, settings.maxSpeedKph());
        assertTrue(settings.allowDuringReverse());

        settings.setAllEnabled(false);
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            assertFalse(settings.rule(profile).enabled);
        }
        assertEquals(7, settings.rule(fl).distanceCm);
        assertTrue(settings.rule(fl).addCentral);
        assertTrue(settings.allowDuringReverse());
    }

    @Test
    public void migrationRepairsStoredNumericValuesOutsideTheContract() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        ParkingCameraProfile frontLeft =
                ParkingCameraProfile.of(ParkingCameraProfile.FL);
        ParkingCameraProfile frontRight =
                ParkingCameraProfile.of(ParkingCameraProfile.FR);
        preferences.putInt(ParkingCameraSettings.distanceKey(frontLeft), 999);
        preferences.putInt(ParkingCameraSettings.distanceKey(frontRight), -10);
        preferences.putInt(ParkingCameraSettings.PREF_MAX_SPEED_KPH, 999);
        preferences.putBoolean(ParkingCameraSettings.enabledKey(frontLeft), true);
        preferences.putBoolean(ParkingCameraSettings.addCentralKey(frontLeft), true);

        ParkingCameraSettings settings = new ParkingCameraSettings(preferences);

        assertEquals(150, preferences.getInt(
                ParkingCameraSettings.distanceKey(frontLeft), -1));
        assertEquals(0, preferences.getInt(
                ParkingCameraSettings.distanceKey(frontRight), -1));
        assertEquals(300, preferences.getInt(
                ParkingCameraSettings.PREF_MAX_SPEED_KPH, -1));
        assertTrue(settings.rule(frontLeft).enabled);
        assertTrue(settings.rule(frontLeft).addCentral);
    }
}
