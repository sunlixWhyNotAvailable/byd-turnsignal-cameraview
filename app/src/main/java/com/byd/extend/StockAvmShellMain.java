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
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Minimal process boundary around vendor TSAPI/AvmController state. */
public final class StockAvmShellMain {
    private StockAvmShellMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "usage: StockAvmShellMain <appUid> <versionCode>");
        int appUid = Integer.parseInt(args[0]);
        int versionCode = Integer.parseInt(args[1]);
        OwnerLock owner = OwnerLock.acquire();
        if (owner == null) return;
        CameraShellMain.prepareMainLooperForShell();
        Context context = systemContext();
        CameraShellMain.initializeSystemFontsForShell();
        Handler handler = new Handler(Looper.getMainLooper());
        ShellBinder binder = new ShellBinder(context, handler, appUid, versionCode);
        binder.attachInterface(null, StockAvmShellProtocol.DESCRIPTOR);
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method addService = serviceManager.getMethod("addService", String.class, IBinder.class);
            addService.invoke(null, StockAvmShellProtocol.SERVICE_NAME, binder);
            System.out.println("READY pid=" + Process.myPid()
                    + " protocol=" + StockAvmShellProtocol.VERSION
                    + " build=" + versionCode);
            System.out.flush();
            Looper.loop();
        } finally {
            try {
                binder.closePreview("process_exit", 0);
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
        private final StockAvmPreview preview;
        private final Runnable processTerminator;
        private IBinder callback;
        private int activeRequestId;
        private boolean processTerminationRequested;

        ShellBinder(Context context, Handler handler, int appUid, int versionCode) {
            this(context, handler, appUid, versionCode, StockAvmShellMain::terminateProcess);
        }

        ShellBinder(Context context, Handler handler, int appUid, int versionCode,
                Runnable processTerminator) {
            this.handler = handler;
            this.appUid = appUid;
            this.versionCode = versionCode;
            this.processTerminator = processTerminator;
            preview = new StockAvmPreview(context, (stage, detail) -> {
                emit("stock_avm_stage", "stage", stage, "detail", detail);
                if ("apply_vehicle_config".equals(stage)) {
                    emit("camera_config_applied", "detail", detail);
                }
            });
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (!StockAvmShellProtocol.isCallerAllowed(Binder.getCallingUid(), appUid)) {
                if (reply != null) reply.writeException(new SecurityException("caller uid denied"));
                return true;
            }
            try {
                data.enforceInterface(StockAvmShellProtocol.DESCRIPTOR);
                if (code == StockAvmShellProtocol.TX_PING) {
                    reply.writeNoException();
                    reply.writeInt(StockAvmShellProtocol.VERSION);
                    reply.writeInt(versionCode);
                    reply.writeInt(Process.myPid());
                    return true;
                }
                if (code == StockAvmShellProtocol.TX_REGISTER_CALLBACK) {
                    registerCallback(data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                }
                if (code == StockAvmShellProtocol.TX_OPEN) {
                    Surface surface = Surface.CREATOR.createFromParcel(data);
                    try {
                        int viewpoint = data.readInt();
                        boolean horizontal = data.readInt() != 0;
                        boolean stockDewarp = data.readInt() != 0;
                        int requestId = data.readInt();
                        int attempt = data.readInt();
                        if (requestId <= 0) throw new IllegalArgumentException("camera request id required");
                        if (attempt <= 0 || attempt > 2) {
                            throw new IllegalArgumentException("invalid AVM open attempt");
                        }
                        StockAvmPreview.Config config = StockAvmPreview.Config.readFromParcel(data);
                        if (!StockAvmPreview.isAllowedViewpoint(viewpoint)) {
                            throw new IllegalArgumentException("viewpoint not whitelisted");
                        }
                        FutureTask<Surface> task = new FutureTask<>(() -> openPreview(
                                surface, viewpoint, config, horizontal, stockDewarp,
                                requestId, attempt));
                        if (!handler.post(task)) throw new IllegalStateException("AVM main handler rejected open");
                        Surface input = task.get(15, TimeUnit.SECONDS);
                        reply.writeNoException();
                        input.writeToParcel(reply, 0);
                    } catch (Throwable error) {
                        if (!preview.isOpen()) surface.release();
                        throw error;
                    }
                    return true;
                }
                if (code == StockAvmShellProtocol.TX_CLOSE) {
                    String reason = data.readString();
                    int requestId = data.readInt();
                    runOnMain(() -> { closePreview(reason, requestId); return null; });
                    reply.writeNoException();
                    return true;
                }
                if (code == StockAvmShellProtocol.TX_SHUTDOWN) {
                    try {
                        runOnMain(() -> { closePreview("controller_shutdown", 0); return null; });
                        reply.writeNoException();
                    } finally {
                        queueProcessTermination();
                    }
                    return true;
                }
                return false;
            } catch (Throwable error) {
                if (reply != null) reply.writeException(new IllegalStateException(summary(error)));
                emit("camera_shell_transaction_error", "code", code, "error", summary(error));
                return true;
            }
        }

        private Surface openPreview(Surface surface, int viewpoint, StockAvmPreview.Config config,
                boolean horizontal, boolean stockDewarp, int requestId, int attempt) {
            String view = StockAvmPreview.viewName(viewpoint);
            try {
                emit("camera_config_received", "detail", config.detail(), "viewpoint", viewpoint,
                        "orientation", horizontal ? "horizontal" : "vertical", "dewarp", stockDewarp);
                boolean initialized = preview.open(surface, viewpoint, config, horizontal, stockDewarp);
                activeRequestId = requestId;
                emit("camera_opened", "renderer", "stock_avm_shell", "view", view,
                        "viewpoint", viewpoint, "initialized", initialized, "request_id", requestId,
                        "attempt", attempt,
                        "orientation", horizontal ? "horizontal" : "vertical", "dewarp", stockDewarp,
                        "shell_uid", Process.myUid(), "display_ready", true,
                        "input_surface_valid", preview.getInputSurface().isValid());
                return preview.getInputSurface();
            } catch (Throwable error) {
                String stage = error instanceof StockAvmPreview.StageException
                        ? ((StockAvmPreview.StageException) error).stage : "open";
                emit("camera_error", "renderer", "stock_avm_shell", "stage", stage, "view", view,
                        "viewpoint", viewpoint, "request_id", requestId, "attempt", attempt,
                        "shell_uid", Process.myUid(),
                        "error", summary(error));
                throw new IllegalStateException(summary(error), error);
            }
        }

        private void closePreview(String reason, int expectedRequestId) {
            if (!preview.isOpen()) {
                int requestId = expectedRequestId > 0 ? expectedRequestId : activeRequestId;
                activeRequestId = 0;
                if (requestId > 0) emit("camera_closed", "renderer", "stock_avm_shell",
                        "view", "unknown", "reason", reason == null ? "unknown" : reason,
                        "request_id", requestId, "error", "");
                return;
            }
            if (expectedRequestId > 0 && expectedRequestId != activeRequestId) {
                emit("camera_close_ignored", "renderer", "stock_avm_shell", "request_id", expectedRequestId,
                        "active_request_id", activeRequestId);
                return;
            }
            String view = StockAvmPreview.viewName(preview.getViewpoint());
            int requestId = activeRequestId;
            String error = "";
            try { preview.close(); } catch (Throwable failure) { error = summary(failure); }
            activeRequestId = 0;
            emit("camera_closed", "renderer", "stock_avm_shell", "view", view,
                    "reason", reason == null ? "unknown" : reason, "request_id", requestId, "error", error);
        }

        private <T> T runOnMain(java.util.concurrent.Callable<T> callable) throws Exception {
            if (Looper.myLooper() == handler.getLooper()) return callable.call();
            FutureTask<T> task = new FutureTask<>(callable);
            if (!handler.post(task)) throw new IllegalStateException("AVM main handler rejected task");
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
            try { closePreview("controller_died", 0); } finally { terminateProcessOnce(); }
        }

        private void queueProcessTermination() {
            if (!handler.post(this::terminateProcessOnce)) terminateProcessOnce();
        }

        private void terminateProcessOnce() {
            synchronized (this) {
                if (processTerminationRequested) return;
                processTerminationRequested = true;
            }
            processTerminator.run();
        }

        private void emit(String kind, Object... fields) {
            try {
                JSONObject json = new JSONObject().put("kind", kind).put("source", "stock_avm_shell")
                        .put("wall_time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                                .format(new Date()))
                        .put("t_ms", SystemClock.elapsedRealtime());
                for (int i = 0; i + 1 < fields.length; i += 2) json.put(String.valueOf(fields[i]), fields[i + 1]);
                String line = json.toString();
                System.out.println(line);
                IBinder target;
                synchronized (this) { target = callback; }
                if (target == null) return;
                Parcel parcel = Parcel.obtain();
                try {
                    parcel.writeInterfaceToken(StockAvmShellProtocol.CALLBACK_DESCRIPTOR);
                    parcel.writeString(line);
                    target.transact(StockAvmShellProtocol.CB_EVENT, parcel, null, IBinder.FLAG_ONEWAY);
                } finally { parcel.recycle(); }
            } catch (Throwable ignored) { }
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

    private static void terminateProcess() { System.out.flush(); Process.killProcess(Process.myPid()); }

    private static String summary(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.lang.reflect.InvocationTargetException
                || current instanceof StockAvmPreview.StageException)) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class OwnerLock {
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final FileLock lock;

        private OwnerLock(RandomAccessFile file, FileChannel channel, FileLock lock) {
            this.file = file; this.channel = channel; this.lock = lock;
        }

        static OwnerLock acquire() {
            try {
                RandomAccessFile file = new RandomAccessFile(StockAvmShellProtocol.LOCK_PATH, "rw");
                FileChannel channel = file.getChannel();
                FileLock lock = channel.tryLock();
                if (lock == null) { channel.close(); file.close(); return null; }
                return new OwnerLock(file, channel, lock);
            } catch (Throwable error) { return null; }
        }

        void close() {
            try { lock.release(); } catch (Throwable ignored) { }
            try { channel.close(); } catch (Throwable ignored) { }
            try { file.close(); } catch (Throwable ignored) { }
        }
    }
}
