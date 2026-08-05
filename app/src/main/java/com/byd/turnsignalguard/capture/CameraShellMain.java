package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Surface;

import org.json.JSONObject;

import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class CameraShellMain {
    private static final long LOG_FLUSH_DELAY_MS = 250;

    private CameraShellMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "usage: CameraShellMain <appUid> <versionCode>");
        int appUid = Integer.parseInt(args[0]);
        int versionCode = Integer.parseInt(args[1]);
        OwnerLock owner = OwnerLock.acquire();
        if (owner == null) return;
        prepareMainLooper();
        Context context = systemContext();
        Handler handler = new Handler(Looper.getMainLooper());
        ShellBinder binder = new ShellBinder(context, handler, appUid, versionCode);
        binder.attachInterface(null, CameraShellProtocol.DESCRIPTOR);
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method addService = serviceManager.getMethod("addService", String.class, IBinder.class);
            addService.invoke(null, CameraShellProtocol.SERVICE_NAME, binder);
            System.out.println("READY pid=" + Process.myPid()
                    + " protocol=" + CameraShellProtocol.VERSION
                    + " build=" + versionCode);
            System.out.flush();
            Looper.loop();
        } finally {
            try {
                binder.closeAll("process_exit");
            } finally {
                System.out.flush();
                owner.close();
            }
        }
    }

    private static final class ShellBinder extends Binder {
        private final Handler handler;
        private final int appUid;
        private final int versionCode;
        private final StockAvmPreview preview;
        private final ShellCameraOverlay[] overlays = new ShellCameraOverlay[CameraProfile.COUNT];
        private final ShellReverseCameraOverlay reverseOverlay;
        private IBinder callback;
        private int activePreviewRequestId;
        private boolean stdoutFlushScheduled;

        ShellBinder(Context context, Handler handler, int appUid, int versionCode) {
            this.handler = handler;
            this.appUid = appUid;
            this.versionCode = versionCode;
            preview = new StockAvmPreview(context, (stage, detail) -> {
                emit("stock_avm_stage", "stage", stage, "detail", detail);
                if ("apply_vehicle_config".equals(stage)) {
                    emit("camera_config_applied", "detail", detail);
                }
            });
            for (CameraProfile profile : CameraProfile.values()) {
                overlays[profile.id] = new ShellCameraOverlay(context, profile.id, this::emit);
            }
            reverseOverlay = new ShellReverseCameraOverlay(context, this::emit);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (!CameraShellProtocol.isCallerAllowed(Binder.getCallingUid(), appUid)) {
                if (reply != null) reply.writeException(new SecurityException("caller uid denied"));
                return true;
            }
            try {
                data.enforceInterface(CameraShellProtocol.DESCRIPTOR);
                if (code == CameraShellProtocol.TX_PING) {
                    reply.writeNoException();
                    reply.writeInt(CameraShellProtocol.VERSION);
                    reply.writeInt(versionCode);
                    reply.writeInt(Process.myPid());
                    return true;
                }
                if (code == CameraShellProtocol.TX_REGISTER_CALLBACK) {
                    registerCallback(data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OPEN) {
                    if (reverseOverlay.isOpen()) {
                        throw new IllegalStateException("reverse overlay has camera priority");
                    }
                    Surface surface = Surface.CREATOR.createFromParcel(data);
                    try {
                        int viewpoint = data.readInt();
                        boolean horizontal = data.readInt() != 0;
                        boolean stockDewarp = data.readInt() != 0;
                        int requestId = data.readInt();
                        if (requestId <= 0) {
                            throw new IllegalArgumentException("camera request id required");
                        }
                        StockAvmPreview.Config config =
                                StockAvmPreview.Config.readFromParcel(data);
                        if (!StockAvmPreview.isAllowedViewpoint(viewpoint)) {
                            throw new IllegalArgumentException("viewpoint not whitelisted");
                        }
                        FutureTask<Surface> openTask = new FutureTask<>(
                                () -> openPreview(
                                        surface, viewpoint, config, horizontal, stockDewarp,
                                        requestId));
                        if (!handler.post(openTask)) {
                            throw new IllegalStateException("camera main handler rejected open");
                        }
                        Surface inputSurface = openTask.get(15, TimeUnit.SECONDS);
                        reply.writeNoException();
                        inputSurface.writeToParcel(reply, 0);
                    } catch (Throwable error) {
                        if (!preview.isOpen()) surface.release();
                        throw error;
                    }
                    return true;
                }
                if (code == CameraShellProtocol.TX_CLOSE) {
                    String reason = data.readString();
                    int requestId = data.readInt();
                    runOnMain(() -> {
                        closePreview(reason, requestId);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_SHUTDOWN) {
                    try {
                        runOnMain(() -> {
                            closeAll("controller_shutdown");
                            emit("camera_shell_shutdown", "reason", "controller_request");
                            return null;
                        });
                    } finally {
                        handler.post(() -> Looper.myLooper().quitSafely());
                    }
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_PREPARE) {
                    if (reverseOverlay.isOpen()) {
                        throw new IllegalStateException("reverse overlay has camera priority");
                    }
                    CameraShellProtocol.OverlaySpec spec =
                            CameraShellProtocol.OverlaySpec.readFromParcel(data);
                    runOnMain(() -> {
                        overlay(spec.cameraId).prepare(spec);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_ACQUIRE_SURFACE) {
                    int cameraId = data.readInt();
                    int requestId = data.readInt();
                    ShellCameraOverlay.SurfaceSnapshot snapshot = runOnMain(
                            () -> overlay(cameraId).acquireSurface(requestId));
                    reply.writeNoException();
                    reply.writeInt(cameraId);
                    reply.writeInt(snapshot.requestId);
                    reply.writeInt(snapshot.surfaceGeneration);
                    snapshot.surface.writeToParcel(reply, 0);
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_ARM_FRAME) {
                    int cameraId = data.readInt();
                    int requestId = data.readInt();
                    int surfaceGeneration = data.readInt();
                    runOnMain(() -> {
                        overlay(cameraId).armFirstFrame(requestId, surfaceGeneration);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_SET_VISIBLE) {
                    int cameraId = data.readInt();
                    int requestId = data.readInt();
                    int surfaceGeneration = data.readInt();
                    boolean visible = data.readInt() != 0;
                    runOnMain(() -> {
                        overlay(cameraId).setVisible(requestId, surfaceGeneration, visible);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_CLOSE) {
                    int cameraId = data.readInt();
                    String reason = data.readString();
                    runOnMain(() -> {
                        overlay(cameraId).close(reason);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_SET_WARNING) {
                    int cameraId = data.readInt();
                    int requestId = data.readInt();
                    int surfaceGeneration = data.readInt();
                    int edge = data.readInt();
                    int mode = data.readInt();
                    CameraShellProtocol.validateWarning(
                            cameraId, requestId, surfaceGeneration, edge, mode);
                    runOnMain(() -> {
                        overlay(cameraId).setWarning(requestId, surfaceGeneration, edge, mode);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_PREPARE) {
                    CameraShellProtocol.ReverseOverlaySpec spec =
                            CameraShellProtocol.ReverseOverlaySpec.readFromParcel(data);
                    runOnMain(() -> {
                        closePreview("reverse_priority");
                        closeOverlays("reverse_priority");
                        reverseOverlay.prepare(spec);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_ACQUIRE_SURFACES) {
                    int requestId = data.readInt();
                    ShellReverseCameraOverlay.SurfaceSnapshot snapshot = runOnMain(
                            () -> reverseOverlay.acquireSurfaces(requestId));
                    reply.writeNoException();
                    reply.writeInt(snapshot.requestId);
                    reply.writeInt(snapshot.generations.length);
                    for (int generation : snapshot.generations) reply.writeInt(generation);
                    for (Surface surface : snapshot.surfaces) surface.writeToParcel(reply, 0);
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_ARM_FRAMES) {
                    int requestId = data.readInt();
                    int[] generations = readReverseGenerations(data);
                    runOnMain(() -> {
                        reverseOverlay.armFrames(requestId, generations);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_SET_VISIBLE) {
                    int requestId = data.readInt();
                    int[] generations = readReverseGenerations(data);
                    boolean visible = data.readInt() != 0;
                    runOnMain(() -> {
                        reverseOverlay.setVisible(requestId, generations, visible);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_CLOSE) {
                    String reason = data.readString();
                    runOnMain(() -> {
                        reverseOverlay.close(reason);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                return false;
            } catch (Throwable error) {
                if (reply != null) reply.writeException(new IllegalStateException(summary(error)));
                emit("camera_shell_transaction_error", "code", code, "error", summary(error));
                return true;
            }
        }

        private Surface openPreview(
                Surface surface, int viewpoint, StockAvmPreview.Config config,
                boolean horizontal, boolean stockDewarp, int requestId) {
            String view = StockAvmPreview.viewName(viewpoint);
            try {
                emit("camera_config_received", "detail", config.detail(),
                        "viewpoint", viewpoint, "orientation",
                        horizontal ? "horizontal" : "vertical",
                        "dewarp", stockDewarp);
                boolean initialized = preview.open(
                        surface, viewpoint, config, horizontal, stockDewarp);
                activePreviewRequestId = requestId;
                emit("camera_opened", "renderer", "stock_avm_shell",
                        "view", view, "viewpoint", viewpoint, "initialized", initialized,
                        "request_id", requestId,
                        "orientation", horizontal ? "horizontal" : "vertical",
                        "dewarp", stockDewarp,
                        "shell_uid", Process.myUid());
                return preview.getInputSurface();
            } catch (Throwable error) {
                String stage = error instanceof StockAvmPreview.StageException
                        ? ((StockAvmPreview.StageException) error).stage : "open";
                emit("camera_error", "renderer", "stock_avm_shell",
                        "stage", stage, "view", view, "viewpoint", viewpoint,
                        "request_id", requestId,
                        "shell_uid", Process.myUid(), "error", summary(error));
                throw new IllegalStateException(summary(error), error);
            }
        }

        private void closePreview(String reason) {
            closePreview(reason, 0);
        }

        private void closePreview(String reason, int expectedRequestId) {
            if (!preview.isOpen()) return;
            if (expectedRequestId > 0 && expectedRequestId != activePreviewRequestId) {
                emit("camera_close_ignored", "renderer", "stock_avm_shell",
                        "reason", reason == null ? "unknown" : reason,
                        "request_id", expectedRequestId,
                        "active_request_id", activePreviewRequestId);
                return;
            }
            String view = StockAvmPreview.viewName(preview.getViewpoint());
            int requestId = activePreviewRequestId;
            String error = "";
            try {
                preview.close();
            } catch (Throwable failure) {
                error = summary(failure);
            } finally {
                activePreviewRequestId = 0;
            }
            emit("camera_closed", "renderer", "stock_avm_shell", "view", view,
                    "reason", reason == null ? "unknown" : reason,
                    "request_id", requestId, "error", error);
        }

        private void closeAll(String reason) {
            closePreview(reason);
            Throwable failure = null;
            try {
                closeOverlays(reason);
            } catch (Throwable error) {
                failure = error;
            }
            try {
                reverseOverlay.close(reason);
            } catch (Throwable error) {
                if (failure == null) failure = error;
            }
            if (failure != null) throw new IllegalStateException(summary(failure), failure);
        }

        private static int[] readReverseGenerations(Parcel data) {
            int count = data.readInt();
            if (count != 3) throw new IllegalArgumentException("three reverse generations required");
            int[] values = new int[count];
            for (int i = 0; i < count; i++) {
                values[i] = data.readInt();
                if (values[i] <= 0) {
                    throw new IllegalArgumentException("invalid reverse Surface generation");
                }
            }
            return values;
        }

        private ShellCameraOverlay overlay(int cameraId) {
            return overlays[CameraProfile.of(cameraId).id];
        }

        private void closeOverlays(String reason) {
            Throwable failure = null;
            for (ShellCameraOverlay value : overlays) {
                try {
                    value.close(reason);
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                }
            }
            if (failure != null) throw new IllegalStateException(summary(failure), failure);
        }

        private <T> T runOnMain(java.util.concurrent.Callable<T> callable) throws Exception {
            if (Looper.myLooper() == handler.getLooper()) return callable.call();
            FutureTask<T> task = new FutureTask<>(callable);
            if (!handler.post(task)) {
                throw new IllegalStateException("camera main handler rejected task");
            }
            return task.get(5, TimeUnit.SECONDS);
        }

        private synchronized void registerCallback(IBinder value) throws RemoteException {
            if (value == null) throw new IllegalArgumentException("callback is null");
            callback = value;
            value.linkToDeath(() -> handler.post(() -> callbackDied(value)), 0);
            emit("camera_shell_callback_registered", "shell_uid", Process.myUid());
        }

        private synchronized void callbackDied(IBinder value) {
            if (callback != value) return;
            callback = null;
            try {
                closeAll("controller_died");
            } finally {
                handler.getLooper().quitSafely();
            }
        }

        private void emit(String kind, Object... fields) {
            String line;
            try {
                JSONObject json = new JSONObject();
                json.put("kind", kind);
                json.put("source", "camera_shell_helper");
                json.put("wall_time", new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
                json.put("t_ms", SystemClock.elapsedRealtime());
                for (int i = 0; i + 1 < fields.length; i += 2) {
                    json.put(String.valueOf(fields[i]), fields[i + 1]);
                }
                line = json.toString();
            } catch (Throwable error) {
                line = "{\"kind\":\"camera_shell_json_error\"}";
            }
            System.out.println(line);
            scheduleStdoutFlush();
            IBinder target;
            synchronized (this) {
                target = callback;
            }
            if (target == null) return;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeInterfaceToken(CameraShellProtocol.CALLBACK_DESCRIPTOR);
                parcel.writeString(line);
                target.transact(CameraShellProtocol.CB_EVENT,
                        parcel, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable error) {
                handler.post(() -> callbackDied(target));
            } finally {
                parcel.recycle();
            }
        }

        private void scheduleStdoutFlush() {
            synchronized (this) {
                if (stdoutFlushScheduled) return;
                stdoutFlushScheduled = true;
            }
            if (!handler.postDelayed(() -> {
                synchronized (ShellBinder.this) {
                    stdoutFlushScheduled = false;
                }
                System.out.flush();
            }, LOG_FLUSH_DELAY_MS)) {
                synchronized (this) {
                    stdoutFlushScheduled = false;
                }
                System.out.flush();
            }
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = type.getMethod("systemMain").invoke(null);
        Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
        if (context == null) throw new IllegalStateException("system context unavailable");
        return context;
    }

    private static void prepareMainLooper() {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
    }

    private static String summary(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof java.lang.reflect.InvocationTargetException
                        || current instanceof StockAvmPreview.StageException)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class OwnerLock {
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final FileLock lock;

        private OwnerLock(RandomAccessFile file, FileChannel channel, FileLock lock) {
            this.file = file;
            this.channel = channel;
            this.lock = lock;
        }

        static OwnerLock acquire() throws Exception {
            RandomAccessFile file = new RandomAccessFile(CameraShellProtocol.LOCK_PATH, "rw");
            FileChannel channel = file.getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                file.close();
                return null;
            }
            return new OwnerLock(file, channel, lock);
        }

        void close() {
            try { lock.release(); } catch (Throwable ignored) {}
            try { channel.close(); } catch (Throwable ignored) {}
            try { file.close(); } catch (Throwable ignored) {}
        }
    }
}
