package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;

final class ReverseGearRuntime {
    static final int DEVICE_TYPE = 1011;
    static final int TRANSACTION = 5;
    static final int GEAR_FID = 555745336;
    static final int REVERSE_RAW = 2;

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;

    private Object device;
    private Object listener;
    private Method unregisterListener;
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
            Class<?> listenerType = Class.forName("android.hardware.IBYDAutoListener");
            Class<?> eventType = Class.forName("android.hardware.IBYDAutoEvent");
            Method getEventId = eventType.getMethod("getEventType");
            Method getEventValue = eventType.getMethod("getValue");
            listener = Proxy.newProxyInstance(
                    ReverseGearRuntime.class.getClassLoader(),
                    new Class<?>[]{listenerType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("toString".equals(method.getName())) return "ReverseGearListener";
                            if ("hashCode".equals(method.getName())) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(method.getName())) return proxy == args[0];
                        }
                        if ("onDataChanged".equals(method.getName())) {
                            int fid = (Integer) getEventId.invoke(args[0]);
                            int value = (Integer) getEventValue.invoke(args[0]);
                            handler.post(() -> acceptValue(fid, value, "callback"));
                        } else if ("onError".equals(method.getName())) {
                            int code = (Integer) args[0];
                            String message = String.valueOf(args[1]);
                            handler.post(() -> listenerFailed(code, message));
                        }
                        return null;
                    });

            Class<?> deviceType = Class.forName(
                    "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            device = deviceType.getMethod("getInstance", Context.class).invoke(null, context);
            Method register = findListenerMethod(device, "registerListener", true);
            unregisterListener = findListenerMethod(device, "unregisterListener", false);
            readValue = deviceType.getMethod("get", int[].class, Class.class);
            register.invoke(device, listener, new int[]{GEAR_FID});
            listenerRegistered = true;
            listenerHealthy = true;
            emitListener("registered", true, "");
            readCurrent("initial_read");
        } catch (Throwable error) {
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
            if (unregisterListener != null && device != null && listener != null) {
                unregisterListener.invoke(device, listener);
                unregistered = true;
            }
        } catch (Throwable failure) {
            error = summary(failure);
        }
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
                "registered", listenerRegistered, "source_event", source,
                "device", DEVICE_TYPE, "tx", TRANSACTION, "fid", GEAR_FID);
    }

    private Method findListenerMethod(Object target, String name, boolean withFeatureIds)
            throws NoSuchMethodException {
        for (Method method : target.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name)
                    && parameters.length == (withFeatureIds ? 2 : 1)
                    && parameters[0].isAssignableFrom(listener.getClass())
                    && (!withFeatureIds || parameters[1] == int[].class)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    static boolean isValidRaw(int value) {
        return value >= 1 && value <= 6;
    }

    static boolean isReverseRaw(boolean valueValid, int value) {
        return valueValid && value == REVERSE_RAW;
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
