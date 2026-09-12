package com.byd.extend;

/** One process-owned result: a cache read or lifecycle callback is never a new hint event. */
final class UpdateResultPresentation {
    private String resultId;
    private boolean offerPending;

    boolean accept(String id, boolean fresh, boolean ownUiVisible,
            boolean enabled, boolean overlayGranted) {
        if (id == null) {
            if (fresh) invalidate();
            return false;
        }
        if (!fresh || id.equals(resultId)) return false;
        resultId = id;
        offerPending = true;
        return !ownUiVisible && enabled && overlayGranted;
    }

    boolean hasOffer(String id) {
        return offerPending && id != null && id.equals(resultId);
    }

    void consume(String id) {
        if (id != null && id.equals(resultId)) offerPending = false;
    }

    void invalidate() {
        offerPending = false;
        resultId = null;
    }
}
