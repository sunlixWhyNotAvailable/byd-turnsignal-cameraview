package com.byd.extend;

final class ActivityCameraCloseIdentity {
    private int requestId;
    private String reason;
    private long cameraShellEpoch;
    private long avmShellEpoch;

    void capture(int requestId, String reason, long cameraShellEpoch) {
        clear();
        if (requestId <= 0) return;
        this.requestId = requestId;
        this.reason = reason;
        this.cameraShellEpoch = cameraShellEpoch;
        this.avmShellEpoch = 0;
    }

    void updateRequestId(int requestId) {
        if (this.requestId > 0 && requestId > 0) this.requestId = requestId;
    }

    boolean matchesOperation(int eventRequestId, String eventReason) {
        return requestId > 0 && eventRequestId == requestId
                && reason != null && reason.equals(eventReason);
    }

    boolean matchesTerminal(int eventRequestId, String eventReason,
            long eventCameraShellEpoch, long eventAvmShellEpoch, boolean stockAvmShell) {
        return matchesOwner(eventRequestId, eventCameraShellEpoch,
                eventAvmShellEpoch, stockAvmShell)
                && reason != null && reason.equals(eventReason);
    }

    boolean matchesUnopenedTerminal(int eventRequestId, String eventReason,
            long eventCameraShellEpoch) {
        return matchesOperation(eventRequestId, eventReason)
                && matchesEpoch(cameraShellEpoch, eventCameraShellEpoch)
                && avmShellEpoch == 0;
    }

    boolean matchesCloseFailure(int eventRequestId, String eventReason,
            long eventCameraShellEpoch, long eventAvmShellEpoch, boolean stockAvmShell) {
        if (requestId <= 0 || eventRequestId != requestId
                || reason == null || !reason.equals(eventReason)) return false;
        if (!stockAvmShell) {
            return matchesOptionalEpoch(cameraShellEpoch, eventCameraShellEpoch);
        }
        if (cameraShellEpoch > 0
                ? !matchesEpoch(cameraShellEpoch, eventCameraShellEpoch)
                : eventCameraShellEpoch < 0) return false;
        // Cancellation before shell creation can fail with zero epochs; only an explicit
        // failure of this exact operation may resolve without established shell ownership.
        return avmShellEpoch > 0
                ? matchesEpoch(avmShellEpoch, eventAvmShellEpoch)
                : eventAvmShellEpoch >= 0;
    }

    boolean bindStockCloseOwner(int eventRequestId, String eventReason,
            long eventCameraShellEpoch, long eventAvmShellEpoch) {
        if (!matchesOperation(eventRequestId, eventReason)
                || eventCameraShellEpoch <= 0 || eventAvmShellEpoch <= 0
                || cameraShellEpoch > 0 && cameraShellEpoch != eventCameraShellEpoch
                || avmShellEpoch > 0 && avmShellEpoch != eventAvmShellEpoch) return false;
        cameraShellEpoch = eventCameraShellEpoch;
        avmShellEpoch = eventAvmShellEpoch;
        return true;
    }

    boolean matchesShellDeath(int eventRequestId,
            long eventCameraShellEpoch, long eventAvmShellEpoch) {
        return matchesOwner(eventRequestId, eventCameraShellEpoch,
                eventAvmShellEpoch, true);
    }

    private boolean matchesOwner(int eventRequestId, long eventCameraShellEpoch,
            long eventAvmShellEpoch, boolean stockAvmShell) {
        return requestId > 0 && eventRequestId == requestId
                && (stockAvmShell
                        ? matchesEpoch(cameraShellEpoch, eventCameraShellEpoch)
                        : matchesOptionalEpoch(cameraShellEpoch, eventCameraShellEpoch))
                && (!stockAvmShell || matchesEpoch(avmShellEpoch, eventAvmShellEpoch));
    }

    private static boolean matchesEpoch(long ownedEpoch, long eventEpoch) {
        return ownedEpoch > 0 && eventEpoch > 0 && ownedEpoch == eventEpoch;
    }

    private static boolean matchesOptionalEpoch(long ownedEpoch, long eventEpoch) {
        return ownedEpoch <= 0 || eventEpoch <= 0 || ownedEpoch == eventEpoch;
    }

    boolean matchesReason(String candidate) {
        return reason != null && reason.equals(candidate);
    }

    boolean ownsCameraShellEpoch(long candidate) {
        return cameraShellEpoch > 0 && cameraShellEpoch == candidate;
    }

    int requestId() {
        return requestId;
    }

    String reason() {
        return reason;
    }

    long cameraShellEpoch() {
        return cameraShellEpoch;
    }

    long avmShellEpoch() {
        return avmShellEpoch;
    }

    void clear() {
        requestId = 0;
        reason = null;
        cameraShellEpoch = 0;
        avmShellEpoch = 0;
    }
}
