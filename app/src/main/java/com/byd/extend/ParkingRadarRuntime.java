package com.byd.extend;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
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

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final int[] raw = new int[ParkingCameraProfile.allRadarFids().length];
    private final boolean[] valid = new boolean[raw.length];
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
            for (Source source : sources) source.reportStatus();
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
            unregisterOnHandler("disabled");
            invalidateAll();
            emitAllStates("disabled");
            return;
        }
        if (started) registerOnHandler();
    }

    private void stopOnHandler() {
        started = false;
        configured = false;
        unregisterOnHandler("stopped");
        invalidateAll();
        emitAllStates("stopped");
    }

    private void registerOnHandler() {
        if (!shouldCollect(started, configured)) return;
        for (Source source : sources) source.register();
    }

    private void recoverSourcesOnHandler() {
        if (!shouldCollect(started, configured)) return;
        for (Source source : sources) {
            if (source.healthy) continue;
            source.stop("recovery_retry");
            source.register();
        }
    }

    private void unregisterOnHandler(String reason) {
        for (Source source : sources) source.stop(reason);
    }

    private boolean allSourcesHealthy() {
        for (Source source : sources) if (!source.healthy) return false;
        return true;
    }

    private void invalidateAll() {
        for (int i = 0; i < valid.length; i++) {
            raw[i] = -1;
            valid[i] = false;
        }
    }

    private void invalidate(int[] fids) {
        for (int fid : fids) {
            int index = indexOf(fid);
            if (index >= 0) {
                valid[index] = false;
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
                "generation", source == null ? 0L : source.generation,
                "source_event", sourceEvent);
    }

    private void acceptValue(Source source, int fid, int value, String sourceEvent) {
        if (!shouldCollect(started, configured)) return;
        int index = indexOf(fid);
        if (index < 0 || source != sourceFor(fid)) {
            emit("parking_radar_event_ignored", "fid", fid, "raw", value,
                    "reason", "unexpected_fid");
            return;
        }
        raw[index] = value;
        valid[index] = ParkingCameraProfile.isValidRadarRaw(fid, value);
        source.cachedRaw[source.indexOf(fid)] = value;
        source.cachedValid[source.indexOf(fid)] = valid[index];
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

    public static boolean isValidRaw(int raw) {
        return raw >= RAW_MIN && raw <= RAW_MAX;
    }

    public static boolean isValidRaw(int fid, int raw) {
        return ParkingCameraProfile.isValidRadarRaw(fid, raw);
    }

    public static boolean shouldCollect(boolean started, boolean configured) {
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
        volatile Object listener;
        Method unregisterListener;
        Method readValue;
        volatile boolean healthy;
        volatile long generation;
        long generationSequence;
        volatile boolean snapshotReady;
        volatile long callbackErrorGeneration;
        final int[] cachedRaw;
        final boolean[] cachedValid;

        Source(String family, String deviceClass, int[] fids) {
            this.family = family;
            this.deviceClass = deviceClass;
            this.fids = fids;
            cachedRaw = new int[fids.length];
            cachedValid = new boolean[fids.length];
            Arrays.fill(cachedRaw, -1);
        }

        void register() {
            if (!shouldCollect(started, configured) || healthy) return;
            final long listenerGeneration = ++generationSequence;
            generation = listenerGeneration;
            snapshotReady = false;
            callbackErrorGeneration = 0L;
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
                                final boolean beforeSnapshot = !snapshotReady;
                                handler.post(() -> {
                                    if (!beforeSnapshot && listener == proxy
                                            && generation == listenerGeneration
                                            && healthy && snapshotReady) {
                                        acceptValue(this, fid, value, "callback");
                                    }
                                });
                            } else if ("onError".equals(method.getName())) {
                                int code = args != null && args.length > 0
                                        && args[0] instanceof Number
                                        ? ((Number) args[0]).intValue() : -1;
                                String message = args != null && args.length > 1
                                        ? String.valueOf(args[1]) : "unknown";
                                callbackErrorGeneration = listenerGeneration;
                                handler.post(() -> {
                                    if (listener == proxy && generation == listenerGeneration) {
                                        fail(code, message);
                                    }
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
                readInitial(listenerGeneration);
            } catch (Throwable error) {
                healthy = false;
                snapshotReady = false;
                invalidateFamily("registration_error");
                emitListener("registration_error", summary(error), false);
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
            generation = ++generationSequence;
            healthy = false;
            snapshotReady = false;
            invalidateFamily(reason);
            emitListener(reason, error, unregistered);
            unregisterListener = null;
            readValue = null;
            listener = null;
            device = null;
        }

        void readInitial(long listenerGeneration) {
            if (!shouldCollect(started, configured) || !healthy
                    || readValue == null || device == null) return;
            int[] pendingRaw = new int[fids.length];
            boolean[] pendingValid = new boolean[fids.length];
            Arrays.fill(pendingRaw, -1);
            for (int i = 0; i < fids.length; i++) {
                int fid = fids[i];
                try {
                    Object value = readValue.invoke(device, new int[]{fid}, Integer.TYPE);
                    int rawValue = integerValue(value);
                    pendingRaw[i] = rawValue;
                    pendingValid[i] = ParkingCameraProfile.isValidRadarRaw(fid, rawValue);
                } catch (Throwable ignored) {
                    // The generation snapshot publishes this FID as invalid and fails closed.
                }
            }
            if (!canCommitInitialSnapshot(listenerGeneration, callbackErrorGeneration,
                    generation, healthy, shouldCollect(started, configured))) {
                return;
            }
            System.arraycopy(pendingRaw, 0, cachedRaw, 0, fids.length);
            System.arraycopy(pendingValid, 0, cachedValid, 0, fids.length);
            for (int i = 0; i < fids.length; i++) {
                int index = indexOf(fids[i]);
                raw[index] = cachedRaw[i];
                valid[index] = cachedValid[i];
            }
            snapshotReady = true;
            emitSnapshot("initial_read");
        }

        void fail(int code, String message) {
            healthy = false;
            snapshotReady = false;
            invalidateFamily("listener_error");
            emitListener("error", code + ": " + message, false);
        }

        void reportStatus() {
            emitListener("status", "", false);
            emitSnapshot("status");
        }

        private int indexOf(int fid) {
            for (int i = 0; i < fids.length; i++) if (fids[i] == fid) return i;
            return -1;
        }

        private void invalidateFamily(String sourceEvent) {
            invalidate(fids);
            Arrays.fill(cachedValid, false);
            emitSnapshot(sourceEvent);
        }

        private void emitSnapshot(String sourceEvent) {
            emit("parking_radar_snapshot", "family", family,
                    "device_class", deviceClass, "generation", generation,
                    "configured", configured, "started", started,
                    "listener_ok", healthy && snapshotReady,
                    "snapshot_ready", snapshotReady, "source_event", sourceEvent,
                    "fids", toJson(fids), "raw", toJson(cachedRaw),
                    "valid", toJson(cachedValid));
        }

        private JSONArray toJson(int[] values) {
            JSONArray array = new JSONArray();
            for (int value : values) array.put(value);
            return array;
        }

        private JSONArray toJson(boolean[] values) {
            JSONArray array = new JSONArray();
            for (boolean value : values) array.put(value);
            return array;
        }

        void emitListener(String action, String error, boolean unregistered) {
            boolean ok = error.isEmpty() && (!"status".equals(action) || healthy);
            emit("parking_radar_listener", "action", action, "ok", ok,
                    "family", family, "device_class", deviceClass,
                    "configured", configured, "started", started,
                    "listener_ok", healthy, "unregistered", unregistered,
                    "generation", generation, "snapshot_ready", snapshotReady,
                    "fids", fids, "error", error);
        }
    }

    static boolean canCommitInitialSnapshot(
            long expectedGeneration, long callbackErrorGeneration,
            long activeGeneration, boolean healthy, boolean running) {
        return running && healthy && expectedGeneration > 0L
                && expectedGeneration == activeGeneration
                && callbackErrorGeneration != expectedGeneration;
    }

    private Object getInstance(Class<?> deviceType) throws Exception {
        try {
            return deviceType.getMethod("getInstance", Context.class).invoke(null, context);
        } catch (NoSuchMethodException ignored) {
            return deviceType.getMethod("getInstance").invoke(null);
        }
    }
}
