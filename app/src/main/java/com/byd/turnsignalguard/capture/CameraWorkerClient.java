package com.byd.turnsignalguard.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Default-process proxy for the private serialized camera worker. */
final class CameraWorkerClient {
    interface ParcelWriter {
        void write(Parcel data);
    }

    private static final String TAG = "BydCameraWorkerClient";
    private static final long CALL_DEADLINE_MS = 3_000;

    private final Context context;
    private final Handler handler;
    private final Consumer<String> eventSink;
    private final ExecutorService calls = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "camera-worker-client");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    private final CameraWorkerEpochGuard epochs = new CameraWorkerEpochGuard();
    private final CameraWorkerPendingOpen pendingOpen = new CameraWorkerPendingOpen();
    private final ConnectionSlots connections = new ConnectionSlots();
    private final Map<String, SessionIdentity> sessions = new HashMap<>();
    private final IBinder callback = new CallbackBinder();
    private final ServiceConnection connection = new WorkerConnection();
    private boolean bound;
    private int nextConsumerGeneration;
    private int nextProducerEpoch;

    CameraWorkerClient(Context context, Handler handler, Consumer<String> eventSink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.eventSink = eventSink;
        bound = this.context.bindService(
                new Intent(this.context, CameraWorkerService.class), connection,
                Context.BIND_AUTO_CREATE);
        if (!bound) emitState("camera_worker_unavailable", "bind_failed", 0, -1, 0);
    }

    boolean discoverCamera() {
        ConnectionSnapshot connection = currentConnection();
        if (connection == null) return false;
        try {
            return withDeadline(() -> transactBoolean(
                    connection.binder, CameraWorkerProtocol.TX_DISCOVER, null));
        } catch (Throwable error) {
            transportFailure("discover_failed", connection, error);
            return false;
        }
    }

    String openGroup(
            String owner, Surface[] surfaces, int[] indexes, int requestId,
            String view, boolean exclusive, boolean shellOwned, int[] profileIds) {
        return openGroup(owner, surfaces, indexes, requestId, view,
                exclusive, shellOwned, profileIds, null);
    }

    private String openGroup(
            String owner, Surface[] surfaces, int[] indexes, int requestId,
            String view, boolean exclusive, boolean shellOwned, int[] profileIds,
            CameraWorkerPendingOpen.Token pendingToken) {
        if (!CameraWorkerProtocol.allowedOwner(owner)) {
            releasePending(pendingToken, surfaces);
            throw new IllegalArgumentException("camera owner rejected");
        }
        ConnectionSnapshot connection = currentConnection();
        if (connection == null && pendingToken == null && !epochs.failClosed(owner)) {
            Surface[] retainedSurfaces = surfaces;
            int[] retainedIndexes = indexes == null ? null : indexes.clone();
            int[] retainedProfiles = profileIds == null ? null : profileIds.clone();
            retainPending(owner, requestId, new CameraWorkerPendingOpen.Operation() {
                @Override
                public void replay(CameraWorkerPendingOpen.Token token) {
                    openGroup(owner, retainedSurfaces, retainedIndexes, requestId,
                            view, exclusive, shellOwned, retainedProfiles, token);
                }

                @Override
                public void release() {
                    CameraWorkerProtocol.release(retainedSurfaces);
                }
            });
            return queued(owner, requestId);
        }
        if (pendingToken != null && !pendingCurrent(pendingToken)) {
            pendingToken.release();
            return rejected(owner, requestId, "camera open cancelled");
        }
        OpenSubmission submission = beginOpen(
                owner, requestId, shellOwned, connection, pendingToken,
                CameraWorkerProtocol.TX_OPEN_GROUP, data -> {
                    data.writeString(owner);
                    data.writeInt(requestId);
                    data.writeString(view);
                    data.writeInt(exclusive ? 1 : 0);
                    data.writeInt(shellOwned ? 1 : 0);
                    writeOptionalInts(data, profileIds);
                    CameraWorkerProtocol.writeSurfaces(data, surfaces, indexes);
                });
        SessionIdentity identity = submission == null ? null : submission.identity;
        if (identity == null) {
            if (pendingToken != null) {
                synchronized (lock) {
                    if (!pendingOpen.isCurrent(pendingToken)) {
                        return rejected(owner, requestId, "camera open cancelled");
                    }
                    if (!matchesSnapshot(connections.current(), connection)) {
                        return queued(owner, requestId);
                    }
                    pendingOpen.cancel(owner, requestId);
                }
            } else {
                CameraWorkerProtocol.release(surfaces);
            }
            emitRejectedOpen(owner, requestId, "worker_epoch_not_recoverable");
            return rejected(owner, requestId, "worker_epoch_not_recoverable");
        }
        try {
            String result = awaitString(submission.call, connection, "open_group");
            boolean ambiguous = ambiguousOwnership(result);
            if (ambiguous) {
                transportFailure("ambiguous_close", connection,
                        new IllegalStateException(result));
            }
            Boolean exhausted = completeOpen(identity, cameraOpened(result));
            if (exhausted == null) {
                finishFailedOpen(owner, identity);
                return rejected(owner, requestId, "stale_worker_connection");
            }
            if (exhausted) {
                emitRecoveryExhausted(identity, "reopen_failed");
                finishFailedOpen(owner, identity);
            }
            return result;
        } finally {
            releasePending(pendingToken, surfaces);
        }
    }

    String openDirect(
            Surface surface, String tag, int index, String owner,
            int requestId, boolean exclusive) {
        return openDirect(surface, tag, index, owner, requestId, exclusive, null);
    }

    private String openDirect(
            Surface surface, String tag, int index, String owner,
            int requestId, boolean exclusive,
            CameraWorkerPendingOpen.Token pendingToken) {
        if (!CameraWorkerProtocol.allowedOwner(owner)
                || !CameraWorkerProtocol.allowedTag(tag)) {
            releasePending(pendingToken, surface);
            throw new IllegalArgumentException("direct camera mapping rejected");
        }
        CameraWorkerProtocol.requireIndex(index);
        ConnectionSnapshot connection = currentConnection();
        if (connection == null && pendingToken == null && !epochs.failClosed(owner)) {
            Surface retainedSurface = surface;
            retainPending(owner, requestId, new CameraWorkerPendingOpen.Operation() {
                @Override
                public void replay(CameraWorkerPendingOpen.Token token) {
                    openDirect(retainedSurface, tag, index, owner, requestId,
                            exclusive, token);
                }

                @Override
                public void release() {
                    if (retainedSurface != null) retainedSurface.release();
                }
            });
            return queued(owner, requestId);
        }
        if (pendingToken != null && !pendingCurrent(pendingToken)) {
            pendingToken.release();
            return rejected(owner, requestId, "camera open cancelled");
        }
        OpenSubmission submission = beginOpen(
                owner, requestId, false, connection, pendingToken,
                CameraWorkerProtocol.TX_OPEN_DIRECT, data -> {
                    data.writeString(owner);
                    data.writeString(tag);
                    data.writeInt(index);
                    data.writeInt(requestId);
                    data.writeInt(exclusive ? 1 : 0);
                    surface.writeToParcel(data, 0);
                });
        SessionIdentity identity = submission == null ? null : submission.identity;
        if (identity == null) {
            if (pendingToken != null) {
                synchronized (lock) {
                    if (!pendingOpen.isCurrent(pendingToken)) {
                        return rejected(owner, requestId, "camera open cancelled");
                    }
                    if (!matchesSnapshot(connections.current(), connection)) {
                        return queued(owner, requestId);
                    }
                    pendingOpen.cancel(owner, requestId);
                }
            } else if (surface != null) {
                surface.release();
            }
            emitRejectedOpen(owner, requestId, "worker_epoch_not_recoverable");
            return rejected(owner, requestId, "worker_epoch_not_recoverable");
        }
        try {
            String result = awaitString(submission.call, connection, "open_direct");
            boolean ambiguous = ambiguousOwnership(result);
            if (ambiguous) {
                transportFailure("ambiguous_close", connection,
                        new IllegalStateException(result));
            }
            Boolean exhausted = completeOpen(identity, cameraOpened(result));
            if (exhausted == null) {
                finishFailedOpen(owner, identity);
                return rejected(owner, requestId, "stale_worker_connection");
            }
            if (exhausted) {
                emitRecoveryExhausted(identity, "reopen_failed");
                finishFailedOpen(owner, identity);
            }
            return result;
        } finally {
            releasePending(pendingToken, surface);
        }
    }

    String closeOwner(String owner, String reason, int requestId) {
        ConnectionSnapshot connection;
        SessionIdentity closing;
        Future<String> closeCall;
        synchronized (lock) {
            connection = connections.current();
            closing = cancelOwnerIntentLocked(owner, requestId);
            if (connection == null) return closeResult("already_closed", "");
            closeCall = enqueueStringLocked(
                    connection, CameraWorkerProtocol.TX_CLOSE_OWNER, data -> {
                        data.writeString(owner);
                        data.writeString(reason == null ? "unknown" : reason);
                        data.writeInt(requestId);
                    });
        }
        String result = awaitString(closeCall, connection, "close_owner");
        if (superseded(connection)) {
            return closeResult("camera_close_ignored", "stale_worker_connection");
        }
        if (ambiguousClose(result)) {
            transportFailure("ambiguous_close", connection,
                    new IllegalStateException(result));
        } else if (successfulClose(result)) {
            synchronized (lock) {
                if (!matchesSnapshot(connections.current(), connection)) {
                    return closeResult("camera_close_ignored", "stale_worker_connection");
                }
                SessionIdentity value = sessions.get(owner);
                if (value != null && value == closing) {
                    sessions.remove(owner);
                }
            }
        }
        return result;
    }

    String closeAll(String reason) {
        ConnectionSnapshot connection;
        Future<String> closeCall;
        synchronized (lock) {
            connection = connections.current();
            cancelAllIntentsLocked();
            if (connection == null) return closeResult("already_closed", "");
            closeCall = enqueueStringLocked(
                    connection, CameraWorkerProtocol.TX_CLOSE_ALL,
                    data -> data.writeString(reason == null ? "unknown" : reason));
        }
        String result = awaitString(closeCall, connection, "close_all");
        if (superseded(connection)) {
            return closeResult("camera_close_ignored", "stale_worker_connection");
        }
        if (ambiguousClose(result)) {
            transportFailure("ambiguous_close", connection,
                    new IllegalStateException(result));
        } else if (successfulClose(result)) {
            synchronized (lock) {
                if (!matchesSnapshot(connections.current(), connection)) {
                    return closeResult("camera_close_ignored", "stale_worker_connection");
                }
                sessions.clear();
            }
        }
        return result;
    }

    void reportState() {
        ConnectionSnapshot connection = currentConnection();
        emitState(connection != null ? "camera_worker_state" : "camera_worker_unavailable",
                connection != null ? "connected" : "not_connected",
                connection == null ? 0 : connection.workerEpoch,
                connection == null ? -1 : connection.pid,
                connection == null ? 0 : connection.connectionGeneration);
    }

    boolean resetRecovery(CameraWorkerProtocol.Owner owner) {
        return epochs.resetBlocked(owner);
    }

    ConnectionSnapshot debugConnectionSnapshot() {
        if (!BuildConfig.DEBUG) throw new SecurityException("debug worker access disabled");
        return currentConnection();
    }

    boolean debugKillWorker(ConnectionSnapshot expected) {
        if (!BuildConfig.DEBUG) throw new SecurityException("debug worker access disabled");
        synchronized (lock) {
            if (!matchesSnapshot(connections.current(), expected)) return false;
            Process.killProcess(expected.pid);
            return true;
        }
    }

    boolean debugApplyStaleFailure(ConnectionSnapshot expected) {
        if (!BuildConfig.DEBUG) throw new SecurityException("debug worker access disabled");
        return transportFailure("debug_stale_completion", expected, null);
    }

    String debugSendSurface(Surface surface, int requestId) {
        if (!BuildConfig.DEBUG) throw new SecurityException("debug Surface probe disabled");
        if (surface == null || !surface.isValid() || requestId <= 0) {
            if (surface != null) surface.release();
            throw new IllegalArgumentException("valid debug Surface/request required");
        }
        ConnectionSnapshot connection = currentConnection();
        OpenSubmission submission = beginOpen(
                CameraHelperMain.CAMERA_OWNER_ACTIVITY, requestId, false,
                connection, null, CameraWorkerProtocol.TX_DEBUG_SURFACE_PROBE,
                data -> {
                    data.writeInt(requestId);
                    surface.writeToParcel(data, 0);
                });
        SessionIdentity identity = submission == null ? null : submission.identity;
        if (identity == null) {
            surface.release();
            return rejected(CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                    requestId, "worker_epoch_not_recoverable");
        }
        try {
            String result = awaitString(
                    submission.call, connection, "debug_surface_probe");
            Boolean exhausted = completeOpen(identity, cameraOpened(result));
            if (exhausted == null) {
                finishFailedOpen(identity.owner, identity);
                return rejected(identity.owner, requestId, "stale_worker_connection");
            }
            boolean success = cameraOpened(result);
            if (exhausted) {
                emitRecoveryExhausted(identity, "debug_surface_probe_failed");
            }
            if (!success) finishFailedOpen(identity.owner, identity);
            return result;
        } finally {
            surface.release();
        }
    }

    int currentRequestId(String owner) {
        synchronized (lock) {
            SessionIdentity value = sessions.get(owner);
            return value == null ? 0 : value.requestId;
        }
    }

    boolean closeShellOwnedActivity(String reason) {
        SessionIdentity value;
        synchronized (lock) {
            value = sessions.get(CameraHelperMain.CAMERA_OWNER_ACTIVITY);
            if (value == null || !CameraHelperMain.HelperBinder.shellDeathInvalidatesConsumer(
                    value.owner, value.shellOwned)) return false;
        }
        closeOwner(value.owner, reason, value.requestId);
        return true;
    }

    void shutdown(String reason) {
        try {
            if (currentConnection() != null) closeAll(reason);
        } catch (Throwable ignored) {
        }
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        synchronized (lock) {
            connections.clear();
            sessions.clear();
            pendingOpen.cancelAll();
            epochs.cancelAllRecovery();
        }
        calls.shutdownNow();
    }

    private void retainPending(
            String owner, int requestId,
            CameraWorkerPendingOpen.Operation operation) {
        synchronized (lock) {
            CameraWorkerPendingOpen.Token previous =
                    pendingOpen.replace(owner, requestId, operation);
            if (previous != null && previous.requestId() != requestId) {
                epochs.cancelRecovery(previous.owner(), previous.requestId());
            }
        }
    }

    private SessionIdentity cancelOwnerIntentLocked(String owner, int requestId) {
        pendingOpen.cancel(owner, requestId);
        epochs.cancelPendingRecovery(owner, requestId);
        SessionIdentity current = sessions.get(owner);
        if (current != null && current.requestId == requestId) {
            current.cancelled = true;
            epochs.cancelOpen(current.permit);
            return current;
        }
        epochs.cancelRecovery(owner, requestId);
        return null;
    }

    private void cancelAllIntentsLocked() {
        pendingOpen.cancelAll();
        for (SessionIdentity identity : sessions.values()) {
            identity.cancelled = true;
            epochs.cancelOpen(identity.permit);
        }
        epochs.cancelAllRecovery();
    }

    private OpenSubmission beginOpen(
            String owner, int requestId, boolean shellOwned,
            ConnectionSnapshot connection,
            CameraWorkerPendingOpen.Token pendingToken,
            int transaction, ParcelWriter writer) {
        if (connection == null) return null;
        synchronized (lock) {
            if (!matchesSnapshot(connections.current(), connection)) return null;
            if (pendingToken != null) {
                if (!pendingOpen.isCurrent(pendingToken)) return null;
            }
            return reserveAndInstallOpenLocked(
                    lock, calls, epochs, pendingOpen, pendingToken, sessions,
                    owner, requestId, permit -> new SessionIdentity(
                            owner, requestId, ++nextConsumerGeneration,
                            connection.workerEpoch, ++nextProducerEpoch,
                            connection.connectionGeneration, shellOwned, permit,
                            connection),
                    () -> transactString(connection.binder, transaction, writer));
        }
    }

    private void finishFailedOpen(String owner, SessionIdentity expected) {
        synchronized (lock) {
            if (sessions.get(owner) == expected) sessions.remove(owner);
        }
    }

    private boolean superseded(ConnectionSnapshot initiating) {
        ConnectionSnapshot current = currentConnection();
        return current != null && !matchesSnapshot(current, initiating);
    }

    private Boolean completeOpen(SessionIdentity identity, boolean success) {
        synchronized (lock) {
            if (!acceptsCurrentSession(sessions.get(identity.owner), identity)
                    || !matchesSnapshot(connections.current(), identity.connection)) {
                epochs.cancelOpen(identity.permit);
                return null;
            }
            return epochs.afterOpen(identity.permit, success);
        }
    }

    private Future<String> enqueueStringLocked(
            ConnectionSnapshot connection, int code, ParcelWriter writer) {
        return enqueueWhileLocked(lock, calls,
                () -> transactString(connection.binder, code, writer));
    }

    static <T> Future<T> enqueueWhileLocked(
            Object orderingLock, ExecutorService executor, Callable<T> call) {
        if (!Thread.holdsLock(orderingLock)) {
            throw new IllegalStateException("camera transaction must be ordered with ownership");
        }
        return executor.submit(call);
    }

    static OpenSubmission reserveAndInstallOpenLocked(
            Object orderingLock, ExecutorService executor,
            CameraWorkerEpochGuard epochs, CameraWorkerPendingOpen pending,
            CameraWorkerPendingOpen.Token token,
            Map<String, SessionIdentity> sessions,
            String owner, int requestId, SessionFactory factory,
            Callable<String> call) {
        if (!Thread.holdsLock(orderingLock)) {
            throw new IllegalStateException("camera ownership lock required");
        }
        CameraWorkerEpochGuard.OpenPermit permit =
                epochs.beforeOpen(owner, requestId);
        if (permit == null) return null;
        if (token != null && !pending.promote(token)) {
            epochs.cancelOpen(permit);
            return null;
        }
        try {
            SessionIdentity next = factory.create(permit);
            Future<String> future =
                    enqueueWhileLocked(orderingLock, executor, call);
            if (token == null) {
                CameraWorkerPendingOpen.Token superseded =
                        pending.cancelOwner(owner);
                if (superseded != null) {
                    epochs.cancelRecovery(
                            superseded.owner(), superseded.requestId());
                }
            }
            SessionIdentity previous = sessions.get(owner);
            if (previous != null) {
                previous.cancelled = true;
                epochs.cancelOpen(previous.permit);
            }
            sessions.put(owner, next);
            return new OpenSubmission(next, future);
        } catch (RuntimeException error) {
            epochs.cancelOpen(permit);
            if (token != null) token.release();
            return null;
        }
    }

    private String awaitString(
            Future<String> call, ConnectionSnapshot connection, String operation) {
        try {
            return await(call);
        } catch (Throwable error) {
            transportFailure(operation + "_failed", connection, error);
            return unavailable(operation);
        }
    }

    private void connect(IBinder service) {
        ConnectionSnapshot candidate = null;
        try {
            CameraWorkerProtocol.Handshake handshake = withDeadline(
                    () -> handshake(service));
            if (!isRemoteWorkerPid(handshake.pid, Process.myPid())) {
                emitState("camera_worker_rejected", "same_process_pid",
                        handshake.workerEpoch, handshake.pid, 0);
                stopBinding();
                return;
            }
            if (!epochs.validHandshake(
                    handshake, CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE)) {
                Process.killProcess(handshake.pid);
                emitState("camera_worker_rejected", "handshake_mismatch",
                        handshake.workerEpoch, handshake.pid, 0);
                return;
            }
            synchronized (lock) {
                candidate = connections.reserve(
                        service, handshake.workerEpoch, handshake.pid);
            }
            withDeadline(() -> {
                transactVoid(service, CameraWorkerProtocol.TX_REGISTER_CALLBACK,
                        data -> data.writeStrongBinder(callback));
                return null;
            });
            ConnectionSnapshot linkedCandidate = candidate;
            service.linkToDeath(() -> handler.post(
                    () -> connectionFailure("binder_died", linkedCandidate, null)), 0);
            boolean replacement = candidate.connectionGeneration > 1;
            boolean accepted;
            FailureOutcome takeover = null;
            synchronized (lock) {
                if (!connections.isCandidate(candidate)) return;
                ConnectionSnapshot previous = connections.current();
                if (previous != null && !matchesSnapshot(previous, candidate)) {
                    takeover = retireCurrentLocked(
                            "worker_replaced", previous, null, candidate.pid);
                }
                accepted = epochs.acceptHandshake(
                        handshake, CameraWorkerProtocol.VERSION, BuildConfig.VERSION_CODE);
                if (accepted) {
                    accepted = connections.install(candidate);
                }
            }
            if (!accepted) {
                if (takeover != null) emitFailure(takeover);
                retireCandidate("handshake_rejected", candidate, null);
                return;
            }
            if (takeover != null) emitFailure(takeover);
            emitState(!replacement ? "camera_worker_handshake"
                            : "camera_worker_epoch_changed",
                    "connected", candidate.workerEpoch, candidate.pid,
                    candidate.connectionGeneration);
            discoverCamera();
            CameraWorkerPendingOpen.Token[] queued;
            synchronized (lock) {
                queued = pendingOpen.claimAll();
            }
            for (CameraWorkerPendingOpen.Token value : queued) {
                value.replay();
            }
        } catch (Throwable error) {
            if (candidate != null) {
                connectionFailure("handshake_failed", candidate, error);
            } else {
                emitState("camera_worker_unavailable", "handshake_failed", 0, -1, 0);
            }
        }
    }

    private void acceptEvent(String line, int callingPid) {
        try {
            JSONObject event = new JSONObject(line);
            long eventEpoch = event.optLong("worker_epoch", 0);
            ConnectionSnapshot connection = currentConnection();
            if (connection == null || connection.pid != callingPid
                    || connection.workerEpoch != eventEpoch
                    || !epochs.acceptsEvent(eventEpoch)) return;
            if ("one_shot_owner_unconfirmed".equals(event.optString("stage"))) {
                return; // Proxy emits the authoritative worker-failure terminal after reply.
            }
            String owner = event.optString("camera_owner", "none");
            int requestId = event.optInt("request_id", 0);
            event.put("connection_generation", connection.connectionGeneration);
            if (CameraWorkerProtocol.allowedOwner(owner)) {
                synchronized (lock) {
                    SessionIdentity current = sessions.get(owner);
                    if (!acceptsCurrentSession(current, current)
                            || requestId != current.requestId
                            || !matchesSnapshot(current.connection, connection)
                            || !current.gate.accepts(event)) return;
                    CameraEventKey boundKey = current.gate.boundKey();
                    if (boundKey != null) current.boundKey = boundKey;
                    String kind = event.optString("kind");
                    if ("camera_closed".equals(kind)
                            || boundKey == null && "camera_error".equals(kind)) {
                        sessions.remove(owner);
                    }
                }
            }
            eventSink.accept(event.toString());
        } catch (Throwable error) {
            Log.w(TAG, "worker event rejected", error);
        }
    }

    private boolean transportFailure(
            String reason, ConnectionSnapshot failed, Throwable error) {
        if (failed == null) return false;
        FailureOutcome outcome;
        synchronized (lock) {
            if (!connections.acceptsFailure(failed)) return false;
            outcome = retireCurrentLocked(reason, failed, error, -1);
        }
        if (outcome == null) return false;
        emitFailure(outcome);
        return true;
    }

    private FailureOutcome retireCurrentLocked(
            String reason, ConnectionSnapshot failed, Throwable error,
            int protectedPid) {
        if (!connections.retireCurrent(failed)) return null;
        SessionIdentity[] abandoned;
        boolean[] exhausted;
        int killPid;
        abandoned = sessions.values().toArray(new SessionIdentity[0]);
        sessions.clear();
        CameraWorkerPendingOpen.Token[] pending = pendingOpen.tokens();
        int activeCount = pending.length;
        for (SessionIdentity identity : abandoned) {
            if (!identity.cancelled) activeCount++;
        }
        CameraWorkerEpochGuard.RecoveryRequest[] recoveryRequests =
                new CameraWorkerEpochGuard.RecoveryRequest[activeCount];
        int next = 0;
        for (SessionIdentity identity : abandoned) {
            if (!identity.cancelled) {
                recoveryRequests[next++] = new CameraWorkerEpochGuard.RecoveryRequest(
                        identity.owner, identity.requestId);
            }
        }
        for (CameraWorkerPendingOpen.Token token : pending) {
            recoveryRequests[next++] = new CameraWorkerEpochGuard.RecoveryRequest(
                    token.owner(), token.requestId());
        }
        exhausted = new boolean[abandoned.length];
        for (int i = 0; i < abandoned.length; i++) {
            if (!abandoned[i].cancelled) {
                exhausted[i] = epochs.failInFlightRecovery(abandoned[i].permit);
            }
        }
        killPid = epochs.quarantine(
                failed.workerEpoch, failed.pid, recoveryRequests);
        if (killPid > 0 && killPid != protectedPid) Process.killProcess(killPid);
        return new FailureOutcome(reason, error, failed, abandoned, exhausted);
    }

    private void emitFailure(FailureOutcome outcome) {
        for (int i = 0; i < outcome.abandoned.length; i++) {
            if (outcome.exhausted[i]) {
                emitRecoveryExhausted(
                        outcome.abandoned[i], "transport_" + outcome.reason);
            } else {
                emitTerminal(outcome.abandoned[i], outcome.reason, outcome.error);
            }
        }
        emitState("camera_worker_failed", outcome.reason,
                outcome.failed.workerEpoch, outcome.failed.pid,
                outcome.failed.connectionGeneration);
    }

    private void emitTerminal(SessionIdentity identity, String reason, Throwable error) {
        try {
            String detail = error == null ? reason
                    : error.getClass().getSimpleName() + ": " + error.getMessage();
            JSONObject failed = identity.event("camera_error")
                    .put("stage", "camera_worker_" + reason)
                    .put("worker_failure", true)
                    .put("failed_worker_epoch", identity.workerEpoch)
                    .put("error", detail);
            eventSink.accept(failed.toString());
            JSONObject closed = identity.event("camera_closed")
                    .put("reason", reason)
                    .put("worker_failure", true)
                    .put("failed_worker_epoch", identity.workerEpoch)
                    .put("error", detail);
            eventSink.accept(closed.toString());
        } catch (Throwable ignored) {
        }
    }

    private void emitRecoveryExhausted(SessionIdentity identity, String reason) {
        try {
            eventSink.accept(recoveryExhaustedSpec(
                    identity.key(), reason).toJson().toString());
        } catch (Throwable ignored) {
        }
    }

    static RecoveryExhaustedSpec recoveryExhaustedSpec(
            CameraEventKey key, String reason) {
        return new RecoveryExhaustedSpec(key,
                reason == null ? "recovery exhausted" : reason);
    }

    private void emitRejectedOpen(String owner, int requestId, String reason) {
        SessionIdentity identity;
        synchronized (lock) {
            identity = new SessionIdentity(owner, requestId,
                    ++nextConsumerGeneration, epochs.epoch(),
                    ++nextProducerEpoch, connections.generation(), false, null,
                    connections.current());
        }
        emitRecoveryExhausted(identity, reason);
    }

    private void emitState(
            String kind, String reason, long epoch, int pid,
            long eventConnectionGeneration) {
        try {
            eventSink.accept(new JSONObject()
                    .put("kind", kind)
                    .put("source", "camera_worker_client")
                    .put("reason", reason)
                    .put("protocol", CameraWorkerProtocol.VERSION)
                    .put("build", BuildConfig.VERSION_CODE)
                    .put("pid", pid)
                    .put("worker_epoch", epoch)
                    .put("producer_epoch", 0)
                    .put("camera_owner", "none")
                    .put("request_id", 0)
                    .put("consumer_generation", 0)
                    .put("connection_generation", eventConnectionGeneration)
                    .toString());
        } catch (Throwable ignored) {
        }
    }

    private ConnectionSnapshot currentConnection() {
        synchronized (lock) {
            return connections.current();
        }
    }

    private boolean pendingCurrent(CameraWorkerPendingOpen.Token token) {
        synchronized (lock) {
            return pendingOpen.isCurrent(token);
        }
    }

    private boolean connectionFailure(
            String reason, ConnectionSnapshot failed, Throwable error) {
        return retireCandidate(reason, failed, error)
                || transportFailure(reason, failed, error);
    }

    private boolean retireCandidate(
            String reason, ConnectionSnapshot failed, Throwable error) {
        if (failed == null) return false;
        synchronized (lock) {
            if (!connections.retireCandidate(failed)) return false;
            Process.killProcess(failed.pid);
        }
        emitState("camera_worker_unavailable", reason,
                failed.workerEpoch, failed.pid, failed.connectionGeneration);
        return true;
    }

    private static void releasePending(
            CameraWorkerPendingOpen.Token token, Surface[] surfaces) {
        if (token != null) token.release();
        else CameraWorkerProtocol.release(surfaces);
    }

    private static void releasePending(
            CameraWorkerPendingOpen.Token token, Surface surface) {
        if (token != null) token.release();
        else if (surface != null) surface.release();
    }

    static boolean matchesSnapshot(
            ConnectionSnapshot installed, ConnectionSnapshot initiating) {
        return installed != null && initiating != null
                && installed.binder == initiating.binder
                && installed.workerEpoch == initiating.workerEpoch
                && installed.pid == initiating.pid
                && installed.connectionGeneration == initiating.connectionGeneration;
    }

    static boolean acceptsCurrentSession(
            SessionIdentity current, SessionIdentity expected) {
        return expected != null && current == expected && !expected.cancelled;
    }

    private static CameraWorkerProtocol.Handshake handshake(IBinder target) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraWorkerProtocol.DESCRIPTOR);
            if (!target.transact(CameraWorkerProtocol.TX_HANDSHAKE, data, reply, 0)) {
                throw new IllegalStateException("camera worker handshake rejected");
            }
            reply.readException();
            return new CameraWorkerProtocol.Handshake(
                    reply.readInt(), reply.readInt(), reply.readInt(), reply.readLong());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String transactString(
            IBinder target, int code, ParcelWriter writer) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraWorkerProtocol.DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!target.transact(code, data, reply, 0)) {
                throw new IllegalStateException("camera worker transaction rejected: " + code);
            }
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactBoolean(
            IBinder target, int code, ParcelWriter writer) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraWorkerProtocol.DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!target.transact(code, data, reply, 0)) {
                throw new IllegalStateException("camera worker transaction rejected: " + code);
            }
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void transactVoid(
            IBinder target, int code, ParcelWriter writer) throws Exception {
        transactStringLike(target, code, writer);
    }

    private static void transactStringLike(
            IBinder target, int code, ParcelWriter writer) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraWorkerProtocol.DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!target.transact(code, data, reply, 0)) {
                throw new IllegalStateException("camera worker transaction rejected: " + code);
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private <T> T withDeadline(Callable<T> operation) throws Exception {
        return await(calls.submit(operation));
    }

    private <T> T await(Future<T> future) throws Exception {
        try {
            return future.get(CALL_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new Exception(cause);
        }
    }

    private static void writeOptionalInts(Parcel data, int[] values) {
        if (values == null) {
            data.writeInt(-1);
            return;
        }
        data.writeInt(values.length);
        for (int value : values) data.writeInt(value);
    }

    private static boolean cameraOpened(String result) {
        return "camera_opened".equals(resultKind(result));
    }

    private static boolean successfulClose(String result) {
        String kind = resultKind(result);
        return ("camera_closed".equals(kind) || "already_closed".equals(kind))
                && resultError(result).isEmpty();
    }

    private static boolean ambiguousClose(String result) {
        String kind = resultKind(result);
        if ("camera_close_ignored".equals(kind)) return false;
        return !successfulClose(result);
    }

    private static boolean ambiguousOwnership(String result) {
        return resultError(result).contains("owner close was not confirmed");
    }

    private static String resultKind(String result) {
        try {
            return new JSONObject(result).optString("kind");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String resultError(String result) {
        try {
            return new JSONObject(result).optString("error", "invalid result");
        } catch (Throwable ignored) {
            return "invalid result";
        }
    }

    private static String rejected(String owner, int requestId, String reason) {
        try {
            return new JSONObject().put("kind", "camera_error")
                    .put("camera_owner", owner).put("request_id", requestId)
                    .put("error", reason).toString();
        } catch (Throwable ignored) {
            return "camera_error";
        }
    }

    private static String queued(String owner, int requestId) {
        try {
            return new JSONObject().put("kind", "camera_worker_open_queued")
                    .put("camera_owner", owner).put("request_id", requestId)
                    .put("error", "").toString();
        } catch (Throwable ignored) {
            return "camera_worker_open_queued";
        }
    }

    private static String closeResult(String kind, String error) {
        try {
            return new JSONObject().put("kind", kind).put("error", error).toString();
        } catch (Throwable ignored) {
            return kind;
        }
    }

    private static String unavailable(String operation) {
        return rejected("none", 0, "camera worker unavailable: " + operation);
    }

    static boolean isRemoteWorkerPid(int handshakePid, int clientPid) {
        return handshakePid > 0 && clientPid > 0 && handshakePid != clientPid;
    }

    static final class ConnectionSnapshot {
        final IBinder binder;
        final long workerEpoch;
        final int pid;
        final long connectionGeneration;

        ConnectionSnapshot(
                IBinder binder, long workerEpoch, int pid,
                long connectionGeneration) {
            if (binder == null || workerEpoch == 0 || pid <= 0
                    || connectionGeneration <= 0) {
                throw new IllegalArgumentException("complete worker connection required");
            }
            this.binder = binder;
            this.workerEpoch = workerEpoch;
            this.pid = pid;
            this.connectionGeneration = connectionGeneration;
        }
    }

    static final class ConnectionSlots {
        private ConnectionSnapshot current;
        private ConnectionSnapshot candidate;
        private long generation;

        ConnectionSnapshot reserve(IBinder binder, long workerEpoch, int pid) {
            if (candidate != null) {
                throw new IllegalStateException("worker candidate already reserved");
            }
            candidate = new ConnectionSnapshot(
                    binder, workerEpoch, pid, ++generation);
            return candidate;
        }

        boolean isCandidate(ConnectionSnapshot expected) {
            return matchesSnapshot(candidate, expected);
        }

        boolean install(ConnectionSnapshot expected) {
            if (!matchesSnapshot(candidate, expected)) return false;
            current = candidate;
            candidate = null;
            return true;
        }

        boolean retireCandidate(ConnectionSnapshot expected) {
            if (!matchesSnapshot(candidate, expected)) return false;
            candidate = null;
            return true;
        }

        boolean acceptsFailure(ConnectionSnapshot expected) {
            return candidate != null
                    ? matchesSnapshot(candidate, expected)
                    : matchesSnapshot(current, expected);
        }

        boolean retireCurrent(ConnectionSnapshot expected) {
            if (!matchesSnapshot(current, expected)) return false;
            current = null;
            return true;
        }

        ConnectionSnapshot current() {
            return current;
        }

        long generation() {
            return generation;
        }

        void clear() {
            current = null;
            candidate = null;
        }
    }

    static final class RecoveryExhaustedSpec {
        private final CameraEventKey key;
        private final String error;

        private RecoveryExhaustedSpec(CameraEventKey key, String error) {
            if (key == null) throw new IllegalArgumentException("camera event key required");
            this.key = key;
            this.error = error;
        }

        String kind() {
            return "camera_error";
        }

        String source() {
            return "helper";
        }

        String stage() {
            return "camera_worker_recovery_exhausted";
        }

        boolean recoveryExhausted() {
            return true;
        }

        boolean workerFailure() {
            return true;
        }

        long failedWorkerEpoch() {
            return key.workerEpoch();
        }

        String error() {
            return error;
        }

        CameraEventKey key() {
            return key;
        }

        JSONObject toJson() throws Exception {
            return key.putInto(new JSONObject()
                    .put("kind", kind())
                    .put("source", source())
                    .put("stage", stage())
                    .put("recovery_exhausted", recoveryExhausted())
                    .put("worker_failure", workerFailure())
                    .put("failed_worker_epoch", failedWorkerEpoch())
                    .put("error", error));
        }
    }

    private static final class FailureOutcome {
        final String reason;
        final Throwable error;
        final ConnectionSnapshot failed;
        final SessionIdentity[] abandoned;
        final boolean[] exhausted;

        FailureOutcome(
                String reason, Throwable error, ConnectionSnapshot failed,
                SessionIdentity[] abandoned, boolean[] exhausted) {
            this.reason = reason;
            this.error = error;
            this.failed = failed;
            this.abandoned = abandoned;
            this.exhausted = exhausted;
        }
    }

    interface SessionFactory {
        SessionIdentity create(CameraWorkerEpochGuard.OpenPermit permit);
    }

    static final class OpenSubmission {
        final SessionIdentity identity;
        final Future<String> call;

        OpenSubmission(SessionIdentity identity, Future<String> call) {
            this.identity = identity;
            this.call = call;
        }
    }

    private void stopBinding() {
        if (!bound) return;
        try {
            context.unbindService(connection);
        } catch (Throwable ignored) {
        }
        bound = false;
    }

    private final class WorkerConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            connect(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            ConnectionSnapshot connection = currentConnection();
            if (connection != null && !connection.binder.isBinderAlive()) {
                transportFailure("service_disconnected", connection, null);
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            ConnectionSnapshot connection = currentConnection();
            if (connection != null && !connection.binder.isBinderAlive()) {
                transportFailure("binding_died", connection, null);
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            emitState("camera_worker_unavailable", "null_binding", 0, -1, 0);
        }
    }

    private final class CallbackBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != CameraWorkerProtocol.CB_EVENT) return false;
            data.enforceInterface(CameraWorkerProtocol.CALLBACK_DESCRIPTOR);
            String line = data.readString();
            int callingPid = Binder.getCallingPid();
            handler.post(() -> acceptEvent(line, callingPid));
            return true;
        }
    }

    static final class SessionIdentity {
        final String owner;
        final int requestId;
        final long workerEpoch;
        final CameraEventKey provisionalKey;
        final CameraWorkerEventGate gate;
        CameraEventKey boundKey;
        final boolean shellOwned;
        final CameraWorkerEpochGuard.OpenPermit permit;
        final ConnectionSnapshot connection;
        boolean cancelled;

        SessionIdentity(
                String owner, int requestId, int consumerGeneration,
                long workerEpoch, int producerEpoch,
                long connectionGeneration, boolean shellOwned,
                CameraWorkerEpochGuard.OpenPermit permit,
                ConnectionSnapshot connection) {
            this.owner = owner;
            this.requestId = requestId;
            this.workerEpoch = workerEpoch;
            provisionalKey = new CameraEventKey(
                    workerEpoch, producerEpoch, owner, requestId,
                    consumerGeneration, connectionGeneration);
            gate = new CameraWorkerEventGate(owner, requestId);
            this.shellOwned = shellOwned;
            this.permit = permit;
            this.connection = connection;
        }

        JSONObject event(String kind) throws Exception {
            return key().putInto(
                    new JSONObject().put("kind", kind).put("source", "helper"));
        }


        CameraEventKey key() {
            return boundKey == null ? provisionalKey : boundKey;
        }
    }
}
