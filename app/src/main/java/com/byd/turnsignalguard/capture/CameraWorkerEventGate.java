package com.byd.turnsignalguard.capture;

import org.json.JSONObject;

/** Binds on first authoritative open and rejects every later tuple mismatch. */
final class CameraWorkerEventGate {
    private final String owner;
    private final int requestId;
    private CameraEventKey bound;

    CameraWorkerEventGate(String owner, int requestId) {
        this.owner = owner;
        this.requestId = requestId;
    }

    synchronized boolean accepts(JSONObject event) {
        if (event == null) return false;
        return accepts(event.optString("kind"), event.optString("source"),
                CameraEventKey.fromEvent(event));
    }

    synchronized boolean accepts(
            String kind, String source, CameraEventKey key) {
        if (!"helper".equals(source)) return false;
        if (key == null || !key.matchesOwnerRequest(owner, requestId)) return false;
        if (bound != null) return bound.equals(key);
        if ("camera_consumer_attached".equals(kind) || "camera_opened".equals(kind)) {
            bound = key;
            return true;
        }
        return "camera_error".equals(kind) || "camera_closed".equals(kind);
    }

    synchronized CameraEventKey boundKey() {
        return bound;
    }
}
