package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AvasEngineMotorMappingTest {
    @Test public void signedMotorWorksBeforeCalibrationAndValidPairsRefineScale() {
        AvasEngineMotorMapping.Scale front = new AvasEngineMotorMapping.Scale();
        AvasEngineMotorMapping.Scale rear = new AvasEngineMotorMapping.Scale();

        AvasEngineMotorMapping.Motion motion = AvasEngineMotorMapping.select(
                42, true, -1_100, true, front, Integer.MIN_VALUE, false, rear);
        assertEquals("motor_front", motion.source);
        assertEquals(1_100f / 75, motion.speedKph, 0.001f);

        observe(front, 1, 50, -2_500, 100);
        observe(front, 2, 50, -3_000, 200);
        observe(front, 3, 50, -2_750, 300);
        assertEquals(55, front.rawPerKph(), 0.001f);
        motion = AvasEngineMotorMapping.select(
                42, true, -2_750, true, front, Integer.MIN_VALUE, false, rear);
        assertEquals("motor_front", motion.source);
        assertEquals(50, motion.speedKph, 0.001f);

        motion = AvasEngineMotorMapping.select(
                42, true, 0, true, front, Integer.MIN_VALUE, false, rear);
        assertEquals("motor_front", motion.source);
        assertEquals(0, motion.speedKph, 0.001f);
    }

    @Test public void frontAndRearCalibrateSeparatelyAndSelectMaximumMagnitude() {
        AvasEngineMotorMapping.Scale front = new AvasEngineMotorMapping.Scale();
        AvasEngineMotorMapping.Scale rear = new AvasEngineMotorMapping.Scale();
        for (int revision = 1; revision <= 3; revision++) {
            observe(front, revision, 20, 2_000, revision * 100L);
            observe(rear, revision, 20, -1_000, revision * 100L);
        }

        assertEquals(100, front.rawPerKph(), 0.001f);
        assertEquals(50, rear.rawPerKph(), 0.001f);
        AvasEngineMotorMapping.Motion motion = AvasEngineMotorMapping.select(
                10, true, 3_000, true, front, -2_000, true, rear);
        assertEquals("motor_front", motion.source);
        assertEquals(30, motion.speedKph, 0.001f);
    }

    @Test public void calibrationRequiresFreshMovingPairsAndKnownRuntimeFids() {
        AvasEngineMotorMapping.Scale scale = new AvasEngineMotorMapping.Scale();
        observe(scale, 1, 20, -1_000, 10);
        scale.observe(20, true, 10, 2, -1_000, true, 6_000, 2);
        assertEquals(1, scale.sampleCount());
        scale.observe(0, true, 6_000, 3, 0, true, 6_000, 3);
        assertEquals(1, scale.sampleCount());
        assertEquals(75, scale.rawPerKph(), 0.001f);

        assertEquals(1141899272, AvasEngineTelemetry.motorFids(true)[0]);
        assertEquals(621805576, AvasEngineTelemetry.motorFids(true)[1]);
        assertEquals(1141901320, AvasEngineTelemetry.motorFids(false)[0]);
        assertEquals(621807624, AvasEngineTelemetry.motorFids(false)[1]);
    }

    @Test public void knownSdkErrorsAreRejectedWithoutDiscardingOtherSignedValues() {
        AvasEngineMotorMapping.Scale scale = new AvasEngineMotorMapping.Scale();
        for (int revision = 1; revision <= 3; revision++) {
            observe(scale, revision, 20, -1_000, revision * 100L);
        }
        assertTrue(AvasEngineMotorMapping.isKnownSdkError(-2_147_482_648));
        assertTrue(AvasEngineMotorMapping.isKnownSdkError(-2_147_482_644));
        assertFalse(AvasEngineMotorMapping.isKnownSdkError(-1));
        assertFalse(AvasEngineMotorMapping.isKnownSdkError(0));

        AvasEngineMotorMapping.Motion error = AvasEngineMotorMapping.select(
                27, true, -2_147_482_648, true, scale,
                Integer.MIN_VALUE, false, new AvasEngineMotorMapping.Scale());
        assertEquals("speed", error.source);
        assertEquals(27, error.speedKph, 0.001f);

        AvasEngineMotorMapping.Motion zero = AvasEngineMotorMapping.select(
                27, true, 0, true, scale, Integer.MIN_VALUE, false,
                new AvasEngineMotorMapping.Scale());
        assertEquals("motor_front", zero.source);
        assertEquals(0, zero.speedKph, 0.001f);

        AvasEngineMotorMapping.Motion negative = AvasEngineMotorMapping.select(
                27, true, -1_000, true, scale, Integer.MIN_VALUE, false,
                new AvasEngineMotorMapping.Scale());
        assertEquals("motor_front", negative.source);
        assertEquals(20, negative.speedKph, 0.001f);
    }

    @Test public void invalidRoadSpeedCannotFreezeMovingOrCoastingEngineAtIdle() {
        AvasEngineMotorMapping.Scale front = new AvasEngineMotorMapping.Scale();
        AvasEngineMotorMapping.Scale rear = new AvasEngineMotorMapping.Scale();
        AvasEngineMotorMapping.Motion motion = AvasEngineMotorMapping.select(
                32, false, 1_200, true, front, -2_400, true, rear);
        assertEquals("motor_rear", motion.source);
        assertEquals(32, motion.speedKph, 0.001f);
        AvasEngineModel model = new AvasEngineModel(900, 6000);
        AvasEngineModel.State state = model.update(0, motion.speedKph, true,
                0, true, 0, true, 4, true);
        assertTrue(state.valid);
        assertTrue(state.rpm > 900);
        assertEquals(0, state.load, 0.001f);
        motion = AvasEngineMotorMapping.select(Float.NaN, false, Integer.MIN_VALUE,
                false, front, -750, true, rear);
        assertEquals(10, motion.speedKph, 0.001f);
        assertEquals("motor_rear", motion.source);
        motion = AvasEngineMotorMapping.select(20, true, Integer.MIN_VALUE, false,
                front, -2_147_482_648, true, rear);
        assertEquals("speed", motion.source);
    }

    private static void observe(AvasEngineMotorMapping.Scale scale, long revision,
            float speed, int motor, long at) {
        scale.observe(speed, true, at, revision, motor, true, at, revision);
    }
}
