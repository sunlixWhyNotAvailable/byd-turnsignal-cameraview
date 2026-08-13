package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class CameraTransitionTest {
    @Test
    public void staleCloseCannotCompleteNewerTransition() {
        CameraTransition transition = new CameraTransition();
        String first = transition.begin("camera_tab_changed");
        String second = transition.begin("camera_tab_changed");

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
    public void transitionReasonMatchesOnlyItsOwnToken() {
        CameraTransition transition = new CameraTransition();
        String cold = transition.begin(CameraHelperMain.ACTIVITY_RESUME_COLD_RESET);

        assertTrue(transition.matches(cold));
        assertTrue(CameraTransition.reasonEquals(
                cold, CameraHelperMain.ACTIVITY_RESUME_COLD_RESET));
        assertFalse(CameraTransition.reasonEquals(cold, "camera_tab_changed"));
        assertFalse(transition.matches(
                "activity_transition:99:activity_resume_cold_reset"));
    }

    @Test
    public void foregroundTabSwitchDoesNotRequireLifecycleReset() {
        assertFalse(CameraProbeActivity.shouldDeferActivityPreviewForReverse(0));
        assertTrue(CameraProbeActivity.shouldResumeActivityPreviewAfterStop(
                false, true, true, false, false));
        assertFalse(CameraProbeActivity.shouldAutoRecoverAfterCameraShellDeath(true));
        assertTrue(CameraProbeActivity.shouldAutoRecoverAfterCameraShellDeath(false));
    }

    @Test
    public void idleTabInputRenewalWaitsForEveryPriorCloseState() {
        assertTrue(CameraProbeActivity.shouldRenewIdleTabInput(
                false, false, false, false, 0));
        assertFalse(CameraProbeActivity.shouldRenewIdleTabInput(
                true, false, false, false, 0));
        assertFalse(CameraProbeActivity.shouldRenewIdleTabInput(
                false, true, false, false, 0));
        assertFalse(CameraProbeActivity.shouldRenewIdleTabInput(
                false, false, true, false, 0));
        assertFalse(CameraProbeActivity.shouldRenewIdleTabInput(
                false, false, false, true, 0));
        assertFalse(CameraProbeActivity.shouldRenewIdleTabInput(
                false, false, false, false, 73));
    }

    @Test
    public void firstCameraAfterAnyResumeRepeatsExactTabTransitionOnce() {
        CameraProbeActivity.ResumeTabWarmup warmup =
                new CameraProbeActivity.ResumeTabWarmup();

        warmup.stopped();
        assertTrue(warmup.required());
        warmup.requested(41);
        assertFalse(warmup.opened(42, 41));
        assertFalse(warmup.opened(41, 42));
        assertTrue(warmup.opened(41, 41));
        assertFalse(warmup.required());
        assertFalse(warmup.opened(41, 41));
    }

    @Test
    public void stockShellCloseErrorFailsOnlyMatchingClose() {
        assertTrue(CameraProbeActivity.isMatchingStockShellCloseError(
                "stock_avm_shell", "close", 51, 51));
        assertFalse(CameraProbeActivity.isMatchingStockShellCloseError(
                "stock_avm_shell", "open", 51, 51));
        assertFalse(CameraProbeActivity.isMatchingStockShellCloseError(
                "stock_avm_shell", "close", 51, 50));
        assertFalse(CameraProbeActivity.isMatchingStockShellCloseError(
                "direct_crop_calibration", "close", 51, 51));
    }

    @Test
    public void stoppedActivityDoesNotDoubleClosePendingTabTransition() {
        assertTrue(CameraProbeActivity.shouldIssueActivityStoppedClose(false));
        assertFalse(CameraProbeActivity.shouldIssueActivityStoppedClose(true));
    }

    @Test
    public void failedTransitionIsScopedAndCannotBlockResumeForever() {
        CameraTransition transition = new CameraTransition();
        String first = transition.begin("camera_tab_changed");
        String current = transition.begin("camera_tab_changed");

        assertFalse(CameraProbeActivity.resolveFailedCameraTransition(transition, first));
        assertTrue(transition.pending());
        assertTrue(CameraProbeActivity.resolveFailedCameraTransition(transition, current));
        assertFalse(transition.pending());
    }

    @Test
    public void lateCloseCannotRecreateInputAfterDestroyOrShutdown() {
        assertTrue(CameraProbeActivity.shouldRenewSelectedInputAfterClose(false, false));
        assertFalse(CameraProbeActivity.shouldRenewSelectedInputAfterClose(true, false));
        assertFalse(CameraProbeActivity.shouldRenewSelectedInputAfterClose(false, true));
    }

    @Test
    public void tabTransitionDoesNotRecreateInputCreatedByFirstVisibility() {
        assertFalse(CameraProbeActivity.shouldRenewTransitionInputAfterClose(
                true, false, false, false));
        assertTrue(CameraProbeActivity.shouldRenewTransitionInputAfterClose(
                true, true, false, false));
        assertFalse(CameraProbeActivity.shouldRenewTransitionInputAfterClose(
                false, true, false, false));
        assertFalse(CameraProbeActivity.shouldRenewTransitionInputAfterClose(
                true, true, true, false));
        assertFalse(CameraProbeActivity.shouldRenewTransitionInputAfterClose(
                true, true, false, true));
    }

    @Test
    public void failedCloseReplyCannotCompleteTabTransition() {
        CameraTransition transition = new CameraTransition();
        String token = transition.begin("camera_tab_changed");

        assertFalse(CameraProbeActivity.shouldCompleteCameraTransitionClose(
                true, false, false));
        assertFalse(CameraProbeActivity.shouldCompleteCameraTransitionClose(
                false, false, false));
        assertFalse(CameraProbeActivity.shouldCompleteCameraTransitionClose(
                false, true, false));
        assertTrue(transition.matches(token));
        assertTrue(CameraProbeActivity.shouldCompleteCameraTransitionClose(
                false, false, true));
        assertTrue(transition.complete(token));
    }

    @Test
    public void deferredIdleEntryRenewsOnlyAfterMatchingCloseCompletes() {
        assertFalse(CameraProbeActivity.shouldRenewIdleTabInput(
                false, false, false, false, 91));
        assertTrue(CameraProbeActivity.shouldRenewIdleTabInput(
                false, false, false, false, 0));
        assertTrue(CameraProbeActivity.shouldWaitForStockShellClose(
                "reverse_preview_with_stock_base", ""));
        assertFalse(CameraProbeActivity.shouldWaitForStockShellClose(
                "reverse_preview_with_stock_base", "stock_avm_shell"));
        assertFalse(CameraProbeActivity.shouldWaitForStockShellClose(
                "direct_pano_h_index_2", ""));
    }

    @Test
    public void reverseStopRestoresOnlyMatchingAutomaticIntent() {
        assertTrue(CameraProbeActivity.shouldHandleReverseCameraStopped(true, 31, 31));
        assertFalse(CameraProbeActivity.shouldHandleReverseCameraStopped(true, 31, 30));
        assertFalse(CameraProbeActivity.shouldHandleReverseCameraStopped(false, 31, 31));
        assertEquals(0, CameraProbeActivity.reverseRequestAfterStopped(31, 31));
        assertEquals(31, CameraProbeActivity.reverseRequestAfterStopped(31, 30));
        assertTrue(CameraProbeActivity.shouldResumeAfterReverseCameraState(true, 31, 0));
        assertFalse(CameraProbeActivity.shouldResumeAfterReverseCameraState(false, 31, 0));
    }

    @Test
    public void helperReverseSnapshotReplaysControllerEndState() {
        int active = CameraHelperMain.updateReverseControllerRequestId(
                0, "reverse_camera_start", 31);
        assertEquals(31, active);
        active = CameraHelperMain.updateReverseControllerRequestId(
                active, "reverse_camera_stopped", 31);
        assertEquals(0, active);
        assertTrue(CameraProbeActivity.shouldResumeAfterReverseCameraState(
                true, 31, 0));
        assertTrue(CameraHelperMain.HelperBinder.shouldDeferActivityColdReset(31));
        assertFalse(CameraHelperMain.HelperBinder.shouldDeferActivityColdReset(0));
    }

    @Test
    public void reverseDetachRequiresMatchingRequest() {
        assertTrue(ReverseCameraController.matchesCameraOpenEvent(44, 44));
        assertFalse(ReverseCameraController.matchesCameraOpenEvent(44, 43));
        assertFalse(ReverseCameraController.matchesCameraOpenEvent(0, 44));
    }

    @Test
    public void overlayHardBlockIncludesEveryLifecycleFlag() {
        assertFalse(BlindSpotOverlayController.isHardBlocked(false, false, false));
        assertTrue(BlindSpotOverlayController.isHardBlocked(true, false, false));
        assertTrue(BlindSpotOverlayController.isHardBlocked(false, true, false));
        assertTrue(BlindSpotOverlayController.isHardBlocked(false, false, true));
    }

    @Test
    public void overlayHardBlockDispatchesOnlyAggregateEdges() {
        boolean blocked = false;
        int enters = 0;
        int exits = 0;
        boolean[][] states = {
                {false, false, false},
                {true, false, false},
                {true, true, false},
                {false, true, false},
                {false, false, false},
                {false, false, true},
                {true, false, true},
                {true, false, false},
                {false, false, false}
        };
        for (boolean[] state : states) {
            boolean next = BlindSpotOverlayController.isHardBlocked(
                    state[0], state[1], state[2]);
            int edge = BlindSpotOverlayController.hardBlockEdge(blocked, next);
            if (edge > 0) enters++;
            if (edge < 0) exits++;
            if (edge != 0) blocked = next;
        }

        assertEquals(2, enters);
        assertEquals(2, exits);
        assertFalse(blocked);
    }

    @Test
    public void delayedRegisterFollowedByStopLeavesNoCallback() {
        CameraProbeActivity.HelperCallbackRegistration<Object> registration =
                new CameraProbeActivity.HelperCallbackRegistration<>();
        CameraHelperMain.CallbackSlot<Object> callbacks = new CameraHelperMain.CallbackSlot<>();
        Object helper = new Object();
        Object callback = new Object();
        long generation = CameraProbeActivity.nextHelperCallbackRegistrationGeneration();

        assertNull(registration.start());
        assertSame(helper, registration.connected(helper));
        CameraProbeActivity.HelperCallbackRegistration.Operation<Object> register =
                registration.queue(helper, generation);
        CameraProbeActivity.HelperCallbackRegistration.Operation<Object> detach =
                registration.stop();
        assertSame(helper, detach.connection);
        assertEquals(generation, detach.generation);

        assertTrue(callbacks.register(callback, generation));
        assertFalse(registration.registered(register));
        assertTrue(callbacks.detach(callback, generation));
        registration.detached(detach);
        assertNull(callbacks.current());
        assertFalse(registration.registered());
    }

    @Test
    public void rapidStopResumeFinalRegistrationWins() {
        Object helper = new Object();
        CameraProbeActivity.HelperCallbackRegistration<Object> registration =
                new CameraProbeActivity.HelperCallbackRegistration<>();
        CameraHelperMain.CallbackSlot<Consumer<String>> callbacks =
                new CameraHelperMain.CallbackSlot<>();
        int[] opened = {0};
        Consumer<String> callback = event -> opened[0]++;

        assertNull(registration.start());
        assertSame(helper, registration.connected(helper));
        long firstGeneration = CameraProbeActivity.nextHelperCallbackRegistrationGeneration();
        CameraProbeActivity.HelperCallbackRegistration.Operation<Object> first =
                registration.queue(helper, firstGeneration);
        assertTrue(callbacks.register(callback, firstGeneration));
        assertTrue(registration.registered(first));

        CameraProbeActivity.HelperCallbackRegistration.Operation<Object> detach =
                registration.stop();
        assertSame(helper, detach.connection);
        assertSame(helper, registration.start());
        long resumedGeneration = CameraProbeActivity.nextHelperCallbackRegistrationGeneration();
        CameraProbeActivity.HelperCallbackRegistration.Operation<Object> resumed =
                registration.queue(helper, resumedGeneration);
        assertTrue(callbacks.detach(callback, detach.generation));
        registration.detached(detach);
        assertTrue(callbacks.register(callback, resumedGeneration));
        assertTrue(registration.registered(resumed));
        callbacks.current().accept("camera_opened");

        assertEquals(1, opened[0]);
        assertTrue(registration.registered());
    }
}
