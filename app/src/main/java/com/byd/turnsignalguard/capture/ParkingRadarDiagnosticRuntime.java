package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Read-only listener for candidate parking-radar feature families. */
final class ParkingRadarDiagnosticRuntime {
    private static final String RADAR_DEVICE_CLASS =
            "android.hardware.bydauto.radar.BYDAutoRadarDevice";
    private static final String ADAS_DEVICE_CLASS =
            "android.hardware.bydauto.adas.BYDAutoADASDevice";
    private static final String LISTENER_CLASS = "android.hardware.IBYDAutoListener";

    private static final int[] RADAR_DISTANCE_FIDS = ParkingCameraProfile.allRadarFids();
    private static final int[] PROBE_STATE_FIDS = {
            0x99000071, 0x99000072, 0x99000073, 0x99000074,
            0x99000075, 0x99000076, 0x99000077, 0x99000078
    };
    private static final int[] SDW_DISTANCE_FIDS = {
            0x36500008, 0x36500010, 0x36500018, 0x36500020,
            0x36500028, 0x36500030, 0x36500038, 0x36500040
    };
    private static final int[] SECTION_DISTANCE_FIDS = {
            0x1EC00008, 0x1EC00010, 0x1EC00018, 0x1EC00020,
            0x1EC00028, 0x1EC00030, 0x1EC00038, 0x1EC00040,
            0x1EC00048, 0x1EC00050, 0x1EC00058, 0x1EC00060,
            0x1EC00068, 0x1EC00070, 0x1EC00078, 0x1EC00080
    };

    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final Source[] sources;
    private boolean started;

    ParkingRadarDiagnosticRuntime(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        if (context == null || handler == null || eventSink == null) {
            throw new IllegalArgumentException("null diagnostic runtime argument");
        }
        this.handler = handler;
        this.eventSink = eventSink;
        sources = new Source[]{
                new Source(context, "radar_distance", RADAR_DEVICE_CLASS, RADAR_DISTANCE_FIDS),
                new Source(context, "probe_state", RADAR_DEVICE_CLASS, PROBE_STATE_FIDS),
                new Source(context, "adas_sdw_distance", ADAS_DEVICE_CLASS, SDW_DISTANCE_FIDS),
                new Source(context, "adas_section_distance",
                        ADAS_DEVICE_CLASS, SECTION_DISTANCE_FIDS)
        };
    }

    void start() {
        runOnHandler(() -> {
            if (started) return;
            started = true;
            for (Source source : sources) source.start();
        });
    }

    void stop() {
        runOnHandler(() -> {
            if (!started) return;
            started = false;
            for (Source source : sources) source.stop();
        });
    }

    void reportStatus() {
        runOnHandler(() -> {
            for (Source source : sources) source.reportStatus();
        });
    }

    static int[] probeStateFids() {
        return PROBE_STATE_FIDS.clone();
    }

    static int[] radarDistanceFids() {
        return RADAR_DISTANCE_FIDS.clone();
    }

    static int[] sdwDistanceFids() {
        return SDW_DISTANCE_FIDS.clone();
    }

    static int[] sectionDistanceFids() {
        return SECTION_DISTANCE_FIDS.clone();
    }

    private void runOnHandler(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private final class Source {
        private final Context context;
        private final String family;
        private final String deviceClass;
        private final int[] fids;
        private Object device;
        private Object listener;
        private Method unregisterListener;
        private Method readValue;
        private boolean healthy;

        Source(Context context, String family, String deviceClass, int[] fids) {
            this.context = context;
            this.family = family;
            this.deviceClass = deviceClass;
            this.fids = fids;
        }

        void start() {
            try {
                Class<?> listenerType = Class.forName(LISTENER_CLASS);
                Class<?> eventType = Class.forName("android.hardware.IBYDAutoEvent");
                Method getEventId = eventType.getMethod("getEventType");
                Method getEventValue = eventType.getMethod("getValue");
                listener = Proxy.newProxyInstance(
                        ParkingRadarDiagnosticRuntime.class.getClassLoader(),
                        new Class<?>[]{listenerType},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                if ("toString".equals(method.getName())) {
                                    return "ParkingRadarDiagnosticListener-" + family;
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
                                int raw = ((Number) getEventValue.invoke(args[0])).intValue();
                                handler.post(() -> {
                                    if (started && listener == proxy) {
                                        emitValue(fid, raw, "callback");
                                    }
                                });
                            } else if ("onError".equals(method.getName())) {
                                int code = args != null && args.length > 0
                                        && args[0] instanceof Number
                                        ? ((Number) args[0]).intValue() : -1;
                                String message = args != null && args.length > 1
                                        ? String.valueOf(args[1]) : "unknown";
                                handler.post(() -> {
                                    if (listener == proxy) {
                                        healthy = false;
                                        emitListener("error", false,
                                                code + ": " + message, false);
                                    }
                                });
                            }
                            return null;
                        });

                Class<?> type = Class.forName(deviceClass);
                device = getInstance(type, context);
                readValue = type.getMethod("get", int[].class, Class.class);
                Method register = ParkingRadarRuntime.findListenerMethod(
                        device, "registerListener", true, listener);
                unregisterListener = ParkingRadarRuntime.findListenerMethod(
                        device, "unregisterListener", false, listener);
                register.invoke(device, listener, fids);
                healthy = true;
                emitListener("registered", true, "", false);
            } catch (Throwable error) {
                healthy = false;
                emitListener("registration_error", false, summary(error), false);
            }
        }

        void stop() {
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
            emitListener("stopped", error.isEmpty(), error, unregistered);
            unregisterListener = null;
            readValue = null;
            listener = null;
            device = null;
        }

        void reportStatus() {
            emitListener("status", healthy, "", false);
            if (device != null && readValue != null) readAll("status_read");
        }

        private void readAll(String source) {
            for (int fid : fids) {
                try {
                    Object value = readValue.invoke(device, new int[]{fid}, Integer.TYPE);
                    emitValue(fid, ParkingRadarRuntime.integerValue(value), source);
                } catch (Throwable error) {
                    emit("parking_radar_diagnostic", "ok", false,
                            "family", family, "fid", fid, "fid_hex", hex(fid),
                            "source_event", source, "error", summary(error));
                }
            }
        }

        private void emitValue(int fid, int raw, String source) {
            emit("parking_radar_diagnostic", "ok", true,
                    "family", family, "fid", fid, "fid_hex", hex(fid),
                    "raw", raw, "source_event", source, "error", "");
        }

        private void emitListener(
                String action, boolean ok, String error, boolean unregistered) {
            emit("parking_radar_diagnostic_listener", "action", action,
                    "ok", ok, "family", family, "device_class", deviceClass,
                    "fids", fids, "listener_ok", healthy,
                    "unregistered", unregistered, "error", error);
        }
    }

    private static Object getInstance(Class<?> type, Context context) throws Exception {
        try {
            return type.getMethod("getInstance", Context.class).invoke(null, context);
        } catch (NoSuchMethodException ignored) {
            return type.getMethod("getInstance").invoke(null);
        }
    }

    private static String hex(int fid) {
        return String.format(Locale.US, "0x%08X", fid);
    }

    private static String summary(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
