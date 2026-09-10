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

/** Fixed AutoTark New23 exterior route; no caller-selected vehicle operations. */
final class AvasExteriorRoute {
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

    void naviFocus(boolean on) {
        call(manager, on ? "requestAudioNaviFocus" : "abandonAudioNaviFocus");
    }

    void mute(boolean on) throws Exception {
        if (call(manager, "setStreamMute", 15, on) == null) {
            throw new IllegalStateException("NAV mute operation unavailable");
        }
    }

    void prepare() throws Exception {
        boolean primary = write(3, POSITION_FID, 1, "prepare") >= 0;
        tryWrite(1000, AUX_FID, 1, "prepare_aux");
        boolean optional = exteriorPath(true);
        naviFocus(true);
        event("avas_route_ready", "primary", primary, "optionalSdkAccepted", optional);
        if (!primary) throw new IllegalStateException("Exterior primary route failed");
    }

    void release(AudioFocusRequest focus) throws Exception {
        Exception failure = null;
        boolean interrupted = false;
        try {
            if (write(3, POSITION_FID, 0, "release") < 0) {
                failure = new IllegalStateException("Exterior primary teardown failed");
            }
        } catch (Exception error) {
            failure = error;
        }
        tryWrite(1000, AUX_FID, 0, "release_aux");
        interrupted |= sleep(180);
        exteriorPath(false);
        interrupted |= sleep(60);
        naviFocus(false);
        try {
            if (focus != null) manager.abandonAudioFocusRequest(focus);
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        interrupted |= sleep(800);
        if (interrupted) {
            Thread.currentThread().interrupt();
            InterruptedException error = new InterruptedException("Interrupted during exterior teardown");
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException ignored) {
            return true;
        }
    }

    private boolean exteriorPath(boolean on) {
        int state = on ? 1 : 0;
        Object body = device("bodywork.BYDAutoBodyworkDevice");
        boolean confirmed = false;
        for (int command : new int[]{4150, 4160, 4159}) {
            confirmed |= accepted(call(body, "set", 850, command, state));
        }
        Object audio = device("audio.BYDAutoAudioDevice");
        for (int[] tuple : HAL) confirmed |= accepted(call(audio, "set", tuple[0], tuple[1], state));
        confirmed |= accepted(call(audio, "setKaraokeMode", on ? 3 : 1));
        confirmed |= accepted(call(audio, "setChannel", on ? 2 : 1));
        Object special = device("special.BYDAutoSpecialDevice");
        for (int[] tuple : HAL) call(special, "postEvent", tuple[0], tuple[1], state, null);
        return confirmed;
    }

    private int tryWrite(int device, int fid, int value, String phase) {
        try {
            return write(device, fid, value, phase);
        } catch (Exception failure) {
            event("avas_route_write_error", "phase", phase, "error", failure.toString());
            return Integer.MIN_VALUE;
        }
    }

    private int write(int device, int fid, int value, String phase) throws Exception {
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
            event("avas_route_write", "phase", phase, "device", device,
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

    private Object device(String name) {
        try {
            Class<?> type = Class.forName(SDK + name);
            try {
                return type.getMethod("getInstance", Context.class).invoke(null, context);
            } catch (NoSuchMethodException ignored) {
                return type.getMethod("getInstance").invoke(null);
            }
        } catch (Exception failure) {
            unavailable(name, failure);
            return null;
        }
    }

    private Object call(Object target, String method, Object... arguments) {
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
            event("avas_route_call", "class", target.getClass().getName(), "method", method,
                    "args", Arrays.toString(arguments), "result", String.valueOf(result));
            return found.getReturnType() == void.class ? Boolean.TRUE : result;
        } catch (Exception failure) {
            unavailable(target.getClass().getName() + "." + method, failure);
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
        Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
        event("avas_route_call_unavailable", "operation", operation, "error", String.valueOf(cause));
    }

    private void event(String kind, Object... fields) {
        if (log == null) return;
        try {
            JSONObject event = new JSONObject().put("kind", kind);
            for (int i = 0; i + 1 < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
            log.accept(event);
        } catch (Throwable ignored) {
        }
    }
}
