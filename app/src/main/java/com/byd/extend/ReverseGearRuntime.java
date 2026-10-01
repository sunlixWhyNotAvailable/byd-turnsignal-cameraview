package com.byd.extend;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;

final class ReverseGearRuntime {
    static final int DEVICE_TYPE = 1011;
    static final int TRANSACTION = 5;
    static final int GEAR_FID = 555745336;
    static final int PARK_RAW = ReverseGearSessionPolicy.RAW_PARK;
    static final int REVERSE_RAW = ReverseGearSessionPolicy.RAW_REVERSE;
    static final int NEUTRAL_RAW = ReverseGearSessionPolicy.RAW_NEUTRAL;
    static final int DRIVE_RAW = ReverseGearSessionPolicy.RAW_DRIVE;

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;

    private Object device;
    private FixedBydTelemetryManager.Subscription subscription;
    private Method readValue;
    private boolean started;
    private boolean listenerRegistered;
    private boolean listenerHealthy;
    private boolean valid;
    private boolean reverse;
    private int raw = -1;

    ReverseGearRuntime(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        this.context = context;
        this.handler = handler;
        this.eventSink = eventSink;
    }

    void start() {
        runOnHandler(this::startOnHandler);
    }

    void stop() {
        runOnHandler(this::stopOnHandler);
    }

    void reportStatus() {
        runOnHandler(() -> {
            emitListener("status", listenerHealthy, "");
            if (started && listenerHealthy) readCurrent("status_read");
            else emitState("status");
        });
    }

    private void startOnHandler() {
        if (started) return;
        started = true;
        try {
            Class<?> deviceType = Class.forName(
                    "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            device = deviceType.getMethod("getInstance", Context.class).invoke(null, context);
            readValue = deviceType.getMethod("get", int[].class, Class.class);
            subscription = FixedBydTelemetryManager.get(context).subscribe(
                    new FixedBydTelemetryManager.Request[]{
                            new FixedBydTelemetryManager.Request(DEVICE_TYPE, GEAR_FID)},
                    new FixedBydTelemetryManager.Listener() {
                        @Override public void onValue(
                                int deviceType, int fid, int value, long receivedMs) {
                            handler.post(() -> acceptValue(fid, value, "callback"));
                        }
                        @Override public void onError(String reason, long receivedMs) {
                            handler.post(() -> listenerFailed(-1, reason));
                        }
                    });
            listenerRegistered = true;
            listenerHealthy = true;
            emitListener("registered", true, "");
            readCurrent("initial_read");
        } catch (Throwable error) {
            if (subscription != null) subscription.close();
            subscription = null;
            listenerRegistered = false;
            invalidate();
            listenerHealthy = false;
            emitListener("registration_error", false, summary(error));
            emitState("registration_error");
        }
    }

    private void stopOnHandler() {
        if (!started) return;
        started = false;
        boolean unregistered = false;
        String error = "";
        try {
            if (subscription != null) {
                subscription.close();
                unregistered = true;
            }
        } catch (Throwable failure) {
            error = summary(failure);
        }
        subscription = null;
        listenerRegistered = false;
        listenerHealthy = false;
        readValue = null;
        invalidate();
        emit("reverse_gear_listener", "action", "stopped", "ok", error.isEmpty(),
                "registered", false, "unregistered", unregistered,
                "device", DEVICE_TYPE, "tx", TRANSACTION, "fid", GEAR_FID,
                "error", error);
        emitState("stopped");
    }

    private void readCurrent(String source) {
        try {
            if (readValue == null || device == null) {
                throw new IllegalStateException("gear read method unavailable");
            }
            Object value = readValue.invoke(device, new int[]{GEAR_FID}, Integer.TYPE);
            Field intValue = value.getClass().getField("intValue");
            acceptValue(GEAR_FID, intValue.getInt(value), source);
        } catch (Throwable error) {
            invalidate();
            emit("reverse_gear_read", "ok", false, "valid", false,
                    "reverse", false, "source", source,
                    "device", DEVICE_TYPE, "tx", TRANSACTION, "fid", GEAR_FID,
                    "error", summary(error));
            emitState(source + "_error");
        }
    }

    private void acceptValue(int fid, int value, String source) {
        if (!started) return;
        if (fid != GEAR_FID) {
            invalidate();
            emit("reverse_gear_read", "ok", false, "valid", false,
                    "raw", value, "reverse", false, "source", source,
                    "device", DEVICE_TYPE, "tx", TRANSACTION, "fid", fid,
                    "expected_fid", GEAR_FID, "error", "unexpected_fid");
            emitState("unexpected_fid");
            return;
        }
        if ("callback".equals(source) && !listenerHealthy) {
            invalidate();
            emit("reverse_gear_read", "ok", false, "valid", false,
                    "raw", value, "reverse", false, "source", source,
                    "device", DEVICE_TYPE, "tx", TRANSACTION, "fid", GEAR_FID,
                    "error", "listener_unhealthy");
            emitState("callback_while_unhealthy");
            return;
        }
        raw = value;
        valid = isValidRaw(value);
        reverse = isReverseRaw(valid, value);
        emit("reverse_gear_read", "ok", valid, "valid", valid,
                "raw", value, "reverse", reverse, "source", source,
                "device", DEVICE_TYPE, "tx", TRANSACTION, "fid", GEAR_FID,
                "error", valid ? "" : "invalid_raw");
        emitState(source);
    }

    private void listenerFailed(int code, String message) {
        listenerHealthy = false;
        invalidate();
        emit("reverse_gear_listener", "action", "error", "ok", false,
                "registered", listenerRegistered, "listener_ok", false,
                "device", DEVICE_TYPE, "tx", TRANSACTION, "fid", GEAR_FID,
                "error_code", code, "error", message);
        emitState("listener_error");
    }

    private void invalidate() {
        raw = -1;
        valid = false;
        reverse = false;
    }

    private void emitListener(String action, boolean ok, String error) {
        emit("reverse_gear_listener", "action", action, "ok", ok,
                "registered", listenerRegistered, "listener_ok", listenerHealthy,
                "device", DEVICE_TYPE, "tx", TRANSACTION, "fid", GEAR_FID,
                "error", error);
    }

    private void emitState(String source) {
        emit("reverse_gear_state", "valid", valid, "raw", raw,
                "reverse", reverse, "listener_ok", listenerHealthy,
                "gear", valid ? ReverseGearSessionPolicy.gearForRaw(raw).name() : "UNKNOWN",
                "registered", listenerRegistered, "source_event", source,
                "device", DEVICE_TYPE, "tx", TRANSACTION, "fid", GEAR_FID);
    }

    static boolean isValidRaw(int value) {
        return ReverseGearSessionPolicy.isValidRaw(value);
    }

    static boolean isReverseRaw(boolean valueValid, int value) {
        return valueValid && ReverseGearSessionPolicy.isReverseRaw(value);
    }

    private void runOnHandler(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private static String summary(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
