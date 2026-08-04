package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CameraRotationTest {
    @Test
    public void rotationStaysWithinSupportedRange() {
        assertEquals(-180, CameraRotation.clamp(-999));
        assertEquals(37, CameraRotation.clamp(37));
        assertEquals(180, CameraRotation.clamp(999));
    }
}
