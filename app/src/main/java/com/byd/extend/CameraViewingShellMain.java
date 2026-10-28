package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import com.byd.extend.remote.CameraViewingRuntime;
import com.byd.extend.remote.CameraViewingDiagnostics;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent shell-UID owner of remote camera viewing and its native network runtime. */
public final class CameraViewingShellMain {
    public static void logViewingEvent(String stage, String provider, String detail) {
        AvasRecoveryJournal.event(new File("/data/local/tmp"), "bydextend_viewing-events.jsonl",
                "viewing_event", "source", "viewing_helper", "stage", stage,
                "provider", provider, "details", detail);
    }

    public static void main(String[] args) {
        String stage = "arguments";
        try {
            if (args.length != 2 || !args[0].matches("[0-9]{1,10}") || args[1].isEmpty())
                throw new IllegalArgumentException("Expected app UID and Extend APK identity");
            if (Process.myUid() != 2000)
                throw new SecurityException("Camera viewing worker must run as shell UID 2000");
            int ownerUid = Integer.parseInt(args[0]);
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            stage = "process_lock";
            try (RandomAccessFile file = new RandomAccessFile(CameraViewingShellProtocol.LOCK_PATH, "rw")) {
                FileLock acquired = file.getChannel().tryLock();
                if (acquired == null) throw new IllegalStateException("Camera viewing worker already running");
                try (FileLock lock = acquired) {
                    stage = "shell_context";
                    Context system = TurnSignalShellMain.systemContext();
                    OemLogPolicy.initialize(system);
                    CameraShellMain.initializeSystemFontsForShell();
                    CameraViewingShellContext shellContext = new CameraViewingShellContext(
                            system, ownerUid, args[1]);
                    stage = "runtime_create";
                    CameraViewingRuntime runtime = new CameraViewingRuntime(
                            shellContext.runtimeContext(), shellContext.privateDirectory(), ownerUid);
                    CameraViewingShellMain daemon = new CameraViewingShellMain(runtime, shellContext,
                            args[1], ownerUid);
                    stage = "publish";
                    daemon.publish();
                    stage = "event_loop";
                    Looper.loop();
                    daemon.close();
                }
            }
            System.exit(0);
        } catch (Throwable error) {
            System.err.println(java.time.Instant.now() + " camera_viewing_start_failed stage=" + stage
                    + " pid=" + Process.myPid() + " uid=" + Process.myUid() + "\n"
                    + CameraViewingDiagnostics.describe(error));
            System.exit(1);
        }
    }

    private final CameraViewingRuntime runtime;
    private final CameraViewingShellContext shellContext;
    private final String apkIdentity;
    private final int ownerUid;
    private final ShellBinder binder = new ShellBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private IBinder observer;
    private IBinder.DeathRecipient observerDeath;

    private CameraViewingShellMain(CameraViewingRuntime runtime,
            CameraViewingShellContext shellContext, String apkIdentity, int ownerUid) {
        this.runtime = runtime;
        this.shellContext = shellContext;
        this.apkIdentity = apkIdentity;
        this.ownerUid = ownerUid;
    }

    private void publish() throws Exception {
        Class.forName("android.os.ServiceManager")
                .getMethod("addService", String.class, IBinder.class)
                .invoke(null, CameraViewingShellProtocol.SERVICE_NAME, binder);
        runtime.setListener(this::publishSnapshot);
        System.out.println("camera_viewing_worker_ready uid=" + Process.myUid() + " pid=" + Process.myPid()
                + " protocol=" + CameraViewingShellProtocol.VERSION);
        System.out.flush();
    }

    private void publishSnapshot(JSONObject snapshot) {
        if (snapshot == null) return;
        IBinder target;
        synchronized (this) { target = observer; }
        if (target == null || !target.isBinderAlive()) return;
        String json = snapshot.toString();
        if (json.length() > CameraViewingShellProtocol.MAX_REPLY_BYTES) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraViewingShellProtocol.CALLBACK_DESCRIPTOR);
            data.writeString(json);
            if (!target.transact(CameraViewingShellProtocol.CB_SNAPSHOT, data, null, IBinder.FLAG_ONEWAY))
                clearObserver(target);
        } catch (RemoteException ignored) {
            clearObserver(target);
        } finally {
            data.recycle();
        }
    }

    private void setObserver(IBinder next) throws RemoteException {
        if (next == null || !next.isBinderAlive()) throw new IllegalArgumentException("viewing observer is dead");
        synchronized (this) {
            if (observer == next) return;
            clearObserverLocked(observer);
            IBinder.DeathRecipient death = () -> clearObserver(next);
            next.linkToDeath(death, 0);
            observer = next;
            observerDeath = death;
        }
        publishSnapshot(runtime.snapshot());
    }

    private void clearObserver(IBinder expected) {
        synchronized (this) { clearObserverLocked(expected); }
    }

    private void clearObserverLocked(IBinder expected) {
        if (expected == null || observer != expected) return;
        if (observerDeath != null) {
            try { expected.unlinkToDeath(observerDeath, 0); } catch (Throwable ignored) { }
        }
        observer = null;
        observerDeath = null;
    }

    private void detachObserver(IBinder expected) {
        synchronized (this) {
            if (expected == null || observer == expected) clearObserverLocked(observer);
        }
    }

    private void close() {
        if (!closed.compareAndSet(false, true)) return;
        detachObserver(null);
        try { runtime.close(); }
        catch (Throwable error) { CameraViewingDiagnostics.failure("runtime_close", "shared", error); }
        shellContext.close();
    }

    private final class ShellBinder extends Binder {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CameraViewingShellProtocol.DESCRIPTOR);
                return true;
            }
            int callerUid = Binder.getCallingUid();
            if (code == CameraViewingShellProtocol.TX_PING) {
                try {
                    if ((callerUid != ownerUid && callerUid != 2000) || reply == null)
                        throw new SecurityException("camera viewing ping denied");
                    data.enforceInterface(CameraViewingShellProtocol.DESCRIPTOR);
                    if (data.dataAvail() != 0) throw new IllegalArgumentException("ping payload not empty");
                    reply.writeNoException();
                    reply.writeInt(CameraViewingShellProtocol.VERSION);
                    reply.writeInt(BuildConfig.VERSION_CODE);
                    reply.writeInt(Process.myUid());
                    reply.writeInt(Process.myPid());
                    reply.writeString(apkIdentity);
                } catch (RuntimeException error) {
                    if (reply != null) reply.writeException(error);
                }
                return true;
            }
            if (callerUid != ownerUid) {
                if (reply != null) reply.writeException(new SecurityException("camera viewing caller denied"));
                return true;
            }
            try {
                data.enforceInterface(CameraViewingShellProtocol.DESCRIPTOR);
                switch (code) {
                    case CameraViewingShellProtocol.TX_REQUEST: {
                        String operation = data.readString();
                        String payloadJson = data.readString();
                        if (!CameraViewingShellProtocol.isOperationAllowed(operation)
                                || payloadJson == null
                                || payloadJson.length() > CameraViewingShellProtocol.MAX_REQUEST_BYTES
                                || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid camera viewing request");
                        JSONObject payload = new JSONObject(payloadJson);
                        JSONObject result;
                        if ("diagnostics".equals(operation)) {
                            if (payload.length() != 1 || !(payload.opt("extended") instanceof Boolean))
                                throw new IllegalArgumentException("invalid diagnostics setting");
                            DiagnosticLogPolicy.configure(payload.getBoolean("extended"));
                            result = new JSONObject().put("extended", DiagnosticLogPolicy.extended());
                        } else {
                            if (payload.has("extendedLogs")) {
                                if (!(payload.opt("extendedLogs") instanceof Boolean))
                                    throw new IllegalArgumentException("invalid extended logs setting");
                                DiagnosticLogPolicy.configure(payload.getBoolean("extendedLogs"));
                            }
                            result = runtime.request(operation, payload);
                        }
                        if (result == null || result.toString().length()
                                > CameraViewingShellProtocol.MAX_REPLY_BYTES)
                            throw new IllegalStateException("invalid camera viewing reply");
                        reply.writeNoException();
                        reply.writeString(result.toString());
                        if ("shutdown".equals(operation)) scheduleStop();
                        return true;
                    }
                    case CameraViewingShellProtocol.TX_SNAPSHOT: {
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("snapshot payload not empty");
                        JSONObject snapshot = runtime.snapshot();
                        String json = snapshot == null ? null : snapshot.toString();
                        if (json == null || json.length() > CameraViewingShellProtocol.MAX_REPLY_BYTES)
                            throw new IllegalStateException("invalid camera viewing snapshot");
                        reply.writeNoException();
                        reply.writeString(json);
                        return true;
                    }
                    case CameraViewingShellProtocol.TX_OBSERVE: {
                        IBinder callback = data.readStrongBinder();
                        if (data.dataAvail() != 0) throw new IllegalArgumentException("invalid observer request");
                        setObserver(callback);
                        reply.writeNoException();
                        reply.writeInt(1);
                        return true;
                    }
                    case CameraViewingShellProtocol.TX_DETACH: {
                        IBinder callback = data.readStrongBinder();
                        if (callback == null || data.dataAvail() != 0)
                            throw new IllegalArgumentException("invalid observer detach");
                        detachObserver(callback);
                        reply.writeNoException();
                        reply.writeInt(1);
                        return true;
                    }
                    default:
                        return false;
                }
            } catch (JSONException | RuntimeException error) {
                CameraViewingDiagnostics.failure("ipc_transaction_" + code, "shared", error);
                if (reply != null) reply.writeException(new IllegalStateException(
                        CameraViewingDiagnostics.safeText(error.getMessage())
                                + " (" + error.getClass().getSimpleName() + ")"));
                return true;
            }
        }

        private void scheduleStop() {
            main.postDelayed(() -> main.getLooper().quitSafely(), 100L);
        }
    }
}
