package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;

/**
 * Read-only radar telemetry bridge.  Registration and refresh are deliberately
 * inert until the shell configures parking radar.
 */
public final class ParkingRadarRuntime {
    public static final String DEVICE_CLASS = "android.hardware.bydauto.radar.BYDAutoRadarDevice";
    public static final String LISTENER_CLASS = "android.hardware.IBYDAutoListener";
    public static final int RAW_MIN = ParkingCameraProfile.RADAR_RAW_MIN;
    public static final int RAW_MAX = ParkingCameraProfile.RADAR_RAW_MAX;
    public static final long REFRESH_PERIOD_MS = 500L;

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final Runnable refreshRunnable = this::refreshOnHandler;
    private final int[] raw = new int[ParkingCameraProfile.allRadarFids().length];
    private final boolean[] valid = new boolean[raw.length];
    private final long[] timestamps = new long[raw.length];

    private Object device;
    private Object listener;
    private Method unregisterListener;
    private Method readValue;
    private boolean started;
    private boolean configured;
    private boolean listenerHealthy;

    public ParkingRadarRuntime(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        if (handler == null || eventSink == null) {
            throw new IllegalArgumentException("null runtime argument");
        }
        this.context = context;
        this.handler = handler;
        this.eventSink = eventSink;
        invalidateAll();
    }

    public void start() {
        runOnHandler(this::startOnHandler);
    }

    public void stop() {
        runOnHandler(this::stopOnHandler);
    }

    public void configure(boolean value) {
        runOnHandler(() -> configureOnHandler(value));
    }

    public void reportStatus() {
        runOnHandler(() -> {
            recoverRegistrationIfNeeded(started, configured, listenerHealthy,
                    () -> unregisterOnHandler("recovery_retry"), this::registerOnHandler);
            emit("parking_radar_listener", "action", "status", "ok", listenerHealthy,
                    "configured", configured, "started", started,
                    "fids", ParkingCameraProfile.allRadarFids());
            emitAllStates("status");
        });
    }

    private void startOnHandler() {
        if (started) return;
        started = true;
        if (configured) registerOnHandler();
    }

    private void configureOnHandler(boolean value) {
        if (configured == value && (!value || listenerHealthy)) return;
        configured = value;
        if (!value) {
            handler.removeCallbacks(refreshRunnable);
            unregisterOnHandler("disabled");
            invalidateAll();
            emitAllStates("disabled");
            return;
        }
        if (started) registerOnHandler();
    }

    private void stopOnHandler() {
        handler.removeCallbacks(refreshRunnable);
        started = false;
        configured = false;
        unregisterOnHandler("stopped");
        invalidateAll();
        emitAllStates("stopped");
    }

    private void registerOnHandler() {
        if (!shouldRefresh(started, configured) || listenerHealthy) return;
        try {
            Class<?> listenerType = Class.forName(LISTENER_CLASS);
            Class<?> eventType = Class.forName("android.hardware.IBYDAutoEvent");
            Method getEventId = eventType.getMethod("getEventType");
            Method getEventValue = eventType.getMethod("getValue");
            listener = Proxy.newProxyInstance(
                    ParkingRadarRuntime.class.getClassLoader(), new Class<?>[]{listenerType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("toString".equals(method.getName())) return "ParkingRadarListener";
                            if ("hashCode".equals(method.getName())) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(method.getName())) return proxy == args[0];
                        }
                        if ("onDataChanged".equals(method.getName()) && args != null
                                && args.length > 0 && args[0] != null) {
                            int fid = ((Number) getEventId.invoke(args[0])).intValue();
                            int value = ((Number) getEventValue.invoke(args[0])).intValue();
                            handler.post(() -> {
                                if (listener == proxy) acceptValue(fid, value, "callback");
                            });
                        } else if ("onError".equals(method.getName())) {
                            int code = args != null && args.length > 0 && args[0] instanceof Number
                                    ? ((Number) args[0]).intValue() : -1;
                            String message = args != null && args.length > 1
                                    ? String.valueOf(args[1]) : "unknown";
                            handler.post(() -> {
                                if (listener == proxy) listenerFailed(code, message);
                            });
                        }
                        return null;
                    });
            Class<?> deviceType = Class.forName(DEVICE_CLASS);
            device = getInstance(deviceType);
            readValue = deviceType.getMethod("get", int[].class, Class.class);
            Method register = findListenerMethod(device, "registerListener", true, listener);
            unregisterListener = findListenerMethod(device, "unregisterListener", false, listener);
            register.invoke(device, listener, ParkingCameraProfile.allRadarFids());
            listenerHealthy = true;
            emit("parking_radar_listener", "action", "registered", "ok", true,
                    "configured", true, "fids", ParkingCameraProfile.allRadarFids());
            readAll("initial_read");
            scheduleRefresh();
        } catch (Throwable error) {
            listenerHealthy = false;
            invalidateAll();
            emit("parking_radar_listener", "action", "registration_error", "ok", false,
                    "configured", configured, "error", summary(error));
            emitAllStates("registration_error");
        }
    }

    private Object getInstance(Class<?> deviceType) throws Exception {
        try {
            return deviceType.getMethod("getInstance", Context.class).invoke(null, context);
        } catch (NoSuchMethodException ignored) {
            return deviceType.getMethod("getInstance").invoke(null);
        }
    }

    private void unregisterOnHandler(String reason) {
        handler.removeCallbacks(refreshRunnable);
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
        unregisterListener = null;
        readValue = null;
        listener = null;
        emit("parking_radar_listener", "action", reason, "ok", error.isEmpty(),
                "unregistered", unregistered, "configured", configured, "error", error);
    }

    private void readAll(String source) {
        if (!shouldRefresh(started, configured) || readValue == null || device == null) return;
        int[] fids = ParkingCameraProfile.allRadarFids();
        for (int fid : fids) readOne(fid, source);
    }

    private void readOne(int fid, String source) {
        try {
            Object value = readValue.invoke(device, new int[]{fid}, Integer.TYPE);
            acceptValue(fid, integerValue(value), source);
        } catch (Throwable error) {
            int index = indexOf(fid);
            if (index >= 0) {
                valid[index] = false;
                timestamps[index] = 0L;
            }
            emit("parking_radar_read", "ok", false, "fid", fid,
                    "source", source, "error", summary(error));
            emitState(index, fid, source + "_error");
        }
    }

    static int integerValue(Object value) throws Exception {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) throw new IllegalStateException("null radar value");
        try {
            Field intValue = value.getClass().getField("intValue");
            return intValue.getInt(value);
        } catch (NoSuchFieldException ignored) {
            return Integer.parseInt(String.valueOf(value));
        }
    }

    private void acceptValue(int fid, int value, String source) {
        if (!shouldRefresh(started, configured)) return;
        int index = indexOf(fid);
        if (index < 0) {
            emit("parking_radar_event_ignored", "fid", fid, "raw", value,
                    "reason", "unexpected_fid");
            return;
        }
        if ("callback".equals(source) && !listenerHealthy) {
            valid[index] = false;
            emitState(index, fid, "callback_while_unhealthy");
            return;
        }
        raw[index] = value;
        valid[index] = ParkingCameraProfile.isValidRadarRaw(value);
        timestamps[index] = valid[index] ? android.os.SystemClock.elapsedRealtime() : 0L;
        emit("parking_radar_read", "ok", valid[index], "fid", fid, "raw", value,
                "valid", valid[index], "source", source);
        emitState(index, fid, source);
    }

    private void listenerFailed(int code, String message) {
        listenerHealthy = false;
        invalidateAll();
        emit("parking_radar_listener", "action", "error", "ok", false,
                "error_code", code, "error", message, "configured", configured);
        emitAllStates("listener_error");
    }

    private void refreshOnHandler() {
        if (!shouldRefresh(started, configured)) return;
        readAll("refresh");
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        if (shouldRefresh(started, configured)) {
            handler.postDelayed(refreshRunnable, REFRESH_PERIOD_MS);
        }
    }

    private void invalidateAll() {
        for (int i = 0; i < valid.length; i++) {
            raw[i] = -1;
            valid[i] = false;
            timestamps[i] = 0L;
        }
    }

    private void emitAllStates(String source) {
        int[] fids = ParkingCameraProfile.allRadarFids();
        for (int i = 0; i < fids.length; i++) emitState(i, fids[i], source);
    }

    private void emitState(int index, int fid, String source) {
        boolean stateValid = index >= 0 && index < valid.length && valid[index];
        int stateRaw = index >= 0 && index < raw.length ? raw[index] : -1;
        emit("parking_radar_state", "configured", configured, "listener_ok", listenerHealthy,
                "fid", fid, "raw", stateRaw, "valid", stateValid,
                "source_event", source);
    }

    private int indexOf(int fid) {
        int[] fids = ParkingCameraProfile.allRadarFids();
        for (int i = 0; i < fids.length; i++) if (fids[i] == fid) return i;
        return -1;
    }

    public static boolean isValidRaw(int raw) {
        return raw >= RAW_MIN && raw <= RAW_MAX;
    }

    public static boolean shouldRefresh(boolean started, boolean configured) {
        return started && configured;
    }

    static boolean recoverRegistrationIfNeeded(
            boolean started, boolean configured, boolean listenerHealthy,
            Runnable cleanup, Runnable register) {
        if (!shouldRefresh(started, configured) || listenerHealthy) return false;
        cleanup.run();
        register.run();
        return true;
    }

    static Method findListenerMethod(
            Object target, String name, boolean withFeatureIds, Object listener)
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
