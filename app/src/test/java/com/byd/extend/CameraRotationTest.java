package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
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
    public void alignedSourceRoiRemainsAxisAlignedBeforeOutputTransform() {
        assertArrayEquals(new float[]{192.0f, 130.0f, 1152.0f, 130.0f,
                        1152.0f, 910.0f, 192.0f, 910.0f},
                CameraRotation.axisSourceCorners(
                        0.10f, 0.10f, 0.50f, 0.60f,
                        1920, 1300, 1920, 1300), 0.0001f);
    }

    @Test
    public void stretchCropMaskIsTheFixedDestinationPane() {
        assertArrayEquals(new float[]{12.0f, 8.0f, 212.0f, 8.0f,
                        212.0f, 108.0f, 12.0f, 108.0f},
                CameraRotation.fixedOutputCorners(
                        CameraRotation.MODE_ALIGNED, 12.0f, 8.0f, 212.0f, 108.0f),
                0.0001f);
        assertEquals(null, CameraRotation.fixedOutputCorners(
                CameraRotation.MODE_FILL, 12.0f, 8.0f, 212.0f, 108.0f));
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

    @Test
    public void rearFillUsesSamePhysicalTransformForPaneAndLiveInputs() {
        ReverseCameraLayout.Rect crop = ReverseCameraLayout.sourceCrop(
                0.08f, 0.12f, 0.84f, 0.76f);
        float[] pane = CameraRotation.sourceAwareProportionalTransformValues(
                crop.left, crop.top, crop.width, crop.height,
                0.0f, 0.0f, 234.0f, 97.0f,
                -42, CameraRotation.MODE_FILL,
                1920, 1300, 234, 97, false);
        float[] live = CameraRotation.sourceAwareProportionalTransformValues(
                crop.left, crop.top, crop.width, crop.height,
                0.0f, 0.0f, 234.0f, 97.0f,
                -42, CameraRotation.MODE_FILL,
                1920, 1300, 1920, 1300, false);
        assertArrayEquals(new float[]{
                1.4112214f, 2.0754814f, -148.77374f,
                -1.2706695f, 2.3050556f, 85.37313f,
                0.0f, 0.0f, 1.0f
        }, pane, 0.0001f);

        assertCorrespondingPointMapsEqually(pane, 234, 97, live, 1920, 1300,
                0.25f, 0.40f);
        assertCorrespondingPointMapsEqually(pane, 234, 97, live, 1920, 1300,
                0.75f, 0.60f);
    }

    @Test
    public void frontStretchUsesSamePhysicalCornersForPaneAndLiveInputs() {
        float[] pane = CameraRotation.alignedSourceCorners(
                0.11f, 0.07f, 0.81f, 0.86f,
                -45, 1920, 1300, 234, 97);
        float[] live = CameraRotation.alignedSourceCorners(
                0.11f, 0.07f, 0.81f, 0.86f,
                -45, 1920, 1300, 1920, 1300);

        for (int i = 0; i < pane.length; i += 2) {
            assertEquals(live[i] / 1920.0f, pane[i] / 234.0f, 0.0001f);
            assertEquals(live[i + 1] / 1300.0f, pane[i + 1] / 97.0f, 0.0001f);
        }
    }

    @Test
    public void capturedBlindRearLeftAlignedRotationMatchesLiveBitmapGeometry() {
        float[] production = CameraRotation.alignedSourceCorners(
                0.10472298f, 0.18013895f, 0.53600174f, 0.54995716f,
                -32, 1920, 1300, 576, 356);
        float[] live = CameraRotation.alignedSourceCorners(
                0.10472298f, 0.18013895f, 0.53600174f, 0.54995716f,
                -32, 1920, 1300, 1920, 1300);

        for (int i = 0; i < production.length; i += 2) {
            assertEquals(live[i] / 1920.0f, production[i] / 576.0f, 0.0001f);
            assertEquals(live[i + 1] / 1300.0f, production[i + 1] / 356.0f, 0.0001f);
        }
    }

    private static void assertCorrespondingPointMapsEqually(
            float[] first, int firstWidth, int firstHeight,
            float[] second, int secondWidth, int secondHeight,
            float normalizedX, float normalizedY) {
        float[] firstPoint = map(
                first, normalizedX * firstWidth, normalizedY * firstHeight);
        float[] secondPoint = map(
                second, normalizedX * secondWidth, normalizedY * secondHeight);
        assertEquals(secondPoint[0], firstPoint[0], 0.001f);
        assertEquals(secondPoint[1], firstPoint[1], 0.001f);
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
