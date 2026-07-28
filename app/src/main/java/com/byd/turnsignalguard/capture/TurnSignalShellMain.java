package com.byd.turnsignalguard.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    static final class ShellBinder extends Binder {
        private static final long RECOVERY_DEATH_SETTLE_MS = 500;
        private static final long RECOVERY_WAKE_SETTLE_MS = 500;
        private static final long RECOVERY_WAKE_CHECK_MS = 1_000;
        private static final long RECOVERY_RETRY_MS = 5_000;
        private static final long RECOVERY_COMMAND_TIMEOUT_MS = 2_000;

        private final Context context;
        private final Handler handler;
        private final int appUid;
        private final int versionCode;
        private final TurnSignalGuardRuntime runtime;
        private final PowerManager powerManager;
        private final ExecutorService recoveryWorker = Executors.newSingleThreadExecutor();
        private final Runnable recoveryRunnable = this::attemptRecovery;
        private final Runnable wakeCheckRunnable = this::checkDeferredRecovery;
        private final BroadcastReceiver powerReceiver;
        private IBinder callback;
        private IBinder controllerToken;
        private boolean guardEnabled;
        private boolean recoveryEnabled;
        private boolean recoveryCommandInFlight;
        private boolean awaitingControllerAttach;
        private boolean powerReceiverRegistered;
        private IBinder.DeathRecipient controllerDeathRecipient;

        ShellBinder(Context context, Handler handler, int appUid, int versionCode) {
            this.context = context;
            this.handler = handler;
            this.appUid = appUid;
            this.versionCode = versionCode;
            powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            powerReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    String action = intent == null ? "" : String.valueOf(intent.getAction());
                    handler.post(() -> powerStateChanged(action));
                }
            };
            runtime = new TurnSignalGuardRuntime(context, handler, this::emit);
        }

        void start() {
            registerPowerReceiver();
            runtime.start();
            handler.post(() -> powerStateChanged("helper_start"));
        }

        void stop() {
            handler.removeCallbacks(recoveryRunnable);
            handler.removeCallbacks(wakeCheckRunnable);
            unregisterPowerReceiver();
            recoveryWorker.shutdownNow();
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
            boolean completedRecovery = controllerToken == null && recoveryEnabled;
            if (controllerToken != null) {
                controllerToken.unlinkToDeath(controllerDeathRecipient, 0);
            }
            controllerToken = token;
            recoveryEnabled = requestedRecovery;
            controllerDeathRecipient = () -> controllerDied(token);
            token.linkToDeath(controllerDeathRecipient, 0);
            emit("controller_attached", "recovery_enabled", recoveryEnabled);
            handler.post(() -> {
                awaitingControllerAttach = false;
                handler.removeCallbacks(recoveryRunnable);
                handler.removeCallbacks(wakeCheckRunnable);
                if (completedRecovery) {
                    emit("controller_recovery_complete", "reason", "controller_reattached");
                }
            });
        }

        private void controllerDied(IBinder deadToken) {
            handler.post(() -> controllerDiedOnHandler(deadToken));
        }

        private void controllerDiedOnHandler(IBinder deadToken) {
            boolean restart;
            synchronized (this) {
                if (controllerToken != deadToken) return;
                controllerToken = null;
                controllerDeathRecipient = null;
                callback = null;
                restart = recoveryEnabled;
            }
            boolean interactive = isInteractive();
            emit("controller_died", "recovery_enabled", restart,
                    "interactive", interactive,
                    "restart_requested", restart && interactive,
                    "restart_deferred", restart && !interactive);
            if (!restart) return;
            if (interactive) {
                scheduleRecovery(RECOVERY_DEATH_SETTLE_MS, "controller_died");
            } else {
                emit("controller_recovery_deferred", "reason", "android_not_interactive");
                scheduleWakeCheck();
            }
        }

        private void registerPowerReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction("android.intent.action.QUICKBOOT_POWERON");
            try {
                context.registerReceiver(powerReceiver, filter);
                powerReceiverRegistered = true;
                emit("shell_power_receiver", "registered", true);
            } catch (Throwable error) {
                emit("shell_power_receiver", "registered", false,
                        "error", summary(error));
            }
        }

        private void unregisterPowerReceiver() {
            if (!powerReceiverRegistered) return;
            powerReceiverRegistered = false;
            try {
                context.unregisterReceiver(powerReceiver);
            } catch (Throwable ignored) {
            }
        }

        private void powerStateChanged(String action) {
            boolean interactive = isInteractive();
            boolean waiting;
            synchronized (this) {
                waiting = recoveryEnabled && controllerToken == null;
            }
            emit("shell_power_state", "action", action, "interactive", interactive,
                    "recovery_waiting", waiting);
            if (!interactive) {
                handler.removeCallbacks(recoveryRunnable);
                if (waiting) {
                    emit("controller_recovery_deferred",
                            "reason", "android_not_interactive", "action", action);
                    scheduleWakeCheck();
                }
                return;
            }
            handler.removeCallbacks(wakeCheckRunnable);
            if (waiting) scheduleRecovery(RECOVERY_WAKE_SETTLE_MS, "android_wake");
        }

        private void scheduleWakeCheck() {
            handler.removeCallbacks(wakeCheckRunnable);
            handler.postDelayed(wakeCheckRunnable, RECOVERY_WAKE_CHECK_MS);
        }

        private void checkDeferredRecovery() {
            boolean waiting;
            synchronized (this) {
                waiting = recoveryEnabled && controllerToken == null;
            }
            if (!waiting) return;
            if (isInteractive()) {
                emit("shell_power_state", "action", "wake_poll", "interactive", true,
                        "recovery_waiting", true);
                scheduleRecovery(RECOVERY_WAKE_SETTLE_MS, "wake_poll");
            } else {
                scheduleWakeCheck();
            }
        }

        private boolean isInteractive() {
            try {
                return powerManager != null && powerManager.isInteractive();
            } catch (Throwable error) {
                emit("shell_power_state_error", "error", summary(error));
                return false;
            }
        }

        private void scheduleRecovery(long delayMs, String reason) {
            boolean attached;
            boolean enabled;
            synchronized (this) {
                attached = controllerToken != null;
                enabled = recoveryEnabled;
            }
            if (!shouldAttemptRecovery(enabled, attached, isInteractive(),
                    recoveryCommandInFlight)) return;
            handler.removeCallbacks(recoveryRunnable);
            handler.postDelayed(recoveryRunnable, delayMs);
            emit("controller_recovery_scheduled", "reason", reason,
                    "delay_ms", delayMs);
        }

        private void attemptRecovery() {
            boolean attached;
            boolean enabled;
            synchronized (this) {
                attached = controllerToken != null;
                enabled = recoveryEnabled;
            }
            boolean interactive = isInteractive();
            if (!shouldAttemptRecovery(enabled, attached, interactive,
                    recoveryCommandInFlight)) {
                emit("controller_recovery_skipped", "recovery_enabled", enabled,
                        "controller_attached", attached, "interactive", interactive,
                        "command_in_flight", recoveryCommandInFlight);
                return;
            }
            if (awaitingControllerAttach) {
                awaitingControllerAttach = false;
                emit("controller_attach_timeout", "timeout_ms", RECOVERY_RETRY_MS);
            }
            recoveryCommandInFlight = true;
            emit("recovery_broadcast_requested", "interactive", true);
            recoveryWorker.execute(() -> {
                RecoveryCommandResult result = runRecoveryCommand();
                handler.post(() -> finishRecoveryCommand(result));
            });
        }

        private void finishRecoveryCommand(RecoveryCommandResult result) {
            recoveryCommandInFlight = false;
            boolean attached;
            boolean enabled;
            synchronized (this) {
                attached = controllerToken != null;
                enabled = recoveryEnabled;
            }
            emit("controller_recovery_command", "ok", result.ok,
                    "exit_code", result.exitCode, "elapsed_ms", result.elapsedMs,
                    "output", result.output, "error", result.error,
                    "controller_attached", attached);
            awaitingControllerAttach = result.ok && !attached;
            if (shouldAttemptRecovery(enabled, attached, isInteractive(), false)) {
                scheduleRecovery(RECOVERY_RETRY_MS,
                        result.ok ? "controller_not_attached" : "command_failed");
            }
        }

        private static RecoveryCommandResult runRecoveryCommand() {
            long startedAt = SystemClock.elapsedRealtime();
            java.lang.Process process = null;
            try {
                process = new ProcessBuilder(recoveryCommandForTest())
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(RECOVERY_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                    return new RecoveryCommandResult(false, -1, "", "timeout",
                            SystemClock.elapsedRealtime() - startedAt);
                }
                String output = readFully(process.getInputStream()).trim();
                int exitCode = process.exitValue();
                return new RecoveryCommandResult(exitCode == 0, exitCode, output,
                        exitCode == 0 ? "" : "exit_code=" + exitCode,
                        SystemClock.elapsedRealtime() - startedAt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new RecoveryCommandResult(false, -1, "", "interrupted",
                        SystemClock.elapsedRealtime() - startedAt);
            } catch (Throwable error) {
                return new RecoveryCommandResult(false, -1, "", summary(error),
                        SystemClock.elapsedRealtime() - startedAt);
            } finally {
                if (process != null) process.destroy();
            }
        }

        static boolean shouldAttemptRecovery(
                boolean enabled, boolean attached, boolean interactive, boolean inFlight) {
            return enabled && !attached && interactive && !inFlight;
        }

        static String[] recoveryCommandForTest() {
            return new String[]{
                    "am", "broadcast", "--user", "0", "--include-stopped-packages",
                    "--receiver-foreground", "--async",
                    "-a", GuardRecovery.ACTION_SHELL_RECOVERY,
                    "-n", CameraHelperMain.PACKAGE_NAME + "/.ShellRecoveryReceiver"
            };
        }

        private static String readFully(InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }

        private static final class RecoveryCommandResult {
            final boolean ok;
            final int exitCode;
            final String output;
            final String error;
            final long elapsedMs;

            RecoveryCommandResult(
                    boolean ok, int exitCode, String output, String error, long elapsedMs) {
                this.ok = ok;
                this.exitCode = exitCode;
                this.output = output == null ? "" : output;
                this.error = error == null ? "" : error;
                this.elapsedMs = elapsedMs;
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
