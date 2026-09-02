package com.byd.extend;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.byd.extend.ui.CameraHostKind;
import com.byd.extend.ui.CameraHostSlot;
import com.byd.extend.ui.CameraProfileId;
import com.byd.extend.ui.CameraGroup;
import com.byd.extend.ui.CameraSide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

public final class ActivityCameraLifecycleTest {
    @Test
    public void calibrationHostOwnershipFollowsTheActualProfileRootTab() {
        CameraHostSlot blind = new CameraHostSlot(CameraHostKind.CalibrationOriginal,
                new CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left), null, null, null, false);
        CameraHostSlot parking = new CameraHostSlot(CameraHostKind.CalibrationCorrected,
                new CameraProfileId.Parking(com.byd.extend.ui.ParkingView.FrontLeft), null, null, null, false);
        CameraHostSlot reverse = new CameraHostSlot(CameraHostKind.CalibrationOutput,
                new CameraProfileId.Reverse(com.byd.extend.ui.ReverseElement.RearLeft,
                        com.byd.extend.ui.ReverseSource.Rear), null, null, null, false);
        assertTrue(CameraProbeActivity.slotBelongsToTab(blind, 1));
        assertFalse(CameraProbeActivity.slotBelongsToTab(blind, 4));
        assertTrue(CameraProbeActivity.slotBelongsToTab(parking, 8));
        assertFalse(CameraProbeActivity.slotBelongsToTab(parking, 4));
        assertTrue(CameraProbeActivity.slotBelongsToTab(reverse, 5));
        assertFalse(CameraProbeActivity.slotBelongsToTab(reverse, 4));
    }

    @Test
    public void diagnosticStageTerminalEventsClearPending() {
        assertTrue(CameraProbeActivity.isTerminalDiagnosticStage("closed"));
        assertTrue(CameraProbeActivity.isTerminalDiagnosticStage("open_error"));
        assertTrue(CameraProbeActivity.isTerminalDiagnosticStage("request_completed"));
        assertFalse(CameraProbeActivity.isTerminalDiagnosticStage("set_viewpoint"));
    }

    @Test
    public void lateUnkeyedOrRetiredAvmStageCannotAffectTheCurrentRequest() {
        assertFalse(CameraProbeActivity.isDiagnosticStageIdentityMatch(7, 0, 123, 0));
        assertFalse(CameraProbeActivity.isDiagnosticStageIdentityMatch(7, 0, 123, 123));
        assertFalse(CameraProbeActivity.isDiagnosticStageIdentityMatch(7, 6, 123, 123));
        assertFalse(CameraProbeActivity.isDiagnosticStageIdentityMatch(0, 0, 123, 123));
        assertFalse(CameraProbeActivity.isDiagnosticStageIdentityMatch(7, 7, 123, 122));
        assertFalse(CameraProbeActivity.isDiagnosticStageIdentityMatch(7, 7, 0, 123));
        assertFalse(CameraProbeActivity.isDiagnosticStageIdentityMatch(7, 7, 123, -1));
        assertTrue(CameraProbeActivity.isDiagnosticStageIdentityMatch(7, 7, 123, 0));
        assertTrue(CameraProbeActivity.isDiagnosticStageIdentityMatch(7, 7, 123, 123));
    }

    @Test
    public void reverseRotationPreviewKeepsBothStageGeometriesWithoutWrites() {
        for (boolean front : new boolean[]{false, true}) {
            for (int cameraIndex : new int[]{1, 2, 3}) {
                TestSharedPreferences settings = new TestSharedPreferences();
                ReverseCameraLayout.Rect raw = ReverseCameraLayout.sourceCrop(
                        0.10f, 0.15f, 0.70f, 0.65f);
                ReverseCameraLayout.Rect corrected = ReverseCameraLayout.sourceCrop(
                        0.25f, 0.30f, 0.35f, 0.40f);
                android.content.SharedPreferences.Editor editor = settings.edit();
                if (front) {
                    ReverseCameraController.writeFrontSourceCrop(editor, cameraIndex, raw, false);
                    ReverseCameraController.writeFrontSourceCrop(editor, cameraIndex, corrected, true);
                } else {
                    ReverseCameraController.writeSourceCrop(editor, cameraIndex, raw, false);
                    ReverseCameraController.writeSourceCrop(editor, cameraIndex, corrected, true);
                }
                editor.apply();
                Map<String, ?> before = settings.getAll();
                for (int rotation : new int[]{-90, -30, 0, 45, 90, 180}) {
                    DirectCameraCrop[] crops = CameraProbeActivity.reverseRotationPreviewCrops(
                            settings, cameraIndex, front, rotation);
                    for (int stage = 0; stage < 2; stage++) {
                        ReverseCameraLayout.Rect expected = stage == 0 ? raw : corrected;
                        assertEquals(expected.left, crops[stage].left, 0.0f);
                        assertEquals(expected.top, crops[stage].top, 0.0f);
                        assertEquals(expected.width, crops[stage].width, 0.0f);
                        assertEquals(expected.height, crops[stage].height, 0.0f);
                        assertEquals(rotation, crops[stage].rotationDegrees);
                    }
                    assertEquals(crops[0].rotationMode, crops[1].rotationMode);
                    assertEquals(crops[0].mirrorHorizontally, crops[1].mirrorHorizontally);
                }
                assertEquals(before, settings.getAll());
                assertEquals(1, settings.transactions);
            }
        }
    }

    @Test
    public void tabletPreviewKeepsSelectedModeWithoutChangingGeometryOrMirror() {
        DirectCameraCrop fill = DirectCameraCrop.requireUiGeometry(
                0.12f, 0.18f, 0.62f, 0.54f, 42,
                CameraRotation.MODE_FILL).withOutputTransformPreservingGeometry(
                42, CameraRotation.MODE_FILL, true);
        assertEquals(0.12f, fill.left, 0.0f);
        assertEquals(0.18f, fill.top, 0.0f);
        assertEquals(0.62f, fill.width, 0.0f);
        assertEquals(0.54f, fill.height, 0.0f);
        assertEquals(42, fill.rotationDegrees);
        assertEquals(CameraRotation.MODE_FILL, fill.rotationMode);
        assertTrue(fill.mirrorHorizontally);
        DirectCameraCrop stretch = fill.withOutputTransformPreservingGeometry(
                42, CameraRotation.MODE_ALIGNED, true);
        assertEquals(CameraRotation.MODE_ALIGNED, stretch.rotationMode);
    }

    @Test
    public void reverseFallbackSourceKeepsCentralIdentityAndTracksSideConfigRole() {
        for (int mode : new int[]{ReverseSideSelectorView.MODE_REAR,
                ReverseSideSelectorView.MODE_FRONT}) {
            assertFalse(ReverseCameraCompositionView.fallbackSourceIsFront(1, mode));
            assertTrue(ReverseCameraCompositionView.fallbackSourceIsFront(4, mode));
        }
        for (int source : new int[]{2, 3}) {
            assertFalse(ReverseCameraCompositionView.fallbackSourceIsFront(
                    source, ReverseSideSelectorView.MODE_REAR));
            assertTrue(ReverseCameraCompositionView.fallbackSourceIsFront(
                    source, ReverseSideSelectorView.MODE_FRONT));
        }
    }

    @Test
    public void revealedOptionalCentralFrontLossKeepsActivityReverseOpen() {
        assertFalse(CameraProbeActivity.reverseSurfaceLossIsTerminal(4, true));
        assertTrue(CameraProbeActivity.reverseSurfaceLossIsTerminal(4, false));
        assertTrue(CameraProbeActivity.reverseSurfaceLossIsTerminal(3, true));
    }

    @Test
    public void headerUsesLastAdbResultAndActualWeatherPermission() {
        com.byd.extend.ui.HeaderUiState header = CameraProbeActivity.productionHeader(
                LocalAdbClient.AccessState.Status.OK, true, false);
        assertEquals("ADB", header.getAdb().getText());
        assertEquals(com.byd.extend.ui.StatusTone.Ok, header.getAdb().getTone());
        assertTrue(header.getLocation().getVisible());
        assertEquals(com.byd.extend.ui.StatusTone.Error, header.getLocation().getTone());
        header = CameraProbeActivity.productionHeader(LocalAdbClient.AccessState.Status.ERROR, false, true);
        assertEquals(com.byd.extend.ui.StatusTone.Error, header.getAdb().getTone());
        assertFalse(header.getLocation().getVisible());
        header = CameraProbeActivity.productionHeader(LocalAdbClient.AccessState.Status.UNKNOWN, true, true);
        assertEquals(com.byd.extend.ui.StatusTone.Neutral, header.getAdb().getTone());
        assertEquals(com.byd.extend.ui.StatusTone.Ok, header.getLocation().getTone());
    }

    @Test
    public void manualDiagnosticsRequireHealthyParkedIdleRuntime() {
        assertTrue(CameraProbeActivity.manualDiagnosticsAllowed(true, true, true, false, false, false));
        assertFalse(CameraProbeActivity.manualDiagnosticsAllowed(false, true, true, false, false, false));
        assertFalse(CameraProbeActivity.manualDiagnosticsAllowed(true, false, true, false, false, false));
        assertFalse(CameraProbeActivity.manualDiagnosticsAllowed(true, true, false, false, false, false));
        assertFalse(CameraProbeActivity.manualDiagnosticsAllowed(true, true, true, true, false, false));
        assertFalse(CameraProbeActivity.manualDiagnosticsAllowed(true, true, true, false, true, false));
        assertFalse(CameraProbeActivity.manualDiagnosticsAllowed(true, true, true, false, false, true));
    }

    @Test
    public void composeManualCommandsKeepApprovedPayloadMapping() {
        assertEquals(2, CameraProbeActivity.manualSignalPayload(com.byd.extend.ui.CommandId.SignalLeft));
        assertEquals(3, CameraProbeActivity.manualSignalPayload(com.byd.extend.ui.CommandId.SignalRight));
        assertEquals(1, CameraProbeActivity.manualSignalPayload(com.byd.extend.ui.CommandId.SignalHazard));
        assertEquals(0, CameraProbeActivity.manualSignalPayload(com.byd.extend.ui.CommandId.SignalReset));
        assertEquals(-1, CameraProbeActivity.manualSignalPayload(com.byd.extend.ui.CommandId.StopDiagnosticCamera));
    }

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
    public void directCameraSelectionAllowsHotSwitchOnlyAfterOpen() {
        assertTrue(CameraProbeActivity.directCameraSelectionAllowed(
                false, false, false));
        assertTrue(CameraProbeActivity.directCameraSelectionAllowed(
                true, true, true));
        assertFalse(CameraProbeActivity.directCameraSelectionAllowed(
                true, true, false));
        assertFalse(CameraProbeActivity.directCameraSelectionAllowed(
                true, false, true));
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
                token, token, "stock_avm_shell", "camera_closed", "", 27, 27, 7, 7));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, token, "pano_h", "camera_closed", "", 27, 27, 7, 7));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, token, "stock_avm_shell", "camera_closed", "", 27, 26, 7, 7));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, token, "stock_avm_shell", "camera_closed", "", 0, 0, 7, 7));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, token, "stock_avm_shell", "camera_closed", "", 27, 27, 7, 6));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, token, "stock_avm_shell", "camera_closed", "", 27, 27, 7, 0));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                token, "activity_stopped", "stock_avm_shell", "camera_closed", "", 27, 27, 7, 7));
        assertFalse(CameraProbeActivity.isMatchingColdResetShellCallback(
                "activity_transition:99:activity_resume_cold_reset", token,
                "stock_avm_shell", "camera_closed", "", 27, 27, 7, 7));
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
    public void reversePriorityCloseCompletesMatchingPendingActivityStop() {
        assertTrue(CameraProbeActivity.isMatchingPendingActivityShellClose(
                true, 14, 14, "stock_avm_shell"));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityShellClose(
                false, 14, 14, "stock_avm_shell"));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityShellClose(
                true, 14, 13, "stock_avm_shell"));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityShellClose(
                true, 0, 0, "stock_avm_shell"));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityShellClose(
                true, 14, 14, "pano_h"));
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
    public void shellEpochGateRejectsStaleAndDuplicateTerminalEvents() {
        assertTrue(CameraProbeActivity.shouldAcceptActivityCameraShellAttach(0, 7, false));
        assertFalse(CameraProbeActivity.shouldAcceptActivityCameraShellAttach(7, 7, true));
        assertTrue(CameraProbeActivity.shouldAcceptActivityCameraShellAttach(7, 8, true));
        assertFalse(CameraProbeActivity.shouldAcceptActivityCameraShellAttach(7, 7, false));

        assertFalse(CameraProbeActivity.shouldAcceptActivityCameraShellDeath(0, 7, 0, false));
        assertTrue(CameraProbeActivity.shouldAcceptActivityCameraShellDeath(7, 7, 0, false));
        assertFalse(CameraProbeActivity.shouldAcceptActivityCameraShellDeath(7, 7, 7, true));
        assertFalse(CameraProbeActivity.shouldAcceptActivityCameraShellDeath(8, 7, 8, false));
        assertFalse(CameraProbeActivity.shouldAcceptActivityCameraShellDeath(7, 9, 7, true));
        assertTrue(CameraProbeActivity.isMatchingActivityCameraShellEpoch(7, 0));
        assertFalse(CameraProbeActivity.isMatchingActivityCameraShellEpoch(7, 6));
        assertTrue(CameraProbeActivity.isMatchingActivityCameraShellEpoch(7, 7));
    }

    @Test
    public void avmEpochIsEstablishedOnlyByAttachOrAcceptedOpen() {
        assertEquals(7, CameraProbeActivity.nextActivityAvmShellEpoch(
                0, "stock_avm_shell_attached", "", 7, false));
        assertEquals(8, CameraProbeActivity.nextActivityAvmShellEpoch(
                7, "camera_opened", "stock_avm_shell", 8, true));
        assertEquals(7, CameraProbeActivity.nextActivityAvmShellEpoch(
                7, "camera_opened", "stock_avm_shell", 8, false));
        assertEquals(7, CameraProbeActivity.nextActivityAvmShellEpoch(
                7, "camera_closed", "stock_avm_shell", 8, false));
        assertEquals(7, CameraProbeActivity.nextActivityAvmShellEpoch(
                7, "stock_avm_shell_died", "", 8, false));
        assertEquals(0, CameraProbeActivity.nextActivityAvmShellEpoch(
                7, "stock_avm_shell_died", "", 7, false));
    }

    @Test
    public void guardAnglesAllowIndependentZeroValues() {
        assertTrue(CameraProbeActivity.isValidGuardThresholds(0.0f, 0.0f));
        assertTrue(CameraProbeActivity.isValidGuardThresholds(360.0f, 45.0f));
        assertTrue(CameraProbeActivity.isValidGuardThresholds(5.0f, 45.0f));
        assertFalse(CameraProbeActivity.isValidGuardThresholds(-0.1f, 0.0f));
        assertFalse(CameraProbeActivity.isValidGuardThresholds(0.0f, 45.1f));
        assertFalse(CameraProbeActivity.isValidGuardThresholds(Float.NaN, 0.0f));
    }

    @Test
    public void stockAvmTerminalCallbackRequiresCurrentLifecycleEpoch() {
        assertTrue(CameraProbeActivity.isMatchingActivityAvmShellEpoch(4, 4));
        assertFalse(CameraProbeActivity.isMatchingActivityAvmShellEpoch(4, 3));
        assertFalse(CameraProbeActivity.isMatchingActivityAvmShellEpoch(4, 0));
        assertFalse(CameraProbeActivity.isMatchingActivityAvmShellEpoch(0, 4));
        assertTrue(CameraProbeActivity.isMatchingActivityCameraShellEpochStrict(4, 4));
        assertFalse(CameraProbeActivity.isMatchingActivityCameraShellEpochStrict(4, 0));
        assertFalse(CameraProbeActivity.isMatchingActivityCameraShellEpochStrict(0, 4));
    }

    @Test
    public void queuedActivityTransitionAcceptsExactAvmCloseOnceAndRejectsStaleIdentity() {
        CameraTransition transition = new CameraTransition();
        String token = transition.begin("camera_tab_changed");
        int closingRequestId = 27;

        assertTrue(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, token, "stock_avm_shell", "camera_closed", "",
                closingRequestId, closingRequestId, 7, 7, 8, 8));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, token, "stock_avm_shell", "camera_closed", "",
                closingRequestId, closingRequestId - 1, 7, 7, 8, 8));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, token, "stock_avm_shell", "camera_closed", "",
                closingRequestId, closingRequestId, 7, 7, 8, 7));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, token, "stock_avm_shell", "camera_closed", "",
                closingRequestId, closingRequestId, 7, 6, 8, 8));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, token, "stock_avm_shell", "camera_closed", "",
                closingRequestId, closingRequestId, 7, 0, 8, 8));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, "activity_transition:99:camera_tab_changed",
                "stock_avm_shell", "camera_closed", "",
                closingRequestId, closingRequestId, 7, 7, 8, 8));
        assertFalse(transition.matches("camera_closed_without_transition_token"));
        assertTrue(transition.pending());
        assertTrue(transition.complete(token));
        assertFalse(transition.complete(token));
        assertFalse(transition.pending());

        String nextToken = transition.begin("camera_tab_changed");
        assertTrue(transition.pending());
        assertFalse(token.equals(nextToken));
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
        assertFalse(off.liveUsesCorrected);
        assertEquals(1, off.correctedPaneWidth);
        assertEquals(0.0f, off.correctedPaneWeight, 0.0f);
        assertTrue(off.copyRawMirror);

        CameraProbeActivity.CalibrationUiState corrected =
                CameraProbeActivity.calibrationUiState(true, false);
        assertTrue(corrected.showCorrected);
        assertTrue(corrected.correctedEditable);
        assertTrue(corrected.liveUsesCorrected);
        assertEquals(0, corrected.correctedPaneWidth);
        assertEquals(1.0f, corrected.correctedPaneWeight, 0.0f);
        assertFalse(corrected.copyRawMirror);

        CameraProbeActivity.CalibrationUiState fallback =
                CameraProbeActivity.calibrationUiState(true, true);
        assertTrue(fallback.showCorrected);
        assertFalse(fallback.correctedEditable);
        assertFalse(fallback.liveUsesCorrected);
        assertEquals(0, fallback.correctedPaneWidth);
        assertEquals(1.0f, fallback.correctedPaneWeight, 0.0f);
        assertFalse(fallback.copyRawMirror);
    }

    @Test
    public void savedReversePaneRestoresExactSwitchBindingWithoutWrites() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean("camera_dewarp_v2_left_enabled", false);
        CameraDewarpConfig.saveForReverse(settings,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT,
                        true, 137, CameraDewarpConfig.PROJECTION_CYLINDRICAL));
        CameraDewarpConfig.saveForReverse(settings,
                ReverseCameraLayout.REAR_CAMERA_INDEX,
                CameraDewarpConfig.of(CameraDewarpConfig.LENS_REAR,
                        false, 120, CameraDewarpConfig.PROJECTION_RECTILINEAR));
        ReverseCameraController.saveEditorSelection(
                settings, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        ReverseCameraController.saveFrontIntegrated(
                settings, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, true);
        Map<String, ?> beforeRestore = new HashMap<>(settings.getAll());

        CameraProbeActivity.ReversePaneUiBinding restored =
                CameraProbeActivity.restoredReversePaneUiBinding(settings);

        assertEquals(ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                restored.cameraIndex);
        assertTrue(restored.dewarp.enabled);
        assertEquals(CameraDewarpConfig.LENS_LEFT, restored.dewarp.lens);
        assertEquals(137, restored.dewarp.fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                restored.dewarp.projection);
        assertTrue(restored.frontIntegrated);
        assertFalse(restored.widgetVisible);
        assertEquals(beforeRestore, settings.getAll());
        assertFalse(settings.getBoolean("camera_dewarp_v2_left_enabled", true));
        assertFalse(CameraDewarpConfig.loadForReverse(settings,
                ReverseCameraLayout.REAR_CAMERA_INDEX).enabled);
    }

    @Test
    public void qualitySelectionNotifiesOverlayAndReverseControllers() {
        AtomicInteger overlay = new AtomicInteger();
        AtomicInteger reverse = new AtomicInteger();

        CameraProbeActivity.notifyCameraQualityControllers(
                overlay::incrementAndGet, reverse::incrementAndGet);

        assertEquals(1, overlay.get());
        assertEquals(1, reverse.get());
    }

    @Test
    public void restoredReverseVisibilityBindingCarriesExactMaskWithoutWrites() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraController.saveVisibility(
                settings, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, false);
        ReverseCameraController.saveVisibility(
                settings, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX, false);
        ReverseCameraController.saveEditorSelection(
                settings, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        Map<String, ?> beforeRestore = new HashMap<>(settings.getAll());

        CameraProbeActivity.ReversePaneUiBinding restored =
                CameraProbeActivity.restoredReversePaneUiBinding(settings);

        assertEquals(ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, restored.cameraIndex);
        assertFalse(restored.visible);
        assertEquals(ReverseCameraLayout.VISIBILITY_ALL
                        & ~ReverseCameraLayout.visibilityBitForPane(
                                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX)
                        & ~ReverseCameraLayout.visibilityBitForPane(
                                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX),
                restored.visibilityMask);
        assertEquals(beforeRestore, settings.getAll());
    }

    @Test
    public void reverseSelectorStrikesOnlyHiddenLabels() {
        int base = 0x2000;
        assertEquals(base,
                CameraProbeActivity.reversePaneButtonPaintFlags(base, true));
        assertEquals(base | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG,
                CameraProbeActivity.reversePaneButtonPaintFlags(base, false));
        assertEquals("Rear left", CameraProbeActivity.reversePaneLabel(
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
        assertEquals("Віджет", CameraProbeActivity.reversePaneLabel(
                ReverseCameraLayout.WIDGET_PANE_ID));
    }

    @Test
    public void reverseFrontIntegrationSlotsVisibleOnlyForCameraPanes() {
        assertEquals(android.view.View.VISIBLE,
                CameraProbeActivity.reverseFrontIntegrationVisibility(
                        ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
        assertEquals(android.view.View.VISIBLE,
                CameraProbeActivity.reverseFrontIntegrationVisibility(
                        ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX));
        assertEquals(android.view.View.VISIBLE,
                CameraProbeActivity.reverseFrontIntegrationVisibility(
                        ReverseCameraLayout.REAR_CAMERA_INDEX));
        assertEquals(android.view.View.INVISIBLE,
                CameraProbeActivity.reverseFrontIntegrationVisibility(
                        ReverseCameraLayout.BACKGROUND_PANE_ID));
        assertEquals(android.view.View.INVISIBLE,
                CameraProbeActivity.reverseFrontIntegrationVisibility(
                        ReverseCameraLayout.WIDGET_PANE_ID));
    }

    @Test
    public void reverseWidgetBindingDefaultsOffAndDoesNotUseCameraMask() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraController.saveEditorSelection(
                settings, ReverseCameraLayout.WIDGET_PANE_ID);
        Map<String, ?> beforeRestore = new HashMap<>(settings.getAll());

        CameraProbeActivity.ReversePaneUiBinding restored =
                CameraProbeActivity.restoredReversePaneUiBinding(settings);

        assertEquals(ReverseCameraLayout.WIDGET_PANE_ID, restored.cameraIndex);
        assertFalse(restored.visible);
        assertFalse(restored.widgetVisible);
        assertEquals(ReverseCameraLayout.VISIBILITY_ALL, restored.visibilityMask);
        assertEquals(beforeRestore, settings.getAll());
    }

    @Test
    public void parkingSelectorStaysHorizontalWithEightEqualButtons() {
        assertEquals(android.widget.LinearLayout.HORIZONTAL,
                CameraProbeActivity.PARKING_SELECTOR_ORIENTATION);
        assertEquals(1.0f, CameraProbeActivity.PARKING_SELECTOR_BUTTON_WEIGHT, 0.0f);
        assertEquals(0.65f, CameraProbeActivity.PARKING_SETTINGS_WEIGHT, 0.0f);
        assertEquals(0.35f, CameraProbeActivity.PARKING_PREVIEW_WEIGHT, 0.0f);
        assertArrayEquals(new String[]{
                        "Перед-ліво", "Перед", "Перед-право",
                        "Зад-праворуч", "Зад", "Зад-ліворуч", "Ліво", "Право"},
                CameraProbeActivity.calibrationLabels(true));
    }

    @Test
    public void calibrationRebindsWhenPhysicalSourceChanges() {
        assertTrue(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, 0, 2, false, 0, 3));
        assertTrue(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, 0, -1, false, 0, 3));
    }

    @Test
    public void calibrationRebindsWhenSamePhysicalLogicalProfileChanges() {
        assertTrue(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, CameraProfile.REAR_LEFT, 2,
                false, CameraProfile.FRONT_LEFT, 2));
    }

    @Test
    public void calibrationRebindsWhenScopeChangesOnSamePhysicalSource() {
        assertTrue(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, CameraProfile.REAR_LEFT, 2,
                true, ParkingCameraProfile.FL, 2));
    }

    @Test
    public void calibrationDoesNotRebindForExactIdentityOrClosedPreview() {
        assertFalse(CameraProbeActivity.calibrationNeedsIdentityRebind(
                true, false, CameraProfile.REAR_LEFT, 2,
                false, CameraProfile.REAR_LEFT, 2));
        assertFalse(CameraProbeActivity.calibrationNeedsIdentityRebind(
                false, false, CameraProfile.REAR_LEFT, 2,
                false, CameraProfile.FRONT_LEFT, 2));
    }

    @Test
    public void calibrationEntryBindsParkingContextBeforeHiddenTabAndKeepsLabelsSeparate() {
        CameraProbeActivity.CalibrationEntry parking = CameraProbeActivity.calibrationEntry(
                8, true, ParkingCameraProfile.REAR);
        assertEquals(8, parking.originTab);
        assertTrue(parking.parking);
        assertEquals(ParkingCameraProfile.REAR, parking.logicalId);

        CameraProbeActivity.CalibrationEntry blind = CameraProbeActivity.calibrationEntry(
                1, false, CameraProfile.FRONT_RIGHT);
        assertEquals(1, blind.originTab);
        assertFalse(blind.parking);
        assertEquals(CameraProfile.FRONT_RIGHT, blind.logicalId);
        assertEquals("Задня ліва", CameraProbeActivity.calibrationLabel(false, 0));
        assertEquals("Перед-ліво", CameraProbeActivity.calibrationLabel(true, 0));
        assertEquals("overlay_front_right", CameraProbeActivity.calibrationScopeLabel(
                false, CameraProfile.FRONT_RIGHT));
        assertEquals("parking_Rear", CameraProbeActivity.calibrationScopeLabel(
                true, ParkingCameraProfile.REAR));
    }

    @Test
    public void reverseVisibilityMapsToAlphaWithoutChangingSurfaceLifecycle() {
        int mask = ReverseCameraLayout.VISIBILITY_ALL
                & ~ReverseCameraLayout.visibilityBitForPane(
                        ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        assertEquals(1.0f, ReverseCameraCompositionView.alphaForVisibility(
                mask, ReverseCameraLayout.BACKGROUND_PANE_ID), 0.0f);
        assertEquals(0.0f, ReverseCameraCompositionView.alphaForVisibility(
                mask, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX), 0.0f);
        assertEquals(1.0f, ReverseCameraCompositionView.alphaForVisibility(
                mask, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX), 0.0f);
    }

    @Test
    public void mirrorUiUsesTransferLabelsAndFixedRotationControlSize() {
        assertEquals("Перенести →",
                CameraProbeActivity.calibrationTransferLabel(false));
        assertEquals("← Перенести",
                CameraProbeActivity.calibrationTransferLabel(true));
        assertEquals("Перенести →",
                CameraProbeActivity.reverseTransferLabel(
                        ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
        assertEquals("← Перенести",
                CameraProbeActivity.reverseTransferLabel(
                        ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX));
        assertEquals(42, CameraProbeActivity.CALIBRATION_ROTATION_ROW_HEIGHT_DP);
        assertEquals(120, CameraProbeActivity.OUTPUT_MIRROR_BUTTON_WIDTH_DP);
    }

    @Test
    public void directMirrorStatePersistsForRawAndCorrectedOutput() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        DirectCameraCrop raw = DirectCameraCrop.defaultFor(profile)
                .withMirrorHorizontally(true);
        DirectCameraCrop corrected = DirectCameraCrop.loadCorrected(
                settings, profile, raw).withMirrorHorizontally(true);

        DirectCameraCrop.save(settings, profile, raw);
        DirectCameraCrop.saveCorrected(settings, profile, corrected);

        DirectCameraCrop loadedRaw = DirectCameraCrop.load(settings, profile);
        DirectCameraCrop loadedCorrected = DirectCameraCrop.loadCorrected(
                settings, profile, loadedRaw);
        assertTrue(loadedRaw.mirrorHorizontally);
        assertTrue(loadedCorrected.mirrorHorizontally);
    }

    @Test
    public void reverseMirrorDefaultsOnAndSurvivesLayoutUpdates() {
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        assertTrue(layout.pane(ReverseCameraLayout.REAR_CAMERA_INDEX).mirrorHorizontally);
        assertTrue(layout.pane(ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX).mirrorHorizontally);
        assertTrue(layout.pane(ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX).mirrorHorizontally);

        layout = ReverseCameraLayout.withMirrorHorizontally(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, false);
        layout = ReverseCameraLayout.withRotation(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 45);
        layout = ReverseCameraLayout.withDisplayMode(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.DISPLAY_MODE_FILL);
        assertFalse(layout.pane(ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX)
                .mirrorHorizontally);
    }

    @Test
    public void reverseCalibrationWaitsForCurrentRequestAndSelectedMirrorFrame() {
        CameraProbeActivity.ReverseCalibrationFreshnessGate gate =
                new CameraProbeActivity.ReverseCalibrationFreshnessGate();
        gate.arm(41, 2, false, true, false);

        assertFalse(gate.markMirrorFresh(41, 2, false, true));
        assertFalse(gate.markRequestFresh(40, 2, false, true));
        assertTrue(gate.markRequestFresh(41, 2, false, true));
        assertFalse(gate.markMirrorFresh(41, 3, false, true));
        assertFalse(gate.markMirrorFresh(41, 2, true, true));
        assertFalse(gate.markMirrorFresh(41, 2, false, false));
        assertTrue(gate.markMirrorFresh(41, 2, false, true));
        assertTrue(gate.allows(41, 2, false, true));

        gate.arm(42, 2, false, true, false);
        assertFalse(gate.allows(41, 2, false, true));
        assertFalse(gate.allows(42, 2, false, true));
        gate.clear();
        assertFalse(gate.allows(42, 2, false, true));

        gate.arm(42, 2, false, true, true);
        assertFalse(gate.allows(42, 2, false, true));
        assertTrue(gate.markMirrorFresh(42, 2, false, true));
        assertTrue(gate.allows(42, 2, false, true));
    }

    @Test
    public void reverseCalibrationCopyRequiresEveryLiveLifecycleCondition() {
        boolean[] conditions = {true, true, true, true, true, true, true, true, true};
        assertTrue(CameraProbeActivity.shouldCopyReverseCalibrationFrame(
                conditions[0], conditions[1], conditions[2], conditions[3], conditions[4],
                conditions[5], conditions[6], conditions[7], conditions[8]));
        for (int i = 0; i < conditions.length; i++) {
            conditions[i] = false;
            assertFalse(CameraProbeActivity.shouldCopyReverseCalibrationFrame(
                    conditions[0], conditions[1], conditions[2], conditions[3], conditions[4],
                    conditions[5], conditions[6], conditions[7], conditions[8]));
            conditions[i] = true;
        }
    }

    @Test
    public void retainedCalibrationBundleDetachesStageOnlyAndRejectsStaleRelease() {
        CameraProbeActivity.CalibrationHostBundle bundle =
                new CameraProbeActivity.CalibrationHostBundle();
        Object owner = new Object();
        Object raw = new Object();
        Object corrected = new Object();
        Object output = new Object();
        Object rawMirror = new Object();
        Object correctedMirror = new Object();
        AtomicInteger correctedDetach = new AtomicInteger();
        bundle.bind(owner, raw, corrected, output, rawMirror, correctedMirror);

        // Initial OFF has no corrected surface, but the owner and roots stay reusable for ON.
        assertTrue(bundle.isComplete(owner, raw, corrected, output));
        assertTrue(bundle.acceptMirror(owner, correctedMirror, false));
        assertTrue(bundle.releaseStage(
                com.byd.extend.ui.CameraHostKind.CalibrationCorrected,
                corrected, correctedDetach::incrementAndGet));
        assertEquals(1, correctedDetach.get());
        assertFalse(bundle.acceptMirror(owner, correctedMirror, false));
        assertTrue(bundle.isComplete(owner, raw, corrected, output));
        bundle.activateStage(
                com.byd.extend.ui.CameraHostKind.CalibrationCorrected, corrected);
        assertTrue(bundle.acceptMirror(owner, correctedMirror, false));
        assertTrue(bundle.releaseStage(
                com.byd.extend.ui.CameraHostKind.CalibrationOriginal,
                raw, null));
        assertTrue(bundle.isComplete(owner, raw, corrected, output));

        // A late release from an old bundle cannot detach a replacement owner's mirror.
        assertTrue(bundle.clear());
        Object replacementOwner = new Object();
        Object replacementRaw = new Object();
        Object replacementCorrected = new Object();
        Object replacementOutput = new Object();
        Object replacementMirror = new Object();
        bundle.bind(replacementOwner, replacementRaw, replacementCorrected, replacementOutput,
                new Object(), replacementMirror);
        assertFalse(bundle.acceptMirror(owner, correctedMirror, false));
        assertTrue(bundle.acceptMirror(replacementOwner, replacementMirror, false));
        assertFalse(bundle.releaseStage(
                com.byd.extend.ui.CameraHostKind.CalibrationCorrected,
                corrected, correctedDetach::incrementAndGet));
        assertEquals(1, correctedDetach.get());
        assertTrue(bundle.isComplete(
                replacementOwner, replacementRaw, replacementCorrected, replacementOutput));

        // Workspace exit retires once; repeated teardown cannot retire a new/empty owner.
        assertTrue(bundle.clear());
        assertFalse(bundle.clear());
        assertFalse(bundle.hasAny());
    }
}
