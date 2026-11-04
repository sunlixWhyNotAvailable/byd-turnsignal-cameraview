package com.byd.extend;

import android.content.Intent;

import org.junit.Test;

import static org.junit.Assert.*;

public class OemCameraVisibilityRuntimeTest {
    @Test public void liveCloseSurvivesStalePropertyAndNewReceiverCanBootstrap() {
        OemCameraVisibilityRuntime.State state = new OemCameraVisibilityRuntime.State();
        assertTrue(state.accept("1", false));
        assertTrue(state.visible());
        // Recorded sequence: close broadcast, then property=1 23 ms later.
        assertTrue(state.accept("0", true));
        assertTrue(state.hasLiveState());
        assertTrue(state.accept("1", false));
        assertFalse(state.visible());
        assertTrue(state.accept("1", true));
        assertTrue(state.visible());
        state.accept("0", false);
        assertTrue(state.visible());
        state.invalidate();
        assertFalse(state.hasLiveState());
        assertTrue(state.accept("0", false));
        assertFalse(state.visible());
    }

    @Test public void stateParserTracksVisibleClosedAndUnknownInputs() {
        OemCameraVisibilityRuntime.State state = new OemCameraVisibilityRuntime.State();
        assertFalse(state.known());
        assertEquals(-1, state.panoState());

        assertTrue(state.accept(" 1 ", true));
        assertTrue(state.known());
        assertTrue(state.visible());

        assertFalse(state.accept("invalid", true));
        assertFalse(state.known());
        assertFalse(state.visible());
        assertEquals(-1, state.panoState());

        assertTrue(state.accept("0", true));
        assertTrue(state.known());
        assertFalse(state.visible());

        assertFalse(state.accept(null, true));
        assertFalse(state.known());
        state.invalidate();
        assertEquals(-1, state.panoState());
    }

    @Test public void panoReconcilesOnWakeAndResumeActionsOnly() {
        assertTrue(CameraRuntimeClient.shouldReconcilePano(
                CameraRuntimeClient.powerEvent(Intent.ACTION_SCREEN_ON)));
        assertTrue(CameraRuntimeClient.shouldReconcilePano(
                CameraRuntimeClient.powerEvent(Intent.ACTION_USER_PRESENT)));
        assertTrue(CameraRuntimeClient.shouldReconcilePano(
                CameraRuntimeClient.powerEvent("android.intent.action.QUICKBOOT_POWERON")));
        assertFalse(CameraRuntimeClient.shouldReconcilePano(
                CameraRuntimeClient.powerEvent(Intent.ACTION_SCREEN_OFF)));
        assertFalse(CameraRuntimeClient.shouldReconcilePano(
                CameraRuntimeClient.powerEvent("android.media.VOLUME_CHANGED_ACTION")));
        assertFalse(CameraRuntimeClient.shouldReconcilePano(
                CameraRuntimeClient.powerEvent(null)));
    }
}
