package com.byd.extend;

import java.util.concurrent.atomic.AtomicLong;

/** Serialized callback-first state machine for the guard's four fixed telemetry signals. */
final class TurnSignalTelemetryController implements AutoCloseable {
    static final long CALLBACK_GET_MS = 1_000;
    static final long CALLBACK_GET_DEADLINE_MS = 1_250;
    static final long FALLBACK_GET_MS = 50;
    static final long FALLBACK_GET_DEADLINE_MS = 250;
    static final long SUBSCRIBE_RETRY_MS = 1_000;

    static final int STALK_DEVICE = 1004;
    static final int STALK_FID = 321912876;
    static final int STEERING_DEVICE = 1001;
    static final int STEERING_FID = 300941320;
    static final int BLINK_DEVICE = 1004;
    static final int BLINK_FID = 950009900;
    static final int SPEED_DEVICE = 1013;
    static final int SPEED_FID = -1807745016;
    // Quiet NaN raw bits: invalid for both float signals and outside both integer enums.
    static final int MISSING = 0x7fc00000;
    static final int LIVE_STALK = 1;
    static final int LIVE_STEERING = 1 << 1;
    static final int LIVE_BLINK = 1 << 2;
    static final int LIVE_SPEED = 1 << 3;

    interface Clock { long now(); }
    interface Dispatcher { void execute(Runnable task); }
    interface Transport {
        Subscription subscribe(Listener listener) throws Exception;
        Snapshot read();
    }
    interface Subscription { void close(); }
    interface Listener {
        void onValue(int device, int fid, int value, long receivedMs);
        void onError(String reason, long receivedMs);
    }
    interface Sink {
        void onSnapshot(Snapshot snapshot, Source source, int liveMask,
                boolean conflict, long observedMs);
        void onMode(boolean subscribed, boolean seeded, String reason);
    }
    enum Source { INITIAL, CALLBACK, RECONCILE, FALLBACK, RECOVERY_SEED }

    static final class Snapshot {
        final int stalk;
        final int steering;
        final int blink;
        final int speed;
        Snapshot(int stalk, int steering, int blink, int speed) {
            this.stalk = stalk;
            this.steering = steering;
            this.blink = blink;
            this.speed = speed;
        }
        boolean valid() {
            return validStalk(stalk) && validSteering(steering)
                    && validBlink(blink) && validSpeed(speed);
        }
    }

    private final Clock clock;
    private final Dispatcher dispatcher;
    private final Transport transport;
    private final Sink sink;
    private volatile ArrivalState arrivals = new ArrivalState();
    private volatile long generation;
    private Subscription subscription;
    private boolean active;
    private boolean subscribed;
    private boolean seeded;
    private int stalk = MISSING;
    private int steering = MISSING;
    private int blink = MISSING;
    private int speed = MISSING;
    private long lastGetAt = Long.MIN_VALUE;
    private long lastReadAttemptAt = Long.MIN_VALUE;
    private long lastSubscribeAttempt = Long.MIN_VALUE;
    private boolean fallbackReadsWorking;

    TurnSignalTelemetryController(
            Clock clock, Dispatcher dispatcher, Transport transport, Sink sink) {
        this.clock = clock;
        this.dispatcher = dispatcher;
        this.transport = transport;
        this.sink = sink;
    }

    void start() {
        if (active) return;
        active = true;
        resetBaseline();
        trySubscribe("start");
        readAndApply(subscribed ? Source.INITIAL : Source.FALLBACK);
    }

    void tick() {
        if (!active) return;
        long now = clock.now();
        if (!subscribed && elapsed(now, lastSubscribeAttempt) >= SUBSCRIBE_RETRY_MS) {
            trySubscribe("retry");
            if (subscribed) readAndApply(Source.RECOVERY_SEED);
        }
        long interval = subscribed ? CALLBACK_GET_MS
                : fallbackReadsWorking ? FALLBACK_GET_MS : SUBSCRIBE_RETRY_MS;
        if (elapsed(now, lastReadAttemptAt) >= interval) {
            readAndApply(subscribed ? Source.RECONCILE : Source.FALLBACK);
        }
    }

    void reseed() {
        if (!active) return;
        resetBaseline();
        readAndApply(subscribed ? Source.INITIAL : Source.FALLBACK);
    }

    boolean subscriptionHealthy() { return subscribed && seeded; }

    boolean reconciliationFresh(long now) {
        return subscribed && seeded && lastGetAt != Long.MIN_VALUE
                && elapsed(now, lastGetAt) <= CALLBACK_GET_DEADLINE_MS;
    }

    boolean dataFresh(long now) {
        return seeded && lastGetAt != Long.MIN_VALUE
                && elapsed(now, lastGetAt) <= (subscribed
                        ? CALLBACK_GET_DEADLINE_MS : FALLBACK_GET_DEADLINE_MS);
    }

    private void trySubscribe(String reason) {
        lastSubscribeAttempt = clock.now();
        long listenerGeneration = ++generation;
        ArrivalState listenerArrivals = new ArrivalState();
        arrivals = listenerArrivals;
        try {
            Subscription next = transport.subscribe(new Listener() {
                @Override public void onValue(int device, int fid, int value, long receivedMs) {
                    AtomicLong arrival = listenerArrivals.forSignal(device, fid);
                    if (arrival == null) return;
                    long sequence = arrival.incrementAndGet();
                    safeDispatch(() -> applyCallback(listenerGeneration, listenerArrivals,
                            device, fid, value, receivedMs, sequence));
                }
                @Override public void onError(String error, long receivedMs) {
                    safeDispatch(() -> subscriptionFailed(listenerGeneration, error, receivedMs));
                }
            });
            if (!active || listenerGeneration != generation) {
                next.close();
                return;
            }
            closeSubscription();
            subscription = next;
            subscribed = true;
            resetBaseline();
            sink.onMode(true, false, reason);
        } catch (Exception failure) {
            subscribed = false;
            sink.onMode(false, seeded, failure.toString());
        }
    }

    private void applyCallback(long listenerGeneration, ArrivalState listenerArrivals,
            int device, int fid, int value, long receivedMs, long sequence) {
        if (!active || !subscribed || listenerGeneration != generation
                || listenerArrivals != arrivals) return;
        if (!validSignal(device, fid, value)) {
            enterFallback("invalid_callback");
            return;
        }
        if (!listenerArrivals.applyIfNewer(device, fid, sequence)) return;
        set(device, fid, value);
        Snapshot snapshot = snapshot();
        if (!snapshot.valid()) return;
        boolean first = !seeded;
        seeded = true;
        sink.onSnapshot(snapshot, first ? Source.INITIAL : Source.CALLBACK,
                first ? 0 : liveMask(device, fid), false, receivedMs);
        if (first) sink.onMode(true, true, "valid_callback_seed");
    }

    private void subscriptionFailed(long listenerGeneration, String reason, long receivedMs) {
        if (!active || listenerGeneration != generation) return;
        enterFallback(reason + " at " + receivedMs);
    }

    private void enterFallback(String reason) {
        generation++;
        arrivals = new ArrivalState();
        closeSubscription();
        subscribed = false;
        lastSubscribeAttempt = clock.now();
        lastReadAttemptAt = Long.MIN_VALUE;
        fallbackReadsWorking = false;
        resetBaseline();
        sink.onMode(false, false, reason);
    }

    private void readAndApply(Source source) {
        ArrivalState before = arrivals;
        long[] sequence = before.snapshot();
        Snapshot old = snapshot();
        long readStartedAt = clock.now();
        Snapshot read = transport.read();
        long now = clock.now();
        if (!active || before != arrivals) return;
        lastReadAttemptAt = now;
        long readDuration = elapsed(now, readStartedAt);
        long readDeadline = subscribed ? CALLBACK_GET_DEADLINE_MS : FALLBACK_GET_DEADLINE_MS;
        if (readDuration > readDeadline) {
            if (subscribed) {
                enterFallback("get_duration_" + readDuration + "ms");
                lastReadAttemptAt = now;
            }
            else {
                fallbackReadsWorking = false;
                resetBaseline();
                sink.onMode(false, false, "fallback_get_duration_" + readDuration + "ms");
            }
            return;
        }
        lastGetAt = now;
        boolean[] apply = before.unchanged(sequence);
        if (apply[0]) stalk = read.stalk;
        if (apply[1]) steering = read.steering;
        if (apply[2]) blink = read.blink;
        if (apply[3]) speed = read.speed;
        Snapshot combined = snapshot();
        boolean invalidApplied = (apply[0] && !validStalk(read.stalk))
                || (apply[1] && !validSteering(read.steering))
                || (apply[2] && !validBlink(read.blink))
                || (apply[3] && !validSpeed(read.speed));
        if (invalidApplied) {
            if (subscribed) {
                enterFallback("invalid_get");
                lastReadAttemptAt = now;
            } else {
                fallbackReadsWorking = false;
                resetBaseline();
                sink.onMode(false, false, "invalid_fallback_get");
            }
            return;
        }
        if (!combined.valid()) {
            if (!all(apply)) return; // A queued newer callback will complete the state.
            if (subscribed) {
                enterFallback("invalid_get");
                lastReadAttemptAt = now;
            }
            else {
                fallbackReadsWorking = false;
                resetBaseline();
                sink.onMode(false, false, "invalid_fallback_get");
            }
            return;
        }
        boolean mismatch = seeded && source == Source.RECONCILE
                && ((apply[0] && read.stalk != old.stalk)
                        || (apply[1] && read.steering != old.steering)
                        || (apply[2] && read.blink != old.blink)
                        || (apply[3] && read.speed != old.speed));
        if (seeded && !all(apply)) {
            // The raced field belongs to its newer callback. Non-racing GET differences remain a
            // silent reconciliation conflict so runtime can cancel ambiguous in-flight control.
            if (mismatch) sink.onSnapshot(combined, Source.RECONCILE, 0, true, now);
            return;
        }
        boolean conflict = mismatch;
        boolean first = !seeded;
        seeded = true;
        if (!subscribed) fallbackReadsWorking = true;
        Source delivered = first
                ? (subscribed ? source : Source.INITIAL)
                : source;
        sink.onSnapshot(combined, delivered, 0, conflict, now);
        if (first) sink.onMode(subscribed, subscribed, "valid_get_seed");
    }

    private static boolean all(boolean[] values) {
        for (boolean value : values) if (!value) return false;
        return true;
    }

    private Snapshot snapshot() { return new Snapshot(stalk, steering, blink, speed); }

    private void set(int device, int fid, int value) {
        if (device == STALK_DEVICE && fid == STALK_FID) stalk = value;
        else if (device == STEERING_DEVICE && fid == STEERING_FID) steering = value;
        else if (device == BLINK_DEVICE && fid == BLINK_FID) blink = value;
        else if (device == SPEED_DEVICE && fid == SPEED_FID) speed = value;
    }

    private void resetBaseline() {
        seeded = false;
        lastGetAt = Long.MIN_VALUE;
        resetBaselineValues();
    }

    private void resetBaselineValues() {
        stalk = MISSING;
        steering = MISSING;
        blink = MISSING;
        speed = MISSING;
    }

    private void closeSubscription() {
        Subscription old = subscription;
        subscription = null;
        if (old != null) old.close();
    }

    private void safeDispatch(Runnable task) {
        try { dispatcher.execute(task); } catch (RuntimeException ignored) {}
    }

    private static long elapsed(long now, long then) {
        if (then == Long.MIN_VALUE || now < then) return Long.MAX_VALUE;
        return now - then;
    }

    private static boolean validSignal(int device, int fid, int value) {
        if (device == STALK_DEVICE && fid == STALK_FID) return validStalk(value);
        if (device == STEERING_DEVICE && fid == STEERING_FID) return validSteering(value);
        if (device == BLINK_DEVICE && fid == BLINK_FID) return validBlink(value);
        return device == SPEED_DEVICE && fid == SPEED_FID && validSpeed(value);
    }

    private static int liveMask(int device, int fid) {
        if (device == STALK_DEVICE && fid == STALK_FID) return LIVE_STALK;
        if (device == STEERING_DEVICE && fid == STEERING_FID) return LIVE_STEERING;
        if (device == BLINK_DEVICE && fid == BLINK_FID) return LIVE_BLINK;
        if (device == SPEED_DEVICE && fid == SPEED_FID) return LIVE_SPEED;
        return 0;
    }

    private static boolean validStalk(int value) { return value >= 1 && value <= 5; }
    private static boolean validBlink(int value) { return value >= 1 && value <= 9; }
    private static boolean validSteering(int value) {
        float decoded = Float.intBitsToFloat(value);
        return Float.isFinite(decoded) && decoded >= -780.0f && decoded <= 780.0f;
    }
    private static boolean validSpeed(int value) {
        float decoded = Float.intBitsToFloat(value);
        return Float.isFinite(decoded) && decoded >= 0.0f && decoded <= 350.0f;
    }

    @Override public void close() {
        active = false;
        generation++;
        arrivals = new ArrivalState();
        closeSubscription();
        resetBaseline();
    }

    private static final class ArrivalState {
        final AtomicLong stalk = new AtomicLong();
        final AtomicLong steering = new AtomicLong();
        final AtomicLong blink = new AtomicLong();
        final AtomicLong speed = new AtomicLong();
        long appliedStalk;
        long appliedSteering;
        long appliedBlink;
        long appliedSpeed;

        AtomicLong forSignal(int device, int fid) {
            if (device == STALK_DEVICE && fid == STALK_FID) return stalk;
            if (device == STEERING_DEVICE && fid == STEERING_FID) return steering;
            if (device == BLINK_DEVICE && fid == BLINK_FID) return blink;
            if (device == SPEED_DEVICE && fid == SPEED_FID) return speed;
            return null;
        }
        boolean applyIfNewer(int device, int fid, long sequence) {
            if (device == STALK_DEVICE && fid == STALK_FID) {
                if (sequence <= appliedStalk) return false;
                appliedStalk = sequence;
            } else if (device == STEERING_DEVICE && fid == STEERING_FID) {
                if (sequence <= appliedSteering) return false;
                appliedSteering = sequence;
            } else if (device == BLINK_DEVICE && fid == BLINK_FID) {
                if (sequence <= appliedBlink) return false;
                appliedBlink = sequence;
            } else if (device == SPEED_DEVICE && fid == SPEED_FID) {
                if (sequence <= appliedSpeed) return false;
                appliedSpeed = sequence;
            } else return false;
            return true;
        }
        long[] snapshot() {
            return new long[]{stalk.get(), steering.get(), blink.get(), speed.get()};
        }
        boolean[] unchanged(long[] before) {
            return new boolean[]{before[0] == stalk.get() && before[0] == appliedStalk,
                    before[1] == steering.get() && before[1] == appliedSteering,
                    before[2] == blink.get() && before[2] == appliedBlink,
                    before[3] == speed.get() && before[3] == appliedSpeed};
        }
    }
}
