package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ActivityCameraLifecycleTest {
    private static final int CAMERAS = 1;
    private static final int CALIBRATION = 4;
    private static final int REVERSE = 5;

    @Test
    public void manualStopAndTabSwitchCancelOnlyTheOwnedAutomaticIntent() {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(CAMERAS);
        lifecycle.workerEpochChanged(8, 1);
        lifecycle.beginRequest(CAMERAS, 10, true, input(1));
        lifecycle.clearAutoIntent();
        lifecycle.detach(false);
        assertFalse(lifecycle.shouldOpenAutomatic(0));

        lifecycle.selectTab(CALIBRATION);
        lifecycle.armAutoIntent(CALIBRATION, 0, 0);
        assertTrue(lifecycle.shouldOpenAutomatic(0));
        lifecycle.beginRequest(CALIBRATION, 11, true, input(2));
        lifecycle.selectTab(REVERSE);
        lifecycle.armAutoIntent(REVERSE, 0, 0);
        lifecycle.detach(false);
        assertTrue(lifecycle.shouldOpenAutomatic(0));

        CameraProbeActivity.ActivityCameraLifecycle.Request current =
                lifecycle.beginRequest(REVERSE, 12, true, reverseInputs(3));
        assertEquals(REVERSE, current.tab);
        assertFalse(lifecycle.acceptOpened(event(8, 2, 11, 3, 1)));
        assertTrue(lifecycle.acceptOpened(event(8, 2, 12, 4, 1)));
    }

    @Test
    public void opaqueEpochReplacementReopensOnceAndRejectsStaleReplay() {
        assertTrue(CameraProbeActivity.isMatchingWorkerFailure(true, 900, 900));
        assertFalse(CameraProbeActivity.isMatchingWorkerFailure(false, 900, 900));
        assertFalse(CameraProbeActivity.isMatchingWorkerFailure(true, 0, 900));
        assertFalse(CameraProbeActivity.isMatchingWorkerFailure(true, 100, 900));

        CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(CAMERAS);
        assertFalse(lifecycle.workerEpochChanged(900, 7));
        CameraEventKey failed = event(900, 4, 20, 6, 7);
        lifecycle.beginRequest(CAMERAS, 20, true, input(4));
        assertTrue(lifecycle.acceptOpened(failed));
        assertTrue(lifecycle.acceptWorkerFailureTerminal(failed));

        assertFalse(lifecycle.shouldOpenAutomatic(0));
        assertFalse(lifecycle.workerEpochChanged(100, 7));
        assertTrue(lifecycle.workerEpochChanged(100, 8));
        assertTrue(lifecycle.shouldOpenAutomatic(0));

        CameraProbeActivity.ActivityCameraLifecycle.Request recovery =
                lifecycle.beginRequest(CAMERAS, 21, true, input(5));
        assertTrue(recovery.workerRecovery);
        assertFalse(lifecycle.shouldOpenAutomatic(0));
        CameraEventKey replacement = event(100, 1, 21, 7, 8);
        assertTrue(lifecycle.acceptOpened(replacement));
        assertFalse(lifecycle.acceptCurrent(failed));
        assertFalse(lifecycle.acceptTerminal(failed));
        assertFalse(lifecycle.workerEpochChanged(200, 9));
        assertFalse(lifecycle.shouldOpenAutomatic(0));
    }

    @Test
    public void dormantWorkerRecoveryRetainsItsExactCloseRequestId() {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(CAMERAS);
        lifecycle.workerEpochChanged(901, 20);
        lifecycle.beginRequest(CAMERAS, 80, true, input(18));
        CameraEventKey failed = event(901, 2, 80, 40, 20);
        assertTrue(lifecycle.acceptOpened(failed));
        assertTrue(lifecycle.acceptWorkerFailureTerminal(failed));

        int exactCloseRequestId = lifecycle.requestIdForClose();
        assertTrue(lifecycle.hasAutoIntent());
        assertFalse(lifecycle.shouldOpenAutomatic(0));
        lifecycle.clearAutoIntent();

        assertEquals(80, exactCloseRequestId);
        assertEquals(0, lifecycle.requestIdForClose());
    }

    @Test
    public void capturedOldIntentRequestCannotAliasNewerActivityRequest() {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(CAMERAS);
        lifecycle.workerEpochChanged(902, 21);
        lifecycle.beginRequest(CAMERAS, 81, true, input(19));
        CameraEventKey failed = event(902, 3, 81, 41, 21);
        assertTrue(lifecycle.acceptOpened(failed));
        assertTrue(lifecycle.acceptWorkerFailureTerminal(failed));
        int staleCloseRequestId = lifecycle.requestIdForClose();

        lifecycle.clearAutoIntent();
        lifecycle.selectTab(CALIBRATION);
        lifecycle.armAutoIntent(CALIBRATION, 0, 0);
        lifecycle.beginRequest(CALIBRATION, 82, true, input(20));
        CameraEventKey current = event(902, 4, 82, 42, 21);

        assertEquals(81, staleCloseRequestId);
        assertEquals(82, lifecycle.requestIdForClose());
        assertTrue(lifecycle.acceptOpened(current));
    }

    @Test
    public void reverseTakeoverDefersRestoreAndRecoveryFailureClearsIntent() {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(REVERSE);
        lifecycle.workerEpochChanged(12, 3);
        lifecycle.beginRequest(REVERSE, 30, true, reverseInputs(6));
        CameraEventKey failed = event(12, 5, 30, 8, 3);
        assertTrue(lifecycle.acceptOpened(failed));
        assertTrue(lifecycle.acceptWorkerFailureTerminal(failed));
        assertTrue(lifecycle.workerEpochChanged(13, 4));
        assertFalse(lifecycle.shouldOpenAutomatic(77));
        assertTrue(lifecycle.shouldOpenAutomatic(0));

        CameraProbeActivity.ActivityCameraLifecycle.Request recovery =
                lifecycle.beginRequest(REVERSE, 31, true, reverseInputs(10));
        assertTrue(recovery.workerRecovery);
        assertTrue(lifecycle.acceptTerminal(event(13, 1, 31, 9, 4)));
        assertFalse(lifecycle.hasAutoIntent());
        assertFalse(lifecycle.workerEpochChanged(14, 5));
    }

    @Test
    public void lifecycleEventsRequireTheExactAcceptedKey() {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(CAMERAS);
        lifecycle.workerEpochChanged(14, 5);
        lifecycle.beginRequest(CAMERAS, 40, true, input(14));
        assertFalse(lifecycle.acceptOpened(null));
        CameraEventKey accepted = event(14, 2, 40, 10, 5);
        assertTrue(lifecycle.acceptOpened(accepted));

        assertFalse(lifecycle.acceptCurrent(event(13, 2, 40, 10, 5)));
        assertFalse(lifecycle.acceptCurrent(event(14, 3, 40, 10, 5)));
        assertFalse(lifecycle.acceptCurrent(key(
                14, 2, CameraHelperMain.CAMERA_OWNER_REVERSE, 40, 10, 5)));
        assertFalse(lifecycle.acceptCurrent(event(14, 2, 41, 10, 5)));
        assertFalse(lifecycle.acceptCurrent(event(14, 2, 40, 9, 5)));
        assertFalse(lifecycle.acceptCurrent(event(14, 2, 40, 10, 6)));
        assertTrue(lifecycle.acceptTerminal(accepted));
    }

    @Test
    public void calibrationCopiesRequireResumedOpenedFreshExactKey() {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(CALIBRATION);
        lifecycle.workerEpochChanged(15, 6);
        lifecycle.beginRequest(CALIBRATION, 50, true, input(15));
        CameraEventKey first = event(15, 2, 50, 11, 6);
        assertFalse(lifecycle.canCopyCalibration(CALIBRATION, first));
        assertTrue(lifecycle.acceptOpened(first));
        assertFalse(lifecycle.canCopyCalibration(CALIBRATION, first));
        assertFalse(lifecycle.markFresh(event(15, 3, 50, 11, 6)));
        assertTrue(lifecycle.markFresh(first));
        assertTrue(lifecycle.canCopyCalibration(CALIBRATION, first));

        lifecycle.onPause();
        assertFalse(lifecycle.canCopyCalibration(CALIBRATION, first));
        lifecycle.detach(false);
        lifecycle.onResume();
        lifecycle.beginRequest(CALIBRATION, 51, true, input(16));
        CameraEventKey second = event(15, 2, 51, 12, 6);
        assertTrue(lifecycle.acceptOpened(second));
        assertFalse(lifecycle.canCopyCalibration(CALIBRATION, second));
        assertFalse(lifecycle.markFresh(first));
        assertTrue(lifecycle.markFresh(second));
        assertTrue(lifecycle.canCopyCalibration(CALIBRATION, second));
    }

    @Test
    public void camerasFramesStayBlockedUntilOpenThenRevealForExactKey() {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(CAMERAS);
        CameraProbeActivity.PreviewFreshnessGate gate =
                new CameraProbeActivity.PreviewFreshnessGate();
        lifecycle.workerEpochChanged(71, 8);
        int[] cameraInput = input(17);
        lifecycle.beginRequest(CAMERAS, 60, true, cameraInput);
        CameraEventKey opened = event(71, 3, 60, 13, 8);

        assertNull(gate.accept(opened, 60, cameraInput));
        assertFalse(lifecycle.markFresh(opened));
        assertTrue(lifecycle.acceptOpened(opened));
        gate.arm(opened, cameraInput);
        assertNull(gate.accept(opened, 61, cameraInput));
        assertNull(gate.accept(event(71, 4, 60, 13, 8), 60, cameraInput));
        assertNull(gate.accept(opened, 60, input(16)));
        assertSame(opened, gate.accept(opened, 60, cameraInput));
        assertTrue(lifecycle.markFresh(opened));
    }

    @Test
    public void reverseFramesRequireOpenIdentityAndSurfaceGenerations() {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(REVERSE);
        CameraProbeActivity.PreviewFreshnessGate gate =
                new CameraProbeActivity.PreviewFreshnessGate();
        int[] surfaces = reverseInputs(20);
        lifecycle.workerEpochChanged(72, 9);
        lifecycle.beginRequest(REVERSE, 70, true, surfaces);
        CameraEventKey opened = event(72, 4, 70, 14, 9);

        assertNull(gate.accept(opened, 70, surfaces));
        assertTrue(lifecycle.acceptOpened(opened));
        gate.arm(opened, surfaces);
        assertNull(gate.accept(opened, 70, new int[]{20, 21, 22, 24}));
        assertNull(gate.accept(opened, 69, surfaces));
        assertNull(gate.accept(event(72, 4, 70, 15, 9), 70, surfaces));
        assertSame(opened, gate.accept(opened, 70, surfaces));
        assertTrue(lifecycle.markFresh(opened));
    }

    @Test
    public void stopResumeRenewsEveryAutomaticTabInputAndBlocksOldFrames() {
        int[] tabs = {CAMERAS, CALIBRATION, REVERSE};
        for (int tab : tabs) {
            CameraProbeActivity.ActivityCameraLifecycle lifecycle = lifecycle(tab);
            CameraProbeActivity.PreviewFreshnessGate gate =
                    new CameraProbeActivity.PreviewFreshnessGate();
            BlindSpotCameraView.InputGeneration[] counters =
                    inputCounters(tab == REVERSE ? 4 : 1);
            int[] firstInputs = nextInputs(counters);
            lifecycle.workerEpochChanged(80 + tab, 10 + tab);
            lifecycle.beginRequest(tab, 100 + tab, true, firstInputs);
            CameraEventKey first = event(
                    80 + tab, 1, 100 + tab, 20 + tab, 10 + tab);
            assertTrue(lifecycle.acceptOpened(first));
            gate.arm(first, firstInputs);

            lifecycle.onPause();
            lifecycle.detach(false);
            gate.clear();
            int[] resumedInputs = nextInputs(counters);
            lifecycle.onResume();
            lifecycle.beginRequest(tab, 200 + tab, true, resumedInputs);
            CameraEventKey resumed = event(
                    80 + tab, 1, 200 + tab, 30 + tab, 10 + tab);
            assertTrue(lifecycle.acceptOpened(resumed));
            gate.arm(resumed, resumedInputs);

            for (int source = 0; source < resumedInputs.length; source++) {
                assertTrue(resumedInputs[source] != firstInputs[source]);
            }
            int[] staleFrames = frameInputs(counters);
            int[] freshFrames = frameInputs(counters);
            assertNull(gate.accept(resumed, 200 + tab, staleFrames));
            assertSame(resumed, gate.accept(resumed, 200 + tab, freshFrames));
            assertTrue(lifecycle.markFresh(resumed));
        }
    }

    @Test
    public void correctionUiKeepsSavedCorrectedStageReadOnlyDuringRawFallback() {
        CameraProbeActivity.CalibrationUiState off =
                CameraProbeActivity.calibrationUiState(false, false);
        assertFalse(off.showCorrected);
        assertFalse(off.correctedEditable);
        assertFalse(off.liveUsesCorrected);

        CameraProbeActivity.CalibrationUiState corrected =
                CameraProbeActivity.calibrationUiState(true, false);
        assertTrue(corrected.showCorrected);
        assertTrue(corrected.correctedEditable);
        assertTrue(corrected.liveUsesCorrected);

        CameraProbeActivity.CalibrationUiState fallback =
                CameraProbeActivity.calibrationUiState(true, true);
        assertTrue(fallback.showCorrected);
        assertFalse(fallback.correctedEditable);
        assertFalse(fallback.liveUsesCorrected);
    }

    private static CameraProbeActivity.ActivityCameraLifecycle lifecycle(int tab) {
        CameraProbeActivity.ActivityCameraLifecycle lifecycle =
                new CameraProbeActivity.ActivityCameraLifecycle();
        lifecycle.selectTab(tab);
        lifecycle.onResume();
        lifecycle.armAutoIntent(tab, 0, 0);
        return lifecycle;
    }

    private static CameraEventKey event(
            long workerEpoch, int producerEpoch, int requestId,
            int generation, long connectionGeneration) {
        return key(workerEpoch, producerEpoch,
                CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                requestId, generation, connectionGeneration);
    }

    private static CameraEventKey key(
            long workerEpoch, int producerEpoch, String owner, int requestId,
            int generation, long connectionGeneration) {
        return new CameraEventKey(workerEpoch, producerEpoch, owner,
                requestId, generation, connectionGeneration);
    }

    private static int[] input(int generation) {
        return new int[]{generation};
    }

    private static int[] reverseInputs(int firstGeneration) {
        return new int[]{firstGeneration, firstGeneration + 1,
                firstGeneration + 2, firstGeneration + 3};
    }

    private static BlindSpotCameraView.InputGeneration[] inputCounters(int count) {
        BlindSpotCameraView.InputGeneration[] values =
                new BlindSpotCameraView.InputGeneration[count];
        for (int i = 0; i < count; i++) {
            values[i] = new BlindSpotCameraView.InputGeneration();
        }
        return values;
    }

    private static int[] nextInputs(BlindSpotCameraView.InputGeneration[] counters) {
        int[] values = new int[counters.length];
        for (int i = 0; i < counters.length; i++) values[i] = counters[i].next();
        return values;
    }

    private static int[] frameInputs(BlindSpotCameraView.InputGeneration[] counters) {
        int[] values = new int[counters.length];
        for (int i = 0; i < counters.length; i++) values[i] = counters[i].frame();
        return values;
    }
}
