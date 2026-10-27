package com.byd.extend;

/** Per-input progress; all times use nanoTime, never the OEM timestamp's clock domain. */
final class RemoteCameraFrameProgress {
    private long offered, received, composed, lastComposedReceive;
    private long lastOfferNs = -1, lastReceiveNs = -1, freshNs = -1;
    private long acquiredNs, producerNs;
    private Object source;

    synchronized void offered(long nowNs) { offered++; lastOfferNs = nowNs; }

    synchronized void received(Object source, long acquiredNs, long producerNs, long nowNs) {
        received++;
        lastReceiveNs = nowNs;
        // A repeated source timestamp is not a fresh image, even if copied/delivered again.
        if (this.source != source || (producerNs > 0 ? this.producerNs != producerNs
                : this.acquiredNs != acquiredNs)) {
            freshNs = Math.min(nowNs, acquiredNs);
        }
        this.source = source;
        this.acquiredNs = acquiredNs;
        this.producerNs = producerNs;
    }

    synchronized boolean fresh(long nowNs, long timeoutNs) {
        return freshNs >= 0 && nowNs >= freshNs && nowNs - freshNs < timeoutNs;
    }

    synchronized void composed() {
        if (lastComposedReceive != received) { composed++; lastComposedReceive = received; }
    }

    synchronized String snapshot(long nowNs) {
        return "offered=" + offered + " received=" + received + " composed_new=" + composed
                + " offer_age_ms=" + age(nowNs, lastOfferNs)
                + " receive_age_ms=" + age(nowNs, lastReceiveNs)
                + " fresh_age_ms=" + age(nowNs, freshNs)
                + " acquired_ns=" + acquiredNs + " producer_ns=" + producerNs;
    }

    private static long age(long nowNs, long thenNs) {
        return thenNs < 0 ? -1 : Math.max(0, nowNs - thenNs) / 1_000_000;
    }
}
