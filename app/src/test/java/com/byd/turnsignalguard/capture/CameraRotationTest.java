package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraRotationTest {
    @Test
    public void rotationStaysWithinSupportedRange() {
        assertEquals(-180, CameraRotation.clamp(-999));
        assertEquals(37, CameraRotation.clamp(37));
        assertEquals(180, CameraRotation.clamp(999));
    }

    @Test
    public void fitLetterboxesDifferingAspectWithoutStretching() {
        float[] transform = transform(CameraRotation.MODE_FIT, 0, false);

        assertEquals(1.5f, transform[0], 0.0001f);
        assertEquals(1.5f, transform[4], 0.0001f);
        assertEquals(200.0f, map(transform, 0.0f, 0.0f)[0], 0.0001f);
        assertEquals(1400.0f, map(transform, 800.0f, 600.0f)[0], 0.0001f);
        assertEquals(0.0f, map(transform, 0.0f, 0.0f)[1], 0.0001f);
        assertEquals(900.0f, map(transform, 800.0f, 600.0f)[1], 0.0001f);
    }

    @Test
    public void fillCropsDifferingAspectSymmetricallyWithoutStretching() {
        float[] transform = transform(CameraRotation.MODE_FILL, 0, false);

        assertEquals(2.0f, transform[0], 0.0001f);
        assertEquals(2.0f, transform[4], 0.0001f);
        assertEquals(0.0f, map(transform, 0.0f, 0.0f)[0], 0.0001f);
        assertEquals(1600.0f, map(transform, 800.0f, 600.0f)[0], 0.0001f);
        assertEquals(-150.0f, map(transform, 0.0f, 0.0f)[1], 0.0001f);
        assertEquals(1050.0f, map(transform, 800.0f, 600.0f)[1], 0.0001f);
    }

    @Test
    public void rotationKeepsUniformScaleAndCentersCropAtDifferentAspects() {
        for (int degrees : new int[]{45, 90, -45}) {
            float[] transform = transform(CameraRotation.MODE_FIT, degrees, false);
            float[] center = map(transform, 400.0f, 300.0f);
            assertEquals(800.0f, center[0], 0.0001f);
            assertEquals(450.0f, center[1], 0.0001f);
            float xScale = (float) Math.hypot(transform[0], transform[3]);
            float yScale = (float) Math.hypot(transform[1], transform[4]);
            assertEquals(xScale, yScale, 0.0001f);
        }
    }

    @Test
    public void mirrorPreservesCenterAndVerticalCoordinatesWhileReversingHorizontal() {
        float[] normal = transform(CameraRotation.MODE_FILL, 37, false);
        float[] mirrored = transform(CameraRotation.MODE_FILL, 37, true);
        float[] center = map(normal, 400.0f, 300.0f);
        assertEquals(800.0f, center[0], 0.0001f);
        assertEquals(450.0f, center[1], 0.0001f);
        assertEquals(center[0], map(mirrored, 400.0f, 300.0f)[0], 0.0001f);
        assertEquals(center[1], map(mirrored, 400.0f, 300.0f)[1], 0.0001f);

        float[] normalPoint = map(normal, 520.0f, 330.0f);
        float[] mirroredPoint = map(mirrored, 520.0f, 330.0f);
        assertEquals(1600.0f - normalPoint[0], mirroredPoint[0], 0.0001f);
        assertEquals(normalPoint[1], mirroredPoint[1], 0.0001f);
        assertTrue(determinant(normal) * determinant(mirrored) < 0.0f);
    }

    private static float[] transform(int mode, int degrees, boolean mirror) {
        return CameraRotation.proportionalTransformValues(
                0.0f, 0.0f, 800.0f, 600.0f,
                0.0f, 0.0f, 1600.0f, 900.0f,
                degrees, mode, mirror);
    }

    private static float[] map(float[] transform, float x, float y) {
        return new float[]{
                transform[0] * x + transform[1] * y + transform[2],
                transform[3] * x + transform[4] * y + transform[5]
        };
    }

    private static float determinant(float[] transform) {
        return transform[0] * transform[4] - transform[1] * transform[3];
    }
}
