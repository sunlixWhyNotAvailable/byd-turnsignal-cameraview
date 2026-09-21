package com.byd.extend;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/** Serialized callback-first Power/Lock state pipeline with GET reconciliation fallback. */
final class AvasTelemetryController implements AutoCloseable {
    static final long RECONCILE_MS = 60_000;
    static final long FALLBACK_MS = 250;
    static final int POWER_DEVICE = 1001;
    static final int POWER_FID = 315621418;
    static final int LOCK_DEVICE = 1032;
    static final int LOCK_FID = 1081081864;

    interface Clock { long now(); }
    interface Executor {
        void execute(Runnable task);
        Cancellable schedule(Runnable task, long delayMs);
    }
    interface Cancellable { void cancel(); }
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
        boolean powerOnSuppressionEligible();
        boolean powerOffSuppressionEligible();
        void onProfile(String profile);
        void onSuppressed(String profile, String powerProfile, long deltaMs);
        void log(String kind, Object... fields);
    }
    static final class Snapshot {
        final int power;
        final int lock;
        Snapshot(int power, int lock) { this.power = power; this.lock = lock; }
        boolean valid() {
            return AvasEventPolicy.normalizedPower(power) >= 0 && (lock == 1 || lock == 2);
        }
    }

    private final Clock clock;
    private final Executor executor;
    private final Transport transport;
    private final Sink sink;
    private final AvasEventPolicy policy;
    private volatile ArrivalState arrivals = new ArrivalState();
    private volatile long generation;
    private int power = -1;
    private int lock = -1;
    private boolean initialized;
    private volatile boolean active;
    private volatile boolean subscribed;
    private long lastSubscribeAttempt = Long.MIN_VALUE;
    private Subscription subscription;
    private Cancellable task;
    private boolean telemetryHealthy;
    private boolean gapLogged;

    AvasTelemetryController(Clock clock, Executor executor, Transport transport,
            Sink sink, AvasEventPolicy policy) {
        this.clock = clock;
        this.executor = executor;
        this.transport = transport;
        this.sink = sink;
        this.policy = policy;
    }

    void activate() { executor.execute(this::activateSerialized); }
    void deactivate() { executor.execute(this::deactivateSerialized); }
    void eligibilityChanged(boolean onReady, boolean offReady) {
        executor.execute(() -> policy.invalidateIneligible(onReady, offReady));
    }

    private void activateSerialized() {
        if (active) return;
        active = true;
        telemetryHealthy = false;
        gapLogged = false;
        resetBaseline();
        trySubscribe();
        readAndApply(true, subscribed ? "initial" : "fallback_initial");
        scheduleNext(subscribed ? RECONCILE_MS : FALLBACK_MS);
    }

    private void deactivateSerialized() {
        if (!active && subscription == null && task == null) return;
        active = false;
        generation++;
        arrivals = new ArrivalState();
        cancelTask();
        closeSubscription();
        resetBaseline();
        telemetryHealthy = false;
        gapLogged = false;
        sink.log("avas_telemetry_mode", "mode", "off", "reason", "automatic_profiles_disabled");
    }

    private void trySubscribe() {
        lastSubscribeAttempt = clock.now();
        long listenerGeneration = ++generation;
        ArrivalState listenerArrivals = new ArrivalState();
        arrivals = listenerArrivals;
        try {
            Subscription next = transport.subscribe(new Listener() {
                @Override public void onValue(int device, int fid, int value, long receivedMs) {
                    long sequence;
                    if (device == POWER_DEVICE && fid == POWER_FID) {
                        sequence = listenerArrivals.powerArrival.incrementAndGet();
                    } else if (device == LOCK_DEVICE && fid == LOCK_FID) {
                        sequence = listenerArrivals.lockArrival.incrementAndGet();
                    } else return;
                    safeExecute(() -> callback(listenerGeneration, device, fid, value,
                            receivedMs, sequence, listenerArrivals));
                }
                @Override public void onError(String reason, long receivedMs) {
                    safeExecute(() -> subscriptionFailed(listenerGeneration, reason, receivedMs));
                }
            });
            if (!active || listenerGeneration != generation) {
                next.close();
                return;
            }
            closeSubscription();
            subscription = next;
            subscribed = true;
            sink.log("avas_telemetry_subscription", "registered", true,
                    "mode", "callback", "reason", "fixed_power_lock_fids");
        } catch (Exception failure) {
            subscribed = false;
            sink.log("avas_telemetry_subscription", "registered", false,
                    "mode", "fallback_250ms", "reason", failure.toString());
        }
    }

    private void callback(long listenerGeneration, int device, int fid, int value, long receivedMs,
            long sequence, ArrivalState listenerArrivals) {
        if (!active || !subscribed || listenerGeneration != generation
                || listenerArrivals != arrivals) return;
        if (device == POWER_DEVICE && fid == POWER_FID) {
            if (sequence <= listenerArrivals.powerApplied) return;
            listenerArrivals.powerApplied = sequence;
            power = value;
            if (AvasEventPolicy.normalizedPower(value) < 0) {
                invalidCallback(receivedMs);
                return;
            }
        } else if (device == LOCK_DEVICE && fid == LOCK_FID) {
            if (sequence <= listenerArrivals.lockApplied) return;
            listenerArrivals.lockApplied = sequence;
            lock = value;
            if (value != 1 && value != 2) {
                invalidCallback(receivedMs);
                return;
            }
        }
        else return;
        // A valid first half received while initial GET is racing waits for the queued other half.
        if (!initialized && (AvasEventPolicy.normalizedPower(power) < 0
                || (lock != 1 && lock != 2))) return;
        observe(receivedMs, "callback");
    }

    private void invalidCallback(long receivedMs) {
        int invalidPower = power;
        int invalidLock = lock;
        resetBaseline();
        logGapOnce("callback", invalidPower, invalidLock, receivedMs);
        enterFallback("invalid_callback", true);
        scheduleNext(0);
    }

    private void subscriptionFailed(long listenerGeneration, String reason, long receivedMs) {
        if (!active || listenerGeneration != generation) return;
        sink.log("avas_telemetry_subscription", "registered", false,
                "mode", "fallback_250ms", "reason", reason, "received_t_ms", receivedMs);
        enterFallback("listener_error", true);
        scheduleNext(0);
    }

    private void tick() {
        task = null;
        if (!active) return;
        if (!subscribed && elapsedSinceSubscribeAttempt() >= RECONCILE_MS) {
            trySubscribe();
            if (subscribed) {
                sink.log("avas_telemetry_mode", "mode", "callback",
                        "reason", "subscription_recovered");
            }
        }
        if (subscribed) readAndApply(true, "reconcile_60s");
        else readAndApply(false, "fallback_250ms");
        scheduleNext(subscribed ? RECONCILE_MS : FALLBACK_MS);
    }

    private long elapsedSinceSubscribeAttempt() {
        long now = clock.now();
        if (lastSubscribeAttempt == Long.MIN_VALUE || now < lastSubscribeAttempt) return Long.MAX_VALUE;
        return now - lastSubscribeAttempt;
    }

    private void readAndApply(boolean silent, String source) {
        ArrivalState before = arrivals;
        long powerBefore = before.powerArrival.get();
        long lockBefore = before.lockArrival.get();
        int oldPower = power;
        int oldLock = lock;
        Snapshot snapshot = transport.read();
        if (!active) return;
        boolean sameSubscription = before == arrivals;
        boolean applyPower = sameSubscription && powerBefore == before.powerArrival.get()
                && powerBefore == before.powerApplied;
        boolean applyLock = sameSubscription && lockBefore == before.lockArrival.get()
                && lockBefore == before.lockApplied;
        if (!applyPower || !applyLock) {
            sink.log("avas_telemetry_snapshot_raced", "source", source,
                    "power_discarded", !applyPower, "lock_discarded", !applyLock);
        }
        if (!applyPower || !applyLock) {
            // During initial seeding retain the non-racing half so the queued callback can
            // complete the baseline. Once initialized, discard the whole racing snapshot.
            if (!initialized) {
                boolean invalidAppliedPower = applyPower
                        && AvasEventPolicy.normalizedPower(snapshot.power) < 0;
                boolean invalidAppliedLock = applyLock
                        && snapshot.lock != 1 && snapshot.lock != 2;
                if (invalidAppliedPower || invalidAppliedLock) {
                    logGapOnce(source, snapshot.power, snapshot.lock, clock.now());
                    enterFallback("invalid_initial_get", true);
                    return;
                }
                if (applyPower) power = snapshot.power;
                if (applyLock) lock = snapshot.lock;
            }
            return;
        }
        reconcileSnapshot(snapshot, silent, source, oldPower, oldLock);
    }

    private void reconcileSnapshot(Snapshot snapshot, boolean silent, String source,
            int oldPower, int oldLock) {
        if (!snapshot.valid()) {
            resetBaseline();
            logGapOnce(source, snapshot.power, snapshot.lock, clock.now());
            if (subscribed) enterFallback("invalid_get", true);
            return;
        }
        if (silent) {
            // Compare against the cached callback/fallback state before replacing it.
            boolean mismatch = initialized && (AvasEventPolicy.normalizedPower(oldPower)
                    != AvasEventPolicy.normalizedPower(snapshot.power) || oldLock != snapshot.lock);
            if (!initialized) {
                power = snapshot.power;
                lock = snapshot.lock;
                policy.reconcile(power, lock);
                initialized = true;
                logReadyOnce(source, power, lock, clock.now());
            } else if (mismatch) {
                power = snapshot.power;
                lock = snapshot.lock;
                policy.reconcile(power, lock);
                initialized = true;
                sink.log("avas_telemetry_reconciled", "source", source, "power", power,
                        "lock", lock, "action", "silent_no_audio_no_power_token");
                enterFallback("sparse_get_mismatch", false);
            } else {
                power = snapshot.power;
                lock = snapshot.lock;
                // Matching reconciliation must preserve an active power-suppression token.
                logReadyOnce(source, power, lock, clock.now());
            }
            return;
        }
        power = snapshot.power;
        lock = snapshot.lock;
        observe(clock.now(), source);
    }

    private void observe(long observedMs, String source) {
        boolean valid = AvasEventPolicy.normalizedPower(power) >= 0 && (lock == 1 || lock == 2);
        if (!valid) {
            int invalidPower = power;
            int invalidLock = lock;
            resetBaseline();
            logGapOnce(source, invalidPower, invalidLock, observedMs);
            if (subscribed) {
                enterFallback("invalid_callback", true);
                scheduleNext(0);
            }
            return;
        }
        if (!initialized) {
            policy.reconcile(power, lock);
            initialized = true;
            logReadyOnce(source, power, lock, observedMs);
            return;
        }
        List<String> profiles = policy.sample(observedMs, power, lock,
                sink.powerOnSuppressionEligible(), sink.powerOffSuppressionEligible());
        for (String profile : profiles) sink.onProfile(profile);
        if (policy.wasEventSuppressed()) sink.onSuppressed(policy.suppressedProfile(),
                policy.suppressionPowerProfile(), policy.suppressionDeltaMs());
        if (!profiles.isEmpty() || policy.wasEventSuppressed()) {
            sink.log("avas_telemetry_transition", "source", source, "power", power,
                    "lock", lock, "received_t_ms", observedMs);
        }
    }

    private void enterFallback(String reason, boolean loseBaseline) {
        generation++;
        arrivals = new ArrivalState();
        closeSubscription();
        subscribed = false;
        lastSubscribeAttempt = clock.now();
        if (loseBaseline) resetBaseline();
        sink.log("avas_telemetry_mode", "mode", "fallback_250ms", "reason", reason);
    }

    private void resetBaseline() {
        initialized = false;
        power = -1;
        lock = -1;
        policy.reset();
    }

    private void logGapOnce(String source, int rawPower, int rawLock, long observedMs) {
        if (gapLogged) return;
        gapLogged = true;
        telemetryHealthy = false;
        sink.log("avas_telemetry_gap", "source", source, "power", rawPower, "lock", rawLock,
                "received_t_ms", observedMs);
    }

    private void logReadyOnce(String source, int rawPower, int rawLock, long observedMs) {
        if (telemetryHealthy) return;
        telemetryHealthy = true;
        gapLogged = false;
        sink.log("avas_telemetry_ready", "source", source, "power", rawPower, "lock", rawLock,
                "received_t_ms", observedMs);
    }

    private void scheduleNext(long delayMs) {
        cancelTask();
        if (active) task = executor.schedule(this::tick, delayMs);
    }

    private void cancelTask() {
        if (task != null) task.cancel();
        task = null;
    }

    private void closeSubscription() {
        Subscription old = subscription;
        subscription = null;
        if (old != null) old.close();
    }

    private void safeExecute(Runnable task) {
        try { executor.execute(task); } catch (RuntimeException ignored) {}
    }

    private static final class ArrivalState {
        final AtomicLong powerArrival = new AtomicLong();
        final AtomicLong lockArrival = new AtomicLong();
        long powerApplied;
        long lockApplied;
    }

    @Override public void close() {
        // Reject callback ingress immediately, then wait for serialized native cleanup. Runtime
        // shuts the executor down only after this returns, so a slow in-flight GET cannot drop it.
        active = false;
        generation++;
        arrivals = new ArrivalState();
        CountDownLatch done = new CountDownLatch(1);
        boolean interrupted = false;
        try {
            executor.execute(() -> {
                try { deactivateSerialized(); } finally { done.countDown(); }
            });
            while (true) {
                try {
                    done.await();
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        } catch (RuntimeException ignored) {
            cancelTask();
            closeSubscription();
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
