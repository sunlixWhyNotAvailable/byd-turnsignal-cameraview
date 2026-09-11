package com.byd.extend;

import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/** Pure request identity and bounded sampling policy for best-effort audio diagnostics. */
final class AvasAudioDiagnostics {
    interface Clock { long now(); }
    interface Probe { Snapshot read() throws Exception; }

    static final class Context {
        final long requestId;
        final String profile;
        final String source;
        final int helperPid;
        final long acceptedMs;
        final long enqueuedMs;

        Context(long requestId, String profile, String source, int helperPid,
                long acceptedMs, long enqueuedMs) {
            this.requestId = requestId;
            this.profile = profile;
            this.source = source;
            this.helperPid = helperPid;
            this.acceptedMs = acceptedMs;
            this.enqueuedMs = enqueuedMs;
        }
    }

    static final class Snapshot {
        final boolean available;
        final String error;
        final long playbackHead;

        private Snapshot(boolean available, String error, long playbackHead) {
            this.available = available;
            this.error = error;
            this.playbackHead = playbackHead;
        }

        static Snapshot ready(long playbackHead) { return new Snapshot(true, "", playbackHead); }
        static Snapshot unavailable() { return new Snapshot(false, "unavailable", -1); }
        static Snapshot error(Throwable failure) {
            return new Snapshot(false, String.valueOf(failure), -1);
        }
    }

    static final class SampleGate {
        private static final long INTERVAL_MS = 100;
        private static final long WINDOW_MS = 2000;
        private final long startedMs;
        private long lastSampleMs = Long.MIN_VALUE;
        private boolean progress;

        SampleGate(long startedMs) { this.startedMs = startedMs; }

        boolean initial(long nowMs) {
            if (progress || nowMs - startedMs > WINDOW_MS) return false;
            if (lastSampleMs != Long.MIN_VALUE && nowMs - lastSampleMs < INTERVAL_MS) return false;
            lastSampleMs = nowMs;
            return true;
        }

        void progress() { progress = true; }
        boolean hasProgress() { return progress; }
    }

    private AvasAudioDiagnostics() {}

    static Snapshot safeProbe(Probe probe) {
        if (probe == null) return Snapshot.unavailable();
        try {
            Snapshot value = probe.read();
            return value == null ? Snapshot.unavailable() : value;
        } catch (Throwable failure) {
            return Snapshot.error(failure);
        }
    }

    static IntConsumer bind(Context context, BiConsumer<Context, Integer> callback) {
        return value -> callback.accept(context, value);
    }
}
