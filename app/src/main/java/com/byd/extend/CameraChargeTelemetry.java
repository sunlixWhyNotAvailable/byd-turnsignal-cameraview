package com.byd.extend;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/** App-side read-only status facade plus the shell process's single telemetry owner. */
public final class CameraChargeTelemetry {
    interface StateSink { void onState(CameraChargePolicy.State state); }
    interface ReadCompletion { void complete(CameraChargePolicy.State state, Throwable failure); }
    public interface Listener { void onState(CameraChargePolicy.State state); }

    static String fullDiagnostic(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    static final class SignalReadFence {
        private final AtomicLong callbackVersion = new AtomicLong();

        long beginRead() { return callbackVersion.get(); }

        void callbackArrived() { callbackVersion.incrementAndGet(); }

        boolean isCurrent(long readVersion) { return callbackVersion.get() == readVersion; }
    }

    static final class CallbackLifetime {
        private long generation;

        synchronized long open() { return ++generation; }

        synchronized long invalidate() { return ++generation; }

        synchronized boolean isCurrent(long candidate) {
            return candidate != 0L && generation == candidate;
        }

        synchronized boolean runIfCurrent(long candidate, Runnable action) {
            if (candidate == 0L || generation != candidate) return false;
            action.run();
            return true;
        }

        synchronized boolean retireIfCurrent(long candidate) {
            if (candidate == 0L || generation != candidate) return false;
            generation++;
            return true;
        }
    }

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile CameraChargeTelemetry instance;
    private static volatile CameraChargePolicy.State lastPublished =
            new CameraChargePolicy.State(null, null,
                    CameraChargePolicy.MISSING_SOC | CameraChargePolicy.MISSING_VOLTAGE,
                    0, false, true);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private volatile CameraChargePolicy.State state;

    private CameraChargeTelemetry(Context context) {
        state = lastPublished;
        try {
            state = MotionDetectorClient.readChargeState(context);
            lastPublished = state;
        }
        catch (Throwable unavailable) { /* No worker yet: the cached state remains unavailable. */ }
    }

    public static CameraChargeTelemetry get(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        CameraChargeTelemetry current = instance;
        if (current != null) return current;
        synchronized (INSTANCE_LOCK) {
            current = instance;
            if (current == null) {
                Context application = context.getApplicationContext();
                current = new CameraChargeTelemetry(application == null ? context : application);
                instance = current;
            }
            return current;
        }
    }

    /** Returns the latest worker-published sample without opening a vehicle subscription. */
    public CameraChargePolicy.State snapshot() { return state; }

    /** Registers a UI-only observer and immediately delivers the latest cached worker state. */
    public Observation observe(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener required");
        CameraChargePolicy.State current;
        synchronized (listeners) {
            listeners.add(listener);
            current = state;
        }
        dispatch(listener, current);
        return new Observation(this, listener);
    }

    /** Forces a fresh worker-owned read before a viewing lease is acquired. */
    public static CameraChargePolicy.State checkBeforeViewing(Context context) throws Exception {
        CameraChargePolicy.State fresh = MotionDetectorClient.readChargeState(context, true);
        if (!fresh.isMonitoring())
            throw new IllegalStateException("shared camera charge telemetry is not active");
        publish(fresh);
        return fresh;
    }

    static void publish(CameraChargePolicy.State next) {
        if (next == null) return;
        lastPublished = next;
        CameraChargeTelemetry current = instance;
        if (current != null) current.accept(next);
    }

    private void accept(CameraChargePolicy.State next) {
        state = next;
        Listener[] targets;
        synchronized (listeners) { targets = listeners.toArray(new Listener[0]); }
        for (Listener listener : targets) dispatch(listener, next);
    }

    private void dispatch(Listener listener, CameraChargePolicy.State value) {
        if (Looper.myLooper() == Looper.getMainLooper()) listener.onState(value);
        else main.post(() -> {
            synchronized (listeners) { if (!listeners.contains(listener)) return; }
            listener.onState(value);
        });
    }

    private void remove(Listener listener) {
        synchronized (listeners) { listeners.remove(listener); }
    }

    /** Closing this handle removes only the observing screen; shell telemetry ownership is unchanged. */
    public static final class Observation implements AutoCloseable {
        private CameraChargeTelemetry owner;
        private final Listener listener;

        private Observation(CameraChargeTelemetry owner, Listener listener) {
            this.owner = owner;
            this.listener = listener;
        }

        @Override public void close() {
            CameraChargeTelemetry current = owner;
            owner = null;
            if (current != null) current.remove(listener);
        }
    }

    /** Shell-only owner: OEM reads use operations; policy updates use the Handler. */
    static final class Owner implements AutoCloseable {
        static final int SOC_DEVICE = 1014;
        static final int SOC_FID = 1145045040;
        static final int VOLTAGE_DEVICE = 1032;
        static final int VOLTAGE_FID = 1150287896;
        static final long POLL_INTERVAL_MS = 5_000L;

        private static final FixedBydTelemetryManager.Request[] REQUESTS = {
                new FixedBydTelemetryManager.Request(SOC_DEVICE,
                        FixedBydTelemetryManager.ValueType.INTEGER, SOC_FID),
                new FixedBydTelemetryManager.Request(VOLTAGE_DEVICE,
                        FixedBydTelemetryManager.ValueType.FLOAT, VOLTAGE_FID)
        };

        private final Context context;
        private final Handler handler;
        private final ExecutorService operations;
        private final StateSink sink;
        private final CameraChargePolicy policy;
        private final Runnable poll = this::poll;
        private final SignalReadFence socReadFence = new SignalReadFence();
        private final SignalReadFence voltageReadFence = new SignalReadFence();
        private final CallbackLifetime callbackLifetime = new CallbackLifetime();
        private final Object callbackDispatchLock = new Object();
        private FixedBydTelemetryManager manager;
        private FixedBydTelemetryManager.Subscription subscription;
        private long subscriptionToken;
        private final Map<String, String> failures = new HashMap<>();
        private volatile boolean active;
        private long lifecycleGeneration;

        Owner(Context context, Handler handler, ExecutorService operations, StateSink sink) throws IOException {
            if (context == null || handler == null || operations == null || sink == null)
                throw new IllegalArgumentException("charge owner dependencies required");
            this.context = context;
            this.handler = handler;
            this.operations = operations;
            this.sink = sink;
            policy = new CameraChargePolicy(new File(MotionDetectorProtocol.STATE_ROOT),
                    CameraChargeSettings.DEFAULT_SOC_THRESHOLD_PERCENT,
                    Math.round(CameraChargeSettings.DEFAULT_VOLTAGE_THRESHOLD_VOLTS * 10.0f));
        }

        void start(MotionConfig config, ReadCompletion completion) {
            if (config == null || !config.anyEnabled()) throw new IllegalArgumentException("enabled config required");
            policy.setThresholds(config.chargeSocThresholdPercent, config.chargeVoltageThresholdTenths);
            active = true;
            lifecycleGeneration++;
            long generation = lifecycleGeneration;
            policy.setMonitoring(true);
            handler.removeCallbacks(poll);
            readNow((state, failure) -> {
                if (active && generation == lifecycleGeneration)
                    handler.postDelayed(poll, POLL_INTERVAL_MS);
                completion.complete(state, failure);
            }); // Initial values are evaluated before any Motion camera/native preparation.
        }

        boolean isMonitoring() { return active; }

        void readNow(ReadCompletion completion) {
            if (!active) {
                handler.post(() -> completion.complete(null,
                        new IllegalStateException("camera charge telemetry is not active")));
                return;
            }
            long generation = lifecycleGeneration;
            long socReadVersion = socReadFence.beginRead();
            long voltageReadVersion = voltageReadFence.beginRead();
            try {
                operations.execute(() -> {
                    Throwable subscribeFailure = trySubscribe();
                    FixedBydTelemetryManager source = manager;
                    Integer soc = null;
                    Float voltage = null;
                    Throwable socFailure = null;
                    Throwable voltageFailure = null;
                    long socSampleElapsedMs = -1L;
                    long voltageSampleElapsedMs = -1L;
                    if (source != null) {
                        try { soc = source.readInt(SOC_DEVICE, SOC_FID); }
                        catch (Throwable failure) { socFailure = failure; }
                        socSampleElapsedMs = SystemClock.elapsedRealtime();
                        try { voltage = source.readFloat(VOLTAGE_DEVICE, VOLTAGE_FID); }
                        catch (Throwable failure) { voltageFailure = failure; }
                        voltageSampleElapsedMs = SystemClock.elapsedRealtime();
                    }
                    Integer socValue = soc;
                    Float voltageValue = voltage;
                    long socSampleAt = socSampleElapsedMs;
                    long voltageSampleAt = voltageSampleElapsedMs;
                    Throwable subscriptionError = subscribeFailure;
                    Throwable socError = socFailure;
                    Throwable voltageError = voltageFailure;
                    synchronized (callbackDispatchLock) {
                        boolean applySocRead = socReadFence.isCurrent(socReadVersion);
                        boolean applyVoltageRead = voltageReadFence.isCurrent(voltageReadVersion);
                        handler.post(() -> {
                            if (!active || generation != lifecycleGeneration) {
                                if (subscriptionError != null)
                                    reportFailure("stale_subscribe", subscriptionError);
                                if (socError != null) reportFailure("stale_soc_read", socError);
                                if (voltageError != null) reportFailure("stale_voltage_read", voltageError);
                                completion.complete(null,
                                        new IllegalStateException("camera charge read belongs to a stale session"));
                                return;
                            }
                            reportFailure("subscribe", subscriptionError);
                            if (source != null) {
                                reportFailure("soc_read", socError);
                                reportFailure("voltage_read", voltageError);
                            }
                            if (applySocRead) policy.updateSoc(socValue,
                                    source == null ? "unknown" : "get", socSampleAt);
                            if (applyVoltageRead) policy.updateVoltage(voltageValue,
                                    source == null ? "unknown" : "get", voltageSampleAt);
                            CameraChargePolicy.State state = policy.snapshot();
                            sink.onState(state);
                            completion.complete(state, null);
                        });
                    }
                });
            } catch (Throwable failure) {
                handler.post(() -> completion.complete(null, failure));
            }
        }

        CameraChargePolicy.State snapshot() { return policy.snapshot(); }

        private void poll() {
            if (!active) return;
            long generation = lifecycleGeneration;
            readNow((state, failure) -> {
                if (active && generation == lifecycleGeneration)
                    handler.postDelayed(poll, POLL_INTERVAL_MS);
            });
        }

        private Throwable trySubscribe() {
            if (!active) return null;
            if (subscription != null && callbackLifetime.isCurrent(subscriptionToken)) return null;
            if (subscription != null) {
                Throwable closeFailure = closeSubscription(subscriptionToken);
                if (closeFailure != null)
                    handler.post(() -> reportFailure("stale_subscription_close", closeFailure));
            }
            try {
                if (manager == null) manager = FixedBydTelemetryManager.get(context);
                long token = callbackLifetime.open();
                try {
                    FixedBydTelemetryManager.Subscription created = manager.subscribe(REQUESTS,
                            new FixedBydTelemetryManager.Listener() {
                                @Override public void onValue(int device, int fid, int value, long receivedMs) {
                                    SignalReadFence fence = readFenceFor(device, fid);
                                    if (fence == null) return;
                                    synchronized (callbackDispatchLock) {
                                        if (!callbackLifetime.runIfCurrent(token, fence::callbackArrived)) return;
                                        handler.post(() -> acceptCallback(token, device, fid, value, receivedMs));
                                    }
                                }

                                @Override public void onError(String reason, long receivedMs) {
                                    if (!callbackLifetime.retireIfCurrent(token)) return;
                                    handler.post(() -> acceptError(token, reason));
                                }
                            });
                    subscription = created;
                    subscriptionToken = token;
                } catch (Throwable failure) {
                    callbackLifetime.retireIfCurrent(token);
                    return failure;
                }
                return null;
            } catch (Throwable unavailable) {
                // A refused callback subscription must not disable independent typed GETs.
                return unavailable;
            }
        }

        private SignalReadFence readFenceFor(int device, int fid) {
            if (device == SOC_DEVICE && fid == SOC_FID) return socReadFence;
            if (device == VOLTAGE_DEVICE && fid == VOLTAGE_FID) return voltageReadFence;
            return null;
        }

        private void acceptCallback(long token, int device, int fid, int raw, long receivedMs) {
            if (!active || !callbackLifetime.isCurrent(token)) return;
            if (device == SOC_DEVICE && fid == SOC_FID) policy.updateSoc(raw, "callback", receivedMs);
            else if (device == VOLTAGE_DEVICE && fid == VOLTAGE_FID)
                policy.updateVoltage(Float.intBitsToFloat(raw), "callback", receivedMs);
            else return;
            reportFailure("callback", null);
            sink.onState(policy.snapshot());
        }

        private void acceptError(long token, String reason) {
            reportCallbackFailure(reason);
            if (!active) return;
            try {
                operations.execute(() -> {
                    Throwable failure = closeSubscription(token);
                    if (failure != null) handler.post(() -> reportFailure("callback_close", failure));
                });
            } catch (RuntimeException failure) { reportFailure("callback_close", failure); }
            // Existing polling refreshes each signal independently, including callback recovery.
        }

        @Override public void close() {
            if (!active) return;
            active = false;
            lifecycleGeneration++;
            callbackLifetime.invalidate();
            handler.removeCallbacks(poll);
            try {
                operations.execute(() -> {
                    Throwable failure = closeSubscription(0L);
                    manager = null;
                    if (failure != null) handler.post(() -> reportFailure("close_subscription", failure));
                });
            } catch (RuntimeException failure) { reportFailure("close", failure); }
            policy.setMonitoring(false);
            sink.onState(policy.snapshot());
        }

        private Throwable closeSubscription(long expectedToken) {
            FixedBydTelemetryManager.Subscription old = subscription;
            if (old == null || expectedToken != 0L && subscriptionToken != expectedToken) return null;
            subscription = null;
            subscriptionToken = 0L;
            try { old.close(); return null; }
            catch (Throwable failure) { return failure; }
        }

        private void reportFailure(String stage, Throwable failure) {
            if (failure == null) {
                if (failures.remove(stage) != null) Log.i("CameraCharge", stage + " recovered");
                return;
            }
            String diagnostic = fullDiagnostic(failure);
            if (!diagnostic.equals(failures.put(stage, diagnostic)))
                Log.w("CameraCharge", stage + " failed", failure);
        }

        private void reportCallbackFailure(String reason) {
            String diagnostic = "callback reason=" + (reason == null ? "null" : "value:" + reason);
            if (!diagnostic.equals(failures.put("callback", diagnostic)))
                Log.w("CameraCharge", "callback failed reason=" + (reason == null ? "<null>" : reason));
        }

    }
}
