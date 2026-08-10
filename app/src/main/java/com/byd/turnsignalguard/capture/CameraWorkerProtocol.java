package com.byd.turnsignalguard.capture;

import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;

final class CameraWorkerProtocol {
    static final String DESCRIPTOR =
            "com.byd.turnsignalguard.capture.ICameraWorker";
    static final String CALLBACK_DESCRIPTOR = DESCRIPTOR + ".Callback";
    static final int VERSION = 1;

    static final int TX_HANDSHAKE = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_DISCOVER = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_OPEN_GROUP = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_OPEN_DIRECT = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_CLOSE_OWNER = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int TX_CLOSE_ALL = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int TX_DEBUG_SURFACE_PROBE = IBinder.FIRST_CALL_TRANSACTION + 7;
    static final int CB_EVENT = IBinder.FIRST_CALL_TRANSACTION;

    enum Owner {
        ACTIVITY("activity"),
        OVERLAY("overlay"),
        REVERSE("reverse");

        private final String wireName;

        Owner(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }

        static Owner fromWire(String value) {
            for (Owner owner : values()) {
                if (owner.wireName.equals(value)) return owner;
            }
            return null;
        }
    }

    static final int MAX_SURFACES = 4;

    private CameraWorkerProtocol() {}

    static boolean allowedOwner(String owner) {
        return Owner.fromWire(owner) != null;
    }

    static boolean allowedTag(String tag) {
        return "pano_h".equals(tag) || "pano_l".equals(tag)
                || "apa".equals(tag) || "byd_apa".equals(tag);
    }

    static void requireIndex(int index) {
        if (index < 0 || index > 4) {
            throw new IllegalArgumentException("Preview index must be 0..4");
        }
    }

    static void writeSurfaces(Parcel data, Surface[] surfaces, int[] indexes) {
        if (surfaces == null || indexes == null || surfaces.length == 0
                || surfaces.length > MAX_SURFACES || surfaces.length != indexes.length) {
            throw new IllegalArgumentException("camera Surface/index count mismatch");
        }
        data.writeInt(surfaces.length);
        for (int i = 0; i < surfaces.length; i++) {
            Surface surface = surfaces[i];
            requireIndex(indexes[i]);
            if (surface == null || !surface.isValid()) {
                throw new IllegalArgumentException("camera Surface is invalid");
            }
            surface.writeToParcel(data, 0);
            data.writeInt(indexes[i]);
        }
    }

    static SurfaceBatch readSurfaces(Parcel data) {
        int count = data.readInt();
        if (count <= 0 || count > MAX_SURFACES) {
            throw new IllegalArgumentException("camera Surface count must be 1..4");
        }
        Surface[] surfaces = new Surface[count];
        int[] indexes = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                surfaces[i] = Surface.CREATOR.createFromParcel(data);
                indexes[i] = data.readInt();
                requireIndex(indexes[i]);
                if (surfaces[i] == null || !surfaces[i].isValid()) {
                    throw new IllegalArgumentException("camera Surface is invalid");
                }
            }
            return new SurfaceBatch(surfaces, indexes);
        } catch (Throwable error) {
            release(surfaces);
            throw error;
        }
    }

    static void release(Surface[] surfaces) {
        if (surfaces == null) return;
        for (Surface surface : surfaces) {
            if (surface != null) surface.release();
        }
    }

    static final class Handshake {
        final int protocol;
        final int build;
        final int pid;
        final long workerEpoch;

        Handshake(int protocol, int build, int pid, long workerEpoch) {
            this.protocol = protocol;
            this.build = build;
            this.pid = pid;
            this.workerEpoch = workerEpoch;
        }
    }

    static final class SurfaceBatch {
        final Surface[] surfaces;
        final int[] indexes;

        SurfaceBatch(Surface[] surfaces, int[] indexes) {
            this.surfaces = surfaces;
            this.indexes = indexes;
        }
    }
}
