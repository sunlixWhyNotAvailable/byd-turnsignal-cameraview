package com.byd.extend;

import java.util.function.BooleanSupplier;

/** Per-request NAV_SOURCE state. Callbacks only publish state and wake the playback worker. */
final class AvasNavSourceGate implements AutoCloseable {
    static final int DEVICE = 1002;
    static final int FID = 0x4C60001D;
    static final long TIMEOUT_MILLIS = 3000;
    static final long RECONCILE_POLL_MILLIS = 250;
    private static final long CALLBACK_WAIT_MILLIS = 10;

    interface Listener {
        void changed(int value);
        void error(String detail);
    }

    interface Subscription extends AutoCloseable {
        @Override void close() throws Exception;
    }

    interface Transport {
        Subscription subscribe(Listener listener) throws Exception;
        Snapshot read() throws Exception;
    }

    interface Clock {
        long now();
        void sleep(long millis) throws InterruptedException;
    }

    static final class Snapshot {
        final int status;
        final int value;

        Snapshot(int status, int value) {
            this.status = status;
            this.value = value;
        }

        boolean valid() { return status >= 0; }
    }

    static final class Diagnostics {
        final boolean registrationFailed;
        final long registrationFinishedMs;
        final int callbacks;
        final int callbackErrors;
        final long lastCallbackMs;
        final long getStartedMs;
        final long getFinishedMs;
        final boolean getStale;
        final int lastCallbackValue;
        final String lastError;
        final int zeroCallbacks;
        final int oneCallbacks;
        final long firstZeroCallbackMs;

        Diagnostics(boolean registrationFailed, long registrationFinishedMs, int callbacks,
                int callbackErrors, long lastCallbackMs, long getStartedMs, long getFinishedMs,
                boolean getStale, int lastCallbackValue, String lastError, int zeroCallbacks,
                int oneCallbacks, long firstZeroCallbackMs) {
            this.registrationFailed = registrationFailed;
            this.registrationFinishedMs = registrationFinishedMs;
            this.callbacks = callbacks;
            this.callbackErrors = callbackErrors;
            this.lastCallbackMs = lastCallbackMs;
            this.getStartedMs = getStartedMs;
            this.getFinishedMs = getFinishedMs;
            this.getStale = getStale;
            this.lastCallbackValue = lastCallbackValue;
            this.lastError = lastError;
            this.zeroCallbacks = zeroCallbacks;
            this.oneCallbacks = oneCallbacks;
            this.firstZeroCallbackMs = firstZeroCallbackMs;
        }
    }

    private final Transport transport;
    private final Clock clock;
    private Subscription subscription;
    private long revision;
    private int value = Integer.MIN_VALUE;
    private boolean valid;
    private boolean fallbackPolling;
    private long nextPoll;
    private boolean waitActive;
    private boolean closed;
    private boolean registrationFailed;
    private long registrationFinishedMs;
    private int callbacks;
    private int callbackErrors;
    private long lastCallbackMs = -1;
    private long getStartedMs = -1;
    private long getFinishedMs = -1;
    private boolean getStale;
    private int lastCallbackValue = Integer.MIN_VALUE;
    private String lastError = "";
    private int zeroCallbacks;
    private int oneCallbacks;
    private long firstZeroCallbackMs = -1;
    private int lastGetStatus = Integer.MIN_VALUE, lastGetValue = Integer.MIN_VALUE;
    private String firstReadError = "", lastReadError = "";
    private String registrationError = "";
    private final java.util.ArrayDeque<String> timeline = new java.util.ArrayDeque<>();

    AvasNavSourceGate(Transport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    void start() {
        try {
            subscription = transport.subscribe(new Listener() {
                @Override public void changed(int next) {
                    synchronized (AvasNavSourceGate.this) {
                        if (closed) return;
                        revision++;
                        callbacks++;
                        lastCallbackMs = clock.now();
                        lastCallbackValue = next;
                        record("callback value=" + next);
                        if (next == 0) {
                            zeroCallbacks++;
                            if (firstZeroCallbackMs < 0) firstZeroCallbackMs = lastCallbackMs;
                        } else if (next == 1) {
                            oneCallbacks++;
                        }
                        value = next;
                        valid = true;
                        AvasNavSourceGate.this.notifyAll();
                    }
                }

                @Override public void error(String detail) {
                    synchronized (AvasNavSourceGate.this) {
                        if (closed) return;
                        revision++;
                        callbackErrors++;
                        lastCallbackMs = clock.now();
                        lastError = detail == null ? "" : detail;
                        record("callback_error");
                        fallbackPolling = true;
                        AvasNavSourceGate.this.notifyAll();
                    }
                }
            });
            synchronized (this) { registrationFinishedMs = clock.now(); }
        } catch (Exception failure) {
            synchronized (this) {
                registrationFailed = true;
                lastError = diagnostic(failure);
                registrationError = lastError;
                record("subscribe_error");
                registrationFinishedMs = clock.now();
                fallbackPolling = true;
            }
        }
    }

    /** Always performs a fresh GET; a callback that arrives during it wins. */
    Snapshot refresh() {
        final long startedRevision;
        synchronized (this) {
            startedRevision = revision;
            getStartedMs = clock.now();
        }
        Snapshot snapshot;
        try {
            snapshot = transport.read();
        } catch (Exception failure) {
            synchronized (this) {
                lastReadError = diagnostic(failure);
                if (firstReadError.isEmpty()) firstReadError = lastReadError;
                record("get_exception " + failure.getClass().getName());
            }
            snapshot = new Snapshot(Integer.MIN_VALUE, Integer.MIN_VALUE);
        }
        synchronized (this) {
            getFinishedMs = clock.now();
            if (waitActive) nextPoll = getFinishedMs + RECONCILE_POLL_MILLIS;
            getStale = revision != startedRevision;
            lastGetStatus = snapshot.status;
            lastGetValue = snapshot.value;
            record("get status=" + snapshot.status + " value=" + snapshot.value
                    + " stale=" + getStale + " started_revision=" + startedRevision);
            if (!closed && revision == startedRevision) {
                revision++;
                value = snapshot.valid() ? snapshot.value : Integer.MIN_VALUE;
                valid = snapshot.valid();
            }
            return snapshot;
        }
    }

    synchronized boolean ready() { return !closed && valid && value == 0; }

    synchronized boolean hadFailure() {
        return registrationFailed || callbackErrors > 0 || !firstReadError.isEmpty();
    }

    synchronized String evidence() {
        return "device=" + DEVICE + " fid=" + FID + " revision=" + revision
                + " get_status=" + lastGetStatus + " get_value=" + lastGetValue
                + " get_stale=" + getStale + " observed=" + value + " valid=" + valid
                + " timeline=" + timeline + "\nregistration_exception=" + registrationError
                + "\nfirst_get_exception=" + firstReadError
                + "\nlast_get_exception=" + lastReadError;
    }

    private void record(String detail) {
        if (timeline.size() == 64) timeline.removeFirst();
        timeline.addLast(clock.now() + ":r" + revision + ":" + detail);
    }

    private static String diagnostic(Throwable failure) {
        java.io.StringWriter text = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }

    synchronized int value() { return valid ? value : Integer.MIN_VALUE; }

    synchronized boolean fallbackPolling() { return fallbackPolling; }

    synchronized void beginWait() {
        waitActive = true;
        nextPoll = clock.now() + RECONCILE_POLL_MILLIS;
    }

    synchronized Diagnostics diagnostics() {
        return new Diagnostics(registrationFailed, registrationFinishedMs, callbacks,
                callbackErrors, lastCallbackMs, getStartedMs, getFinishedMs, getStale,
                lastCallbackValue, lastError, zeroCallbacks, oneCallbacks, firstZeroCallbackMs);
    }

    /** Worker-side wait between bounded zero writes. */
    boolean awaitChangeOrPoll(long deadline, BooleanSupplier cancelled) throws InterruptedException {
        long observed;
        synchronized (this) { observed = revision; }
        while (!cancelled.getAsBoolean()) {
            long remaining = deadline - clock.now();
            if (remaining <= 0 || ready()) return ready();
            if (fallbackPolling()) {
                clock.sleep(Math.min(CALLBACK_WAIT_MILLIS, remaining));
            } else {
                synchronized (this) {
                    if (closed) return false;
                    if (revision != observed) return ready();
                    wait(Math.min(CALLBACK_WAIT_MILLIS, remaining));
                }
            }
            // A successful subscription does not guarantee every transition is delivered.
            // Reconcile only on this playback worker, within the active readiness wait.
            boolean poll;
            synchronized (this) {
                if (closed || cancelled.getAsBoolean()) return false;
                poll = waitActive && !ready() && clock.now() < deadline
                        && clock.now() >= nextPoll;
            }
            if (poll) refresh();
            return ready();
        }
        return false;
    }

    @Override public void close() throws Exception {
        Subscription owned;
        synchronized (this) {
            if (closed) return;
            closed = true;
            revision++;
            notifyAll();
            owned = subscription;
            subscription = null;
        }
        if (owned != null) owned.close();
    }
}
