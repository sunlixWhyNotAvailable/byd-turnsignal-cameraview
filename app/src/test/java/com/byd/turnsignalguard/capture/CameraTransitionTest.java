package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
    public void reverseDetachRequiresMatchingRequestAndCompletesWithoutShellRestart() {
        assertTrue(ReverseCameraController.matchesCameraOpenEvent(44, 44));
        assertFalse(ReverseCameraController.matchesCameraOpenEvent(44, 43));
        assertFalse(ReverseCameraController.matchesCameraOpenEvent(0, 44));
        assertFalse(ReverseCameraController.shouldAttemptReverseCameraClose(true, true));
        assertTrue(ReverseCameraController.shouldAttemptReverseCameraClose(false, true));
        assertTrue(ReverseCameraController.shouldAttemptReverseCameraClose(false, false));
    }

    @Test
    public void overlayPriorityHandoffPreservesPreparedSessionForRearm() {
        assertFalse(BlindSpotOverlayController.shouldRearmPreparedOverlay(true, true));
        assertTrue(BlindSpotOverlayController.shouldRearmPreparedOverlay(false, true));
        assertFalse(BlindSpotOverlayController.shouldRearmPreparedOverlay(false, false));
    }

    @Test
    public void lifecycleReopensAutomaticPreviewButNotManualStop() {
        assertTrue(CameraProbeActivity.shouldResumeActivityPreviewAfterStop(
                false, true, true, false, false));
        assertTrue(CameraProbeActivity.shouldResumeActivityPreviewAfterStop(
                false, true, false, true, false));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreviewAfterStop(
                false, true, false, false, false));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreviewAfterStop(
                false, false, true, false, false));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreviewAfterStop(
                true, true, true, false, false));
    }

    @Test
    public void lifecycleRetainsAnAttachedAutomaticPreviewWithoutReopen() {
        assertTrue(CameraProbeActivity.shouldRetainActivityPreviewAcrossStop(
                false, true, true, false, false, true, 41));
        assertFalse(CameraProbeActivity.shouldRetainActivityPreviewAcrossStop(
                false, true, false, false, false, true, 41));
        assertFalse(CameraProbeActivity.shouldRetainActivityPreviewAcrossStop(
                false, true, true, true, false, true, 41));
        assertFalse(CameraProbeActivity.shouldRetainActivityPreviewAcrossStop(
                false, true, true, false, true, true, 41));
        assertFalse(CameraProbeActivity.shouldRetainActivityPreviewAcrossStop(
                false, true, true, false, false, false, 41));
        assertFalse(CameraProbeActivity.shouldRetainActivityPreviewAcrossStop(
                false, true, true, false, false, true, 0));
        assertFalse(CameraProbeActivity.shouldRetainActivityPreviewAcrossStop(
                true, true, true, false, false, true, 41));
    }

    @Test
    public void automaticActivityPreviewConsumersAreCompatibleWithPreparedOverlay() {
        assertFalse(CameraProbeActivity.activityPreviewUsesExclusiveConsumer(false));
        assertTrue(CameraProbeActivity.activityPreviewUsesExclusiveConsumer(true));
    }

    @Test
    public void lifecycleRestoreRequiresResumedCurrentIntentTabAndRequest() {
        assertTrue(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, false, true, 1, 1, 12, 0));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreview(
                false, false, false, true, 1, 1, 12, 0));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, false, false, 1, 1, 12, 0));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, false, true, 5, 1, 12, 0));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, false, true, 1, 1, 12, 11));
        assertTrue(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, false, true, 1, 1, 12, 12));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreview(
                true, true, false, true, 1, 1, 12, 0));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, true, true, 1, 1, 12, 0));
        assertFalse(CameraProbeActivity.shouldDeferActivityPreviewForReverse(0));
        assertTrue(CameraProbeActivity.shouldDeferActivityPreviewForReverse(31));
        assertTrue(CameraProbeActivity.shouldAutoOpenSelectedPreview(true, 0));
        assertFalse(CameraProbeActivity.shouldAutoOpenSelectedPreview(true, 31));
        assertFalse(CameraProbeActivity.shouldAutoOpenSelectedPreview(false, 0));
    }

    @Test
    public void unchangedLifecycleCameraPoliciesDoNotNotifyService() {
        assertFalse(CameraProbeActivity.cameraPolicyChanged(1, 300, 1, 300));
        assertTrue(CameraProbeActivity.cameraPolicyChanged(1, 300, 2, 300));
        assertFalse(CameraProbeActivity.frontCameraPolicyChanged(
                0, 30, 10.0f, 0, 30, 10.0f));
        assertTrue(CameraProbeActivity.frontCameraPolicyChanged(
                0, 30, 10.0f, 0, 30, 11.0f));
    }

    @Test
    public void lateReverseStopCannotReopenManualOrStalePreview() {
        boolean autoIntent = true;
        assertTrue(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, false, autoIntent, 5, 5, 21, 21));

        // Manual Stop consumes the auto intent before a queued reverse stop arrives.
        autoIntent = false;
        assertFalse(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, false, autoIntent, 5, 5, 21, 21));

        // A tab switch invalidates the old owner even if the old request id arrives later.
        autoIntent = true;
        assertFalse(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, false, autoIntent, 1, 5, 21, 21));
        assertFalse(CameraProbeActivity.shouldResumeActivityPreview(
                true, false, false, autoIntent, 5, 5, 21, 20));
        assertTrue(CameraProbeActivity.shouldHandleReverseCameraStopped(
                true, 31, 31));
        assertFalse(CameraProbeActivity.shouldHandleReverseCameraStopped(
                true, 31, 30));
        assertFalse(CameraProbeActivity.shouldHandleReverseCameraStopped(
                false, 31, 31));
        assertFalse(CameraProbeActivity.shouldHandleReverseCameraStopped(
                true, 0, 31));
        assertEquals(0, CameraProbeActivity.reverseRequestAfterStopped(31, 31));
        assertEquals(31, CameraProbeActivity.reverseRequestAfterStopped(31, 30));
        assertTrue(CameraProbeActivity.shouldResumeAfterReverseCameraState(
                true, 31, 0));
        assertFalse(CameraProbeActivity.shouldResumeAfterReverseCameraState(
                false, 31, 0));
        assertEquals(0, CameraProbeActivity.normalizedReverseCameraRequestId(-1));
    }

    @Test
    public void reverseTakeoverArmsRestoreButManualStopDoesNot() {
        assertTrue(CameraProbeActivity.shouldArmAutoPreviewAfterTakeover(
                false, true, true, "replace_with_multi_preview"));
        assertFalse(CameraProbeActivity.shouldArmAutoPreviewAfterTakeover(
                false, true, true, "user_close"));
        assertFalse(CameraProbeActivity.shouldArmAutoPreviewAfterTakeover(
                true, true, true, "replace_with_multi_preview"));
        assertFalse(CameraProbeActivity.shouldArmAutoPreviewAfterTakeover(
                false, false, true, "replace_with_multi_preview"));

        assertTrue(CameraProbeActivity.shouldHandleReverseCameraStopped(
                true, 31, 31));
        assertFalse(CameraProbeActivity.shouldHandleReverseCameraStopped(
                false, 31, 31));
    }

    @Test
    public void reverseStateSurvivesCallbackDetachAndReplaysIdle() {
        int state = CameraHelperMain.updateReverseControllerRequestId(
                0, "reverse_camera_start", 44);
        assertEquals(44, state);

        // Callback detachment does not mutate the helper-owned controller state.
        state = CameraHelperMain.updateReverseControllerRequestId(
                state, "reverse_camera_stopped", 43);
        assertEquals(44, state);
        state = CameraHelperMain.updateReverseControllerRequestId(
                state, "reverse_camera_stopped", 44);
        assertEquals(0, state);
    }

    @Test
    public void terminalCameraEventsRejectStaleRequestsWhenIdle() {
        assertTrue(CameraProbeActivity.isCurrentActivityCameraTerminalEvent(0, -1));
        assertTrue(CameraProbeActivity.isCurrentActivityCameraTerminalEvent(14, 14));
        assertFalse(CameraProbeActivity.isCurrentActivityCameraTerminalEvent(0, 13));
        assertFalse(CameraProbeActivity.isCurrentActivityCameraTerminalEvent(14, 13));
    }
}
