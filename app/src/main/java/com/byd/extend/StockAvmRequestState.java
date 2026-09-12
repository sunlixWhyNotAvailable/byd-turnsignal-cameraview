package com.byd.extend;

/** Request identity outlives input delivery; all access is under HelperBinder's lock. */
final class StockAvmRequestState {
    private int requestId;
    private boolean awaitingInput;

    void begin(int requestId) {
        if (requestId <= 0) throw new IllegalArgumentException("camera request id required");
        this.requestId = requestId;
        awaitingInput = true;
    }

    int id() {
        return requestId;
    }

    int pendingId() {
        return awaitingInput ? requestId : 0;
    }

    boolean isActive() {
        return requestId > 0;
    }

    boolean matches(int expectedRequestId) {
        return expectedRequestId > 0 && requestId == expectedRequestId;
    }

    boolean takeInput(int expectedRequestId) {
        if (!awaitingInput || !matches(expectedRequestId)) return false;
        awaitingInput = false;
        return true;
    }

    void clear() {
        requestId = 0;
        awaitingInput = false;
    }
}
