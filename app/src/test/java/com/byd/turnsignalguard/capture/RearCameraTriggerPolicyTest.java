package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RearCameraTriggerPolicyTest {
    private static final int LEFT = CameraProfile.of(CameraProfile.REAR_LEFT).bit();
    private static final int RIGHT = CameraProfile.of(CameraProfile.REAR_RIGHT).bit();

    @Test
    public void newSettingsDefaultOff() {
        assertEquals(135.0f,
                BlindSpotOverlayController.DEFAULT_REAR_SHARP_TURN_ANGLE_DEG, 0.0f);
        assertEquals(LEFT, BlindSpotOverlayController.desiredCameraMask(
                true, 2, 20.0f, 300.0f,
                true, 10, 300,
                false, 0, 10, true, 10.0f));
    }

    @Test
    public void sharpTurnIsDirectionalAndIncludesExactBoundary() {
        assertEquals(LEFT | RIGHT, desired(2, 20.0f, 135.0f,
                true, 135.0f, false, false, -1, false, -1));
        assertEquals(LEFT | RIGHT, desired(4, 20.0f, -135.0f,
                true, 135.0f, false, false, -1, false, -1));
        assertEquals(LEFT, desired(2, 20.0f, -135.0f,
                true, 135.0f, false, false, -1, false, -1));
        assertEquals(RIGHT, desired(4, 20.0f, 135.0f,
                true, 135.0f, false, false, -1, false, -1));
        assertEquals(LEFT, desired(2, 20.0f, Float.NaN,
                true, 135.0f, false, false, -1, false, -1));
        assertEquals(LEFT, desired(2, 20.0f, 300.0f,
                true, -1.0f, false, false, -1, false, -1));
        assertEquals(LEFT, desired(2, 20.0f, 300.0f,
                true, 781.0f, false, false, -1, false, -1));
    }

    @Test
    public void bsdOnlyFiltersOnlyAuthoritativeInactiveNormalSide() {
        assertEquals(LEFT, desired(2, 20.0f, 0.0f,
                false, 135.0f, true, true, 2, false, -1));
        assertEquals(0, desired(2, 20.0f, 0.0f,
                false, 135.0f, true, true, 0, false, -1));
        assertEquals(0, desired(4, 20.0f, 0.0f,
                false, 135.0f, true, false, -1, true, 1));
        assertEquals(LEFT, desired(2, 20.0f, 0.0f,
                false, 135.0f, true, false, -1, false, -1));
        assertEquals(RIGHT, desired(4, 20.0f, 0.0f,
                false, 135.0f, true, false, -1, true, 9));
    }

    @Test
    public void sharpTurnRemainsAdditiveWhenBsdHidesNormalPane() {
        assertEquals(RIGHT, desired(2, 20.0f, 135.0f,
                true, 135.0f, true, true, 0, false, -1));
        assertEquals(LEFT | RIGHT, desired(2, 20.0f, 135.0f,
                true, 135.0f, true, true, 2, false, -1));
    }

    @Test
    public void rearEnableAndInclusiveSpeedRangeGateBothPanes() {
        assertEquals(0, desired(false, 2, 20.0f, 200.0f,
                true, 135.0f, false, false, -1, false, -1));
        assertEquals(0, desired(2, 9.99f, 200.0f,
                true, 135.0f, false, false, -1, false, -1));
        assertEquals(LEFT | RIGHT, desired(2, 10.0f, 200.0f,
                true, 135.0f, false, false, -1, false, -1));
        assertEquals(LEFT | RIGHT, desired(2, 300.0f, 200.0f,
                true, 135.0f, false, false, -1, false, -1));
        assertEquals(0, desired(2, 300.01f, 200.0f,
                true, 135.0f, false, false, -1, false, -1));
    }

    private static int desired(
            int blink, float speed, float angle,
            boolean sharpTurn, float threshold, boolean bsdOnly,
            boolean leftValid, int leftRaw, boolean rightValid, int rightRaw) {
        return desired(true, blink, speed, angle, sharpTurn, threshold, bsdOnly,
                leftValid, leftRaw, rightValid, rightRaw);
    }

    private static int desired(
            boolean rearEnabled, int blink, float speed, float angle,
            boolean sharpTurn, float threshold, boolean bsdOnly,
            boolean leftValid, int leftRaw, boolean rightValid, int rightRaw) {
        return BlindSpotOverlayController.desiredCameraMask(
                true, blink, speed, angle,
                rearEnabled, 10, 300,
                false, 0, 10, true, 10.0f,
                sharpTurn, threshold, bsdOnly,
                leftValid, leftRaw, rightValid, rightRaw);
    }
}
