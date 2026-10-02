package com.byd.extend;

/** Single-threaded ownership of a live runtime; UI attachment is not session ownership. */
final class CameraRuntimeLifetime<T> {
    private volatile T client;
    private boolean started;
    private volatile boolean stopped;

    boolean attach(T next, Runnable initialize) {
        if (next == null) throw new IllegalArgumentException("client is null");
        if (stopped) throw new IllegalStateException("runtime stopped");
        boolean retained = started;
        if (!started) { initialize.run(); started = true; }
        client = next;
        return retained;
    }

    boolean detach(T expected) {
        if (expected == null || client != expected) return false;
        client = null;
        return true;
    }

    T client() { return client; }
    boolean started() { return started; }
    boolean stopped() { return stopped; }

    void shutdown(Runnable close) {
        if (stopped) return;
        stopped = true;
        client = null;
        close.run();
    }
}
