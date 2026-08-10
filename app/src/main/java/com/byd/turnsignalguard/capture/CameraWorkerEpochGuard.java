package com.byd.turnsignalguard.capture;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Small production-used state machine for accepting only a genuinely new worker epoch. */
final class CameraWorkerEpochGuard {
    private long epoch;
    private long quarantinedEpoch;
    private int pid = -1;
    private boolean stalePidKilled;
    private final Set<RecoveryRequest> recoveryRequests = new HashSet<>();
    private final Map<RecoveryRequest, OpenPermit> inFlightRecovery = new HashMap<>();
    private final Set<String> blockedOwners = new HashSet<>();

    static final class RecoveryRequest {
        private final String owner;
        private final int requestId;

        RecoveryRequest(String owner, int requestId) {
            if (owner == null || requestId <= 0) {
                throw new IllegalArgumentException("owner/request required");
            }
            this.owner = owner;
            this.requestId = requestId;
        }

        String owner() {
            return owner;
        }

        int requestId() {
            return requestId;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof RecoveryRequest)) return false;
            RecoveryRequest other = (RecoveryRequest) value;
            return requestId == other.requestId && owner.equals(other.owner);
        }

        @Override
        public int hashCode() {
            return 31 * owner.hashCode() + requestId;
        }
    }

    static final class OpenPermit {
        private final RecoveryRequest request;
        private final boolean recoveryAttempt;
        private boolean resolved;

        private OpenPermit(RecoveryRequest request, boolean recoveryAttempt) {
            this.request = request;
            this.recoveryAttempt = recoveryAttempt;
        }

        String owner() {
            return request.owner;
        }

        int requestId() {
            return request.requestId;
        }

        boolean recoveryAttempt() {
            return recoveryAttempt;
        }

        private boolean resolve() {
            if (resolved) return false;
            resolved = true;
            return true;
        }
    }

    synchronized boolean acceptHandshake(
            CameraWorkerProtocol.Handshake value, int protocol, int build) {
        if (!validHandshake(value, protocol, build)) return false;
        epoch = value.workerEpoch;
        pid = value.pid;
        stalePidKilled = false;
        return true;
    }

    synchronized boolean validHandshake(
            CameraWorkerProtocol.Handshake value, int protocol, int build) {
        return value != null && value.protocol == protocol && value.build == build
                && value.pid > 0 && value.workerEpoch != 0
                && value.workerEpoch != quarantinedEpoch;
    }

    synchronized OpenPermit beforeOpen(String owner, int requestId) {
        RecoveryRequest request = new RecoveryRequest(owner, requestId);
        if (epoch == 0 || blockedOwners.contains(owner)) return null;
        if (!recovering()) return new OpenPermit(request, false);
        if (!recoveryRequests.remove(request)) return null;
        OpenPermit permit = new OpenPermit(request, true);
        inFlightRecovery.put(request, permit);
        return permit;
    }

    synchronized boolean afterOpen(OpenPermit permit, boolean success) {
        if (permit == null || !permit.recoveryAttempt
                || inFlightRecovery.get(permit.request) != permit
                || !permit.resolve()) return false;
        inFlightRecovery.remove(permit.request);
        if (success) return false;
        blockedOwners.add(permit.owner());
        return true;
    }

    synchronized boolean failInFlightRecovery(OpenPermit permit) {
        if (permit == null || !permit.recoveryAttempt
                || inFlightRecovery.get(permit.request) != permit
                || !permit.resolve()) return false;
        inFlightRecovery.remove(permit.request);
        blockedOwners.add(permit.owner());
        return true;
    }

    synchronized boolean cancelRecovery(String owner, int requestId) {
        RecoveryRequest request = new RecoveryRequest(owner, requestId);
        boolean changed = recoveryRequests.remove(request);
        OpenPermit permit = inFlightRecovery.remove(request);
        if (permit != null) {
            permit.resolve();
            changed = true;
        }
        return changed;
    }

    synchronized boolean cancelPendingRecovery(String owner, int requestId) {
        return recoveryRequests.remove(new RecoveryRequest(owner, requestId));
    }

    synchronized boolean cancelOpen(OpenPermit permit) {
        if (permit == null) return false;
        if (!permit.recoveryAttempt) return permit.resolve();
        if (inFlightRecovery.get(permit.request) != permit || !permit.resolve()) {
            return false;
        }
        inFlightRecovery.remove(permit.request);
        return true;
    }

    synchronized void cancelAllRecovery() {
        for (OpenPermit permit : inFlightRecovery.values()) permit.resolve();
        inFlightRecovery.clear();
        recoveryRequests.clear();
    }

    synchronized boolean resetBlocked(CameraWorkerProtocol.Owner owner) {
        return owner != null && blockedOwners.remove(owner.wireName());
    }

    synchronized int quarantine(
            long failedEpoch, int failedPid, RecoveryRequest[] previouslyActive) {
        if (failedEpoch == 0 || failedEpoch != epoch || failedPid != pid) return -1;
        if (stalePidKilled) return -1;
        quarantinedEpoch = failedEpoch;
        Set<RecoveryRequest> nextRequests = new HashSet<>(recoveryRequests);
        recoveryRequests.clear();
        if (previouslyActive != null) {
            for (RecoveryRequest request : previouslyActive) {
                if (request != null && !blockedOwners.contains(request.owner)) {
                    nextRequests.add(request);
                }
            }
        }
        for (RecoveryRequest request : nextRequests) {
            if (!blockedOwners.contains(request.owner)) recoveryRequests.add(request);
        }
        stalePidKilled = true;
        return failedPid;
    }

    synchronized boolean acceptsEvent(long eventEpoch) {
        return eventEpoch != 0 && eventEpoch == epoch && eventEpoch != quarantinedEpoch;
    }

    synchronized long epoch() {
        return epoch;
    }

    synchronized int pid() {
        return pid;
    }

    synchronized boolean failClosed(String owner) {
        return blockedOwners.contains(owner);
    }

    private boolean recovering() {
        return !recoveryRequests.isEmpty() || !inFlightRecovery.isEmpty();
    }
}
