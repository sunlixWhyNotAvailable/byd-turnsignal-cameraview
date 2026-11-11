package com.byd.extend;

/** Request lifetime outlives Surface delivery; cancel and IPC admission share one lock. */
final class ReversePreparation {
    final int requestId;
    final long shellGeneration;
    private boolean cancelled, resources, prepared;

    ReversePreparation(int requestId, long shellGeneration) {
        this.requestId = requestId;
        this.shellGeneration = shellGeneration;
    }

    synchronized void cancel() { cancelled = true; }
    synchronized boolean current() { return !cancelled; }
    synchronized boolean pending() { return !cancelled && !prepared; }
    synchronized boolean begin() {
        if (cancelled) return false;
        resources = true;
        return true;
    }
    synchronized void prepared() { prepared = true; }
    synchronized boolean hasResources() { return resources; }
    synchronized void closed() { resources = false; cancelled = true; }
}
