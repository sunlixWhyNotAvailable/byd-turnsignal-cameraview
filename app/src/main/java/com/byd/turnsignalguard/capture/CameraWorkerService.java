package com.byd.turnsignalguard.capture;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import org.json.JSONObject;

/** Private :camera process. This is the only application process that owns AVMCamera. */
public final class CameraWorkerService extends Service {
    private static final String TAG = "BydCameraWorker";

    private final Object eventLock = new Object();
    private final CameraWorkerEventIdentity identities = new CameraWorkerEventIdentity();
    private final WorkerBinder binder = new WorkerBinder();
    private CameraHelperMain.HelperBinder backend;
    private IBinder callback;
    private long workerEpoch;

    @Override
    public void onCreate() {
        super.onCreate();
        workerEpoch = newWorkerEpoch(Process.myPid(), SystemClock.elapsedRealtimeNanos());
        backend = CameraHelperMain.HelperBinder.cameraWorker(
                getApplicationContext(), this::forwardWorkerEvent, workerEpoch);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        CameraHelperMain.HelperBinder active = backend;
        backend = null;
        if (active != null) active.shutdownCameraWorker("worker_service_destroyed");
        synchronized (eventLock) {
            callback = null;
            identities.clear();
        }
        super.onDestroy();
    }

    static long newWorkerEpoch(int pid, long elapsedNanos) {
        long value = ((elapsedNanos << 11) ^ (elapsedNanos >>> 17) ^ pid)
                & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }

    private void registerCallback(IBinder value) throws RemoteException {
        if (value == null) throw new IllegalArgumentException("worker callback required");
        synchronized (eventLock) {
            callback = value;
        }
        value.linkToDeath(() -> {
            synchronized (eventLock) {
                if (callback != value) return;
                callback = null;
            }
            CameraHelperMain.HelperBinder active = backend;
            if (active != null) active.shutdownCameraWorker("controller_binder_died");
            stopSelf();
        }, 0);
    }

    private void forwardWorkerEvent(String line) {
        String enriched = enrichEvent(line);
        Log.i(TAG, enriched);
        IBinder target;
        synchronized (eventLock) {
            target = callback;
        }
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraWorkerProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(enriched);
            target.transact(CameraWorkerProtocol.CB_EVENT, data, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable error) {
            Log.e(TAG, "worker callback failed", error);
        } finally {
            data.recycle();
        }
    }

    private String enrichEvent(String line) {
        try {
            JSONObject event = new JSONObject(line == null ? "{}" : line);
            String kind = event.optString("kind", "camera_worker_event");
            String owner = event.optString("camera_owner", "none");
            int requestId = event.optInt("request_id", 0);
            int generation = 0;
            synchronized (eventLock) {
                if (CameraWorkerProtocol.allowedOwner(owner)) {
                    generation = identities.generation(owner, requestId, kind);
                }
                event.put("worker_epoch", workerEpoch);
                if (!event.has("producer_epoch")) {
                    CameraHelperMain.HelperBinder active = backend;
                    event.put("producer_epoch",
                            active == null ? 0 : active.workerProducerEpoch());
                }
                if (!event.has("camera_owner")) event.put("camera_owner", owner);
                if (!event.has("request_id")) event.put("request_id", requestId);
                if (!event.has("consumer_generation")) {
                    event.put("consumer_generation", generation);
                }
                identities.afterEvent(owner, requestId, kind);
            }
            return event.toString();
        } catch (Throwable error) {
            return "{\"kind\":\"camera_worker_json_error\","
                    + "\"source\":\"helper\",\"worker_epoch\":" + workerEpoch
                    + ",\"producer_epoch\":0,\"camera_owner\":\"none\","
                    + "\"request_id\":0,\"consumer_generation\":0}";
        }
    }

    private static int[] readOptionalInts(Parcel data) {
        int count = data.readInt();
        if (count < 0) return null;
        if (count > CameraWorkerProtocol.MAX_SURFACES) {
            throw new IllegalArgumentException("too many camera profiles");
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = data.readInt();
        return values;
    }

    private final class WorkerBinder extends Binder {
        @Override
        protected synchronized boolean onTransact(
                int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            CameraWorkerProtocol.SurfaceBatch batch = null;
            Surface directSurface = null;
            try {
                data.enforceInterface(CameraWorkerProtocol.DESCRIPTOR);
                if (Binder.getCallingUid() != Process.myUid()) {
                    throw new SecurityException("camera worker caller UID rejected");
                }
                CameraHelperMain.HelperBinder active = backend;
                if (active == null) throw new IllegalStateException("camera worker stopped");
                if (code == CameraWorkerProtocol.TX_HANDSHAKE) {
                    reply.writeNoException();
                    reply.writeInt(CameraWorkerProtocol.VERSION);
                    reply.writeInt(BuildConfig.VERSION_CODE);
                    reply.writeInt(Process.myPid());
                    reply.writeLong(workerEpoch);
                    return true;
                }
                if (code == CameraWorkerProtocol.TX_REGISTER_CALLBACK) {
                    registerCallback(data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraWorkerProtocol.TX_DISCOVER) {
                    boolean ready = active.discoverCamera();
                    reply.writeNoException();
                    reply.writeInt(ready ? 1 : 0);
                    return true;
                }
                if (code == CameraWorkerProtocol.TX_DEBUG_SURFACE_PROBE) {
                    if (!BuildConfig.DEBUG) {
                        throw new SecurityException("debug Surface probe disabled");
                    }
                    int requestId = data.readInt();
                    if (requestId <= 0) {
                        throw new IllegalArgumentException("debug request required");
                    }
                    directSurface = Surface.CREATOR.createFromParcel(data);
                    if (directSurface == null || !directSurface.isValid()) {
                        throw new IllegalArgumentException("debug Surface is invalid");
                    }
                    identities.begin(CameraHelperMain.CAMERA_OWNER_ACTIVITY, requestId);
                    forwardWorkerEvent(new JSONObject()
                            .put("kind", "camera_consumer_attached")
                            .put("source", "helper")
                            .put("camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY)
                            .put("request_id", requestId)
                            .put("producer_epoch", 1)
                            .put("debug_surface_valid", true)
                            .toString());
                    directSurface.release();
                    boolean released = !directSurface.isValid();
                    directSurface = null;
                    forwardWorkerEvent(new JSONObject()
                            .put("kind", "camera_closed")
                            .put("source", "helper")
                            .put("camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY)
                            .put("request_id", requestId)
                            .put("producer_epoch", 1)
                            .put("reason", "debug_surface_probe")
                            .put("debug_surface_released", released)
                            .toString());
                    reply.writeNoException();
                    reply.writeString(new JSONObject()
                            .put("kind", "camera_opened")
                            .put("debug_surface_valid", true)
                            .put("debug_surface_released", released)
                            .toString());
                    return true;
                }
                if (code == CameraWorkerProtocol.TX_OPEN_GROUP) {
                    String owner = data.readString();
                    int requestId = data.readInt();
                    String view = data.readString();
                    boolean exclusive = data.readInt() != 0;
                    data.readInt(); // Shell ownership never crosses into :camera.
                    int[] profiles = readOptionalInts(data);
                    batch = CameraWorkerProtocol.readSurfaces(data);
                    if (!CameraWorkerProtocol.allowedOwner(owner)) {
                        throw new IllegalArgumentException("camera owner rejected");
                    }
                    if (view == null || view.length() > 96) {
                        throw new IllegalArgumentException("camera view rejected");
                    }
                    identities.begin(owner, requestId);
                    String result = active.workerOpenGroup(
                            owner, batch.surfaces, batch.indexes, requestId, view,
                            exclusive, false, profiles);
                    if (!cameraOpened(result)) identities.failedOpen(owner, requestId);
                    batch = null;
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == CameraWorkerProtocol.TX_OPEN_DIRECT) {
                    String owner = data.readString();
                    String tag = data.readString();
                    int index = data.readInt();
                    int requestId = data.readInt();
                    boolean exclusive = data.readInt() != 0;
                    directSurface = Surface.CREATOR.createFromParcel(data);
                    if (!CameraWorkerProtocol.allowedOwner(owner)
                            || !CameraWorkerProtocol.allowedTag(tag)) {
                        throw new IllegalArgumentException("direct camera mapping rejected");
                    }
                    CameraWorkerProtocol.requireIndex(index);
                    if (directSurface == null || !directSurface.isValid()) {
                        throw new IllegalArgumentException("camera Surface is invalid");
                    }
                    identities.begin(owner, requestId);
                    String result = active.workerOpenDirect(
                            directSurface, tag, index, owner, requestId, exclusive);
                    if (!cameraOpened(result)) identities.failedOpen(owner, requestId);
                    directSurface = null;
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                if (code == CameraWorkerProtocol.TX_CLOSE_OWNER) {
                    String owner = data.readString();
                    String reason = data.readString();
                    int requestId = data.readInt();
                    if (!CameraWorkerProtocol.allowedOwner(owner)) {
                        throw new IllegalArgumentException("camera owner rejected");
                    }
                    reply.writeNoException();
                    reply.writeString(active.workerCloseOwner(owner, reason, requestId));
                    return true;
                }
                if (code == CameraWorkerProtocol.TX_CLOSE_ALL) {
                    reply.writeNoException();
                    reply.writeString(active.workerCloseAll(data.readString()));
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable error) {
                if (batch != null) CameraWorkerProtocol.release(batch.surfaces);
                if (directSurface != null) directSurface.release();
                reply.writeException(new IllegalStateException(errorMessage(error)));
                return true;
            }
        }
    }

    private static String errorMessage(Throwable error) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        String message = value.getMessage();
        return value.getClass().getSimpleName()
                + (message == null ? "" : ": " + message);
    }

    private static boolean cameraOpened(String result) {
        try {
            return "camera_opened".equals(new JSONObject(result).optString("kind"));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
