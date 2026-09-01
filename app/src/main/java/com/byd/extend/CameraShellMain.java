package com.byd.extend;

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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;

public final class CameraShellMain {
    private static final long LOG_FLUSH_DELAY_MS = 250;

    private CameraShellMain() {}

    static Map<String, Object> eventFieldMap(Object... fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            values.put(String.valueOf(fields[i]), fields[i + 1]);
        }
        return values;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "usage: CameraShellMain <appUid> <versionCode>");
        int appUid = Integer.parseInt(args[0]);
        int versionCode = Integer.parseInt(args[1]);
        OwnerLock owner = OwnerLock.acquire();
        if (owner == null) return;
        prepareMainLooperForShell();
        Context context = systemContext();
        initializeSystemFontsForShell();
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

    static final class ShellBinder extends Binder {
        private final Handler handler;
        private final int appUid;
        private final int versionCode;
        private final ShellCameraOverlay[] overlays =
                new ShellCameraOverlay[CameraOverlayProfile.COUNT];
        private final ShellReverseCameraOverlay reverseOverlay;
        private final Runnable processTerminator;
        private IBinder callback;
        private boolean stdoutFlushScheduled;
        private boolean processTerminationRequested;

        ShellBinder(Context context, Handler handler, int appUid, int versionCode) {
            this(context, handler, appUid, versionCode, CameraShellMain::terminateProcess);
        }

        ShellBinder(
                Context context, Handler handler, int appUid, int versionCode,
                Runnable processTerminator) {
            if (processTerminator == null) {
                throw new IllegalArgumentException("process terminator is null");
            }
            this.handler = handler;
            this.appUid = appUid;
            this.versionCode = versionCode;
            this.processTerminator = processTerminator;
            for (CameraOverlayProfile profile : CameraOverlayProfile.values()) {
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
                    throw new IllegalStateException("stock AVM is isolated in bydextend_avm");
                }
                if (code == CameraShellProtocol.TX_CLOSE) {
                    throw new IllegalStateException("stock AVM is isolated in bydextend_avm");
                }
                if (code == CameraShellProtocol.TX_SHUTDOWN) {
                    try {
                        runOnMain(() -> {
                            closeAll("controller_shutdown");
                            emit("camera_shell_shutdown", "reason", "controller_request");
                            return null;
                        });
                        reply.writeNoException();
                    } finally {
                        queueProcessTermination();
                    }
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_PREPARE) {
                    CameraShellProtocol.OverlaySpec spec =
                            CameraShellProtocol.OverlaySpec.readFromParcel(data);
                    if (reverseOverlay.isOpen()
                            && !overlayAllowedWhileReverseActive(spec.cameraId)) {
                        throw new IllegalStateException("reverse overlay has camera priority");
                    }
                    int prepareResult;
                    try {
                        runOnMain(() -> {
                            overlay(spec.cameraId).prepare(spec);
                            return null;
                        });
                        prepareResult = CameraShellProtocol.PREPARE_OK;
                    } catch (CameraShellProtocol.PrepareRestartRequired restart) {
                        prepareResult = CameraShellProtocol.PREPARE_RESTART_REQUIRED;
                        emit("camera_shell_prepare_restart_required",
                                "camera_id", spec.cameraId,
                                "request_id", spec.requestId,
                                "reason", restart.reason);
                    }
                    reply.writeNoException();
                    reply.writeInt(prepareResult);
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
                    OverlayFrameArm arm = OverlayFrameArm.fromWireFields(
                            data.readInt(), data.readInt(), data.readInt(), data.readInt());
                    runOnMain(() -> {
                        overlay(arm.cameraId).armFirstFrame(arm);
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
                    int prepareResult;
                    try {
                        runOnMain(() -> {
                            closeOverlays("reverse_priority", CameraOverlayProfile.BLIND_COUNT);
                            reverseOverlay.prepare(spec);
                            return null;
                        });
                        prepareResult = CameraShellProtocol.PREPARE_OK;
                    } catch (CameraShellProtocol.PrepareRestartRequired restart) {
                        prepareResult = CameraShellProtocol.PREPARE_RESTART_REQUIRED;
                        emit("camera_shell_reverse_prepare_restart_required",
                                "request_id", spec.requestId,
                                "reason", restart.reason);
                    }
                    reply.writeNoException();
                    reply.writeInt(prepareResult);
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
                if (code == CameraShellProtocol.TX_REVERSE_UPDATE_VISIBILITY) {
                    int requestId = data.readInt();
                    int[] generations = readReverseGenerations(data);
                    int visibilityMask = data.readInt();
                    boolean widgetVisible = data.readInt() != 0;
                    runOnMain(() -> {
                        reverseOverlay.updateVisibility(
                                requestId, generations, visibilityMask, widgetVisible);
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
                if (code == CameraShellProtocol.TX_UPDATE_VISUALS) {
                    int cornerRadiusDp = data.readInt();
                    int transparencyPercent = data.readInt();
                    CameraShellProtocol.validateVisualStyle(
                            cornerRadiusDp, transparencyPercent);
                    runOnMain(() -> {
                        Throwable[] failure = new Throwable[1];
                        for (ShellCameraOverlay overlay : overlays) {
                            try {
                                overlay.updateVisuals(cornerRadiusDp, transparencyPercent);
                            } catch (Throwable error) {
                                if (failure[0] == null) failure[0] = error;
                            }
                        }
                        try {
                            reverseOverlay.updateVisuals(cornerRadiusDp, transparencyPercent);
                        } catch (Throwable error) {
                            if (failure[0] == null) failure[0] = error;
                        }
                        if (failure[0] != null) throw new IllegalStateException(
                                summary(failure[0]), failure[0]);
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

        private void closeAll(String reason) {
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
            if (count != 3 && count != 4) {
                throw new IllegalArgumentException(
                        "three or four reverse generations required");
            }
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
            return overlays[CameraOverlayProfile.of(cameraId).id];
        }

        private void closeOverlays(String reason) {
            closeOverlays(reason, overlays.length);
        }

        private void closeOverlays(String reason, int count) {
            Throwable failure = null;
            for (int i = 0; i < count; i++) {
                try {
                    overlays[i].close(reason);
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                }
            }
            if (failure != null) throw new IllegalStateException(summary(failure), failure);
        }

        static boolean overlayAllowedWhileReverseActive(int cameraId) {
            CameraOverlayProfile.of(cameraId);
            return CameraOverlayProfile.isParking(cameraId);
        }

        private <T> T runOnMain(java.util.concurrent.Callable<T> callable) throws Exception {
            if (Looper.myLooper() == handler.getLooper()) return callable.call();
            FutureTask<T> task = new FutureTask<>(callable);
            if (!handler.post(task)) {
                throw new IllegalStateException("camera main handler rejected task");
            }
            try {
                return task.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw error;
            }
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
                terminateProcessOnce();
            }
        }

        private void queueProcessTermination() {
            boolean posted = false;
            try {
                posted = handler.post(this::terminateProcessOnce);
            } finally {
                if (!posted) terminateProcessOnce();
            }
        }

        private void terminateProcessOnce() {
            synchronized (this) {
                if (processTerminationRequested) return;
                processTerminationRequested = true;
            }
            processTerminator.run();
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
                if ("camera_overlay_first_frame".equals(kind)) {
                    for (Map.Entry<String, Object> field : eventFieldMap(fields).entrySet()) {
                        json.put(field.getKey(), field.getValue());
                    }
                } else {
                    for (int i = 0; i + 1 < fields.length; i += 2) {
                        json.put(String.valueOf(fields[i]), fields[i + 1]);
                    }
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

    static Looper prepareMainLooperForShell() {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        return Looper.getMainLooper();
    }

    static void initializeSystemFontsForShell() throws Exception {
        Class<?> typeface = Class.forName("android.graphics.Typeface");
        Object current = typeface.getMethod("getSystemFontMap").invoke(null);
        if (current instanceof Map && !((Map<?, ?>) current).isEmpty()) return;
        typeface.getMethod("loadPreinstalledSystemFontMap").invoke(null);
    }

    private static void terminateProcess() {
        System.out.flush();
        Process.killProcess(Process.myPid());
    }

    private static String summary(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && current instanceof java.lang.reflect.InvocationTargetException) {
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
