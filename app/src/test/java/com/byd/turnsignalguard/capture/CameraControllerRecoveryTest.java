package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraControllerRecoveryTest {
    @Test
    public void overlayAndReverseRetainTheExactFullWorkerEventKey() throws Exception {
        assertExactKeyRequired(CameraHelperMain.CAMERA_OWNER_OVERLAY);
        assertExactKeyRequired(CameraHelperMain.CAMERA_OWNER_REVERSE);
    }

    @Test
    public void failedSoleOverlayRecoveryOpenStaysHiddenAcrossRetryPeriods() {
        OverlayHarness harness = new OverlayHarness();

        harness.recoveryExhausted();
        harness.recoveryExhausted();
        harness.advanceRetryPeriods(6);

        assertEquals(1, harness.windowPreparations);
        assertEquals(1, harness.proxy.cameraOpens);
        assertEquals(1, harness.stopCalls);
        assertEquals(0, harness.proxy.resetCalls);
        assertEquals(0, harness.proxy.syntheticExhaustedEvents);
        assertFalse(harness.visible);

        harness.settingsChanged(true);
        harness.advanceRetryPeriods(3);
        assertEquals(0, harness.proxy.resetCalls);
        assertEquals(1, harness.proxy.cameraOpens);
        harness.settingsChanged(false);
        harness.advanceRetryPeriods(3);
        assertEquals(0, harness.proxy.resetCalls);
        assertEquals(1, harness.proxy.cameraOpens);
        harness.settingsChanged(true);
        assertEquals(1, harness.proxy.resetCalls);
        harness.advanceRetryPeriods(1);
        assertEquals(2, harness.windowPreparations);
        assertEquals(2, harness.proxy.cameraOpens);
        assertEquals(0, harness.proxy.syntheticExhaustedEvents);
        harness.settingsChanged(true);
        assertEquals(1, harness.proxy.resetCalls);
    }

    @Test
    public void failedSoleReverseRecoveryOpenWaitsForNewGearEngagement() {
        ReverseHarness harness = new ReverseHarness();

        harness.recoveryExhausted();
        harness.recoveryExhausted();
        harness.advanceRetryPeriods(6);

        assertEquals(1, harness.windowPreparations);
        assertEquals(1, harness.proxy.cameraOpens);
        assertEquals(1, harness.stopCalls);
        assertEquals(0, harness.proxy.resetCalls);
        assertEquals(0, harness.proxy.syntheticExhaustedEvents);
        assertFalse(harness.visible);

        harness.gearChanged(false, false);
        harness.gearChanged(true, true);
        harness.advanceRetryPeriods(3);
        assertEquals(0, harness.proxy.resetCalls);
        assertEquals(1, harness.proxy.cameraOpens);
        harness.gearChanged(true, false);
        harness.advanceRetryPeriods(3);
        assertEquals(0, harness.proxy.resetCalls);
        assertEquals(1, harness.proxy.cameraOpens);
        harness.gearChanged(true, true);
        assertEquals(1, harness.proxy.resetCalls);
        harness.advanceRetryPeriods(1);
        assertEquals(2, harness.windowPreparations);
        assertEquals(2, harness.proxy.cameraOpens);
        assertEquals(0, harness.proxy.syntheticExhaustedEvents);
        harness.gearChanged(true, true);
        assertEquals(1, harness.proxy.resetCalls);
    }

    private static void assertExactKeyRequired(String owner) throws Exception {
        int requestId = 41;
        CameraEventKey exact = new CameraEventKey(
                20L, 7, owner, requestId, 9, 3L);
        CameraWorkerEventGate gate = new CameraWorkerEventGate(owner, requestId);
        assertTrue(gate.accepts("camera_consumer_attached", "helper", exact));
        assertTrue(gate.accepts("camera_opened", "helper", exact));
        assertEquals(exact, gate.boundKey());

        String otherOwner = CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(owner)
                ? CameraHelperMain.CAMERA_OWNER_REVERSE
                : CameraHelperMain.CAMERA_OWNER_OVERLAY;
        assertMismatchRejected(gate, new CameraEventKey(
                21L, 7, owner, requestId, 9, 3L));
        assertMismatchRejected(gate, new CameraEventKey(
                20L, 8, owner, requestId, 9, 3L));
        assertMismatchRejected(gate, new CameraEventKey(
                20L, 7, otherOwner, requestId, 9, 3L));
        assertMismatchRejected(gate, new CameraEventKey(
                20L, 7, owner, requestId + 1, 9, 3L));
        assertMismatchRejected(gate, new CameraEventKey(
                20L, 7, owner, requestId, 10, 3L));
        assertMismatchRejected(gate, new CameraEventKey(
                20L, 7, owner, requestId, 9, 4L));
        assertFalse(gate.accepts("camera_opened", "camera_worker_client", exact));

        assertEquals(exact, gate.boundKey());
        assertTrue(gate.accepts("camera_error", "helper", exact));
        assertTrue(gate.accepts("camera_closed", "helper", exact));
    }

    private static void assertMismatchRejected(
            CameraWorkerEventGate gate, CameraEventKey key) {
        assertFalse(gate.accepts("camera_opened", "helper", key));
        assertFalse(gate.accepts("camera_error", "helper", key));
        assertFalse(gate.accepts("camera_closed", "helper", key));
    }

    private static final class OverlayHarness {
        final BlindSpotOverlayController.OverlayRecoveryLatch recovery =
                new BlindSpotOverlayController.OverlayRecoveryLatch();
        final ProxyHarness proxy = new ProxyHarness(CameraWorkerProtocol.Owner.OVERLAY);
        int windowPreparations = 1;
        int stopCalls;
        boolean visible = true;

        void recoveryExhausted() {
            if (!recovery.exhaust()) return;
            visible = false;
            stopCalls++;
        }

        void settingsChanged(boolean enabled) {
            if (!recovery.settingsChanged(enabled, true)) return;
            proxy.reset();
        }

        void advanceRetryPeriods(int periods) {
            for (int i = 0; i < periods; i++) {
                if (recovery.blocked()) continue;
                windowPreparations++;
                proxy.open();
            }
        }
    }

    private static final class ReverseHarness {
        final ReverseCameraController.ReverseRecoveryLatch recovery =
                new ReverseCameraController.ReverseRecoveryLatch();
        final ProxyHarness proxy = new ProxyHarness(CameraWorkerProtocol.Owner.REVERSE);
        int windowPreparations = 1;
        int stopCalls;
        boolean visible = true;

        void recoveryExhausted() {
            if (!recovery.exhaust()) return;
            visible = false;
            stopCalls++;
        }

        void gearChanged(boolean valid, boolean reverse) {
            if (!recovery.acceptGear(valid, reverse, true)) return;
            proxy.reset();
        }

        void advanceRetryPeriods(int periods) {
            for (int i = 0; i < periods; i++) {
                if (recovery.blocked()) continue;
                windowPreparations++;
                proxy.open();
            }
        }
    }

    private static final class ProxyHarness {
        private static final int PROTOCOL = 1;
        private static final int BUILD = 64;
        final CameraWorkerEpochGuard epochs = new CameraWorkerEpochGuard();
        final CameraWorkerProtocol.Owner owner;
        final int recoveryRequestId;
        final int rearmedRequestId;
        int cameraOpens;
        int resetCalls;
        int syntheticExhaustedEvents;

        ProxyHarness(CameraWorkerProtocol.Owner owner) {
            this.owner = owner;
            recoveryRequestId = owner == CameraWorkerProtocol.Owner.OVERLAY ? 1 : 2;
            rearmedRequestId = recoveryRequestId + 10;
            assertTrue(epochs.acceptHandshake(
                    new CameraWorkerProtocol.Handshake(PROTOCOL, BUILD, 1001, 20L),
                    PROTOCOL, BUILD));
            assertEquals(1001, epochs.quarantine(
                    20L, 1001, new CameraWorkerEpochGuard.RecoveryRequest[]{
                            new CameraWorkerEpochGuard.RecoveryRequest(
                                    owner.wireName(), recoveryRequestId)}));
            assertTrue(epochs.acceptHandshake(
                    new CameraWorkerProtocol.Handshake(PROTOCOL, BUILD, 1002, 21L),
                    PROTOCOL, BUILD));
            CameraWorkerEpochGuard.OpenPermit recovery =
                    epochs.beforeOpen(owner.wireName(), recoveryRequestId);
            assertTrue(recovery != null && recovery.recoveryAttempt());
            cameraOpens++;
            assertTrue(epochs.afterOpen(recovery, false));
        }

        void reset() {
            resetCalls++;
            assertTrue(epochs.resetBlocked(owner));
        }

        void open() {
            CameraWorkerEpochGuard.OpenPermit permit =
                    epochs.beforeOpen(owner.wireName(), rearmedRequestId);
            if (permit == null) {
                syntheticExhaustedEvents++;
                return;
            }
            assertFalse(permit.recoveryAttempt());
            cameraOpens++;
        }
    }
}
