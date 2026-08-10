package com.byd.turnsignalguard.capture;

import java.util.HashMap;
import java.util.Map;

/** Allocates request identity before backend work, including synchronous failures. */
final class CameraWorkerEventIdentity {
    private final Map<String, Identity> values = new HashMap<>();
    private int nextGeneration;

    synchronized int begin(String owner, int requestId) {
        Identity current = values.get(owner);
        if (current != null && current.requestId == requestId) return current.generation;
        Identity next = new Identity(requestId, ++nextGeneration);
        values.put(owner, next);
        return next.generation;
    }

    synchronized int generation(String owner, int requestId, String kind) {
        Identity current = values.get(owner);
        if (current == null || current.requestId != requestId) {
            if (!"camera_consumer_attached".equals(kind)
                    && !"camera_opened".equals(kind)) return 0;
            return begin(owner, requestId);
        }
        return current.generation;
    }

    synchronized void afterEvent(String owner, int requestId, String kind) {
        if (!"camera_closed".equals(kind)) return;
        Identity current = values.get(owner);
        if (current != null && current.requestId == requestId) values.remove(owner);
    }

    synchronized void failedOpen(String owner, int requestId) {
        Identity current = values.get(owner);
        if (current != null && current.requestId == requestId) values.remove(owner);
    }

    synchronized void clear() {
        values.clear();
    }

    private static final class Identity {
        final int requestId;
        final int generation;

        Identity(int requestId, int generation) {
            this.requestId = requestId;
            this.generation = generation;
        }
    }
}
