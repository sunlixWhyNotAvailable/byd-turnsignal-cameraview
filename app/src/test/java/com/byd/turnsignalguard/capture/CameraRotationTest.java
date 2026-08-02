package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CameraRotationTest {
    @Test
    public void rotationChangesBoundsAndPreservesContentAspect() {
        assertEquals(4.0f / 3.0f,
                CameraRotation.rotatedAspect(4.0f / 3.0f, 0), 0.0001f);
        assertEquals(3.0f / 4.0f,
                CameraRotation.rotatedAspect(4.0f / 3.0f, 90), 0.0001f);
        assertEquals(1.0f,
                CameraRotation.rotatedAspect(4.0f / 3.0f, 45), 0.0001f);

        float[] unrotated = CameraRotation.scaleToRotatedBounds(
                400, 300, 4.0f / 3.0f, 0);
        assertEquals(1.0f, unrotated[0], 0.0001f);
        assertEquals(1.0f, unrotated[1], 0.0001f);
        float[] quarterTurn = CameraRotation.scaleToRotatedBounds(
                300, 400, 4.0f / 3.0f, 90);
        assertEquals(4.0f / 3.0f, quarterTurn[0], 0.0001f);
        assertEquals(3.0f / 4.0f, quarterTurn[1], 0.0001f);
        assertEquals(300, CameraRotation.rotatedBounds(400, 300, 90)[0]);
        assertEquals(400, CameraRotation.rotatedBounds(400, 300, 90)[1]);
    }
}
