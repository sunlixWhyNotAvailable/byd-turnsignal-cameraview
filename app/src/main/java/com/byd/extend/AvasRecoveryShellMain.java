package com.byd.extend;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Shell-owned singleton that requests only the fixed app recovery broadcast every 30 seconds. */
public final class AvasRecoveryShellMain {
    private static final long PERIOD_MS = 30_000L;
    private static final long COMMAND_TIMEOUT_MS = 10_000L;
    private static final long MAX_LOG_BYTES = 256L * 1024L;
    private static final String COMPONENT =
            "com.byd.extend/com.byd.extend.ShellRecoveryReceiver";

    private AvasRecoveryShellMain() {}

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("Shell UID required");
        if (args.length != 2) throw new IllegalArgumentException(
                "usage: AvasRecoveryShellMain <appUid> <apkIdentity>");
        int appUid = Integer.parseInt(args[0]);
        String apkIdentity = args[1];
        RandomAccessFile ownerFile = new RandomAccessFile(
                AvasRecoveryDaemonProtocol.LOCK_PATH, "rw");
        FileLock owner = ownerFile.getChannel().tryLock();
        if (owner == null) {
            ownerFile.close();
            return;
        }
        Looper.prepareMainLooper();
        Context context = TurnSignalShellMain.avasShellContext(
                TurnSignalShellMain.systemContext());
        if (!installedIdentityMatches(context, appUid, apkIdentity)) {
            throw new SecurityException("Application identity mismatch");
        }
        AvasShellSettings settings = new AvasShellSettings(context);
        RecoveryBinder binder = new RecoveryBinder(context, settings, appUid, apkIdentity);
        binder.attachInterface(null, AvasRecoveryDaemonProtocol.DESCRIPTOR);
        try {
            settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, 1);
            Method addService = Class.forName("android.os.ServiceManager")
                    .getMethod("addService", String.class, IBinder.class);
            addService.invoke(null, AvasRecoveryDaemonProtocol.SERVICE_NAME, binder);
            journal("daemon_start", "app_uid", appUid, "apk_identity", apkIdentity);
            new Thread(() -> keepAlive(settings, binder), "avas-recovery-loop").start();
            System.out.println("READY pid=" + Process.myPid()
                    + " protocol=" + AvasRecoveryDaemonProtocol.VERSION);
            System.out.flush();
            Looper.loop();
        } finally {
            try { settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, 0); }
            catch (Throwable ignored) {}
            settings.close();
            owner.release();
            ownerFile.close();
        }
    }

    private static void keepAlive(AvasShellSettings settings, RecoveryBinder binder) {
        while (binder.running) {
            long started = SystemClock.elapsedRealtime();
            try {
                if (settings.getInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, 0) != 1) break;
                if (!installedIdentityMatches(binder.context, binder.appUid,
                        binder.apkIdentity)) {
                    try {
                        settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, 0);
                    } catch (Throwable failure) {
                        journal("installation_disable_failed", "error", summary(failure));
                    }
                    journal("installation_identity_mismatch",
                            "app_uid", binder.appUid,
                            "apk_identity", binder.apkIdentity);
                    break;
                }
                java.lang.Process command = new ProcessBuilder(
                        "/system/bin/am", "broadcast", "--user",
                        Integer.toString(AvasRecoveryPolicy.userIdForUid(binder.appUid)),
                        "--include-stopped-packages",
                        "-a", GuardRecovery.ACTION_SHELL_RECOVERY,
                        "-n", COMPONENT).redirectErrorStream(true)
                        .redirectOutput(new File("/dev/null")).start();
                boolean complete;
                try {
                    complete = command.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!complete) {
                        command.destroy();
                        command.waitFor(1, TimeUnit.SECONDS);
                    }
                    journal("recovery_request", "complete", complete,
                            "exit", complete ? command.exitValue() : -1);
                } finally {
                    if (command.isAlive()) command.destroy();
                    try { command.getInputStream().close(); } catch (Throwable ignored) {}
                    try { command.getErrorStream().close(); } catch (Throwable ignored) {}
                    try { command.getOutputStream().close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable failure) {
                journal("recovery_request_failed", "error", summary(failure));
            }
            long remaining = PERIOD_MS - (SystemClock.elapsedRealtime() - started);
            while (binder.running && remaining > 0) {
                try {
                    Thread.sleep(Math.min(remaining, 1_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    binder.running = false;
                }
                remaining = PERIOD_MS - (SystemClock.elapsedRealtime() - started);
            }
        }
        journal("daemon_exit");
        System.exit(0);
    }

    static final class RecoveryBinder extends Binder {
        private final Context context;
        private final AvasShellSettings settings;
        private final int appUid;
        private final String apkIdentity;
        volatile boolean running = true;

        RecoveryBinder(Context context, AvasShellSettings settings,
                int appUid, String apkIdentity) {
            this.context = context;
            this.settings = settings;
            this.appUid = appUid;
            this.apkIdentity = apkIdentity;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(AvasRecoveryDaemonProtocol.DESCRIPTOR);
                return true;
            }
            if (!AvasRecoveryDaemonProtocol.isCallerAllowed(Binder.getCallingUid(), appUid)) {
                reply.writeException(new SecurityException("Caller UID denied"));
                return true;
            }
            long identity = Binder.clearCallingIdentity();
            try {
                data.enforceInterface(AvasRecoveryDaemonProtocol.DESCRIPTOR);
                if (code == AvasRecoveryDaemonProtocol.TX_PING) {
                    reply.writeNoException();
                    reply.writeInt(AvasRecoveryDaemonProtocol.VERSION);
                    reply.writeInt(Process.myPid());
                    reply.writeString(apkIdentity);
                    return true;
                }
                if (code == AvasRecoveryDaemonProtocol.TX_SET_ENABLED) {
                    boolean enabled = data.readInt() != 0;
                    settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, enabled ? 1 : 0);
                    running = enabled;
                    reply.writeNoException();
                    return true;
                }
                if (code == AvasRecoveryDaemonProtocol.TX_SHUTDOWN) {
                    settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, 0);
                    running = false;
                    reply.writeNoException();
                    return true;
                }
            } catch (Throwable failure) {
                reply.writeException(new IllegalStateException(summary(failure)));
                return true;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return false;
        }
    }

    private static boolean installedIdentityMatches(
            Context context, int expectedUid, String expectedIdentity) {
        try {
            PackageManager packages = context.getPackageManager();
            Method getPackageInfoAsUser = PackageManager.class.getMethod(
                    "getPackageInfoAsUser", String.class, int.class, int.class);
            PackageInfo installed = (PackageInfo) getPackageInfoAsUser.invoke(
                    packages, BuildConfig.APPLICATION_ID, 0,
                    AvasRecoveryPolicy.userIdForUid(expectedUid));
            return AvasRecoveryPolicy.installedIdentityMatches(
                    expectedUid, expectedIdentity,
                    installed.applicationInfo == null ? null : installed.applicationInfo.uid,
                    installed.applicationInfo == null ? null : installed.applicationInfo.sourceDir,
                    installed.lastUpdateTime);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static synchronized void journal(String kind, Object... fields) {
        try {
            File current = new File(AvasRecoveryDaemonProtocol.LOG_PATH);
            if (current.isFile() && current.length() >= MAX_LOG_BYTES) {
                Files.move(current.toPath(),
                        new File(AvasRecoveryDaemonProtocol.PREVIOUS_LOG_PATH).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            JSONObject event = new JSONObject()
                    .put("kind", kind)
                    .put("source", "avas_recovery_daemon")
                    .put("pid", Process.myPid())
                    .put("wall_time", new SimpleDateFormat(
                            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()))
                    .put("wall_time_ms", System.currentTimeMillis())
                    .put("elapsed_ms", SystemClock.elapsedRealtime())
                    .put("uptime_ms", SystemClock.uptimeMillis());
            try {
                event.put("boot_id", new String(Files.readAllBytes(
                        new File("/proc/sys/kernel/random/boot_id").toPath()),
                        StandardCharsets.UTF_8).trim());
            } catch (Throwable ignored) {}
            for (int index = 0; fields != null && index + 1 < fields.length; index += 2) {
                event.put(String.valueOf(fields[index]), fields[index + 1]);
            }
            try (FileOutputStream output = new FileOutputStream(current, true)) {
                output.write((event.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (Throwable ignored) {}
    }

    private static String summary(Throwable error) {
        String message = error == null ? "unknown" : error.getMessage();
        return error == null ? "unknown" : error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
