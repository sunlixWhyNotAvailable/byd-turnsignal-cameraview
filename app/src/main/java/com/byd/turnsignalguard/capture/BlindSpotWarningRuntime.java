package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;

final class BlindSpotWarningRuntime {
    static final int DEVICE_TYPE = 1038;
    static final int TRANSACTION = 5;
    static final int LEFT_FID = 1098907664;
    static final int RIGHT_FID = 1098907666;
    static final int ACTIVE_RAW = 2;

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;

    private Object device;
    private Object listener;
    private Method unregisterListener;
    private boolean started;
    private boolean listenerHealthy;
    private boolean leftValid;
    private boolean rightValid;
    private int leftRaw = -1;
    private int rightRaw = -1;

    BlindSpotWarningRuntime(
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
            emit("bsd_listener_status", "ok", listenerHealthy,
                    "device", DEVICE_TYPE, "tx", TRANSACTION,
                    "left_fid", LEFT_FID, "right_fid", RIGHT_FID);
            emitState("status", -1);
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
                    BlindSpotWarningRuntime.class.getClassLoader(),
                    new Class<?>[]{listenerType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("toString".equals(method.getName())) {
                                return "BlindSpotWarningListener";
                            }
                            if ("hashCode".equals(method.getName())) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(method.getName())) return proxy == args[0];
                        }
                        if ("onDataChanged".equals(method.getName())) {
                            int fid = (Integer) getEventId.invoke(args[0]);
                            int raw = (Integer) getEventValue.invoke(args[0]);
                            handler.post(() -> acceptValue(fid, raw, "callback"));
                        } else if ("onError".equals(method.getName())) {
                            int code = (Integer) args[0];
                            String message = String.valueOf(args[1]);
                            handler.post(() -> listenerFailed(code + ": " + message));
                        }
                        return null;
                    });

            Class<?> deviceType = Class.forName(
                    "android.hardware.bydauto.adas.BYDAutoADASDevice");
            device = deviceType.getMethod("getInstance", Context.class).invoke(null, context);
            readInitial(deviceType, LEFT_FID);
            readInitial(deviceType, RIGHT_FID);
            Method register = findListenerMethod(device, "registerListener", true);
            unregisterListener = findListenerMethod(device, "unregisterListener", false);
            register.invoke(device, listener, new int[]{LEFT_FID, RIGHT_FID});
            listenerHealthy = true;
            emit("bsd_listener_registered", "ok", true,
                    "device", DEVICE_TYPE, "tx", TRANSACTION,
                    "left_fid", LEFT_FID, "right_fid", RIGHT_FID);
            emitState("initial", -1);
        } catch (Throwable error) {
            listenerHealthy = false;
            leftValid = false;
            rightValid = false;
            emit("bsd_listener_registered", "ok", false,
                    "device", DEVICE_TYPE, "tx", TRANSACTION,
                    "left_fid", LEFT_FID, "right_fid", RIGHT_FID,
                    "error", summary(error));
            emitState("registration_error", -1);
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
        listenerHealthy = false;
        leftValid = false;
        rightValid = false;
        emit("bsd_listener_stopped", "unregistered", unregistered, "error", error);
    }

    private void readInitial(Class<?> deviceType, int fid) {
        try {
            Object value = deviceType.getMethod("get", int[].class, Class.class)
                    .invoke(device, new int[]{fid}, Integer.TYPE);
            Field intValue = value.getClass().getField("intValue");
            acceptValue(fid, intValue.getInt(value), "initial_read");
        } catch (Throwable error) {
            invalidate(fid);
            emit("bsd_read", "ok", false, "fid", fid,
                    "device", DEVICE_TYPE, "tx", TRANSACTION,
                    "error", summary(error));
        }
    }

    private void acceptValue(int fid, int raw, String source) {
        if (!started) return;
        if (fid != LEFT_FID && fid != RIGHT_FID) {
            emit("bsd_event_ignored", "fid", fid, "raw", raw,
                    "reason", "unexpected_fid");
            return;
        }
        if ("callback".equals(source) && !listenerHealthy) {
            invalidate(fid);
            emit("bsd_event_ignored", "fid", fid, "raw", raw,
                    "reason", "listener_unhealthy");
            emitState("callback_while_unhealthy", fid);
            return;
        }
        boolean valid = isValidRaw(raw);
        if (fid == LEFT_FID) {
            leftRaw = raw;
            leftValid = valid;
        } else {
            rightRaw = raw;
            rightValid = valid;
        }
        emit("bsd_read", "ok", valid, "fid", fid, "raw", raw,
                "device", DEVICE_TYPE, "tx", TRANSACTION, "source", source);
        emitState(source, fid);
    }

    private void listenerFailed(String error) {
        listenerHealthy = false;
        leftValid = false;
        rightValid = false;
        emit("bsd_listener_error", "error", error);
        emitState("listener_error", -1);
    }

    private void invalidate(int fid) {
        if (fid == LEFT_FID) leftValid = false;
        else if (fid == RIGHT_FID) rightValid = false;
    }

    private void emitState(String source, int changedFid) {
        emit("bsd_state",
                "left_valid", leftValid, "left_raw", leftValid ? leftRaw : "unknown",
                "right_valid", rightValid, "right_raw", rightValid ? rightRaw : "unknown",
                "listener_ok", listenerHealthy,
                "source_event", source, "changed_fid", changedFid);
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

    static boolean isValidRaw(int raw) {
        return raw >= 0 && raw <= ACTIVE_RAW;
    }

    static boolean isActiveRaw(boolean valid, int raw) {
        return valid && raw == ACTIVE_RAW;
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
