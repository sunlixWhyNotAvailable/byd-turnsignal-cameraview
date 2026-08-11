package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ActivityCameraLifecycleTest {
    @Test
    public void freshGateAcceptsOnlyCurrentRequestAndInputGeneration() {
        CameraProbeActivity.PreviewFreshnessGate gate =
                new CameraProbeActivity.PreviewFreshnessGate();
        gate.arm(12, new int[]{3});

        assertFalse(gate.accept(11, new int[]{3}));
        assertFalse(gate.accept(12, new int[]{4}));
        assertTrue(gate.accept(12, new int[]{3}));
        gate.clear();
        assertFalse(gate.accept(12, new int[]{3}));
    }

    @Test
    public void coldResetReasonIsBoundToTransitionToken() {
        CameraTransition transition = new CameraTransition();
        String token = transition.begin(CameraHelperMain.ACTIVITY_RESUME_COLD_RESET);

        assertTrue(CameraTransition.reasonEquals(
                token, CameraHelperMain.ACTIVITY_RESUME_COLD_RESET));
        assertFalse(CameraTransition.reasonEquals(token, "camera_tab_changed"));
        assertFalse(CameraTransition.reasonEquals(
                "activity_transition:9:camera_tab_changed",
                CameraHelperMain.ACTIVITY_RESUME_COLD_RESET));
    }

    @Test
    public void coldResetResultRejectsErrorsAndAcceptsCleanClose() {
        assertTrue(CameraProbeActivity.isSuccessfulColdResetResult(
                "camera_closed", ""));
        assertTrue(CameraProbeActivity.isSuccessfulColdResetResult(
                "already_closed", ""));
        assertFalse(CameraProbeActivity.isSuccessfulColdResetResult(
                "stock_avm_shell_close_queued", ""));
        assertTrue(CameraProbeActivity.isQueuedColdResetResult(
                "stock_avm_shell_close_queued", ""));
        assertFalse(CameraProbeActivity.isQueuedColdResetResult(
                "stock_avm_shell_close_queued", "vendor"));
        assertTrue(CameraProbeActivity.isDeferredColdResetResult(
                CameraHelperMain.COLD_RESET_DEFERRED_REVERSE, ""));
        assertFalse(CameraProbeActivity.isDeferredColdResetResult(
                CameraHelperMain.COLD_RESET_DEFERRED_REVERSE, "vendor"));
        assertFalse(CameraProbeActivity.isSuccessfulColdResetResult(
                "camera_error", "close failed"));
        assertFalse(CameraProbeActivity.isSuccessfulColdResetResult(
                "camera_closed", "vendor"));
        assertFalse(CameraProbeActivity.isSuccessfulColdResetResult("", ""));
    }

    @Test
    public void queuedColdResetCompletesOnlyOnMatchingShellCallback() {
        CameraTransition transition = new CameraTransition();
        String token = transition.begin(CameraHelperMain.ACTIVITY_RESUME_COLD_RESET);

        assertTrue(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, token, "stock_avm_shell", "camera_closed", "", 27, 27));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, token, "pano_h", "camera_closed", "", 27, 27));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, token, "stock_avm_shell", "camera_closed", "", 27, 26));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, token, "stock_avm_shell", "camera_closed", "", 0, 0));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, "activity_stopped", "stock_avm_shell", "camera_closed", "", 27, 27));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                "activity_transition:99:activity_resume_cold_reset", token,
                "stock_avm_shell", "camera_closed", "", 27, 27));
    }

    @Test
    public void stoppedActivityRequiresColdResetEvenWithoutPreviewIntent() {
        assertTrue(CameraProbeActivity.shouldRetainColdResetAfterCancel(
                false, false));
        assertFalse(CameraProbeActivity.shouldRetainColdResetAfterCancel(
                true, false));
        assertFalse(CameraProbeActivity.shouldRetainColdResetAfterCancel(
                false, true));
    }

    @Test
    public void coldResetShellRequestUsesSavedClosingRequestBeforeIntentFallback() {
        assertEquals(27, CameraProbeActivity.expectedColdResetShellRequestId(27, 31));
        assertEquals(31, CameraProbeActivity.expectedColdResetShellRequestId(0, 31));
        assertEquals(0, CameraProbeActivity.expectedColdResetShellRequestId(0, 0));
    }

    @Test
    public void shellCloseEventRequiresStockRendererAndEmptyError() {
        assertTrue(CameraProbeActivity.isSuccessfulShellCloseEvent(
                "stock_avm_shell", "camera_closed", ""));
        assertFalse(CameraProbeActivity.isSuccessfulShellCloseEvent(
                "pano_h", "camera_closed", ""));
        assertFalse(CameraProbeActivity.isSuccessfulShellCloseEvent(
                "stock_avm_shell", "camera_closed", "close failed"));
        assertFalse(CameraProbeActivity.isSuccessfulShellCloseEvent(
                "stock_avm_shell", "camera_opened", ""));
    }

    @Test
    public void inputRetirementCreatesNewLogicalGeneration() {
        BlindSpotCameraView.InputGeneration generation =
                new BlindSpotCameraView.InputGeneration();
        int first = generation.next();
        int second = generation.next();

        assertTrue(first > 0);
        assertTrue(second > 0);
        assertTrue(first != second);
        assertTrue(generation.frame() != second);
        assertSame(Integer.valueOf(second), Integer.valueOf(generation.frame()));
    }

    @Test
    public void activityEventsRequireHelperSourceAndExactRequest() {
        assertTrue(CameraProbeActivity.isCurrentActivityCameraEvent(
                true, 42, 42, "helper"));
        assertFalse(CameraProbeActivity.isCurrentActivityCameraEvent(
                true, 42, 41, "helper"));
        assertFalse(CameraProbeActivity.isCurrentActivityCameraEvent(
                true, 42, 42, "activity"));
        assertFalse(CameraProbeActivity.isCurrentActivityCameraEvent(
                false, 42, 42, "helper"));
    }

    @Test
    public void lifecycleIntentRestoresAutomaticPreviewButNotManualStop() {
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
    public void correctedStageFallbackRemainsReadOnly() {
        CameraProbeActivity.CalibrationUiState off =
                CameraProbeActivity.calibrationUiState(false, false);
        assertFalse(off.showCorrected);
        assertFalse(off.correctedEditable);

        CameraProbeActivity.CalibrationUiState corrected =
                CameraProbeActivity.calibrationUiState(true, false);
        assertTrue(corrected.showCorrected);
        assertTrue(corrected.correctedEditable);

        CameraProbeActivity.CalibrationUiState fallback =
                CameraProbeActivity.calibrationUiState(true, true);
        assertTrue(fallback.showCorrected);
        assertFalse(fallback.correctedEditable);
        assertFalse(fallback.liveUsesCorrected);
    }
}
