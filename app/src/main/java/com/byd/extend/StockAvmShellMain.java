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

    interface OpenLifecycle<T> {
        boolean open() throws Exception;
        T handoff() throws Exception;
        void close() throws Exception;
    }

    static final class OpenResult<T> {
        final boolean initialized;
        final T value;

        OpenResult(boolean initialized, T value) {
            this.initialized = initialized;
            this.value = value;
        }
    }

    static <T> OpenResult<T> openOwned(OpenLifecycle<T> lifecycle) throws Exception {
        boolean opened = false;
        try {
            boolean initialized = lifecycle.open();
            opened = true;
            return new OpenResult<>(initialized, lifecycle.handoff());
        } catch (Throwable error) {
            if (opened) {
                try {
                    lifecycle.close();
                } catch (Throwable closeError) {
                    error.addSuppressed(closeError);
                }
            }
            if (error instanceof Exception) throw (Exception) error;
            if (error instanceof Error) throw (Error) error;
            throw new Exception(error);
        }
    }

    static void releaseBeforeTransfer(boolean transferred, Runnable release) {
        if (!transferred) release.run();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "usage: StockAvmShellMain <appUid> <versionCode>");
        int appUid = Integer.parseInt(args[0]);
        int versionCode = Integer.parseInt(args[1]);
        System.out.println(new JSONObject().put("kind", "stock_avm_process_start")
                .put("pid", Process.myPid()).put("wall_ms", System.currentTimeMillis())
                .put("build", versionCode).put("protocol", StockAvmShellProtocol.VERSION));
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
            DiagnosticLogPolicy.flushOutput();
            Looper.loop();
        } finally {
            try {
                binder.closePreview("process_exit", 0);
            } finally {
                DiagnosticLogPolicy.flushOutput();
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
        private CameraGpuOutput.Binding outputLease;
        private volatile boolean processTerminationQueued;
        private boolean processTerminationRequested;
        private Runnable openingCancellation;
        private int openingRequestId;

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
                emit("stock_avm_stage", "stage", stage, "detail", detail,
                        "request_id", openingRequestId);
                if (openingCancellation != null) openingCancellation.run();
                if ("apply_vehicle_config".equals(stage)) {
                    emit("camera_config_applied", "detail", detail);
                }
            });
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(StockAvmShellProtocol.DESCRIPTOR);
                return true;
            }
            if (!StockAvmShellProtocol.isCallerAllowed(Binder.getCallingUid(), appUid)) {
                if (reply != null) reply.writeException(new SecurityException("caller uid denied"));
                return true;
            }
            try {
                data.enforceInterface(StockAvmShellProtocol.DESCRIPTOR);
                if (code == StockAvmShellProtocol.TX_CONFIGURE_LOGGING) {
                    int enabled = data.readInt();
                    if (enabled != 0 && enabled != 1) throw new IllegalArgumentException("invalid logging flag");
                    DiagnosticLogPolicy.configure(enabled == 1);
                    reply.writeNoException();
                    return true;
                }
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
                    Surface surface = CameraGpuOutput.readSurface(data, appUid);
                    boolean surfaceTransferred = false;
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
                        IBinder cancellation = data.readStrongBinder();
                        if (cancellation == null) throw new IllegalArgumentException("AVM cancellation required");
                        if (!StockAvmPreview.isAllowedViewpoint(viewpoint)) {
                            throw new IllegalArgumentException("viewpoint not whitelisted");
                        }
                        FutureTask<Surface> task = new FutureTask<>(() -> openPreview(
                                surface, viewpoint, config, horizontal, stockDewarp,
                                requestId, attempt, () -> checkCancellation(cancellation)));
                        if (!handler.post(task)) throw new IllegalStateException("AVM main handler rejected open");
                        surfaceTransferred = true;
                        try {
                            Surface input = task.get(15, TimeUnit.SECONDS);
                            reply.writeNoException();
                            reply.writeInt(StockAvmShellProtocol.OPEN_RESULT_OK);
                            input.writeToParcel(reply, 0);
                        } catch (Throwable error) {
                            releaseBeforeTransfer(surfaceTransferred, surface::release);
                            BydStockAvm.CloudConfigStatException statFailure =
                                    findCloudConfigStatFailure(error);
                            if (statFailure != null
                                    && StockAvmShellProtocol.isEacces(statFailure.errno)) {
                                Throwable source = statFailure.getCause();
                                String errorReference = StockAvmShellProtocol.errorReference(
                                        Process.myPid(), requestId, attempt);
                                reply.writeNoException();
                                reply.writeInt(StockAvmShellProtocol.OPEN_RESULT_BYD_CONFIG_EACCES);
                                reply.writeInt(requestId);
                                reply.writeInt(attempt);
                                reply.writeInt(Process.myUid());
                                reply.writeInt(Process.myPid());
                                reply.writeInt(statFailure.errno);
                                reply.writeString("byd_cloud_config_stat");
                                reply.writeString("stat");
                                reply.writeString(BydStockAvm.CLOUD_CONFIG_PATH);
                                reply.writeString(StockAvmShellProtocol.errnoName(statFailure.errno));
                                reply.writeString(source.getClass().getName());
                                reply.writeString(source.getMessage());
                                reply.writeString(errorReference);
                                return true;
                            }
                            throw error;
                        }
                    } catch (Throwable error) {
                        releaseBeforeTransfer(surfaceTransferred, surface::release);
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
                // Binder cannot preserve the remote Throwable chain; persist it before replying.
                emit("camera_shell_transaction_error", "code", code,
                        "error", StockAvmShellProtocol.diagnostic(error));
                if (reply != null) reply.writeException(new IllegalStateException(summary(error)));
                return true;
            }
        }

        private Surface openPreview(Surface surface, int viewpoint, StockAvmPreview.Config config,
                boolean horizontal, boolean stockDewarp, int requestId, int attempt,
                Runnable cancellation) {
            String view = StockAvmPreview.viewName(viewpoint);
            boolean ownsPreview = false;
            try {
                cancellation.run();
                if (preview.isOpen()) closePreview("output_replaced", 0);
                if (processTerminationQueued) throw new IllegalStateException("AVM shutdown pending");
                ownsPreview = true;
                outputLease = CameraGpuOutput.bind(surface,
                        () -> handler.post(() -> closePreview("output_retired", requestId)));
                emit("camera_config_received", "detail", config.detail(), "viewpoint", viewpoint,
                        "orientation", horizontal ? "horizontal" : "vertical", "dewarp", stockDewarp);
                OpenResult<Surface> opened = openOwned(new OpenLifecycle<Surface>() {
                    @Override
                    public boolean open() throws Exception {
                        openingRequestId = requestId;
                        openingCancellation = cancellation;
                        try {
                            cancellation.run();
                            return preview.open(surface, viewpoint, config, horizontal, stockDewarp);
                        } finally {
                            // Cleanup stages must run even when initialization was canceled.
                            openingCancellation = null;
                        }
                    }

                    @Override
                    public Surface handoff() {
                        cancellation.run();
                        Surface input = preview.getInputSurface();
                        if (!input.isValid()) {
                            throw new IllegalStateException("AVM input Surface is invalid");
                        }
                        return input;
                    }

                    @Override
                    public void close() throws Exception {
                        try { preview.close(); }
                        catch (Exception | Error failure) {
                            queueProcessTermination();
                            throw failure;
                        }
                    }
                });
                boolean initialized = opened.initialized;
                activeRequestId = requestId;
                emit("camera_opened", "renderer", "stock_avm_shell", "view", view,
                        "viewpoint", viewpoint, "initialized", initialized, "request_id", requestId,
                        "attempt", attempt,
                        "orientation", horizontal ? "horizontal" : "vertical", "dewarp", stockDewarp,
                        "shell_uid", Process.myUid(), "display_ready", true,
                        "input_surface_valid", true);
                return opened.value;
            } catch (Throwable error) {
                if (ownsPreview && !processTerminationQueued) {
                    try {
                        preview.close();
                        releaseOutputLease();
                    } catch (Throwable cleanup) {
                        error.addSuppressed(cleanup);
                        queueProcessTermination();
                    }
                }
                surface.release();
                if (!preview.isOpen()) activeRequestId = 0;
                String stage = error instanceof StockAvmPreview.StageException
                        ? ((StockAvmPreview.StageException) error).stage : "open";
                BydStockAvm.CloudConfigStatException statFailure =
                        findCloudConfigStatFailure(error);
                String errorReference = statFailure != null
                        && StockAvmShellProtocol.isEacces(statFailure.errno)
                        ? StockAvmShellProtocol.errorReference(Process.myPid(), requestId, attempt)
                        : "";
                int preflightErrno = errorReference.isEmpty() ? 0 : statFailure.errno;
                emit("camera_error", "renderer", "stock_avm_shell", "stage", stage, "view", view,
                        "viewpoint", viewpoint, "request_id", requestId, "attempt", attempt,
                        "shell_uid", Process.myUid(),
                        "preflight_errno", preflightErrno,
                        "error_ref", errorReference,
                        "error", StockAvmShellProtocol.diagnostic(error));
                throw new IllegalStateException(summary(error), error);
            } finally {
                openingRequestId = 0;
            }
        }

        private void checkCancellation(IBinder cancellation) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(StockAvmShellProtocol.CANCELLATION_DESCRIPTOR);
                if (!cancellation.transact(StockAvmShellProtocol.TX_IS_CANCELLED, data, reply, 0)) {
                    throw new IllegalStateException("AVM cancellation transaction rejected");
                }
                reply.readException();
                if (reply.readInt() != 0) {
                    throw new java.util.concurrent.CancellationException("AVM open canceled by owner");
                }
            } catch (RemoteException failure) {
                throw new IllegalStateException("AVM cancellation owner unavailable", failure);
            } finally {
                reply.recycle();
                data.recycle();
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
            try {
                preview.close();
                releaseOutputLease();
            } catch (Throwable failure) {
                error = StockAvmShellProtocol.diagnostic(failure);
                // A failed detach cannot acknowledge consumer release. Binder death will do it.
                queueProcessTermination();
            }
            activeRequestId = 0;
            emit("camera_closed", "renderer", "stock_avm_shell", "view", view,
                    "reason", reason == null ? "unknown" : reason, "request_id", requestId, "error", error);
        }

        private void releaseOutputLease() {
            CameraGpuOutput.Binding lease = outputLease;
            outputLease = null;
            if (lease != null) lease.close();
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
            processTerminationQueued = true;
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
            if (!DiagnosticLogPolicy.shouldProduce(kind)) return;
            try {
                JSONObject json = new JSONObject().put("kind", kind).put("source", "stock_avm_shell")
                        .put("wall_time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                                .format(new Date()))
                        .put("t_ms", SystemClock.elapsedRealtime());
                for (int i = 0; i + 1 < fields.length; i += 2) json.put(String.valueOf(fields[i]), fields[i + 1]);
                String line = json.toString();
                DiagnosticLogPolicy.print(line);
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

    private static void terminateProcess() { DiagnosticLogPolicy.flushOutput(); Process.killProcess(Process.myPid()); }

    private static String summary(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.lang.reflect.InvocationTargetException
                || current instanceof StockAvmPreview.StageException)) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static BydStockAvm.CloudConfigStatException findCloudConfigStatFailure(
            Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof BydStockAvm.CloudConfigStatException) {
                return (BydStockAvm.CloudConfigStatException) current;
            }
        }
        return null;
    }

    private static final class OwnerLock {
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final FileLock lock;

        private OwnerLock(RandomAccessFile file, FileChannel channel, FileLock lock) {
            this.file = file; this.channel = channel; this.lock = lock;
        }

        static OwnerLock acquire() throws java.io.IOException {
            RandomAccessFile file = new RandomAccessFile(StockAvmShellProtocol.LOCK_PATH, "rw");
            try {
                FileChannel channel = file.getChannel();
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    System.err.println("stock_avm_owner_lock_already_held: " + StockAvmShellProtocol.LOCK_PATH);
                    file.close();
                    return null;
                }
                return new OwnerLock(file, channel, lock);
            } catch (java.io.IOException | RuntimeException | Error error) {
                try { file.close(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        }

        void close() {
            try { lock.release(); } catch (Throwable failure) { failure.printStackTrace(System.err); }
            try { channel.close(); } catch (Throwable failure) { failure.printStackTrace(System.err); }
            try { file.close(); } catch (Throwable failure) { failure.printStackTrace(System.err); }
        }
    }
}
