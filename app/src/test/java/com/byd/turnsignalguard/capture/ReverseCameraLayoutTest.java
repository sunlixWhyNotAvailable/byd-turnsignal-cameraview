package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReverseCameraLayoutTest {
    @Test
    public void backgroundStaysBelowIndependentCameraOrdering() {
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        assertEquals(0.0f, layout.background.left, 0.0f);
        assertEquals(0.0f, layout.background.top, 0.0f);
        assertEquals(1.0f, layout.background.width, 0.0f);
        assertEquals(1.0f, layout.background.height, 0.0f);

        ReverseCameraLayout moved = ReverseCameraLayout.withBackground(layout,
                ReverseCameraLayout.destination(0.1f, 0.2f, 0.7f, 0.6f));
        assertEquals(0.1f, moved.background.left, 0.0001f);
        assertEquals(0.2f, moved.background.top, 0.0001f);
        assertEquals(layout.rear.zOrder, moved.rear.zOrder);
        assertEquals(layout.rearLeft.zOrder, moved.rearLeft.zOrder);
        assertEquals(layout.rearRight.zOrder, moved.rearRight.zOrder);
    }

    @Test
    public void onlyCentralRearCameraIsMirrored() {
        assertTrue(ReverseCameraLayout.mirrorHorizontally(
                ReverseCameraLayout.REAR_CAMERA_INDEX));
        assertFalse(ReverseCameraLayout.mirrorHorizontally(
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
        assertFalse(ReverseCameraLayout.mirrorHorizontally(
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX));
    }

    @Test
    public void nudgeMovesSelectedPaneAndClampsToCanvas() {
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        ReverseCameraLayout moved = ReverseCameraLayout.move(layout,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 0.01f, -0.01f);
        assertEquals(0.01f, moved.rearLeft.destination.left, 0.0001f);
        assertEquals(0.49f, moved.rearLeft.destination.top, 0.0001f);

        ReverseCameraLayout clamped = ReverseCameraLayout.move(moved,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 2.0f, 2.0f);
        assertEquals(0.5f, clamped.rearLeft.destination.left, 0.0001f);
        assertEquals(0.5f, clamped.rearLeft.destination.top, 0.0001f);
    }
}
