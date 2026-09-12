package com.byd.extend;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.Parcel;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/** Fixed AutoTark CHANNEL0 exterior route; no caller-selected vehicle operations. */
final class AvasExteriorRoute {
    private static final int NO_PRIMARY_DEVICE = -1;
    private static final int LEGACY_EXTERIOR_DEVICE = 3;
    private static final int CHANNEL0_DEVICE = 1000;
    private static final int POSITION_FID = 0xAA000282;
    private static final int AUX_FID = 0x94E88A89;
    private static final String SDK = "android.hardware.bydauto.";
    private static final int[][] HAL = {{449, 14}, {433, 67}, {850, 4150}};

    private final Context context;
    private final AudioManager manager;
    private final Consumer<JSONObject> log;
    private IBinder autoservice;

    AvasExteriorRoute(Context context, Consumer<JSONObject> log) {
        this.context = context;
        this.log = log;
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    void naviFocus(boolean on) { naviFocus(on, null); }

    void naviFocus(boolean on, AvasAudioDiagnostics.Context diagnostics) {
        call(diagnostics, manager, on ? "requestAudioNaviFocus" : "abandonAudioNaviFocus");
    }

    void mute(boolean on) throws Exception { mute(on, null); }

    void mute(boolean on, AvasAudioDiagnostics.Context diagnostics) throws Exception {
        if (call(diagnostics, manager, "setStreamMute", 15, on) == null) {
            throw new IllegalStateException("NAV mute operation unavailable");
        }
        event(diagnostics, "avas_mute_command", "muted", on);
    }

    void prepare(AudioFocusRequest focus, AvasAudioDiagnostics.Context diagnostics,
            AvasNavigationRecovery.Marker marker)
            throws Exception {
        boolean primary = acquirePrimary(marker,
                () -> tryWrite(CHANNEL0_DEVICE, POSITION_FID, 1, "prepare", diagnostics));
        tryWrite(CHANNEL0_DEVICE, AUX_FID, 1, "prepare_aux", diagnostics);
        boolean optional = exteriorPath(true, diagnostics);
        naviFocus(true, diagnostics);
        int focusResult = manager.requestAudioFocus(focus);
        event(diagnostics, "avas_focus_request", "result", focusResult, "phase", "post_route");
        boolean ready = routeAccepted(primary, optional,
                focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        // Command acceptance only; physical amplifier readiness is not observable here.
        event(diagnostics, "avas_route_ready", "primary", primary,
                "optionalSdkAccepted", optional, "focus_result", focusResult,
                "accepted", ready, "amplifier_ready", "unknown");
        if (!ready) throw new IllegalStateException("Exterior route and focus were not accepted");
    }

    static boolean acquirePrimary(AvasNavigationRecovery.Marker marker,
            AvasNavigationRecovery.Command command) throws Exception {
        marker.write(AvasShellSettings.EXTERIOR_CHANNEL0_DIRTY);
        int status = writePrimary(command);
        if (status < 0 && status != Integer.MIN_VALUE) {
            // Both primary replies rejected the request, but shared SDK setup still follows.
            marker.write(AvasShellSettings.EXTERIOR_CHANNEL0_SHARED);
        }
        return status >= 0;
    }

    static int writePrimary(AvasNavigationRecovery.Command command) throws Exception {
        boolean uncertain = false;
        int status = Integer.MIN_VALUE;
        // AutoTark's profile write falls back once to the identical fixed CHANNEL0 write.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                status = command.run();
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception missingReply) {
                status = Integer.MIN_VALUE;
            }
            if (status >= 0) return status;
            uncertain |= status == Integer.MIN_VALUE;
        }
        return uncertain ? Integer.MIN_VALUE : status;
    }

    static boolean routeAccepted(boolean primary, boolean sdk, boolean focus) {
        // The selected controller-series23 reference accepts any of these existing paths.
        return primary || sdk || focus;
    }

    void release(AudioFocusRequest focus, AvasAudioDiagnostics.Context diagnostics, int dirty)
            throws Exception {
        Exception failure = null;
        boolean interrupted = false;
        int primaryDevice = releasePrimaryDevice(dirty);
        if (primaryDevice != NO_PRIMARY_DEVICE) {
            try {
                int status = dirty == AvasShellSettings.EXTERIOR_CHANNEL0_DIRTY
                        ? writePrimary(() -> tryWrite(primaryDevice, POSITION_FID, 0,
                                "release", diagnostics))
                        : write(primaryDevice, POSITION_FID, 0, "release", diagnostics);
                if (status < 0) {
                    failure = new IllegalStateException("Exterior primary teardown failed");
                }
            } catch (Exception error) {
                failure = error;
            }
        }
        if (dirty != AvasShellSettings.EXTERIOR_CHANNEL0_UNACQUIRED) {
            tryWrite(CHANNEL0_DEVICE, AUX_FID, 0, "release_aux", diagnostics);
            interrupted |= sleep(180);
            exteriorPath(false, diagnostics);
            interrupted |= sleep(60);
        }
        naviFocus(false, diagnostics);
        try {
            if (focus != null) manager.abandonAudioFocusRequest(focus);
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (dirty != AvasShellSettings.EXTERIOR_CHANNEL0_UNACQUIRED) {
            interrupted |= sleep(800);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            InterruptedException error = new InterruptedException("Interrupted during exterior teardown");
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    static int releasePrimaryDevice(int dirty) {
        if (dirty == AvasShellSettings.EXTERIOR_DIRTY) return LEGACY_EXTERIOR_DEVICE;
        if (dirty == AvasShellSettings.EXTERIOR_CHANNEL0_DIRTY) return CHANNEL0_DEVICE;
        if (dirty == AvasShellSettings.EXTERIOR_CHANNEL0_UNACQUIRED
                || dirty == AvasShellSettings.EXTERIOR_CHANNEL0_SHARED) return NO_PRIMARY_DEVICE;
        throw new IllegalArgumentException("Invalid exterior route marker " + dirty);
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException ignored) {
            return true;
        }
    }

    private boolean exteriorPath(boolean on, AvasAudioDiagnostics.Context diagnostics) {
        int state = on ? 1 : 0;
        Object body = device("bodywork.BYDAutoBodyworkDevice", diagnostics);
        boolean confirmed = false;
        for (int command : new int[]{4150, 4160, 4159}) {
            confirmed |= accepted(call(diagnostics, body, "set", 850, command, state));
        }
        Object audio = device("audio.BYDAutoAudioDevice", diagnostics);
        for (int[] tuple : HAL) confirmed |= accepted(call(diagnostics, audio, "set", tuple[0], tuple[1], state));
        confirmed |= accepted(call(diagnostics, audio, "setKaraokeMode", on ? 3 : 1));
        confirmed |= accepted(call(diagnostics, audio, "setChannel", on ? 2 : 1));
        Object special = device("special.BYDAutoSpecialDevice", diagnostics);
        for (int[] tuple : HAL) call(diagnostics, special, "postEvent", tuple[0], tuple[1], state, null);
        return confirmed;
    }

    private int tryWrite(int device, int fid, int value, String phase,
            AvasAudioDiagnostics.Context diagnostics) {
        try {
            return write(device, fid, value, phase, diagnostics);
        } catch (Exception failure) {
            event(diagnostics, "avas_route_write_error", "phase", phase, "error", failure.toString());
            return Integer.MIN_VALUE;
        }
    }

    private int write(int device, int fid, int value, String phase,
            AvasAudioDiagnostics.Context diagnostics) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder service = service();
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            data.writeInt(device);
            data.writeInt(fid);
            data.writeInt(value);
            if (!service.transact(6, data, reply, 0) || reply.dataAvail() < 4) {
                throw new IllegalStateException("Empty exterior route reply");
            }
            int status = reply.readInt();
            event(diagnostics, "avas_route_write", "phase", phase, "device", device,
                    "fid", "0x" + Integer.toHexString(fid).toUpperCase(Locale.ROOT),
                    "value", value, "status", status);
            return status;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private synchronized IBinder service() throws Exception {
        if (autoservice == null || !autoservice.isBinderAlive()) {
            autoservice = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "autoservice");
        }
        if (autoservice == null) throw new IllegalStateException("autoservice unavailable");
        return autoservice;
    }

    private Object device(String name, AvasAudioDiagnostics.Context diagnostics) {
        try {
            Class<?> type = Class.forName(SDK + name);
            try {
                return type.getMethod("getInstance", Context.class).invoke(null, context);
            } catch (NoSuchMethodException ignored) {
                return type.getMethod("getInstance").invoke(null);
            }
        } catch (Exception failure) {
            unavailable(diagnostics, name, failure);
            return null;
        }
    }

    private Object call(Object target, String method, Object... arguments) {
        return call(null, target, method, arguments);
    }

    private Object call(AvasAudioDiagnostics.Context diagnostics, Object target,
            String method, Object... arguments) {
        if (target == null) return null;
        try {
            Method found = null;
            for (Class<?> type = target.getClass(); type != null && found == null; type = type.getSuperclass()) {
                for (Method candidate : type.getDeclaredMethods()) {
                    if (candidate.getName().equals(method) && matches(candidate.getParameterTypes(), arguments)) {
                        found = candidate;
                        break;
                    }
                }
            }
            if (found == null) throw new NoSuchMethodException(method);
            found.setAccessible(true);
            Object result = found.invoke(target, arguments);
            event(diagnostics, "avas_route_call", "class", target.getClass().getName(), "method", method,
                    "args", Arrays.toString(arguments), "result", String.valueOf(result));
            return found.getReturnType() == void.class ? Boolean.TRUE : result;
        } catch (Exception failure) {
            unavailable(diagnostics, target.getClass().getName() + "." + method, failure);
            return null;
        }
    }

    private static boolean matches(Class<?>[] parameters, Object[] arguments) {
        if (parameters.length != arguments.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            if (arguments[i] == null) continue;
            if (parameters[i] == int.class && arguments[i] instanceof Integer) continue;
            if (parameters[i] == boolean.class && arguments[i] instanceof Boolean) continue;
            if (!parameters[i].isInstance(arguments[i])) return false;
        }
        return true;
    }

    private static boolean accepted(Object value) {
        return value instanceof Number ? ((Number) value).intValue() >= 0 : Boolean.TRUE.equals(value);
    }

    private void unavailable(String operation, Exception failure) {
        unavailable(null, operation, failure);
    }

    private void unavailable(AvasAudioDiagnostics.Context diagnostics, String operation,
            Exception failure) {
        Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
        event(diagnostics, "avas_route_call_unavailable", "operation", operation,
                "error", String.valueOf(cause));
    }

    private void event(String kind, Object... fields) {
        event((AvasAudioDiagnostics.Context) null, kind, fields);
    }

    private void event(AvasAudioDiagnostics.Context diagnostics, String kind, Object... fields) {
        if (log == null) return;
        try {
            JSONObject event = new JSONObject().put("kind", kind);
            if (diagnostics != null) {
                event.put("request", diagnostics.requestId).put("profile", diagnostics.profile)
                        .put("source", diagnostics.source).put("helper_pid", diagnostics.helperPid)
                        .put("accepted_t_ms", diagnostics.acceptedMs)
                        .put("enqueued_t_ms", diagnostics.enqueuedMs);
            }
            for (int i = 0; i + 1 < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
            log.accept(event);
        } catch (Throwable ignored) {
        }
    }
}
