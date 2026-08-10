package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
    public void lifecycleDetachesAutomaticPreviewAndReopensWithNewIdentity() {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle =
                new CameraProbeActivity.ActivityCameraLifecycle();
        lifecycle.selectTab(1);
        lifecycle.onResume();
        lifecycle.armAutoIntent(1, 0, 0);
        lifecycle.workerEpochChanged(7, 1);
        CameraProbeActivity.ActivityCameraLifecycle.Request first =
                lifecycle.beginRequest(1, 41, true, new int[]{5});
        assertTrue(lifecycle.acceptOpened(
                new CameraEventKey(7, 3, CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                        41, 9, 1)));

        lifecycle.onPause();
        lifecycle.armAutoIntent(1, first.requestId, first.consumerGeneration);
        lifecycle.detach(false);
        lifecycle.onResume();

        assertTrue(lifecycle.shouldOpenAutomatic(0));
        CameraProbeActivity.ActivityCameraLifecycle.Request second =
                lifecycle.beginRequest(1, 42, true, new int[]{6});
        assertNotEquals(first.requestId, second.requestId);
        assertEquals(0, second.consumerGeneration);
        assertNotEquals(first.inputGenerations[0], second.inputGenerations[0]);
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

        // The serialized register completes after stop requested its matching detach.
        assertTrue(callbacks.register(callback, generation));
        assertFalse(registration.registered(register));
        assertTrue(callbacks.detach(callback, generation));
        registration.detached(detach);

        assertNull(callbacks.current());
        assertFalse(registration.registered());
    }

    @Test
    public void staleActivityARegisterDetachAndDeathCannotReplaceActivityB() {
        CameraHelperMain.CallbackSlot<Consumer<String>> callbacks =
                new CameraHelperMain.CallbackSlot<>();
        int[] openedA = {0};
        int[] openedB = {0};
        Consumer<String> callbackA = event -> openedA[0]++;
        Consumer<String> callbackB = event -> openedB[0]++;

        assertTrue(callbacks.register(callbackA, 1));
        callbackA.accept("helper_connected");
        assertTrue(callbacks.register(callbackB, 2));
        callbackB.accept("helper_connected");
        boolean staleAccepted = callbacks.register(callbackA, 1);
        if (staleAccepted) callbackA.accept("helper_connected");
        assertFalse(staleAccepted);
        assertFalse(callbacks.detach(callbackA, 1));
        // The death recipient uses the same exact-pair detach path.
        assertFalse(callbacks.detach(callbackA, 1));
        callbacks.current().accept("camera_opened");

        assertSame(callbackB, callbacks.current());
        assertEquals(1, openedA[0]);
        assertEquals(2, openedB[0]);
    }

    @Test
    public void rapidStopResumeFinalRegistrationReceivesOpenedEvent() {
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
        assertTrue(resumedGeneration > firstGeneration);
        CameraProbeActivity.HelperCallbackRegistration.Operation<Object> resumed =
                registration.queue(helper, resumedGeneration);

        // The serialized detach runs before the resume registration.
        assertTrue(callbacks.detach(callback, detach.generation));
        registration.detached(detach);
        assertTrue(callbacks.register(callback, resumedGeneration));
        assertTrue(registration.registered(resumed));
        assertFalse(callbacks.detach(callback, firstGeneration));
        callbacks.current().accept("camera_opened");

        assertEquals(1, opened[0]);
        assertTrue(registration.registered());
    }
}
