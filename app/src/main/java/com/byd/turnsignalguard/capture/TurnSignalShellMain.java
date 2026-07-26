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

import org.json.JSONObject;

import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class TurnSignalShellMain {
    private TurnSignalShellMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
                "usage: TurnSignalShellMain <appUid> <apkPath> <versionCode>");
        int appUid = Integer.parseInt(args[0]);
        int versionCode = Integer.parseInt(args[2]);
        OwnerLock owner = OwnerLock.acquire();
        if (owner == null) return;
        prepareMainLooper();
        Context context = systemContext();
        Handler handler = new Handler(Looper.getMainLooper());
        ShellBinder binder = new ShellBinder(context, handler, appUid, versionCode);
        binder.attachInterface(null, TurnSignalShellProtocol.DESCRIPTOR);
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method addService = serviceManager.getMethod("addService", String.class, IBinder.class);
            addService.invoke(null, TurnSignalShellProtocol.SERVICE_NAME, binder);
            binder.start();
            System.out.println("READY pid=" + Process.myPid()
                    + " protocol=" + TurnSignalShellProtocol.VERSION
                    + " build=" + versionCode);
            System.out.flush();
            Looper.loop();
        } finally {
            binder.stop();
            owner.close();
        }
    }

    private static final class ShellBinder extends Binder {
        private final Handler handler;
        private final int appUid;
        private final int versionCode;
        private final TurnSignalGuardRuntime runtime;
        private IBinder callback;
        private IBinder controllerToken;
        private boolean guardEnabled;
        private boolean recoveryEnabled;
        private IBinder.DeathRecipient controllerDeathRecipient;

        ShellBinder(Context context, Handler handler, int appUid, int versionCode) {
            this.handler = handler;
            this.appUid = appUid;
            this.versionCode = versionCode;
            runtime = new TurnSignalGuardRuntime(context, handler, this::emit);
        }

        void start() {
            runtime.start();
        }

        void stop() {
            runtime.stop();
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (!TurnSignalShellProtocol.isCallerAllowed(Binder.getCallingUid(), appUid)) {
                if (reply != null) reply.writeException(new SecurityException("caller uid denied"));
                return true;
            }
            try {
                data.enforceInterface(TurnSignalShellProtocol.DESCRIPTOR);
                if (code == TurnSignalShellProtocol.TX_PING) {
                    reply.writeNoException();
                    reply.writeInt(TurnSignalShellProtocol.VERSION);
                    reply.writeInt(versionCode);
                    reply.writeInt(Process.myPid());
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_REGISTER_CALLBACK) {
                    registerCallback(data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_CONFIGURE_GUARD) {
                    guardEnabled = data.readInt() != 0;
                    runtime.configure(guardEnabled, data.readFloat(), data.readFloat(),
                            data.readInt(), data.readInt(), data.readInt());
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_SET_MANUAL_STATE) {
                    int payload = data.readInt();
                    if (!TurnSignalShellProtocol.isPayloadAllowed(payload)) {
                        throw new IllegalArgumentException("payload not whitelisted");
                    }
                    runtime.setManualTurnState(payload);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_REPORT_STATUS) {
                    runtime.reportStatus();
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_ATTACH_CONTROLLER) {
                    attachController(data.readStrongBinder(), data.readInt() != 0);
                    reply.writeNoException();
                    return true;
                }
                if (code == TurnSignalShellProtocol.TX_SHUTDOWN) {
                    guardEnabled = false;
                    recoveryEnabled = false;
                    reply.writeNoException();
                    handler.post(() -> {
                        runtime.stop();
                        emit("shell_shutdown", "reason", "controller_request");
                        Looper.myLooper().quitSafely();
                    });
                    return true;
                }
                return false;
            } catch (Throwable error) {
                if (reply != null) reply.writeException(new IllegalStateException(summary(error)));
                emit("shell_transaction_error", "code", code, "error", summary(error));
                return true;
            }
        }

        private synchronized void registerCallback(IBinder value) throws RemoteException {
            if (value == null) throw new IllegalArgumentException("callback is null");
            callback = value;
            value.linkToDeath(() -> handler.post(() -> clearCallback(value)), 0);
            emit("shell_callback_registered", "shell_uid", Process.myUid());
            runtime.reportStatus();
        }

        private synchronized void clearCallback(IBinder value) {
            if (callback == value) callback = null;
        }

        private synchronized void attachController(IBinder token, boolean requestedRecovery)
                throws RemoteException {
            if (token == null) throw new IllegalArgumentException("controller token is null");
            if (controllerToken != null) {
                controllerToken.unlinkToDeath(controllerDeathRecipient, 0);
            }
            controllerToken = token;
            recoveryEnabled = requestedRecovery;
            controllerDeathRecipient = () -> controllerDied(token);
            token.linkToDeath(controllerDeathRecipient, 0);
            emit("controller_attached", "recovery_enabled", recoveryEnabled);
        }

        private void controllerDied(IBinder deadToken) {
            boolean restart;
            synchronized (this) {
                if (controllerToken != deadToken) return;
                controllerToken = null;
                controllerDeathRecipient = null;
                callback = null;
                restart = recoveryEnabled;
            }
            emit("controller_died", "recovery_enabled", restart,
                    "restart_requested", restart);
            if (!restart) return;
            try {
                new ProcessBuilder("sh", "-c",
                        "am start-foreground-service --user 0 -n "
                                + CameraHelperMain.PACKAGE_NAME + "/.CameraHelperService")
                        .redirectErrorStream(true)
                        .start();
            } catch (Throwable error) {
                emit("controller_restart_failed", "error", summary(error));
            }
        }

        private void emit(String kind, Object... fields) {
            String line;
            try {
                JSONObject json = new JSONObject();
                json.put("kind", kind);
                json.put("source", "shell_helper");
                json.put("wall_time", new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
                json.put("t_ms", SystemClock.elapsedRealtime());
                for (int i = 0; i + 1 < fields.length; i += 2) {
                    json.put(String.valueOf(fields[i]), fields[i + 1]);
                }
                line = json.toString();
            } catch (Throwable error) {
                line = "{\"kind\":\"shell_json_error\"}";
            }
            System.out.println(line);
            System.out.flush();
            IBinder target;
            synchronized (this) {
                target = callback;
            }
            if (target == null) return;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeInterfaceToken(TurnSignalShellProtocol.CALLBACK_DESCRIPTOR);
                parcel.writeString(line);
                target.transact(TurnSignalShellProtocol.CB_EVENT,
                        parcel, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable error) {
                clearCallback(target);
            } finally {
                parcel.recycle();
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
        if (Looper.getMainLooper() != null) return;
        Looper.prepareMainLooper();
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
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
            RandomAccessFile file = new RandomAccessFile(TurnSignalShellProtocol.LOCK_PATH, "rw");
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
