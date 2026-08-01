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
}
