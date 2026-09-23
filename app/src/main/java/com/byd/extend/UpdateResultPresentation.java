package com.byd.extend;

/** One process-owned result: a cache read or lifecycle callback is never a new hint event. */
final class UpdateResultPresentation {
    private String resultId;
    private boolean offerPending;
    private boolean hintPending;
    private int failures;
    private long retryAt;
    private long sequence;
    private long activeAttempt;
    private boolean displayReady;

    boolean accept(String id, boolean fresh, boolean ownUiVisible,
            boolean enabled, boolean overlayGranted) {
        if (id == null) {
            if (fresh) invalidate();
            return false;
        }
        if (!fresh || id.equals(resultId)) return false;
        resultId = id;
        offerPending = true;
        cancelHint();
        failures = 0;
        hintPending = !ownUiVisible && enabled && overlayGranted;
        return hintPending;
    }

    boolean hasPendingOffer() { return offerPending; }
    boolean hasPendingHint() { return hintPending; }
    boolean isAttempting() { return activeAttempt != 0L; }
    int failureCount() { return failures; }

    static String coordinationEventId(String resultId, long attemptId) {
        // Peers retire a cleared event, so another window attempt needs its own identity.
        return resultId + "-attempt-" + attemptId;
    }

    void setDisplayReady(boolean ready) {
        displayReady = ready;
        if (!ready) pauseAttempt();
    }

    long delayUntilAttempt(long now) {
        return !hintPending || !displayReady || isAttempting()
                ? -1L : Math.max(0L, retryAt - now);
    }

    long beginAttempt(long now) {
        if (delayUntilAttempt(now) != 0L) return 0L;
        activeAttempt = ++sequence;
        return activeAttempt;
    }

    boolean shown(String id, long attempt) {
        if (!matches(id, attempt)) return false;
        cancelHint();
        return true;
    }

    boolean deferred(String id, long attempt) {
        if (!matches(id, attempt)) return false;
        pauseAttempt();
        return true;
    }

    boolean failed(String id, long attempt, long now) {
        if (!matches(id, attempt)) return false;
        activeAttempt = 0L;
        failures++;
        if (failures >= 3) cancelHint();
        else retryAt = now + (failures == 1 ? 1_000L : 3_000L);
        return true;
    }

    boolean cancelled(String id, long attempt) {
        if (!matches(id, attempt)) return false;
        cancelHint();
        return true;
    }

    // Display suspension does not spend or reset the technical-failure budget.
    void pauseAttempt() { activeAttempt = 0L; }

    boolean cancelHint() {
        boolean wasPending = hintPending;
        hintPending = false;
        activeAttempt = 0L;
        retryAt = 0L;
        return wasPending;
    }

    private boolean matches(String id, long attempt) {
        return hintPending && attempt != 0L && attempt == activeAttempt
                && id != null && id.equals(resultId);
    }

    boolean hasOffer(String id) {
        return offerPending && id != null && id.equals(resultId);
    }

    void consume(String id) {
        if (id != null && id.equals(resultId)) {
            offerPending = false;
            cancelHint();
        }
    }

    void invalidate() {
        offerPending = false;
        resultId = null;
        cancelHint();
    }
}
