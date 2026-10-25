package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import com.byd.extend.remote.CameraViewingDiagnostics;

import org.json.JSONException;
import org.json.JSONObject;

/** App-side connector for the independently owned shell viewing process. */
public final class CameraViewingShellClient implements AutoCloseable {
    public interface Listener {
        void onSnapshot(JSONObject snapshot);
        void onHelperDied();
    }

    /** Source-compatible callback shape for app facades that name state updates explicitly. */
    public interface StateListener extends Listener {
        void onState(JSONObject snapshot);
        @Override default void onSnapshot(JSONObject snapshot) { onState(snapshot); }
    }

    private final Context context;
    private final int ownerUid;
    private final String apkIdentity;
    private final Object lock = new Object();
    private IBinder helper;
    private IBinder.DeathRecipient helperDeath;
    private ShellCallback callback;
    private volatile Listener listener;
    private volatile int helperPid;
    private long helperGeneration;
    private boolean closed;

    public CameraViewingShellClient(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        ownerUid = Process.myUid();
        if (ownerUid <= 0) throw new IllegalStateException("invalid owner UID");
        try { apkIdentity = CameraViewingShellProtocol.currentApkIdentity(this.context); }
        catch (Exception error) { throw new IllegalStateException("could not identify Extend APK", error); }
        try {
            if (this.context.getPackageManager().getApplicationInfo(
                    CameraViewingShellProtocol.OWNER_PACKAGE, 0).uid != ownerUid)
                throw new SecurityException("Extend package UID changed");
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Extend package is unavailable", error);
        }
    }

    /** Profile management must remain available without the camera/Motion backend. */
    public void ensureConnected() throws Exception {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("camera viewing client is closed");
            IBinder candidate = resolve();
            CameraViewingShellProtocol.Identity identity = CameraViewingShellProtocol.readIdentity(candidate);
            if (identity != null && identity.matches(identity.pid, apkIdentity)) {
                adopt(candidate, identity.pid);
            } else {
                if (candidate != null) {
                    if (identity == null) throw new IllegalStateException("cannot identify existing viewing helper");
                    stopObsoleteHelper(identity.pid);
                }
                LocalAdbClient.Result launched = LocalAdbClient.executeAuthorized(context,
                        launchCommand(context, ownerUid, apkIdentity), (kind, fields) ->
                                CameraViewingDiagnostics.detail("helper_launch_transport", "shared",
                                        java.util.Arrays.toString(AdbRecoveryDiagnostics.transportFields(kind, fields))));
                String launchDetail = launched == null ? "missing_result" :
                        "ok=" + launched.ok + " command_read_timeout=" + launched.commandReadTimeout
                                + " authorization_required=" + launched.authorizationRequired
                                + " superseded=" + launched.superseded + " exit_code=" + launched.exitCode
                                + " error=" + launched.error + " output=" + launched.output;
                CameraViewingDiagnostics.detail("helper_launch_result", "shared", launchDetail);
                if (launched == null || launched.authorizationRequired || launched.superseded
                        || (!launched.ok && !launched.commandReadTimeout))
                    throw new IllegalStateException("viewing helper launch not confirmed: " + launchDetail);
                // The detached process can become ready even when the shell reply times out.
                try {
                    candidate = awaitHelper(6_000L);
                    identity = CameraViewingShellProtocol.readIdentity(candidate);
                    if (identity == null || !identity.matches(identity.pid, apkIdentity))
                        throw new IllegalStateException("viewing helper readiness/protocol mismatch");
                    adopt(candidate, identity.pid);
                }
                catch (Exception readinessFailure) {
                    if (launched.ok) throw readinessFailure;
                    IllegalStateException launchFailure = new IllegalStateException(
                            "viewing helper launch not confirmed: " + launchDetail);
                    launchFailure.addSuppressed(readinessFailure);
                    throw launchFailure;
                }
            }
            CameraViewingDiagnostics.detail("helper_ready", "shared", "pid=" + helperPid
                    + " protocol=" + identity.protocol + " build=" + identity.build);
        }
    }

    public void prepareCameraBackend() throws Exception {
        IBinder candidate = requireHelper();
        int pid = helperPid;
        // Do not hold the client lock across worker bootstrap: auth/revoke are independent.
        RemoteCameraPreparation.registerViewingHelper(context, candidate, pid);
        registerCameraViewingHelper(candidate, pid);
        if (candidate != requireHelper()) throw new IllegalStateException("viewing helper replaced");
    }

    /** Compatibility alias used by callers that describe bootstrap as a connection. */
    public void connect() throws Exception { ensureConnected(); }

    public boolean isConnected() {
        synchronized (lock) { return !closed && helper != null && helper.isBinderAlive(); }
    }

    public JSONObject request(String operation, JSONObject payload) throws Exception {
        if (!CameraViewingShellProtocol.isOperationAllowed(operation))
            throw new IllegalArgumentException("unsupported camera viewing operation");
        JSONObject actual = payload == null ? new JSONObject() : payload;
        String json = actual.toString();
        if (json.length() > CameraViewingShellProtocol.MAX_REQUEST_BYTES)
            throw new IllegalArgumentException("camera viewing request is too large");
        IBinder target = requireHelper();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraViewingShellProtocol.DESCRIPTOR);
            data.writeString(operation);
            data.writeString(json);
            if (!target.transact(CameraViewingShellProtocol.TX_REQUEST, data, reply, 0))
                throw new IllegalStateException("camera viewing request unavailable");
            reply.readException();
            String result = reply.readString();
            if (result == null || result.length() > CameraViewingShellProtocol.MAX_REPLY_BYTES
                    || reply.dataAvail() != 0)
                throw new IllegalStateException("invalid camera viewing request reply");
            return new JSONObject(result);
        } catch (JSONException error) {
            throw new IllegalStateException("invalid camera viewing response", error);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public JSONObject snapshot() throws Exception {
        IBinder target = requireHelper();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraViewingShellProtocol.DESCRIPTOR);
            if (!target.transact(CameraViewingShellProtocol.TX_SNAPSHOT, data, reply, 0))
                throw new IllegalStateException("camera viewing snapshot unavailable");
            reply.readException();
            String result = reply.readString();
            if (result == null || result.length() > CameraViewingShellProtocol.MAX_REPLY_BYTES
                    || reply.dataAvail() != 0)
                throw new IllegalStateException("invalid camera viewing snapshot reply");
            return new JSONObject(result);
        } catch (JSONException error) {
            throw new IllegalStateException("invalid camera viewing snapshot", error);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void setListener(Listener next) throws Exception {
        IBinder target;
        ShellCallback previousCallback;
        ShellCallback nextCallback;
        synchronized (lock) {
            target = requireHelper();
            previousCallback = callback;
            if (previousCallback != null) previousCallback.deactivate();
            listener = next;
            nextCallback = next == null ? null : new ShellCallback(target, helperGeneration, next);
            callback = nextCallback;
        }
        if (previousCallback != null) detach(previousCallback.owner, previousCallback);
        if (nextCallback == null) return;
        try {
            observe(target, nextCallback);
        } catch (Throwable failure) {
            synchronized (lock) {
                if (callback == nextCallback) {
                    callback = null;
                    listener = null;
                    nextCallback.deactivate();
                }
            }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException("camera viewing observer registration failed", failure);
        }
    }

    /** Detaches this client from the helper; it deliberately leaves the independent process running. */
    @Override public void close() {
        IBinder target;
        ShellCallback oldCallback;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            target = helper;
            oldCallback = callback;
            listener = null;
            callback = null;
            if (oldCallback != null) oldCallback.deactivate();
            if (target != null && helperDeath != null) {
                try { target.unlinkToDeath(helperDeath, 0); } catch (Throwable ignored) { }
            }
            helper = null;
            helperDeath = null;
            helperPid = 0;
        }
        if (oldCallback != null) detach(oldCallback.owner, oldCallback);
    }

    /** The only process-terminating operation; ordinary close() only detaches. */
    public JSONObject shutdown() throws Exception {
        JSONObject result = request("shutdown", new JSONObject());
        close();
        return result;
    }

    private void adopt(IBinder candidate, int pid) throws RemoteException {
        if (helper == candidate && helperPid == pid) return;
        ShellCallback oldCallback = callback;
        if (oldCallback != null) oldCallback.deactivate();
        if (helper != null && helperDeath != null) {
            try { helper.unlinkToDeath(helperDeath, 0); } catch (Throwable ignored) { }
        }
        IBinder.DeathRecipient death = () -> helperDied(candidate);
        candidate.linkToDeath(death, 0);
        helper = candidate;
        helperPid = pid;
        helperDeath = death;
        helperGeneration++;
        callback = null;
    }

    private void helperDied(IBinder dead) {
        Listener target;
        synchronized (lock) {
            if (helper != dead) return;
            helper = null;
            helperPid = 0;
            helperDeath = null;
            ShellCallback oldCallback = callback;
            callback = null;
            if (oldCallback != null) oldCallback.deactivate();
            helperGeneration++;
            target = listener;
        }
        if (target != null) target.onHelperDied();
    }

    private IBinder requireHelper() {
        synchronized (lock) {
            if (closed || helper == null || !helper.isBinderAlive())
                throw new IllegalStateException("camera viewing helper is disconnected");
            return helper;
        }
    }

    private IBinder awaitHelper(long timeoutMs) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        do {
            IBinder candidate = resolve();
            CameraViewingShellProtocol.Identity identity = CameraViewingShellProtocol.readIdentity(candidate);
            if (identity != null && identity.matches(identity.pid, apkIdentity)) return candidate;
            android.os.SystemClock.sleep(100L);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new IllegalStateException("camera viewing helper did not become ready before timeout");
    }

    private void stopObsoleteHelper(int pid) throws Exception {
        LocalAdbClient.Result stopped = LocalAdbClient.executeAuthorized(context,
                stopObsoleteHelperCommand(pid), (kind, fields) -> { });
        if (stopped == null || !stopped.ok)
            throw new IllegalStateException("obsolete viewing helper stop failed"
                    + (stopped == null ? "" : ": " + stopped.error));
    }

    private void registerCameraViewingHelper(IBinder candidate, int pid) throws Exception {
        IBinder cameraHost = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, TurnSignalShellProtocol.SERVICE_NAME);
        if (!TurnSignalController.ping(cameraHost).healthy())
            throw new IllegalStateException("camera helper unavailable for viewing registration");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(CameraRuntimeHost.VIEW_REGISTER_HELPER);
            data.writeStrongBinder(candidate);
            data.writeInt(pid);
            data.writeString(apkIdentity);
            if (!cameraHost.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("camera viewing helper registration unavailable");
            reply.readException();
            if (reply.readInt() != 1 || reply.dataAvail() != 0)
                throw new IllegalStateException("camera viewing helper registration refused");
        } finally { data.recycle(); reply.recycle(); }
    }

    static String launchCommand(Context context, int appUid, String identity) {
        String lib = Process.is64Bit() ? "lib64" : "lib";
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        String libraryPath = (nativeDir == null || nativeDir.isEmpty() ? "" : nativeDir + ":")
                + "/system/" + lib + ":/vendor/" + lib;
        return "trap '' HUP; LD_LIBRARY_PATH=" + shellQuote(libraryPath)
                + " CLASSPATH=" + shellQuote(context.getApplicationInfo().sourceDir)
                + " setsid app_process /system/bin --nice-name=" + CameraViewingShellProtocol.PROCESS_NAME
                + " " + CameraViewingShellProtocol.MAIN_CLASS + " " + appUid + " "
                + shellQuote(identity) + " </dev/null >>" + CameraViewingShellProtocol.LOG_PATH
                + " 2>&1 & for i in 1 2 3 4 5 6; do service list 2>/dev/null | grep -q '"
                + CameraViewingShellProtocol.SERVICE_NAME + "' && break; sleep 0.5; done; service list 2>/dev/null | grep -q '"
                + CameraViewingShellProtocol.SERVICE_NAME + "'";
    }

    static String stopObsoleteHelperCommand(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("positive viewing helper PID required");
        String proc = "/proc/" + pid;
        return "if [ ! -d " + proc + " ]; then exit 0; fi; "
                + "if [ \"$(tr '\\000' '\\n' <" + proc + "/cmdline | head -n 1)\" != '"
                + CameraViewingShellProtocol.PROCESS_NAME + "' ] || ! grep -Eq "
                + "'^Uid:[[:space:]]+2000[[:space:]]+2000[[:space:]]+2000[[:space:]]+2000$' "
                + proc + "/status; then echo viewing_worker_identity_changed; exit 1; fi; "
                + "kill -TERM " + pid + " || exit 1; "
                + "wait_count=0; while [ -d " + proc + " ] && [ \"$wait_count\" -lt 30 ]; "
                + "do sleep 0.1; wait_count=$((wait_count + 1)); done; "
                + "if [ -d " + proc + " ]; then echo viewing_worker_stop_timeout; exit 1; fi";
    }

    private static IBinder resolve() throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, CameraViewingShellProtocol.SERVICE_NAME);
    }

    private static void observe(IBinder target, IBinder callback) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraViewingShellProtocol.DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (!target.transact(CameraViewingShellProtocol.TX_OBSERVE, data, reply, 0))
                throw new IllegalStateException("camera viewing observer registration unavailable");
            reply.readException();
            if (reply.readInt() != 1 || reply.dataAvail() != 0)
                throw new IllegalStateException("camera viewing observer registration refused");
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void detach(IBinder target, IBinder callback) {
        if (target == null || callback == null || !target.isBinderAlive()) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraViewingShellProtocol.DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (!target.transact(CameraViewingShellProtocol.TX_DETACH, data, reply, 0)) return;
            reply.readException();
        } catch (Throwable ignored) {
        } finally { data.recycle(); reply.recycle(); }
    }

    private final class ShellCallback extends Binder {
        final IBinder owner;
        final long generation;
        volatile Listener listener;
        volatile boolean active = true;

        ShellCallback(IBinder owner, long generation, Listener listener) {
            this.owner = owner;
            this.generation = generation;
            this.listener = listener;
        }

        void deactivate() {
            active = false;
            listener = null;
        }

        private boolean isCurrent() {
            synchronized (lock) {
                return active && !closed && helper == owner && helperGeneration == generation
                        && callback == this && owner.isBinderAlive();
            }
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CameraViewingShellProtocol.CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code != CameraViewingShellProtocol.CB_SNAPSHOT) return false;
            if (Binder.getCallingUid() != 2000 || !isCurrent())
                throw new SecurityException("camera viewing callback sender denied");
            data.enforceInterface(CameraViewingShellProtocol.CALLBACK_DESCRIPTOR);
            String json = data.readString();
            if (json == null || json.length() > CameraViewingShellProtocol.MAX_REPLY_BYTES
                    || data.dataAvail() != 0)
                throw new IllegalArgumentException("invalid camera viewing snapshot callback");
            Listener target = listener;
            if (target != null) {
                try { target.onSnapshot(new JSONObject(json)); }
                catch (JSONException error) { throw new IllegalArgumentException("invalid snapshot JSON", error); }
            }
            return true;
        }
    }

    private static String shellQuote(String value) {
        return "'" + String.valueOf(value).replace("'", "'\\''") + "'";
    }
}
