package com.byd.extend;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.function.BiConsumer;

/** Reads the active model's ephemeral signed steering sample for reverse camera crop travel. */
final class ReverseSteeringRuntime {
    // BYDAutoSettingDevice.mDeviceType and Setting.SETTING_11F_CONTENT_TIMESTAMP.
    static final int SETTING_DEVICE_TYPE = 1023;
    static final int STEERING_TIMESTAMP_FID = -1728052840;
    private static final long RECONCILE_INTERVAL_MS = 1_000L;
    private static final String TAG = "ReverseSteering";

    private final Context context;
    private final Handler eventHandler;
    private final BiConsumer<String, Object[]> eventSink;
    private final Object lifecycleLock = new Object();
    private final Object callbackLock = new Object();
    private final Object stateEventLock = new Object();
    private HandlerThread workerThread;
    private Handler worker;
    private boolean startRequested;
    private boolean startedOnWorker;
    private boolean warned;

    private FixedBydTelemetryManager telemetryManager;
    private FixedBydTelemetryManager.Subscription subscription;
    private ReverseSteeringModelConfig modelConfig;
    private boolean valid;
    private boolean callbackAvailable;
    private boolean readAvailable;
    private float angleDegrees = Float.NaN;
    private long observedMs = -1L;
    private long lastTimestamp = Long.MIN_VALUE;
    private String invalidReason = "not_started";
    private String lastCapabilityKey = "";
    private int pendingCallbackFid;
    private byte[] pendingCallbackBytes;
    private long pendingCallbackReceivedMs;
    private boolean callbackPosted;
    private Object[] pendingStateEvent;
    private boolean stateEventPosted;

    private final Runnable reconcile = this::reconcile;
    private final Runnable drainCallback = this::drainCallback;
    private final Runnable drainStateEvent = this::drainStateEvent;

    ReverseSteeringRuntime(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.eventHandler = handler;
        this.eventSink = eventSink;
    }

    /** Model lookup belongs to the app UID; only fixed telemetry runs in this shell process. */
    void configure(ReverseSteeringModelConfig config) {
        synchronized (lifecycleLock) {
            ensureWorkerLocked();
            worker.post(() -> {
                if (config != null && config.sameModel(modelConfig) && startedOnWorker) return;
                resetSource();
                modelConfig = config;
                invalidate("model_changed", "");
                startOnWorker();
            });
        }
    }

    /** Starts asynchronously so BYDAuto reads never block camera setup. */
    void start() {
        try {
            synchronized (lifecycleLock) {
                if (startRequested) return;
                startRequested = true;
                ensureWorkerLocked();
                if (!worker.post(this::startOnWorker)) {
                    startRequested = false;
                    throw new IllegalStateException("steering worker unavailable");
                }
            }
        } catch (Throwable failure) {
            synchronized (lifecycleLock) { startRequested = false; }
            warnOnce(failure);
            emitState(false, "start_failed", summary(failure), -1L);
        }
    }

    void stop() {
        synchronized (lifecycleLock) {
            if (!startRequested) return;
            startRequested = false;
            if (worker != null) worker.post(this::stopOnWorker);
        }
    }

    void reportStatus() {
        Handler target;
        synchronized (lifecycleLock) { target = worker; }
        if (target == null || !target.post(() -> {
            if (startedOnWorker && telemetryManager != null && modelConfig != null) {
                readCurrent("status");
            } else {
                emitState(false, "status", invalidReason, -1L);
            }
        })) {
            emitState(false, "status", "not_started", -1L);
        }
    }

    private void ensureWorkerLocked() {
        if (worker != null) return;
        HandlerThread thread = new HandlerThread("ReverseSteeringRuntime");
        thread.start();
        workerThread = thread;
        worker = new Handler(thread.getLooper());
    }

    private void startOnWorker() {
        synchronized (lifecycleLock) {
            if (!startRequested || startedOnWorker) return;
            if (modelConfig == null) {
                updateCapability("model_unavailable");
                invalidate("model_unavailable", "Waiting for app-side active model");
                return;
            }
            startedOnWorker = true;
        }
        callbackAvailable = false;
        readAvailable = false;
        try {
            telemetryManager = FixedBydTelemetryManager.get(context);
            try {
                subscription = telemetryManager.subscribe(
                        new FixedBydTelemetryManager.Request[]{
                                new FixedBydTelemetryManager.Request(SETTING_DEVICE_TYPE,
                                        FixedBydTelemetryManager.ValueType.BYTES,
                                        STEERING_TIMESTAMP_FID)},
                        new FixedBydTelemetryManager.Listener() {
                            @Override public void onValue(
                                    int device, int fid, int value, long receivedMs) { }

                            @Override public void onBytes(
                                    int device, int fid, byte[] value, long receivedMs) {
                                queueCallback(fid, value, receivedMs);
                            }

                            @Override public void onError(String reason, long receivedMs) {
                                Handler target = worker;
                                if (target != null) target.post(() -> listenerFailed(reason));
                            }
                        });
                callbackAvailable = true;
            } catch (Throwable callbackFailure) {
                closeSubscription();
                warnOnce(callbackFailure);
            }
            if (callbackAvailable) updateCapability("");
            readCurrent("initial_read");
            scheduleReconcile();
        } catch (Throwable failure) {
            closeSubscription();
            startedOnWorker = false;
            warnOnce(failure);
            String reason = modelConfig == null ? "model_unavailable" : "source_unavailable";
            String detail = summary(failure);
            updateCapability(reason + ": " + detail);
            invalidate(reason, detail);
            scheduleReconcile();
        }
    }

    private void stopOnWorker() {
        resetSource();
        valid = false;
        angleDegrees = Float.NaN;
        observedMs = -1L;
        invalidReason = "stopped";
        updateCapability("stopped");
        emitState(false, "stopped", "", -1L);

        HandlerThread finishedThread = null;
        boolean restart;
        synchronized (lifecycleLock) {
            restart = startRequested;
            if (!restart) {
                finishedThread = workerThread;
                workerThread = null;
                worker = null;
            }
        }
        if (restart) startOnWorker();
        else if (finishedThread != null) finishedThread.quitSafely();
    }

    private void resetSource() {
        Handler target = worker;
        if (target != null) target.removeCallbacks(reconcile);
        closeSubscription();
        telemetryManager = null;
        startedOnWorker = false;
        callbackAvailable = false;
        readAvailable = false;
        lastTimestamp = Long.MIN_VALUE;
        warned = false;
    }

    private void readCurrent(String source) {
        if (!startedOnWorker || telemetryManager == null || modelConfig == null) return;
        try {
            byte[] value = telemetryManager.readBuffer(SETTING_DEVICE_TYPE,
                    STEERING_TIMESTAMP_FID);
            readAvailable = true;
            updateCapability("");
            acceptBytes(STEERING_TIMESTAMP_FID, value, source, elapsedRealtime());
        } catch (Throwable failure) {
            readAvailable = false;
            updateCapability(callbackAvailable ? "" : "source_unavailable: "
                    + summary(failure));
            warnOnce(failure);
            invalidate("read_unavailable", summary(failure));
        }
    }

    private void reconcile() {
        synchronized (lifecycleLock) { if (!startRequested) return; }
        if (!startedOnWorker) startOnWorker();
        else readCurrent("reconcile");
        scheduleReconcile();
    }

    private void scheduleReconcile() {
        Handler target = worker;
        if (modelConfig != null && target != null) {
            target.removeCallbacks(reconcile);
            target.postDelayed(reconcile, RECONCILE_INTERVAL_MS);
        }
    }

    private void acceptBytes(int fid, byte[] bytes, String source, long receivedMs) {
        if (!startedOnWorker) return;
        if (fid != STEERING_TIMESTAMP_FID) {
            invalidate("unexpected_fid", "fid=" + fid);
            return;
        }
        SteeringPacket packet;
        try {
            packet = decode(bytes);
        } catch (IllegalArgumentException failure) {
            invalidate("invalid_payload", failure.getMessage());
            return;
        }
        updateCapability("");
        if (modelConfig == null || !ReverseSteeringShift.isValidAngle(
                packet.angleDegrees, modelConfig.minimumDegrees, modelConfig.maximumDegrees)) {
            lastTimestamp = packet.timestamp;
            invalidate("angle_outside_active_model_bounds", "OEM sample is outside selected model bounds");
            return;
        }
        if ("callback".equals(source) && packet.timestamp == lastTimestamp
                && valid && packet.angleDegrees == angleDegrees) return;

        lastTimestamp = packet.timestamp;
        if ("callback".equals(source)) callbackAvailable = true;
        valid = true;
        angleDegrees = packet.angleDegrees;
        observedMs = receivedMs > 0L ? receivedMs : elapsedRealtime();
        invalidReason = "";
        emitState(true, source, "", observedMs);
    }

    private void invalidate(String reason, String detail) {
        boolean changed = valid || !reason.equals(invalidReason);
        valid = false;
        angleDegrees = Float.NaN;
        observedMs = -1L;
        invalidReason = reason;
        if (!detail.isEmpty()) warnOnce(new IllegalStateException(detail));
        if (changed) emitState(false, reason, detail, -1L);
    }

    private void listenerFailed(String reason) {
        callbackAvailable = false;
        updateCapability(readAvailable ? "" : "source_unavailable");
        invalidate("listener_error", reason);
    }

    private void queueCallback(int fid, byte[] bytes, long receivedMs) {
        Handler target;
        synchronized (lifecycleLock) { target = worker; }
        if (target == null) return;
        synchronized (callbackLock) {
            pendingCallbackFid = fid;
            pendingCallbackBytes = bytes == null ? null : bytes.clone();
            pendingCallbackReceivedMs = receivedMs;
            if (callbackPosted) return;
            callbackPosted = true;
        }
        if (!target.post(drainCallback)) {
            synchronized (callbackLock) {
                callbackPosted = false;
                pendingCallbackBytes = null;
            }
        }
    }

    private void drainCallback() {
        int fid;
        byte[] bytes;
        long receivedMs;
        synchronized (callbackLock) {
            fid = pendingCallbackFid;
            bytes = pendingCallbackBytes;
            receivedMs = pendingCallbackReceivedMs;
            pendingCallbackBytes = null;
            callbackPosted = false;
        }
        if (bytes != null) acceptBytes(fid, bytes, "callback", receivedMs);
    }

    private void updateCapability(String reason) {
        ReverseSteeringModelConfig config = modelConfig;
        boolean available = config != null && (callbackAvailable || readAvailable);
        String model = config == null ? "" : config.model;
        String mode = callbackAvailable
                ? (readAvailable ? "callback_and_read" : "callback")
                : readAvailable ? "read_reconcile" : "unavailable";
        String boundedReason = reason == null ? "" : reason;
        if (boundedReason.length() > 200) boundedReason = boundedReason.substring(0, 200);
        String key = available + "|" + callbackAvailable + "|" + readAvailable
                + "|" + model + "|" + boundedReason;
        if (key.equals(lastCapabilityKey)) return;
        lastCapabilityKey = key;
        emit("reverse_steering_capability", "available", available,
                "source", "BYDAutoManager Setting bytes",
                "callback_available", callbackAvailable,
                "read_available", readAvailable,
                "mode", mode,
                "reason", boundedReason,
                "model", model);
    }

    private void closeSubscription() {
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Throwable failure) {
            warnOnce(failure);
        }
        subscription = null;
    }

    private void emitState(boolean sampleValid, String source, String error, long sampleMs) {
        ReverseSteeringModelConfig config = modelConfig;
        if (sampleValid && config != null && Float.isFinite(angleDegrees)) {
            queueStateEvent("valid", true,
                    "angle_degrees", angleDegrees,
                    "minimum_degrees", config.minimumDegrees,
                    "maximum_degrees", config.maximumDegrees,
                    "observed_ms", sampleMs,
                    "model", config.model,
                    "source_event", source);
        } else if (config != null) {
            queueStateEvent("valid", false,
                    "minimum_degrees", config.minimumDegrees,
                    "maximum_degrees", config.maximumDegrees,
                    "observed_ms", -1L,
                    "model", config.model,
                    "source_event", source,
                    "error", error);
        } else {
            queueStateEvent("valid", false,
                    "observed_ms", -1L,
                    "model", "",
                    "source_event", source,
                    "error", error);
        }
    }

    static SteeringPacket decode(byte[] bytes) {
        if (bytes == null || bytes.length != 6) {
            throw new IllegalArgumentException("expected six timestamped steering bytes");
        }
        int raw = ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
        if ((raw & 0x8000) != 0) raw -= 0x10000;
        long timestamp = ((long) (bytes[2] & 0xff) << 24)
                | ((long) (bytes[3] & 0xff) << 16)
                | ((long) (bytes[4] & 0xff) << 8)
                | (long) (bytes[5] & 0xff);
        return new SteeringPacket(raw / 10.0f, timestamp);
    }

    static final class SteeringPacket {
        final float angleDegrees;
        final long timestamp;

        SteeringPacket(float angleDegrees, long timestamp) {
            this.angleDegrees = angleDegrees;
            this.timestamp = timestamp;
        }
    }

    private void emit(String kind, Object... fields) {
        Object[] copy = fields.clone();
        eventHandler.post(() -> {
            try { eventSink.accept(kind, copy); }
            catch (RuntimeException ignored) { }
        });
    }

    private void queueStateEvent(Object... fields) {
        synchronized (stateEventLock) {
            pendingStateEvent = fields.clone();
            if (stateEventPosted) return;
            stateEventPosted = true;
        }
        if (!eventHandler.post(drainStateEvent)) {
            synchronized (stateEventLock) {
                stateEventPosted = false;
                pendingStateEvent = null;
            }
        }
    }

    private void drainStateEvent() {
        Object[] fields;
        synchronized (stateEventLock) {
            fields = pendingStateEvent;
            pendingStateEvent = null;
            stateEventPosted = false;
        }
        if (fields == null) return;
        try { eventSink.accept("reverse_steering_state", fields); }
        catch (RuntimeException ignored) { }
    }

    private void warnOnce(Throwable failure) {
        synchronized (this) {
            if (warned) return;
            warned = true;
        }
        try { Log.w(TAG, summary(failure)); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private static long elapsedRealtime() {
        try { return SystemClock.elapsedRealtime(); }
        catch (RuntimeException | LinkageError unavailableInLocalJvm) { return 0L; }
    }

    private static String summary(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; depth < 8 && cause.getCause() != null
                && cause.getCause() != cause; depth++) cause = cause.getCause();
        String message = cause.getMessage();
        String result = cause.getClass().getSimpleName()
                + (message == null ? "" : ": " + message);
        return result.length() > 160 ? result.substring(0, 160) : result;
    }
}
