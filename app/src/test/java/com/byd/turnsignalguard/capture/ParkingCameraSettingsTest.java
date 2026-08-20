package com.byd.turnsignalguard.capture;

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
    }

    @Test
    public void sixRulesPersistIndependentlyWithGlobalSpeed() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        ParkingCameraSettings settings = new ParkingCameraSettings(preferences);
        assertFalse(CameraHelperService.anyParkingEnabled(preferences));
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
        settings.setRule(fl, settings.rule(fl).withEnabled(false));
        assertFalse(CameraHelperService.anyParkingEnabled(preferences));
    }
}
