package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReverseSteeringShiftTest {
    @Test
    public void mapsEachSteeringStopToItsAvailableCropEdge() {
        ReverseCameraLayout.Rect saved = ReverseCameraLayout.sourceCrop(.25f, .2f, .5f, .6f);

        assertCrop(0.0f, 0.2f, 0.5f, 0.6f,
                ReverseSteeringShift.apply(saved, -100.0f, -100.0f, 200.0f));
        assertCrop(0.125f, 0.2f, 0.5f, 0.6f,
                ReverseSteeringShift.apply(saved, -50.0f, -100.0f, 200.0f));
        assertCrop(0.25f, 0.2f, 0.5f, 0.6f,
                ReverseSteeringShift.apply(saved, 0.0f, -100.0f, 200.0f));
        assertCrop(0.375f, 0.2f, 0.5f, 0.6f,
                ReverseSteeringShift.apply(saved, 100.0f, -100.0f, 200.0f));
        assertCrop(0.5f, 0.2f, 0.5f, 0.6f,
                ReverseSteeringShift.apply(saved, 200.0f, -100.0f, 200.0f));
    }

    @Test
    public void edgesAndFullWidthCropHaveOnlyAvailableTravel() {
        ReverseCameraLayout.Rect leftHalf = ReverseCameraLayout.sourceCrop(0.0f, .15f, .5f, .7f);
        ReverseCameraLayout.Rect fullWidth = ReverseCameraLayout.sourceCrop(0.0f, .15f, 1.0f, .7f);

        assertCrop(0.0f, .15f, .5f, .7f,
                ReverseSteeringShift.apply(leftHalf, -100.0f, -100.0f, 100.0f));
        assertCrop(.5f, .15f, .5f, .7f,
                ReverseSteeringShift.apply(leftHalf, 100.0f, -100.0f, 100.0f));
        assertCrop(0.0f, .15f, 1.0f, .7f,
                ReverseSteeringShift.apply(fullWidth, 100.0f, -100.0f, 100.0f));
    }

    @Test
    public void unknownStaleOrInvalidBoundsRestoreSavedCrop() {
        ReverseCameraLayout.Rect saved = ReverseCameraLayout.sourceCrop(.2f, .1f, .6f, .8f);

        assertSame(saved, ReverseSteeringShift.apply(saved, Float.NaN, -100.0f, 100.0f));
        assertSame(saved, ReverseSteeringShift.apply(saved, 101.0f, -100.0f, 100.0f));
        assertSame(saved, ReverseSteeringShift.apply(saved, 50.0f, 0.0f, 100.0f));
        assertSame(saved, ReverseSteeringShift.apply(saved, 50.0f, -100.0f, Float.NaN));
    }

    @Test
    public void repeatedUpdatesAlwaysStartFromSavedCrop() {
        ReverseCameraLayout.Rect saved = ReverseCameraLayout.sourceCrop(.25f, .3f, .5f, .4f);
        ReverseCameraLayout.Rect first =
                ReverseSteeringShift.apply(saved, 50.0f, -100.0f, 100.0f);
        ReverseCameraLayout.Rect repeated =
                ReverseSteeringShift.apply(saved, 50.0f, -100.0f, 100.0f);

        assertEquals(first.left, repeated.left, 0.0f);
        assertCrop(.25f, .3f, .5f, .4f, saved);
    }

    @Test
    public void frontSideModeWithoutIntegratedCentralFrontKeepsCentralRearShiftActive() {
        assertFalse(ReverseSteeringShift.usesCentralFrontSource(true, false, true));
        assertFalse(ReverseSteeringShift.usesCentralFrontSource(true, true, false));
        assertTrue(ReverseSteeringShift.usesCentralFrontSource(true, true, true));
        assertFalse(ReverseSteeringShift.usesCentralFrontSource(false, true, true));
    }

    @Test
    public void displayMirrorAndRotationApplyTheSourceXDirectionOnce() {
        ReverseCameraLayout.Rect saved = ReverseCameraLayout.sourceCrop(.25f, .25f, .5f, .5f);
        ReverseCameraLayout.Rect shifted =
                ReverseSteeringShift.apply(saved, 50.0f, -100.0f, 100.0f);
        assertEquals(.375f, shifted.left, .00001f);

        for (int rotation : new int[]{0, 90, 180, -90}) {
            for (boolean mirror : new boolean[]{false, true}) {
                float[] before = cropTransform(saved, rotation, mirror);
                float[] after = cropTransform(shifted, rotation, mirror);
                float[] beforePoint = map(before, .6f * ReverseCameraCompositionView.SOURCE_WIDTH,
                        .5f * ReverseCameraCompositionView.SOURCE_HEIGHT);
                float[] afterPoint = map(after, .6f * ReverseCameraCompositionView.SOURCE_WIDTH,
                        .5f * ReverseCameraCompositionView.SOURCE_HEIGHT);
                double radians = Math.toRadians(rotation);
                float direction = rotation % 180 == 0
                        ? -((mirror ? -1.0f : 1.0f) * (float) Math.cos(radians))
                        : -(float) Math.sin(radians);
                float movement = rotation % 180 == 0
                        ? afterPoint[0] - beforePoint[0]
                        : afterPoint[1] - beforePoint[1];
                assertEquals(Math.signum(direction), Math.signum(movement), 0.0f);
            }
        }
    }

    private static float[] cropTransform(
            ReverseCameraLayout.Rect crop, int rotation, boolean mirror) {
        return CameraRotation.sourceAwareProportionalTransformValues(
                crop.left, crop.top, crop.width, crop.height,
                0, 0, 800, 400, rotation, CameraRotation.MODE_FIT,
                ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT,
                ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT, mirror);
    }

    private static float[] map(float[] transform, float x, float y) {
        return new float[]{
                transform[0] * x + transform[1] * y + transform[2],
                transform[3] * x + transform[4] * y + transform[5]
        };
    }

    private static void assertCrop(
            float left, float top, float width, float height, ReverseCameraLayout.Rect actual) {
        assertEquals(left, actual.left, 0.00001f);
        assertEquals(top, actual.top, 0.00001f);
        assertEquals(width, actual.width, 0.0f);
        assertEquals(height, actual.height, 0.0f);
    }
}
