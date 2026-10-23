package com.byd.extend;

import java.util.List;
import java.io.PrintWriter;
import java.io.StringWriter;
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
        default boolean lockTriggeredPowerOff() { return false; }
        default boolean powerOffPlaybackReady() { return false; }
        void onProfile(String profile);
        default void onPowerOffObserved() {}
        default void onRawPower(int power, boolean baseline) {}
        default void onRawLock(int lock, boolean baseline) {}
        default void onCameraPowerState(
                CameraSourceDemand.PowerGate.Snapshot state, boolean baseline) {}
        void onSuppressed(String profile, String powerProfile, long deltaMs);
        void log(String kind, Object... fields);
    }
    static final class ReadResult {
        final int value;
        final Throwable failure;
        ReadResult(int value, Throwable failure) {
            this.value = value;
            this.failure = failure;
        }
        static ReadResult value(int value) { return new ReadResult(value, null); }
        static ReadResult failure(Throwable failure) { return new ReadResult(-1, failure); }
    }
    static final class Snapshot {
        final int power;
        final int lock;
        final Throwable powerFailure;
        final Throwable lockFailure;
        Snapshot(int power, int lock) { this(power, lock, null, null); }
        Snapshot(int power, int lock, Throwable powerFailure, Throwable lockFailure) {
            this.power = power;
            this.lock = lock;
            this.powerFailure = powerFailure;
            this.lockFailure = lockFailure;
        }
        boolean validPower() {
            return powerFailure == null && AvasEventPolicy.normalizedPower(power) >= 0;
        }
        boolean validLock() { return lockFailure == null && (lock == 1 || lock == 2); }
        boolean valid() {
            return validPower() && validLock();
        }
    }

    private final Clock clock;
    private final Executor executor;
    private final Transport transport;
    private final Sink sink;
    private final AvasEventPolicy policy;
    private final CameraSourceDemand.PowerGate cameraPower = new CameraSourceDemand.PowerGate();
    private volatile ArrivalState arrivals = new ArrivalState();
    private volatile long generation;
    private int power = -1;
    private int lock = -1;
    private boolean initialized;
    private boolean rawPowerSeen;
    private volatile boolean active;
    private volatile boolean subscribed;
    private long lastSubscribeAttempt = Long.MIN_VALUE;
    private Subscription subscription;
    private Cancellable task;
    private boolean telemetryHealthy;
    private boolean gapLogged;
    private boolean powerGetDegraded;
    private String lastPowerReadFailure = "";
    private String lastLockReadFailure = "";

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
    void activateForMotion() {
        executor.execute(() -> {
            if (active) {
                sink.onCameraPowerState(cameraPower.snapshot(), true);
                readAndApply(true, "motion_request");
                scheduleNext(nextDelay());
            }
            else activateSerialized();
        });
    }
    CameraSourceDemand.PowerGate.Snapshot cameraPowerStateSnapshot() {
        return cameraPower.snapshot();
    }
    void eligibilityChanged(boolean onReady, boolean offReady) {
        executor.execute(() -> {
            policy.invalidateIneligible(onReady, offReady);
            try {
                policy.lockTriggeredEligibilityChanged(
                        sink.lockTriggeredPowerOff() && sink.powerOffPlaybackReady());
            } catch (RuntimeException failure) {
                sink.log("avas_power_off_latch_error", "error", failure.toString());
            }
        });
    }

    private void activateSerialized() {
        if (active) return;
        active = true;
        telemetryHealthy = false;
        gapLogged = false;
        resetBaseline();
        sink.onCameraPowerState(cameraPower.snapshot(), true);
        trySubscribe();
        readAndApply(true, subscribed ? "initial" : "fallback_initial");
        scheduleNext(nextDelay());
    }

    private void deactivateSerialized() {
        if (!active && subscription == null && task == null) return;
        active = false;
        generation++;
        arrivals = new ArrivalState();
        cancelTask();
        closeSubscription();
        resetBaseline();
        // Consumers must not restore from a Power value captured before this observation gap.
        publishRawPower(-1, true);
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
            publishRawPower(value, false);
            if (AvasEventPolicy.normalizedPower(value) < 0) {
                invalidCallback(receivedMs);
                return;
            }
            observeValidPower(value, "callback");
            if (AvasEventPolicy.normalizedPower(value) == 0) {
                sink.onPowerOffObserved();
            }
        } else if (device == LOCK_DEVICE && fid == LOCK_FID) {
            if (sequence <= listenerArrivals.lockApplied) return;
            listenerArrivals.lockApplied = sequence;
            lock = value;
            sink.onRawLock(value, !initialized);
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

    private void observeValidPower(int raw, String source) {
        CameraSourceDemand.PowerGate.Snapshot previous = cameraPower.snapshot();
        boolean recovered = powerGetDegraded || previous.consecutiveFailedReads > 0;
        powerGetDegraded = false;
        if (cameraPower.observeValid(raw))
            sink.onCameraPowerState(cameraPower.snapshot(), false);
        if (recovered) {
            sink.log("avas_power_get_recovered", "operation", "GET", "source", source,
                    "previous_failed_reads", previous.consecutiveFailedReads);
            scheduleNext(nextDelay());
        }
        lastPowerReadFailure = "";
    }

    private void observeFailedPower(int raw, Throwable failure, String source) {
        CameraSourceDemand.PowerGate.Snapshot previous = cameraPower.snapshot();
        Throwable cause = failure != null ? failure : invalidValue("Power", raw);
        powerGetDegraded = true;
        boolean changed = cameraPower.failedRead();
        CameraSourceDemand.PowerGate.Snapshot current = cameraPower.snapshot();
        logReadFailure("power", source, cause);
        if (previous.consecutiveFailedReads < 5 && current.consecutiveFailedReads == 5) {
            sink.log("avas_power_get_failure_threshold", "operation", "GET",
                    "device", POWER_DEVICE, "fid", POWER_FID,
                    "source", source, "consecutive_failed_reads", 5,
                    "cause", cause.getClass().getName() + ": " + safeMessage(cause));
        }
        if (changed) sink.onCameraPowerState(current, false);
    }

    private void logReadFailure(String signal, String source, Throwable failure) {
        String diagnostic = stackTrace(failure);
        String previous = "power".equals(signal) ? lastPowerReadFailure : lastLockReadFailure;
        if (diagnostic.equals(previous)) return;
        if ("power".equals(signal)) lastPowerReadFailure = diagnostic;
        else lastLockReadFailure = diagnostic;
        sink.log("avas_" + signal + "_get_error", "operation", "GET",
                "device", "power".equals(signal) ? POWER_DEVICE : LOCK_DEVICE,
                "fid", "power".equals(signal) ? POWER_FID : LOCK_FID,
                "source", source,
                "consecutive_failed_reads", "power".equals(signal)
                        ? cameraPower.snapshot().consecutiveFailedReads : 0,
                "error", diagnostic);
    }

    private static IllegalStateException invalidValue(String signal, int value) {
        return new IllegalStateException(signal + " GET returned invalid value " + value);
    }

    private static String stackTrace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? "" : failure.getMessage();
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
        publishRawPower(-1, true);
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
        if (!subscribed) readAndApply(false, "fallback_250ms");
        else readAndApply(true, powerGetDegraded
                ? "power_get_fallback_250ms" : "reconcile_60s");
        scheduleNext(nextDelay());
    }

    private long nextDelay() {
        return subscribed && !powerGetDegraded ? RECONCILE_MS : FALLBACK_MS;
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
        // Power health is independent of Lock readiness and callback delivery.
        if (applyPower) {
            if (snapshot.validPower()) observeValidPower(snapshot.power, source);
            else observeFailedPower(snapshot.power, snapshot.powerFailure, source);
            publishRawPower(snapshot.power, silent);
            if (snapshot.validPower() && AvasEventPolicy.normalizedPower(snapshot.power) == 0)
                sink.onPowerOffObserved();
        }
        if (applyLock) {
            if (snapshot.validLock()) lastLockReadFailure = "";
            else logReadFailure("lock", source, snapshot.lockFailure != null
                    ? snapshot.lockFailure : invalidValue("Lock", snapshot.lock));
            sink.onRawLock(snapshot.lock, silent || !initialized);
        }
        if (!applyPower || !applyLock) {
            sink.log("avas_telemetry_snapshot_raced", "source", source,
                    "power_discarded", !applyPower, "lock_discarded", !applyLock);
        }
        if (!applyPower || !applyLock) {
            // During initial seeding retain the non-racing half so the queued callback can
            // complete the baseline. Once initialized, discard the whole racing snapshot.
            if (!initialized) {
                boolean invalidAppliedPower = applyPower && !snapshot.validPower();
                boolean invalidAppliedLock = applyLock && !snapshot.validLock();
                if (invalidAppliedPower || invalidAppliedLock) {
                    logGapOnce(source, snapshot.power, snapshot.lock, clock.now());
                    if (invalidAppliedLock) enterFallback("invalid_initial_get", true);
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
            if (subscribed && snapshot.validPower()) enterFallback("invalid_get", true);
            return;
        }
        if (silent) {
            // Compare against the cached callback/fallback state before replacing it.
            boolean mismatch = initialized && (AvasEventPolicy.normalizedPower(oldPower)
                    != AvasEventPolicy.normalizedPower(snapshot.power) || oldLock != snapshot.lock);
            if (!initialized) {
                power = snapshot.power;
                lock = snapshot.lock;
                if (!reconcilePolicy()) return;
                initialized = true;
                logReadyOnce(source, power, lock, clock.now());
            } else if (mismatch) {
                power = snapshot.power;
                lock = snapshot.lock;
                if (!reconcilePolicy()) return;
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
            if (!reconcilePolicy()) return;
            initialized = true;
            logReadyOnce(source, power, lock, observedMs);
            return;
        }
        List<String> profiles;
        boolean pendingBefore = policy.hasPendingPowerOff();
        try {
            profiles = policy.sample(observedMs, power, lock,
                    sink.powerOnSuppressionEligible(), sink.powerOffSuppressionEligible(),
                    sink.lockTriggeredPowerOff(), sink.powerOffPlaybackReady());
        } catch (RuntimeException failure) {
            resetBaseline();
            sink.log("avas_power_off_latch_error", "error", failure.toString());
            return;
        }
        logLatchChange(pendingBefore, source);
        for (String profile : profiles) sink.onProfile(profile);
        if (policy.wasEventSuppressed()) sink.onSuppressed(policy.suppressedProfile(),
                policy.suppressionPowerProfile(), policy.suppressionDeltaMs());
        if (!profiles.isEmpty() || policy.wasEventSuppressed()) {
            sink.log("avas_telemetry_transition", "source", source, "power", power,
                    "lock", lock, "received_t_ms", observedMs);
        }
    }

    private boolean reconcilePolicy() {
        boolean pendingBefore = policy.hasPendingPowerOff();
        try {
            boolean valid = policy.reconcile(power, lock);
            logLatchChange(pendingBefore, "silent_baseline");
            return valid;
        } catch (RuntimeException failure) {
            resetBaseline();
            sink.log("avas_power_off_latch_error", "error", failure.toString());
            return false;
        }
    }

    private void logLatchChange(boolean before, String source) {
        if (before != policy.hasPendingPowerOff()) sink.log("avas_power_off_latch",
                "pending", policy.hasPendingPowerOff(), "source", source, "power", power, "lock", lock);
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
        rawPowerSeen = false;
        power = -1;
        lock = -1;
        policy.reset();
        sink.onRawLock(-1, true);
    }

    private void publishRawPower(int value, boolean silent) {
        sink.onRawPower(value, silent || !rawPowerSeen);
        rawPowerSeen = value >= 0 && value <= 4;
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
