package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ParkingCameraTriggerPolicyTest {
    @Test
    public void distanceAndSpeedBoundariesAreInclusive() {
        ParkingCameraSettings.Rule[] rules = rules();
        rules[ParkingCameraProfile.FL] = rules[ParkingCameraProfile.FL].withEnabled(true);
        int[] raw = new int[ParkingCameraProfile.allRadarFids().length];
        boolean[] valid = new boolean[raw.length];
        raw[0] = 30;
        valid[0] = true;
        int frontLeft = ParkingCameraProfile.of(ParkingCameraProfile.FL).bit();

        assertEquals(frontLeft, evaluateOnce(rules, 10, raw, valid,
                10.0f, true, 0L, 0L));
        raw[0] = 31;
        assertEquals(0, evaluateOnce(rules, 10, raw, valid,
                10.0f, true, 0L, 0L));
        raw[0] = 30;
        assertEquals(0, evaluateOnce(rules, 10, raw, valid,
                10.1f, true, 0L, 0L));
    }

    @Test
    public void additiveCentralIsDeduplicatedAndOnlyFollowsActiveCorner() {
        ParkingCameraSettings.Rule[] rules = rules();
        rules[ParkingCameraProfile.FL] = rules[ParkingCameraProfile.FL].withAddCentral(true);
        int corner = ParkingCameraProfile.of(ParkingCameraProfile.FL).bit();
        int central = ParkingCameraProfile.of(ParkingCameraProfile.FRONT).bit();
        assertEquals(corner | central,
                ParkingCameraTriggerPolicy.applyAdditiveCentral(corner, rules));
        assertEquals(corner | central,
                ParkingCameraTriggerPolicy.applyAdditiveCentral(corner | central, rules));
        assertEquals(central, ParkingCameraTriggerPolicy.applyAdditiveCentral(central, rules));
    }

    @Test
    public void stableRadarAndStaleOrOverspeedTelemetryFollowContract() {
        ParkingCameraSettings.Rule[] rules = rules();
        rules[ParkingCameraProfile.FL] = rules[ParkingCameraProfile.FL].withEnabled(true);
        int[] raw = new int[8];
        boolean[] valid = new boolean[8];
        raw[0] = 30;
        valid[0] = true;
        assertEquals(1, evaluateOnce(
                rules, 10, raw, valid, 10.0f, true, 0L, 0L));
        assertEquals(1, evaluateOnce(
                rules, 10, raw, valid, 10.0f, true, 1_000L, 1_000L));
        assertEquals(0, evaluateOnce(
                rules, 10, raw, valid, 10.0f, true, 1_000L, 1_501L));
        assertEquals(0, evaluateOnce(
                rules, 10, raw, valid, 11.0f, true, 1_000L, 1_000L));
    }

    @Test
    public void centralViewsUseMinimumValidSensor() {
        ParkingCameraSettings.Rule[] rules = rules();
        rules[ParkingCameraProfile.FRONT] =
                rules[ParkingCameraProfile.FRONT].withEnabled(true);
        rules[ParkingCameraProfile.REAR] =
                rules[ParkingCameraProfile.REAR].withEnabled(true);
        int[] raw = {155, 40, 20, 155, 155, 25, 50, 155};
        boolean[] valid = {false, true, true, false, false, true, true, false};
        int expected = ParkingCameraProfile.of(ParkingCameraProfile.FRONT).bit()
                | ParkingCameraProfile.of(ParkingCameraProfile.REAR).bit();

        assertEquals(expected, evaluateOnce(
                rules, 10, raw, valid, 0.0f, true, 0L, 0L));
        valid[2] = false;
        valid[5] = false;
        assertEquals(0, evaluateOnce(
                rules, 10, raw, valid, 0.0f, true, 0L, 0L));
    }

    @Test
    public void sideViewsUseMinimumOfFourAndKeepHighSafeValuesOutOfTriggerRange() {
        ParkingCameraSettings.Rule[] rules = rules();
        rules[ParkingCameraProfile.LEFT] = ParkingCameraSettings.defaults(
                ParkingCameraProfile.of(ParkingCameraProfile.LEFT)).withEnabled(true)
                .withDistanceCm(150);
        int[] raw = new int[ParkingCameraProfile.allRadarFids().length];
        boolean[] valid = new boolean[raw.length];
        int[] side = {200, 151, 149, 220};
        for (int i = 0; i < side.length; i++) {
            raw[8 + i] = side[i];
            valid[8 + i] = true;
        }
        assertEquals(ParkingCameraProfile.of(ParkingCameraProfile.LEFT).bit(),
                evaluateOnce(
                        rules, 10, raw, valid, 0.0f, true, 0L, 0L));
        raw[10] = 151;
        assertEquals(0, evaluateOnce(
                rules, 10, raw, valid, 0.0f, true, 0L, 0L));
    }

    @Test
    public void sideViewsNeverAddCentralFrontOrRear() {
        ParkingCameraSettings.Rule[] rules = rules();
        rules[ParkingCameraProfile.LEFT] = rules[ParkingCameraProfile.LEFT].withEnabled(true)
                .withAddCentral(true);
        int side = ParkingCameraProfile.of(ParkingCameraProfile.LEFT).bit();
        assertEquals(side, ParkingCameraTriggerPolicy.applyAdditiveCentral(side, rules));
    }

    @Test
    public void normalFalseClosesAfterHalfSecondButInvalidIsImmediate() {
        ParkingCameraTriggerPolicy.DelayedCloseState state =
                new ParkingCameraTriggerPolicy.DelayedCloseState();
        assertTrue(state.update(0L, true, true, false));
        assertTrue(state.update(100L, true, false, false));
        assertTrue(state.update(500L, true, false, false));
        assertFalse(state.update(600L, true, false, false));
        state.update(1_000L, true, true, false);
        assertFalse(state.update(1_001L, false, false, true));
    }

    private static ParkingCameraSettings.Rule[] rules() {
        ParkingCameraSettings.Rule[] rules = new ParkingCameraSettings.Rule[ParkingCameraProfile.COUNT];
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            rules[profile.id] = ParkingCameraSettings.defaults(profile);
        }
        return rules;
    }

    private static int evaluateOnce(
            ParkingCameraSettings.Rule[] rules, int maxSpeedKph,
            int[] radarRaw, boolean[] radarValid,
            float speedKph, boolean speedValid, long speedTimestampMs,
            long nowMs) {
        return new ParkingCameraTriggerPolicy.State().update(
                rules, maxSpeedKph, radarRaw, radarValid,
                speedKph, speedValid, speedTimestampMs, nowMs);
    }
}
