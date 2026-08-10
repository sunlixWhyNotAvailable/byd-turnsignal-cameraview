package com.byd.turnsignalguard.capture;

import android.os.IBinder;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CameraWorkerRecoveryTest {
    @Test
    public void defaultProcessCannotReachCameraReflectionGate() {
        try {
            CameraHelperMain.HelperBinder.requireCameraWorkerOwnership(false);
            fail("default process must not own AVMCamera");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(":camera worker"));
        }
        CameraHelperMain.HelperBinder.requireCameraWorkerOwnership(true);
    }

    @Test
    public void timeoutKillsExactPidAndOnlyNewEpochGetsOneReopen() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        CameraWorkerProtocol.Handshake first = handshake(1101, 701L);
        assertTrue(guard.acceptHandshake(
                first, CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit initial = guard.beforeOpen("activity", 11);
        assertFalse(initial.recoveryAttempt());
        assertFalse(guard.afterOpen(initial, true));

        assertEquals(1101, guard.quarantine(
                701L, 1101, requests("activity", 11)));
        assertEquals(-1, guard.quarantine(
                701L, 1101, requests("activity", 11)));
        assertFalse(guard.acceptsEvent(701L));
        assertFalse(guard.acceptHandshake(
                first, CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));

        assertTrue(guard.acceptHandshake(
                handshake(1102, 702L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertFalse(guard.acceptsEvent(701L));
        assertTrue(guard.acceptsEvent(702L));
        CameraWorkerEpochGuard.OpenPermit recovery = guard.beforeOpen("activity", 11);
        assertTrue(recovery.recoveryAttempt());
        assertEquals(null, guard.beforeOpen("activity", 11));
        assertTrue(guard.afterOpen(recovery, false));
        assertFalse(guard.afterOpen(recovery, false));
        assertTrue(guard.failClosed("activity"));
        assertEquals(null, guard.beforeOpen("activity", 11));
    }

    @Test
    public void successfulNewEpochReopenReturnsToNormalWithoutRetryLoop() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1201, 801L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals(1201, guard.quarantine(
                801L, 1201, requests("activity", 21)));
        assertTrue(guard.acceptHandshake(
                handshake(1202, 802L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit recovery = guard.beforeOpen("activity", 21);
        assertTrue(recovery.recoveryAttempt());
        assertFalse(guard.afterOpen(recovery, true));
        assertFalse(guard.failClosed("activity"));
        assertFalse(guard.beforeOpen("activity", 22).recoveryAttempt());
    }

    @Test
    public void compatibleOwnersEachGetOneAttemptAfterNewEpoch() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1251, 851L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals(1251, guard.quarantine(
                851L, 1251, requests("activity", 31, "overlay", 32)));
        assertTrue(guard.acceptHandshake(
                handshake(1252, 852L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit activity = guard.beforeOpen("activity", 31);
        assertTrue(activity.recoveryAttempt());
        assertEquals(null, guard.beforeOpen("activity", 31));
        assertFalse(guard.afterOpen(activity, true));
        CameraWorkerEpochGuard.OpenPermit overlay = guard.beforeOpen("overlay", 32);
        assertTrue(overlay.recoveryAttempt());
        assertEquals(null, guard.beforeOpen("reverse", 33));
        assertFalse(guard.afterOpen(overlay, true));
        assertFalse(guard.failClosed("activity"));
        assertFalse(guard.failClosed("overlay"));
    }

    @Test
    public void lowerOpaqueReplacementEpochIsAcceptedByNewConnection() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1261, 9_000L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals(1261, guard.quarantine(
                9_000L, 1261, requests("activity", 41)));
        assertTrue(guard.acceptHandshake(
                handshake(1262, 7L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals(7L, guard.epoch());
        assertTrue(guard.beforeOpen("activity", 41).recoveryAttempt());
    }

    @Test
    public void approvedOwnerResetClearsOnlyTerminalBlock() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1271, 901L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals(1271, guard.quarantine(
                901L, 1271, requests("overlay", 51)));
        assertTrue(guard.acceptHandshake(
                handshake(1272, 902L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit recovery = guard.beforeOpen("overlay", 51);
        assertTrue(guard.afterOpen(recovery, false));
        assertTrue(guard.failClosed("overlay"));
        assertFalse(guard.cancelRecovery("overlay", 51));
        assertTrue(guard.failClosed("overlay"));
        assertTrue(guard.resetBlocked(CameraWorkerProtocol.Owner.OVERLAY));
        assertFalse(guard.resetBlocked(CameraWorkerProtocol.Owner.OVERLAY));
        assertFalse(guard.beforeOpen("overlay", 52).recoveryAttempt());
        assertFalse(guard.resetBlocked(CameraWorkerProtocol.Owner.REVERSE));
    }

    @Test
    public void workerDeathDuringRecoveryAttemptBlocksBeforeNextQuarantine() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1281, 911L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals(1281, guard.quarantine(
                911L, 1281, requests("activity", 61, "overlay", 62)));
        assertTrue(guard.acceptHandshake(
                handshake(1282, 912L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit activity = guard.beforeOpen("activity", 61);
        assertTrue(activity.recoveryAttempt());
        assertTrue(guard.failInFlightRecovery(activity));
        assertFalse(guard.failInFlightRecovery(activity));
        assertFalse(guard.afterOpen(activity, false));
        assertEquals(1282, guard.quarantine(
                912L, 1282, requests("activity", 61, "overlay", 62)));
        assertTrue(guard.acceptHandshake(
                handshake(1283, 913L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertTrue(guard.failClosed("activity"));
        assertEquals(null, guard.beforeOpen("activity", 61));
        assertTrue(guard.beforeOpen("overlay", 62).recoveryAttempt());
    }

    @Test
    public void activityCloseFailureCannotReaddOrGateOverlayAndReverse() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1291, 921L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals(1291, guard.quarantine(
                921L, 1291, requests("activity", 71, "overlay", 72)));
        assertTrue(guard.acceptHandshake(
                handshake(1292, 922L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertTrue(guard.cancelRecovery("activity", 71));
        CameraWorkerEpochGuard.OpenPermit overlay = guard.beforeOpen("overlay", 72);
        assertTrue(overlay.recoveryAttempt());
        assertFalse(guard.afterOpen(overlay, true));
        assertEquals(-1, guard.quarantine(
                921L, 1291, requests("activity", 71)));
        assertFalse(guard.beforeOpen("reverse", 73).recoveryAttempt());
        assertFalse(guard.beforeOpen("overlay", 74).recoveryAttempt());
        assertFalse(guard.beforeOpen("activity", 75).recoveryAttempt());
    }

    @Test
    public void initialBindRetainsOneOpenAndReplaysItExactlyOnce() {
        CameraWorkerPendingOpen pending = new CameraWorkerPendingOpen();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        pending.replace("activity", 81, operation(replayed, released));
        CameraWorkerPendingOpen.Token value = pending.claimAll()[0];
        assertTrue(pending.promote(value));
        value.replay();
        value.release();
        assertEquals(1, replayed.get());
        assertEquals(1, released.get());
        assertEquals(0, pending.claimAll().length);

        pending.replace("activity", 82, operation(replayed, released));
        pending.replace("activity", 83, operation(replayed, released));
        assertEquals(2, released.get());
        assertTrue(pending.cancel("activity", 83));
        assertEquals(3, released.get());
        assertFalse(pending.cancel("activity", 83));
    }

    @Test
    public void initialBindReplaysCompatibleOwnersInArrivalOrder() {
        CameraWorkerPendingOpen pending = new CameraWorkerPendingOpen();
        StringBuilder order = new StringBuilder();
        pending.replace("activity", 91, ordered(order, "activity"));
        pending.replace("overlay", 92, ordered(order, "overlay"));
        CameraWorkerPendingOpen.Token[] values = pending.claimAll();
        assertEquals(2, values.length);
        for (CameraWorkerPendingOpen.Token value : values) {
            assertTrue(pending.promote(value));
            value.replay();
        }
        assertEquals("activity,overlay,", order.toString());
        assertEquals(0, pending.claimAll().length);
    }

    @Test
    public void claimedOpenExactCancelPreventsReplayAndReleasesOnce() {
        CameraWorkerPendingOpen pending = new CameraWorkerPendingOpen();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        pending.replace("activity", 101, operation(replayed, released));
        CameraWorkerPendingOpen.Token claimed = pending.claimAll()[0];

        assertTrue(pending.cancel("activity", 101));
        claimed.replay();
        claimed.release();

        assertEquals(0, replayed.get());
        assertEquals(1, released.get());
        assertFalse(pending.promote(claimed));
    }

    @Test
    public void promotedOpenAndExactCloseEnqueueInOwnershipOrder() throws Exception {
        Object ownershipLock = new Object();
        ExecutorService calls = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "camera-order-test");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch promoted = new CountDownLatch(1);
        CountDownLatch allowOpenEnqueue = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Future<Boolean>> openCall = new AtomicReference<>();
        AtomicReference<Future<Boolean>> closeCall = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger remoteConsumers = new AtomicInteger();

        Thread open = new Thread(() -> {
            try {
                synchronized (ownershipLock) {
                    promoted.countDown();
                    assertTrue(allowOpenEnqueue.await(1, TimeUnit.SECONDS));
                    openCall.set(CameraWorkerClient.enqueueWhileLocked(
                            ownershipLock, calls, () -> {
                                order.add("OPEN(A)");
                                remoteConsumers.set(1);
                                return true;
                            }));
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        Thread close = new Thread(() -> {
            try {
                synchronized (ownershipLock) {
                    closeCall.set(CameraWorkerClient.enqueueWhileLocked(
                            ownershipLock, calls, () -> {
                                order.add("CLOSE(A)");
                                remoteConsumers.set(0);
                                return true;
                            }));
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        open.start();
        assertTrue(promoted.await(1, TimeUnit.SECONDS));
        close.start();
        allowOpenEnqueue.countDown();
        open.join(1_000);
        close.join(1_000);
        assertEquals(null, failure.get());
        assertTrue(openCall.get().get(1, TimeUnit.SECONDS));
        assertTrue(closeCall.get().get(1, TimeUnit.SECONDS));
        assertEquals("[OPEN(A), CLOSE(A)]", order.toString());
        assertEquals(0, remoteConsumers.get());
        calls.shutdownNow();
    }

    @Test
    public void deniedBPreservesExactActiveAAndQueuesNoOpen() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1031, 28L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals(1031, guard.quarantine(
                28L, 1031,
                requests("activity", 201, "overlay", 202)));
        assertTrue(guard.acceptHandshake(
                handshake(1032, 29L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit permitA =
                guard.beforeOpen("activity", 201);
        assertFalse(guard.afterOpen(permitA, true));

        CameraWorkerClient.ConnectionSnapshot connection =
                new CameraWorkerClient.ConnectionSnapshot(
                        fakeBinder(), 29L, 1032, 1L);
        CameraWorkerClient.SessionIdentity activeA =
                new CameraWorkerClient.SessionIdentity(
                        "activity", 201, 2, 29L, 3, 1L,
                        false, permitA, connection);
        Map<String, CameraWorkerClient.SessionIdentity> sessions = new HashMap<>();
        sessions.put("activity", activeA);
        Object ownershipLock = new Object();
        ExecutorService calls = Executors.newSingleThreadExecutor();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger constructed = new AtomicInteger();
        CameraWorkerClient.OpenSubmission denied;
        synchronized (ownershipLock) {
            denied = CameraWorkerClient.reserveAndInstallOpenLocked(
                    ownershipLock, calls, guard, new CameraWorkerPendingOpen(),
                    null, sessions, "activity", 203, permit -> {
                        constructed.incrementAndGet();
                        return new CameraWorkerClient.SessionIdentity(
                                "activity", 203, 4, 29L, 5, 1L,
                                false, permit, connection);
                    }, () -> {
                        invoked.incrementAndGet();
                        return "opened";
                    });
        }

        assertEquals(null, denied);
        assertEquals(0, constructed.get());
        assertEquals(0, invoked.get());
        assertTrue(CameraWorkerClient.acceptsCurrentSession(
                sessions.get("activity"), activeA));
        calls.shutdownNow();
    }

    @Test
    public void rejectedBSubmissionPreservesAAndCleansTokenOnce() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1041, 30L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit permitA =
                guard.beforeOpen("activity", 211);
        CameraWorkerClient.ConnectionSnapshot connection =
                new CameraWorkerClient.ConnectionSnapshot(
                        fakeBinder(), 30L, 1041, 1L);
        CameraWorkerClient.SessionIdentity activeA =
                new CameraWorkerClient.SessionIdentity(
                        "activity", 211, 2, 30L, 3, 1L,
                        false, permitA, connection);
        Map<String, CameraWorkerClient.SessionIdentity> sessions = new HashMap<>();
        sessions.put("activity", activeA);
        CameraWorkerPendingOpen pending = new CameraWorkerPendingOpen();
        AtomicInteger released = new AtomicInteger();
        pending.replace("activity", 212,
                operation(new AtomicInteger(), released));
        CameraWorkerPendingOpen.Token tokenB = pending.claimAll()[0];
        ExecutorService rejected = Executors.newSingleThreadExecutor();
        rejected.shutdownNow();
        Object ownershipLock = new Object();
        CameraWorkerClient.OpenSubmission reservation;
        synchronized (ownershipLock) {
            reservation = CameraWorkerClient.reserveAndInstallOpenLocked(
                    ownershipLock, rejected, guard, pending, tokenB,
                    sessions, "activity", 212,
                    permit -> new CameraWorkerClient.SessionIdentity(
                            "activity", 212, 4, 30L, 5, 1L,
                            false, permit, connection),
                    () -> "opened");
        }

        assertEquals(null, reservation);
        assertEquals(1, released.get());
        assertFalse(pending.promote(tokenB));
        assertTrue(CameraWorkerClient.acceptsCurrentSession(
                sessions.get("activity"), activeA));
        assertFalse(guard.beforeOpen("activity", 213).recoveryAttempt());
    }

    @Test
    public void successfulBReservationAtomicallyReplacesAndCancelsA()
            throws Exception {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1051, 31L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit permitA =
                guard.beforeOpen("activity", 221);
        CameraWorkerClient.ConnectionSnapshot connection =
                new CameraWorkerClient.ConnectionSnapshot(
                        fakeBinder(), 31L, 1051, 1L);
        CameraWorkerClient.SessionIdentity activeA =
                new CameraWorkerClient.SessionIdentity(
                        "activity", 221, 2, 31L, 3, 1L,
                        false, permitA, connection);
        Map<String, CameraWorkerClient.SessionIdentity> sessions = new HashMap<>();
        sessions.put("activity", activeA);
        ExecutorService calls = Executors.newSingleThreadExecutor();
        Object ownershipLock = new Object();
        CameraWorkerClient.OpenSubmission accepted;
        synchronized (ownershipLock) {
            accepted = CameraWorkerClient.reserveAndInstallOpenLocked(
                    ownershipLock, calls, guard, new CameraWorkerPendingOpen(),
                    null, sessions, "activity", 222,
                    permit -> new CameraWorkerClient.SessionIdentity(
                            "activity", 222, 4, 31L, 5, 1L,
                            false, permit, connection),
                    () -> "opened");
        }

        assertTrue(accepted != null);
        assertEquals("opened", accepted.call.get(1, TimeUnit.SECONDS));
        assertFalse(CameraWorkerClient.acceptsCurrentSession(activeA, activeA));
        assertTrue(CameraWorkerClient.acceptsCurrentSession(
                sessions.get("activity"), accepted.identity));
        assertEquals(222, sessions.get("activity").requestId);
        calls.shutdownNow();
    }

    @Test
    public void claimedAInvalidatedByNewerBNeverPromotesOrOpens() {
        CameraWorkerPendingOpen pending = new CameraWorkerPendingOpen();
        AtomicInteger openedA = new AtomicInteger();
        AtomicInteger releasedA = new AtomicInteger();
        pending.replace("activity", 105, operation(openedA, releasedA));
        CameraWorkerPendingOpen.Token claimedA = pending.claimAll()[0];

        CameraWorkerPendingOpen.Token invalidated = pending.cancelOwner("activity");
        CameraWorkerClient.ConnectionSnapshot connection =
                new CameraWorkerClient.ConnectionSnapshot(
                        fakeBinder(), 30L, 1041, 1L);
        CameraWorkerClient.SessionIdentity currentB =
                new CameraWorkerClient.SessionIdentity(
                        "activity", 106, 2, 30L, 3, 1L,
                        false, null, connection);
        claimedA.replay();

        assertTrue(invalidated == claimedA);
        assertFalse(pending.promote(claimedA));
        assertEquals(0, openedA.get());
        assertEquals(1, releasedA.get());
        assertTrue(CameraWorkerClient.acceptsCurrentSession(currentB, currentB));
    }

    @Test
    public void cancelledSessionRejectsOpenedEventAndReleasesSurfaceOnce() {
        CameraWorkerClient.ConnectionSnapshot connection =
                new CameraWorkerClient.ConnectionSnapshot(
                        fakeBinder(), 31L, 1051, 2L);
        CameraWorkerClient.SessionIdentity identity =
                new CameraWorkerClient.SessionIdentity(
                        "activity", 107, 3, 31L, 4, 2L,
                        false, null, connection);
        assertTrue(CameraWorkerClient.acceptsCurrentSession(identity, identity));
        identity.cancelled = true;
        assertFalse(CameraWorkerClient.acceptsCurrentSession(identity, identity));

        CameraWorkerPendingOpen pending = new CameraWorkerPendingOpen();
        AtomicInteger released = new AtomicInteger();
        pending.replace("activity", 107,
                operation(new AtomicInteger(), released));
        CameraWorkerPendingOpen.Token surface = pending.claimAll()[0];
        assertTrue(pending.cancel("activity", 107));
        surface.release();
        assertEquals(1, released.get());
    }

    @Test
    public void staleCloseCannotCancelNewerSameOwnerPendingToken() {
        CameraWorkerPendingOpen pending = new CameraWorkerPendingOpen();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        pending.replace("activity", 111, operation(replayed, released));
        pending.claimAll();
        pending.replace("activity", 112, operation(replayed, released));

        assertFalse(pending.cancel("activity", 111));
        CameraWorkerPendingOpen.Token current = pending.claimAll()[0];
        assertEquals(112, current.requestId());
        assertTrue(pending.promote(current));
        current.replay();
        current.release();

        assertEquals(1, replayed.get());
        assertEquals(2, released.get());
    }

    @Test
    public void failedOpenAndDetachCloseKeepPositiveStableGeneration() {
        CameraWorkerEventIdentity identities = new CameraWorkerEventIdentity();
        int failed = identities.begin("activity", 41);
        assertTrue(failed > 0);
        assertEquals(failed, identities.generation(
                "activity", 41, "camera_error"));
        identities.failedOpen("activity", 41);

        int opened = identities.begin("overlay", 42);
        assertTrue(opened > failed);
        assertEquals(opened, identities.generation(
                "overlay", 42, "camera_consumer_attached"));
        assertEquals(opened, identities.generation(
                "overlay", 42, "camera_consumer_detached"));
        identities.afterEvent("overlay", 42, "camera_consumer_detached");
        assertEquals(opened, identities.generation(
                "overlay", 42, "camera_closed"));
        identities.afterEvent("overlay", 42, "camera_closed");
        assertEquals(0, identities.generation(
                "overlay", 42, "camera_error"));
    }

    @Test
    public void boundEventKeyRejectsEverySameWorkerTupleMismatch() throws Exception {
        CameraEventKey key = new CameraEventKey(
                77L, 8, "activity", 43, 9, 3L);
        CameraWorkerEventGate gate = new CameraWorkerEventGate("activity", 43);
        assertTrue(gate.accepts(
                "camera_consumer_attached", "helper", key));
        assertEquals(key, gate.boundKey());

        assertFalse(gate.accepts("camera_closed", "helper",
                changed(key, "worker_epoch", 76L)));
        assertFalse(gate.accepts("camera_closed", "helper",
                changed(key, "producer_epoch", 10)));
        assertFalse(gate.accepts("camera_closed", "helper",
                changed(key, "camera_owner", "overlay")));
        assertFalse(gate.accepts("camera_closed", "helper",
                changed(key, "request_id", 44)));
        assertFalse(gate.accepts("camera_closed", "helper",
                changed(key, "consumer_generation", 11)));
        assertFalse(gate.accepts("camera_closed", "helper",
                changed(key, "connection_generation", 4L)));
        assertFalse(gate.accepts("camera_closed", "untrusted", key));
        assertTrue(gate.accepts("camera_closed", "helper", key));
    }

    @Test
    public void validPreOpenScopedTerminalIsPreservedWithoutBinding() throws Exception {
        CameraEventKey key = new CameraEventKey(
                78L, 12, "overlay", 45, 13, 4L);
        CameraWorkerEventGate gate = new CameraWorkerEventGate("overlay", 45);
        assertTrue(gate.accepts("camera_error", "helper", key));
        assertEquals(null, gate.boundKey());
        assertFalse(gate.accepts("camera_error", "helper",
                changed(key, "request_id", 99)));
    }

    @Test
    public void recoveryExhaustedTerminalCarriesExactFullKey() throws Exception {
        CameraEventKey key = new CameraEventKey(
                79L, 15, "reverse", 46, 16, 5L);
        CameraWorkerClient.RecoveryExhaustedSpec event =
                CameraWorkerClient.recoveryExhaustedSpec(
                key, "reopen_failed");
        assertEquals("camera_error", event.kind());
        assertEquals("helper", event.source());
        assertEquals("camera_worker_recovery_exhausted", event.stage());
        assertTrue(event.recoveryExhausted());
        assertTrue(event.workerFailure());
        assertEquals(79L, event.failedWorkerEpoch());
        assertEquals("reopen_failed", event.error());
        assertEquals(key, event.key());
    }

    @Test
    public void shellDeathTargetsOnlyShellOwnedActivityConsumer() {
        assertTrue(CameraHelperMain.HelperBinder.shellDeathInvalidatesConsumer(
                CameraHelperMain.CAMERA_OWNER_ACTIVITY, true));
        assertFalse(CameraHelperMain.HelperBinder.shellDeathInvalidatesConsumer(
                CameraHelperMain.CAMERA_OWNER_ACTIVITY, false));
        assertFalse(CameraHelperMain.HelperBinder.shellDeathInvalidatesConsumer(
                CameraHelperMain.CAMERA_OWNER_OVERLAY, true));
        assertFalse(CameraHelperMain.HelperBinder.shellDeathInvalidatesConsumer(
                CameraHelperMain.CAMERA_OWNER_REVERSE, true));
    }

    @Test
    public void workerTerminalRequestsOneMatchingStockClose() {
        assertTrue(CameraHelperMain.HelperBinder.shouldCloseStockForWorkerTerminal(
                true, 51, 0, 51, "camera_error",
                CameraHelperMain.CAMERA_OWNER_ACTIVITY));
        assertFalse(CameraHelperMain.HelperBinder.shouldCloseStockForWorkerTerminal(
                false, 0, 0, 51, "camera_closed",
                CameraHelperMain.CAMERA_OWNER_ACTIVITY));
        assertFalse(CameraHelperMain.HelperBinder.shouldCloseStockForWorkerTerminal(
                true, 52, 0, 51, "camera_error",
                CameraHelperMain.CAMERA_OWNER_ACTIVITY));
        assertFalse(CameraHelperMain.HelperBinder.shouldCloseStockForWorkerTerminal(
                true, 51, 0, 51, "camera_error",
                CameraHelperMain.CAMERA_OWNER_OVERLAY));
    }

    @Test
    public void workerEpochGenerationIsAlwaysPositiveAndPidSpecific() {
        long first = CameraWorkerService.newWorkerEpoch(1301, 900_001L);
        long second = CameraWorkerService.newWorkerEpoch(1302, 900_001L);
        assertTrue(first > 0);
        assertTrue(second > 0);
        assertTrue(first != second);
    }

    @Test
    public void workerPidMustDifferFromDefaultProcessBeforeAnyKillPath() {
        assertFalse(CameraWorkerClient.isRemoteWorkerPid(1401, 1401));
        assertFalse(CameraWorkerClient.isRemoteWorkerPid(0, 1401));
        assertTrue(CameraWorkerClient.isRemoteWorkerPid(1402, 1401));
    }

    @Test
    public void connectionSnapshotRequiresExactBinderEpochPidAndGeneration() {
        IBinder firstBinder = fakeBinder();
        IBinder secondBinder = fakeBinder();
        CameraWorkerClient.ConnectionSnapshot first =
                new CameraWorkerClient.ConnectionSnapshot(
                        firstBinder, 61L, 1501, 7L);
        assertTrue(CameraWorkerClient.matchesSnapshot(first,
                new CameraWorkerClient.ConnectionSnapshot(
                        firstBinder, 61L, 1501, 7L)));
        assertFalse(CameraWorkerClient.matchesSnapshot(first,
                new CameraWorkerClient.ConnectionSnapshot(
                        secondBinder, 61L, 1501, 7L)));
        assertFalse(CameraWorkerClient.matchesSnapshot(first,
                new CameraWorkerClient.ConnectionSnapshot(
                        firstBinder, 62L, 1501, 7L)));
        assertFalse(CameraWorkerClient.matchesSnapshot(first,
                new CameraWorkerClient.ConnectionSnapshot(
                        firstBinder, 61L, 1502, 7L)));
        assertFalse(CameraWorkerClient.matchesSnapshot(first,
                new CameraWorkerClient.ConnectionSnapshot(
                        firstBinder, 61L, 1501, 8L)));
    }

    @Test
    public void candidateRegisterAndLinkFailuresRetireOnlyExactCandidate() {
        CameraWorkerClient.ConnectionSlots slots =
                new CameraWorkerClient.ConnectionSlots();
        CameraWorkerClient.ConnectionSnapshot registerTimeout =
                slots.reserve(fakeBinder(), 71L, 1601);
        assertTrue(slots.retireCandidate(registerTimeout));
        assertEquals(null, slots.current());

        CameraWorkerClient.ConnectionSnapshot linkFailure =
                slots.reserve(fakeBinder(), 72L, 1602);
        assertTrue(slots.retireCandidate(linkFailure));
        CameraWorkerClient.ConnectionSnapshot replacement =
                slots.reserve(fakeBinder(), 73L, 1603);
        assertTrue(slots.install(replacement));

        assertFalse(slots.retireCandidate(registerTimeout));
        assertTrue(CameraWorkerClient.matchesSnapshot(replacement, slots.current()));
        assertEquals(3L, replacement.connectionGeneration);
    }

    @Test
    public void candidateBTakeoverRecoversActiveAOnceAndLateAFailureIsIgnored() {
        CameraWorkerClient.ConnectionSlots slots =
                new CameraWorkerClient.ConnectionSlots();
        CameraWorkerClient.ConnectionSnapshot first =
                slots.reserve(fakeBinder(), 81L, 1701);
        assertTrue(slots.install(first));

        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1701, 81L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit active =
                guard.beforeOpen("activity", 601);
        assertFalse(active.recoveryAttempt());

        CameraWorkerClient.ConnectionSnapshot replacement =
                slots.reserve(fakeBinder(), 82L, 1702);
        assertFalse(slots.acceptsFailure(first));
        assertTrue(slots.retireCurrent(first));
        assertEquals(1701, guard.quarantine(
                81L, 1701, requests("activity", 601)));
        assertTrue(guard.acceptHandshake(
                handshake(1702, 82L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertTrue(slots.install(replacement));

        assertFalse(slots.acceptsFailure(first));
        assertEquals(-1, guard.quarantine(
                81L, 1701, requests("activity", 601)));
        assertTrue(CameraWorkerClient.matchesSnapshot(replacement, slots.current()));
        assertTrue(guard.beforeOpen("activity", 601).recoveryAttempt());
    }

    @Test
    public void exactRecoveryCancelLeavesNewerSameOwnerAndOtherOwnersNormal() {
        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1801, 91L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals(1801, guard.quarantine(
                91L, 1801, requests("activity", 701)));
        assertFalse(guard.cancelRecovery("activity", 700));
        assertTrue(guard.cancelRecovery("activity", 701));
        assertTrue(guard.acceptHandshake(
                handshake(1802, 92L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));

        assertFalse(guard.beforeOpen("overlay", 702).recoveryAttempt());
        assertFalse(guard.beforeOpen("reverse", 703).recoveryAttempt());

        assertEquals(1802, guard.quarantine(
                92L, 1802, requests("activity", 704)));
        assertFalse(guard.cancelRecovery("activity", 701));
        assertTrue(guard.beforeOpen("activity", 704).recoveryAttempt());
    }

    @Test
    public void oldCloseCannotCancelNewOpenPermit() {

        CameraWorkerEpochGuard guard = new CameraWorkerEpochGuard();
        assertTrue(guard.acceptHandshake(
                handshake(1511, 951L),
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE));
        CameraWorkerEpochGuard.OpenPermit old = guard.beforeOpen("activity", 501);
        CameraWorkerEpochGuard.OpenPermit newer = guard.beforeOpen("activity", 502);
        assertTrue(guard.cancelOpen(old));
        assertTrue(guard.cancelOpen(newer));
        assertFalse(guard.cancelOpen(newer));
    }

    @Test
    public void updateInitializationRunsOnlyInDefaultProcess() {
        assertTrue(TurnSignalGuardApplication.isDefaultProcess(
                "com.byd.turnsignalguard.capture",
                "com.byd.turnsignalguard.capture"));
        assertFalse(TurnSignalGuardApplication.isDefaultProcess(
                "com.byd.turnsignalguard.capture",
                "com.byd.turnsignalguard.capture:camera"));
        assertFalse(TurnSignalGuardApplication.isDefaultProcess(
                "com.byd.turnsignalguard.capture", null));
    }

    private static CameraWorkerProtocol.Handshake handshake(int pid, long epoch) {
        return new CameraWorkerProtocol.Handshake(
                CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE, pid, epoch);
    }

    private static CameraWorkerEpochGuard.RecoveryRequest[] requests(Object... values) {
        CameraWorkerEpochGuard.RecoveryRequest[] requests =
                new CameraWorkerEpochGuard.RecoveryRequest[values.length / 2];
        for (int i = 0; i < values.length; i += 2) {
            requests[i / 2] = new CameraWorkerEpochGuard.RecoveryRequest(
                    (String) values[i], (Integer) values[i + 1]);
        }
        return requests;
    }

    private static IBinder fakeBinder() {
        return (IBinder) Proxy.newProxyInstance(
                CameraWorkerRecoveryTest.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (proxy, method, arguments) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
    }

    private static CameraEventKey changed(
            CameraEventKey key, String field, Object value) {
        return new CameraEventKey(
                "worker_epoch".equals(field) ? ((Number) value).longValue()
                        : key.workerEpoch(),
                "producer_epoch".equals(field) ? ((Number) value).intValue()
                        : key.producerEpoch(),
                "camera_owner".equals(field) ? (String) value : key.owner(),
                "request_id".equals(field) ? ((Number) value).intValue()
                        : key.requestId(),
                "consumer_generation".equals(field) ? ((Number) value).intValue()
                        : key.consumerGeneration(),
                "connection_generation".equals(field) ? ((Number) value).longValue()
                        : key.connectionGeneration());
    }

    private static CameraWorkerPendingOpen.Operation operation(
            AtomicInteger replayed, AtomicInteger released) {
        return new CameraWorkerPendingOpen.Operation() {
            @Override
            public void replay(CameraWorkerPendingOpen.Token token) {
                replayed.incrementAndGet();
            }

            @Override
            public void release() {
                released.incrementAndGet();
            }
        };
    }

    private static CameraWorkerPendingOpen.Operation ordered(
            StringBuilder order, String owner) {
        return new CameraWorkerPendingOpen.Operation() {
            @Override
            public void replay(CameraWorkerPendingOpen.Token token) {
                order.append(owner).append(',');
            }

            @Override
            public void release() {
                order.append("released:").append(owner).append(',');
            }
        };
    }
}
