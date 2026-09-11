package com.byd.extend;

import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.Parcel;

import org.json.JSONObject;

import java.util.Locale;
import java.util.function.Consumer;

/** Fixed OEM in-cabin navigation route used only by the file-list audition action. */
final class AvasNavigationRoute {
    static final int DEVICE = 1000;
    static final int POSITION_FID = 0xAA000282;

    private final AudioManager manager;
    private final Consumer<JSONObject> log;
    private IBinder autoservice;

    AvasNavigationRoute(AudioManager manager, Consumer<JSONObject> log) {
        this.manager = manager;
        this.log = log;
    }

    void prepare() throws Exception {
        int status = write(1, "prepare");
        if (status < 0) throw new IllegalStateException("Navigation route prepare failed");
    }

    void release(AudioFocusRequest focus) throws Exception {
        Exception failure = null;
        try {
            if (write(0, "release") < 0) {
                failure = new IllegalStateException("Navigation route release failed");
            }
        } catch (Exception error) {
            failure = error;
        }
        try {
            if (focus != null) manager.abandonAudioFocusRequest(focus);
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private int write(int value, String phase) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder service = service();
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            data.writeInt(DEVICE);
            data.writeInt(POSITION_FID);
            data.writeInt(value);
            if (!service.transact(6, data, reply, 0) || reply.dataAvail() < 4) {
                throw new IllegalStateException("Empty navigation route reply");
            }
            int status = reply.readInt();
            event("avas_nav_route_write", "phase", phase, "device", DEVICE,
                    "fid", "0x" + Integer.toHexString(POSITION_FID).toUpperCase(Locale.ROOT),
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

    private void event(String kind, Object... fields) {
        if (log == null) return;
        try {
            JSONObject event = new JSONObject().put("kind", kind);
            for (int index = 0; index + 1 < fields.length; index += 2) {
                event.put(String.valueOf(fields[index]), fields[index + 1]);
            }
            log.accept(event);
        } catch (Throwable ignored) {
        }
    }
}
