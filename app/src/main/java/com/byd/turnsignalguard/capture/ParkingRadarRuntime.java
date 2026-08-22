package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;

/** Read-only radar telemetry bridge for the core and side vendor sources. */
public final class ParkingRadarRuntime {
    public static final String DEVICE_CLASS =
            "android.hardware.bydauto.radar.BYDAutoRadarDevice";
    public static final String ADAS_DEVICE_CLASS =
            "android.hardware.bydauto.adas.BYDAutoADASDevice";
    public static final String LISTENER_CLASS = "android.hardware.IBYDAutoListener";
    public static final int RAW_MIN = ParkingCameraProfile.RADAR_RAW_MIN;
    public static final int RAW_MAX = ParkingCameraProfile.RADAR_RAW_MAX;
    public static final int SIDE_RAW_MAX = ParkingCameraProfile.SIDE_RADAR_RAW_MAX;
    public static final long REFRESH_PERIOD_MS = 500L;

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final Runnable refreshRunnable = this::refreshOnHandler;
    private final int[] raw = new int[ParkingCameraProfile.allRadarFids().length];
    private final boolean[] valid = new boolean[raw.length];
    private final long[] timestamps = new long[raw.length];
    private final Source[] sources;

    private boolean started;
    private boolean configured;

    public ParkingRadarRuntime(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        if (handler == null || eventSink == null) {
            throw new IllegalArgumentException("null runtime argument");
        }
        this.context = context;
        this.handler = handler;
        this.eventSink = eventSink;
        sources = new Source[]{
                new Source("radar_core", DEVICE_CLASS, ParkingCameraProfile.coreRadarFids()),
                new Source("adas_side", ADAS_DEVICE_CLASS, ParkingCameraProfile.sideRadarFids())
        };
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
            recoverSourcesOnHandler();
            for (Source source : sources) source.emitListener("status", "", false);
            emitAllStates("status");
        });
    }

    private void startOnHandler() {
        if (started) return;
        started = true;
        if (configured) registerOnHandler();
    }

    private void configureOnHandler(boolean value) {
        if (configured == value) {
            if (!value || allSourcesHealthy()) return;
            if (started) recoverSourcesOnHandler();
            return;
        }
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
        if (!shouldRefresh(started, configured)) return;
        for (Source source : sources) source.register();
        scheduleRefresh();
    }

    private void recoverSourcesOnHandler() {
        if (!shouldRefresh(started, configured)) return;
        for (Source source : sources) {
            if (source.healthy) continue;
            source.stop("recovery_retry");
            source.register();
        }
    }

    private void unregisterOnHandler(String reason) {
        handler.removeCallbacks(refreshRunnable);
        for (Source source : sources) source.stop(reason);
    }

    private void refreshOnHandler() {
        if (!shouldRefresh(started, configured)) return;
        for (Source source : sources) source.readAll("refresh");
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

    private void invalidate(int[] fids) {
        for (int fid : fids) {
            int index = indexOf(fid);
            if (index >= 0) {
                valid[index] = false;
                timestamps[index] = 0L;
            }
        }
    }

    private void emitAllStates(String sourceEvent) {
        int[] fids = ParkingCameraProfile.allRadarFids();
        for (int i = 0; i < fids.length; i++) emitState(i, fids[i], sourceEvent);
    }

    private void emitState(int index, int fid, String sourceEvent) {
        Source source = sourceFor(fid);
        boolean stateValid = index >= 0 && index < valid.length && valid[index];
        int stateRaw = index >= 0 && index < raw.length ? raw[index] : -1;
        emit("parking_radar_state", "configured", configured,
                "listener_ok", source != null && source.healthy,
                "fid", fid, "raw", stateRaw, "valid", stateValid,
                "source_family", source == null ? "" : source.family,
                "source_event", sourceEvent);
    }

    private void acceptValue(Source source, int fid, int value, String sourceEvent) {
        if (!shouldRefresh(started, configured)) return;
        int index = indexOf(fid);
        if (index < 0 || source != sourceFor(fid)) {
            emit("parking_radar_event_ignored", "fid", fid, "raw", value,
                    "reason", "unexpected_fid");
            return;
        }
        if ("callback".equals(sourceEvent) && (source == null || !source.healthy)) {
            valid[index] = false;
            emitState(index, fid, "callback_while_unhealthy");
            return;
        }
        raw[index] = value;
        valid[index] = ParkingCameraProfile.isValidRadarRaw(fid, value);
        timestamps[index] = valid[index] ? android.os.SystemClock.elapsedRealtime() : 0L;
        emit("parking_radar_read", "ok", valid[index], "fid", fid, "raw", value,
                "valid", valid[index], "source", sourceEvent,
                "source_family", source.family);
        emitState(index, fid, sourceEvent);
    }

    private int indexOf(int fid) {
        int[] fids = ParkingCameraProfile.allRadarFids();
        for (int i = 0; i < fids.length; i++) if (fids[i] == fid) return i;
        return -1;
    }

    private Source sourceFor(int fid) {
        for (Source source : sources) {
            for (int sourceFid : source.fids) if (sourceFid == fid) return source;
        }
        return null;
    }

    private boolean allSourcesHealthy() {
        for (Source source : sources) if (!source.healthy) return false;
        return true;
    }

    public static boolean isValidRaw(int raw) {
        return raw >= RAW_MIN && raw <= RAW_MAX;
    }

    public static boolean isValidRaw(int fid, int raw) {
        return ParkingCameraProfile.isValidRadarRaw(fid, raw);
    }

    public static boolean shouldRefresh(boolean started, boolean configured) {
        return started && configured;
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

    private final class Source {
        final String family;
        final String deviceClass;
        final int[] fids;
        Object device;
        Object listener;
        Method unregisterListener;
        Method readValue;
        boolean healthy;

        Source(String family, String deviceClass, int[] fids) {
            this.family = family;
            this.deviceClass = deviceClass;
            this.fids = fids;
        }

        void register() {
            if (!shouldRefresh(started, configured) || healthy) return;
            try {
                Class<?> listenerType = Class.forName(LISTENER_CLASS);
                Class<?> eventType = Class.forName("android.hardware.IBYDAutoEvent");
                Method getEventId = eventType.getMethod("getEventType");
                Method getEventValue = eventType.getMethod("getValue");
                listener = Proxy.newProxyInstance(
                        ParkingRadarRuntime.class.getClassLoader(),
                        new Class<?>[]{listenerType},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                if ("toString".equals(method.getName())) {
                                    return "ParkingRadarListener-" + family;
                                }
                                if ("hashCode".equals(method.getName())) {
                                    return System.identityHashCode(proxy);
                                }
                                if ("equals".equals(method.getName())) {
                                    return args != null && proxy == args[0];
                                }
                            }
                            if ("onDataChanged".equals(method.getName())
                                    && args != null && args.length > 0 && args[0] != null) {
                                int fid = ((Number) getEventId.invoke(args[0])).intValue();
                                int value = ((Number) getEventValue.invoke(args[0])).intValue();
                                handler.post(() -> {
                                    if (listener == proxy) acceptValue(this, fid, value, "callback");
                                });
                            } else if ("onError".equals(method.getName())) {
                                int code = args != null && args.length > 0
                                        && args[0] instanceof Number
                                        ? ((Number) args[0]).intValue() : -1;
                                String message = args != null && args.length > 1
                                        ? String.valueOf(args[1]) : "unknown";
                                handler.post(() -> {
                                    if (listener == proxy) fail(code, message);
                                });
                            }
                            return null;
                        });
                Class<?> type = Class.forName(deviceClass);
                device = getInstance(type);
                readValue = type.getMethod("get", int[].class, Class.class);
                Method register = findListenerMethod(device, "registerListener", true, listener);
                unregisterListener = findListenerMethod(device, "unregisterListener", false, listener);
                register.invoke(device, listener, fids);
                healthy = true;
                emitListener("registered", "", false);
                readAll("initial_read");
            } catch (Throwable error) {
                healthy = false;
                invalidate(fids);
                emitListener("registration_error", summary(error), false);
                for (int fid : fids) emitState(indexOf(fid), fid, "registration_error");
            }
        }

        void stop(String reason) {
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
            healthy = false;
            invalidate(fids);
            emitListener(reason, error, unregistered);
            unregisterListener = null;
            readValue = null;
            listener = null;
            device = null;
        }

        void readAll(String sourceEvent) {
            if (!shouldRefresh(started, configured) || !healthy
                    || readValue == null || device == null) return;
            for (int fid : fids) {
                try {
                    Object value = readValue.invoke(device, new int[]{fid}, Integer.TYPE);
                    acceptValue(this, fid, integerValue(value), sourceEvent);
                } catch (Throwable error) {
                    int index = indexOf(fid);
                    if (index >= 0) {
                        valid[index] = false;
                        timestamps[index] = 0L;
                    }
                    emit("parking_radar_read", "ok", false, "fid", fid,
                            "source", sourceEvent, "source_family", family,
                            "error", summary(error));
                    emitState(index, fid, sourceEvent + "_error");
                }
            }
        }

        void fail(int code, String message) {
            healthy = false;
            invalidate(fids);
            emitListener("error", code + ": " + message, false);
            for (int fid : fids) emitState(indexOf(fid), fid, "listener_error");
        }

        void emitListener(String action, String error, boolean unregistered) {
            boolean ok = error.isEmpty() && (!"status".equals(action) || healthy);
            emit("parking_radar_listener", "action", action, "ok", ok,
                    "family", family, "device_class", deviceClass,
                    "configured", configured, "started", started,
                    "listener_ok", healthy, "unregistered", unregistered,
                    "fids", fids, "error", error);
        }
    }

    private Object getInstance(Class<?> deviceType) throws Exception {
        try {
            return deviceType.getMethod("getInstance", Context.class).invoke(null, context);
        } catch (NoSuchMethodException ignored) {
            return deviceType.getMethod("getInstance").invoke(null);
        }
    }
}
