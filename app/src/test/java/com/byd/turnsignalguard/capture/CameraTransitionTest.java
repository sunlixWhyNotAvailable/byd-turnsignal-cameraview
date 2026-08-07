package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class CameraTransitionTest {
    @Test
    public void staleCloseCannotCompleteNewerTransition() {
        CameraTransition transition = new CameraTransition();
        String first = transition.begin("camera_tab_changed");
        String second = transition.begin("camera_tab_changed");

        assertNotEquals(first, second);
        assertTrue(CameraTransition.owns(first));
        assertFalse(transition.complete(first));
        assertTrue(transition.pending());
        assertTrue(transition.complete(second));
        assertFalse(transition.pending());
    }

    @Test
    public void cancelRejectsLateCompletion() {
        CameraTransition transition = new CameraTransition();
        String token = transition.begin("camera_tab_changed");

        transition.cancel();

        assertFalse(transition.complete(token));
        assertFalse(transition.pending());
    }

    @Test
    public void reverseInputResetWaitsForEveryNewSurfaceGeneration() {
        int[] previous = {1, 1, 1};

        assertFalse(ReverseCameraCompositionView.generationsAdvanced(
                previous, new int[]{2, 1, 2}));
        assertFalse(ReverseCameraCompositionView.generationsAdvanced(
                previous, new int[]{1, 1, 1}));
        assertTrue(ReverseCameraCompositionView.generationsAdvanced(
                previous, new int[]{2, 2, 2}));
    }
}
