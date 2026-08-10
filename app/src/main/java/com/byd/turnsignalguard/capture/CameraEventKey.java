package com.byd.turnsignalguard.capture;

import org.json.JSONObject;

import java.util.Objects;

/** Immutable identity for one logical camera consumer on one worker connection. */
final class CameraEventKey {
    private final long workerEpoch;
    private final int producerEpoch;
    private final String owner;
    private final int requestId;
    private final int consumerGeneration;
    private final long connectionGeneration;

    CameraEventKey(
            long workerEpoch, int producerEpoch, String owner,
            int requestId, int consumerGeneration, long connectionGeneration) {
        this.workerEpoch = workerEpoch;
        this.producerEpoch = producerEpoch;
        this.owner = owner;
        this.requestId = requestId;
        this.consumerGeneration = consumerGeneration;
        this.connectionGeneration = connectionGeneration;
    }

    static CameraEventKey fromEvent(JSONObject event) {
        if (event == null) return null;
        CameraEventKey key = new CameraEventKey(
                event.optLong("worker_epoch", 0),
                event.optInt("producer_epoch", 0),
                event.optString("camera_owner", "none"),
                event.optInt("request_id", 0),
                event.optInt("consumer_generation", 0),
                event.optLong("connection_generation", 0));
        return key.isPositive() ? key : null;
    }

    long workerEpoch() {
        return workerEpoch;
    }

    int producerEpoch() {
        return producerEpoch;
    }

    String owner() {
        return owner;
    }

    int requestId() {
        return requestId;
    }

    int consumerGeneration() {
        return consumerGeneration;
    }

    long connectionGeneration() {
        return connectionGeneration;
    }

    boolean matchesOwnerRequest(String expectedOwner, int expectedRequestId) {
        return owner.equals(expectedOwner) && requestId == expectedRequestId;
    }

    JSONObject putInto(JSONObject event) throws Exception {
        return event.put("worker_epoch", workerEpoch)
                .put("producer_epoch", producerEpoch)
                .put("camera_owner", owner)
                .put("request_id", requestId)
                .put("consumer_generation", consumerGeneration)
                .put("connection_generation", connectionGeneration);
    }

    private boolean isPositive() {
        return workerEpoch != 0 && producerEpoch > 0 && requestId > 0
                && consumerGeneration > 0 && connectionGeneration > 0
                && CameraWorkerProtocol.allowedOwner(owner);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CameraEventKey)) return false;
        CameraEventKey value = (CameraEventKey) other;
        return workerEpoch == value.workerEpoch
                && producerEpoch == value.producerEpoch
                && requestId == value.requestId
                && consumerGeneration == value.consumerGeneration
                && connectionGeneration == value.connectionGeneration
                && owner.equals(value.owner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workerEpoch, producerEpoch, owner, requestId,
                consumerGeneration, connectionGeneration);
    }
}
