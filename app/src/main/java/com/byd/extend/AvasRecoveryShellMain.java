package com.byd.extend;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
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

/** Shell-owned singleton for app recovery, logcat recording, and Wi-Fi retention. */
public final class AvasRecoveryShellMain {
    private static final long PERIOD_MS = 30_000L;
    private static final long COMMAND_TIMEOUT_MS = 10_000L;
    private static final long MAX_LOG_BYTES = 256L * 1024L;
    private static final String COMPONENT =
            "com.byd.extend/com.byd.extend.ShellRecoveryReceiver";

    private AvasRecoveryShellMain() {}

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("Shell UID required");
        boolean clearLogcat = args.length == 3 && "--clear-logcat".equals(args[2]);
        if (args.length != 2 && args.length != 4 && args.length != 5 && !clearLogcat) {
            throw new IllegalArgumentException("usage: AvasRecoveryShellMain <appUid> <apkIdentity> "
                    + "[<recovery> <recording> [<wifi>]|--clear-logcat]");
        }
        boolean recovery = args.length == 2 || "1".equals(args[2]);
        boolean recording = args.length >= 4 && "1".equals(args[3]);
        boolean keepWifiCarOff = args.length == 5 && "1".equals(args[4]);
        if (args.length >= 4 && (!("0".equals(args[2]) || "1".equals(args[2]))
                || !("0".equals(args[3]) || "1".equals(args[3]))
                || (args.length == 5 && !("0".equals(args[4]) || "1".equals(args[4]))))) {
            throw new IllegalArgumentException("Expected boolean startup flags");
        }
        int appUid = Integer.parseInt(args[0]);
        String apkIdentity = args[1];
        RandomAccessFile ownerFile = new RandomAccessFile(
                AvasRecoveryDaemonProtocol.LOCK_PATH, "rw");
        FileLock owner = ownerFile.getChannel().tryLock();
        if (owner == null) {
            ownerFile.close();
            if (clearLogcat) throw new IllegalStateException("Recovery daemon became active; retry Clear");
            return;
        }
        Looper.prepareMainLooper();
        Context context = TurnSignalShellMain.shellContext(
                TurnSignalShellMain.systemContext());
        if (!installedIdentityMatches(context, appUid, apkIdentity)) {
            throw new SecurityException("Application identity mismatch");
        }
        ContinuousLogcatRecorder logcat = new ContinuousLogcatRecorder(
                "pid=" + Process.myPid() + " apk_identity=" + apkIdentity,
                AvasRecoveryShellMain::journal);
        if (clearLogcat) {
            try { System.out.println("CLEARED " + logcat.clearStored()); }
            finally { owner.release(); ownerFile.close(); }
            return;
        }
        AvasShellSettings settings = new AvasShellSettings(context);
        RecoveryBinder binder = new RecoveryBinder(context, settings, appUid, apkIdentity, logcat);
        binder.recoveryEnabled = recovery;
        binder.keepWifiCarOff = keepWifiCarOff;
        binder.running = recovery || recording || keepWifiCarOff;
        binder.attachInterface(null, AvasRecoveryDaemonProtocol.DESCRIPTOR);
        try {
            if (recording) logcat.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { logcat.close(); } catch (Exception ignored) {}
            }, "diagnostic-logcat-stop"));
            settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, 1);
            Method addService = Class.forName("android.os.ServiceManager")
                    .getMethod("addService", String.class, IBinder.class);
            addService.invoke(null, AvasRecoveryDaemonProtocol.SERVICE_NAME, binder);
            journal("daemon_start", "app_uid", appUid, "apk_identity", apkIdentity,
                    "keep_wifi_car_off", keepWifiCarOff);
            new Thread(() -> keepAlive(settings, binder), "avas-recovery-loop").start();
            System.out.println("READY pid=" + Process.myPid()
                    + " protocol=" + AvasRecoveryDaemonProtocol.VERSION);
            System.out.flush();
            Looper.loop();
        } finally {
            try { logcat.close(); } catch (Exception failure) {
                journal("diagnostic_logcat_stop_failed", "error", summary(failure));
            }
            try { settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, 0); }
            catch (Throwable ignored) {}
            settings.close();
            owner.release();
            ownerFile.close();
        }
    }

    private static void keepAlive(AvasShellSettings settings, RecoveryBinder binder) {
        java.lang.Process command = null;
        while (binder.running) {
            long started = SystemClock.elapsedRealtime();
            boolean cycleReady = false;
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
                cycleReady = true;
            } catch (Throwable failure) {
                journal("recovery_request_failed", "error", summary(failure));
            }
            if (cycleReady) {
                // Run the independent radio check before any app recovery request.
                checkWifiRetention(binder);
            }
            try {
                if (cycleReady) {
                    // Logging alone must neither launch AVAS nor revive the app when Auto-start is OFF.
                    if (command != null && stopCommand(command)) command = null;
                    if (command != null) {
                        journal("recovery_request_skipped", "reason", "previous_command_alive");
                    } else if (binder.recoveryEnabled) {
                        // --async avoids holding a system_server Binder thread until receiver completion.
                        command = new ProcessBuilder(
                                "/system/bin/cmd", "activity", "broadcast", "--async",
                                "--receiver-foreground", "--user",
                                Integer.toString(AvasRecoveryPolicy.userIdForUid(binder.appUid)),
                                "--include-stopped-packages",
                                "-a", GuardRecovery.ACTION_SHELL_RECOVERY,
                                "-n", COMPONENT).redirectErrorStream(true)
                                .redirectOutput(new File("/dev/null")).start();
                        try {
                            boolean complete = command.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                            journal("recovery_request", "complete", complete,
                                    "exit", complete ? command.exitValue() : -1,
                                    "request_enqueued", complete && command.exitValue() == 0);
                        } finally {
                            if (stopCommand(command)) command = null;
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                binder.running = false;
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
        if (command != null) {
            try { stopCommand(command); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        journal("daemon_exit");
        System.exit(0);
    }

    private static void checkWifiRetention(RecoveryBinder binder) {
        WifiSnapshot snapshot = binder.wifiSnapshot();
        if (!snapshot.running) {
            binder.wifiStatus("shutdown", "daemon_stopped");
            return;
        }
        if (!snapshot.enabled) {
            binder.wifiStatus("option_off", "option_off");
            return;
        }
        String operation = "Context.getSystemService(WIFI_SERVICE)/WifiManager.getWifiState";
        long commandStarted = -1L;
        try {
            WifiRetentionShell.WifiStateRead wifiRead =
                    WifiRetentionShell.readWifiState(binder.context);
            if (wifiRead.failure != null || wifiRead.state == null) {
                String details = diagnostic(wifiRead.failure);
                binder.wifiStatus("wifi_state_unavailable:" + details,
                        "wifi_state_unknown", "operation", operation,
                        "timeout_ms", WifiRetentionShell.WIFI_STATE_READ_TIMEOUT_MS,
                        "elapsed_ms", wifiRead.elapsedMs,
                        "error_class", wifiRead.failure == null ? "unknown"
                                : wifiRead.failure.getClass().getName(),
                        "error", details);
                return;
            }
            int wifiState = wifiRead.state;
            if (wifiState != WifiManager.WIFI_STATE_DISABLED) {
                String reason = wifiState == WifiManager.WIFI_STATE_ENABLED
                        ? "wifi_already_enabled" : "wifi_state_not_disabled";
                binder.wifiStatus("wifi_state:" + wifiState, reason, "wifi_state", wifiState);
                return;
            }
            if (!binder.isWifiRevisionCurrent(snapshot.revision)) {
                reportWifiInactive(binder);
                return;
            }

            operation = "service call autoservice 5 i32 1001 i32 315621418";
            WifiRetentionShell.CommandResult power = WifiRetentionShell.readVehiclePower();
            if (power.failure != null || power.exitCode != 0) {
                binder.wifiStatus(power.statusKey("vehicle_power_read"),
                        "vehicle_power_read_failed", "operation", "service call autoservice 5",
                        "exit_code", power.exitCode, "elapsed_ms", power.elapsedMs,
                        "output", power.output, "output_truncated", power.outputTruncated,
                        "error", diagnostic(power.failure));
                return;
            }
            if (power.outputTruncated) {
                binder.wifiStatus(power.statusKey("vehicle_power_read"),
                        "vehicle_power_state_unknown", "operation", "service call autoservice 5",
                        "elapsed_ms", power.elapsedMs, "output", power.output,
                        "output_truncated", true);
                return;
            }
            Boolean vehiclePoweredOff = WifiRetentionShell.parseVehiclePoweredOff(power.output);
            if (vehiclePoweredOff == null) {
                binder.wifiStatus("vehicle_power_unknown:" + power.output,
                        "vehicle_power_state_unknown", "operation", "service call autoservice 5",
                        "elapsed_ms", power.elapsedMs, "output", power.output,
                        "output_truncated", power.outputTruncated);
                return;
            }
            if (!WifiRetentionShell.shouldEnableWifi(snapshot.enabled, snapshot.running,
                    true, vehiclePoweredOff)) {
                binder.wifiStatus("vehicle_powered_on", "vehicle_powered_on",
                        "elapsed_ms", power.elapsedMs, "output", power.output);
                return;
            }
            if (!binder.isWifiRevisionCurrent(snapshot.revision)) {
                reportWifiInactive(binder);
                return;
            }

            operation = "svc wifi enable";
            commandStarted = WifiRetentionShell.elapsedRealtime();
            java.lang.Process command = binder.startWifiEnableIfCurrent(snapshot.revision);
            if (command == null) {
                reportWifiInactive(binder);
                return;
            }
            WifiRetentionShell.CommandResult result = WifiRetentionShell.finishWifiEnable(
                    command, commandStarted);
            boolean stillEnabled = binder.isWifiRevisionCurrent(snapshot.revision);
            binder.clearWifiCommand(command);
            WifiSnapshot current = stillEnabled ? null : binder.wifiSnapshot();
            String cancelReason = current == null ? ""
                    : current.running && current.enabled ? "configuration_changed"
                    : current.running ? "option_off" : "daemon_stopped";
            String commandOutcome = result.failure != null ? "exception"
                    : result.exitCode == 0 ? "exit_zero" : "exit_nonzero";
            String outcome = !stillEnabled ? "cancelled"
                    : result.failure != null || result.exitCode != 0 ? "failed" : "requested";
            journal("wifi_retention_enable_attempt", "operation", "svc wifi enable",
                    "config_revision", snapshot.revision, "outcome", outcome,
                    "command_outcome", commandOutcome, "cancel_reason", cancelReason,
                    "exit_code", result.exitCode, "elapsed_ms", result.elapsedMs,
                    "output", result.output, "output_truncated", result.outputTruncated,
                    "error", diagnostic(result.failure));
            if (!stillEnabled) {
                binder.wifiStatus(result.statusKey("wifi_enable_cancelled") + ":" + cancelReason,
                        "wifi_enable_cancelled", "operation", "svc wifi enable",
                        "reason", cancelReason, "exit_code", result.exitCode,
                        "elapsed_ms", result.elapsedMs, "output", result.output,
                        "output_truncated", result.outputTruncated,
                        "error", diagnostic(result.failure));
            } else {
                boolean commandOk = result.exitCode == 0;
                binder.wifiStatus(result.statusKey("wifi_enable"),
                        commandOk ? "wifi_enable_requested" : "wifi_enable_failed",
                        "operation", "svc wifi enable", "exit_code", result.exitCode,
                        "elapsed_ms", result.elapsedMs, "output", result.output,
                        "output_truncated", result.outputTruncated,
                        "error", diagnostic(result.failure));
            }
        } catch (Throwable failure) {
            String details = diagnostic(failure);
            if ("svc wifi enable".equals(operation)) {
                boolean current = binder.isWifiRevisionCurrent(snapshot.revision);
                WifiSnapshot state = current ? null : binder.wifiSnapshot();
                String cancelReason = current ? "" : state.running && state.enabled
                        ? "configuration_changed" : state.running ? "option_off" : "daemon_stopped";
                journal("wifi_retention_enable_attempt", "operation", "svc wifi enable",
                        "config_revision", snapshot.revision,
                        "outcome", current ? "start_failed" : "cancelled",
                        "command_outcome", "start_failed", "cancel_reason", cancelReason,
                        "exit_code", -1,
                        "elapsed_ms", commandStarted < 0 ? 0L
                                : WifiRetentionShell.elapsedRealtime() - commandStarted,
                        "output", "", "output_truncated", false, "error", details);
                if (!current) {
                    reportWifiInactive(binder);
                } else {
                    binder.wifiStatus("wifi_enable_start_failed:" + details,
                            "wifi_enable_failed", "operation", operation, "error", details);
                }
            } else if (binder.isWifiRevisionCurrent(snapshot.revision)) {
                binder.wifiStatus("wifi_check_failed:" + details, "wifi_check_failed",
                        "operation", operation, "error", details);
            } else {
                reportWifiInactive(binder);
            }
        }
    }

    private static void reportWifiInactive(RecoveryBinder binder) {
        WifiSnapshot current = binder.wifiSnapshot();
        binder.wifiStatus(current.running && current.enabled ? "wifi_option_changed"
                        : current.running ? "option_off" : "shutdown",
                current.running && current.enabled ? "configuration_changed"
                        : current.running ? "option_off" : "daemon_stopped");
    }

    private static String diagnostic(Throwable failure) {
        if (failure == null) return "";
        java.io.StringWriter output = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(output));
        return output.toString();
    }

    private static void cancelWifiCommand(java.lang.Process command, String reason) {
        if (command == null) return;
        try {
            command.destroyForcibly();
        } catch (Throwable failure) {
            journal("wifi_retention_cancel_failed", "operation", "Process.destroyForcibly",
                    "reason", reason, "error", diagnostic(failure));
        }
    }

    /** Own the actual cmd process, not am's shell wrapper; never replace a surviving child. */
    static boolean stopCommand(java.lang.Process command) throws InterruptedException {
        try {
            if (command.isAlive()) {
                command.destroy();
                if (!command.waitFor(1, TimeUnit.SECONDS)) {
                    command.destroyForcibly();
                    command.waitFor(1, TimeUnit.SECONDS);
                }
            }
            return !command.isAlive();
        } catch (InterruptedException interrupted) {
            command.destroyForcibly();
            throw interrupted;
        } finally {
            try { command.getInputStream().close(); } catch (Exception ignored) {}
            try { command.getErrorStream().close(); } catch (Exception ignored) {}
            try { command.getOutputStream().close(); } catch (Exception ignored) {}
        }
    }

    static final class RecoveryBinder extends Binder {
        private final Context context;
        private final AvasShellSettings settings;
        private final int appUid;
        private final String apkIdentity;
        private final ContinuousLogcatRecorder logcat;
        private final Object wifiLock = new Object();
        private long wifiRevision;
        private java.lang.Process wifiCommand;
        private String lastWifiStatus;
        volatile boolean running = true;
        volatile boolean recoveryEnabled;
        volatile boolean keepWifiCarOff;

        RecoveryBinder(Context context, AvasShellSettings settings,
                int appUid, String apkIdentity, ContinuousLogcatRecorder logcat) {
            this.context = context;
            this.settings = settings;
            this.appUid = appUid;
            this.apkIdentity = apkIdentity;
            this.logcat = logcat;
        }

        void setEnabled(boolean recovery, boolean recording, boolean keepWifiCarOff) {
            java.lang.Process cancel = null;
            boolean wifiChanged;
            synchronized (wifiLock) {
                boolean wasRunning = running;
                wifiChanged = this.keepWifiCarOff != keepWifiCarOff;
                recoveryEnabled = recovery;
                this.keepWifiCarOff = keepWifiCarOff;
                running = recovery || recording || keepWifiCarOff;
                if (wifiChanged || wasRunning != running) wifiRevision++;
                if ((!keepWifiCarOff || !running) && wifiCommand != null) cancel = wifiCommand;
            }
            if (cancel != null) cancelWifiCommand(cancel, keepWifiCarOff ? "daemon_stopped" : "option_off");
            if (wifiChanged) {
                journal("wifi_retention_option_changed", "enabled", keepWifiCarOff);
            }
        }

        void shutdown() {
            java.lang.Process cancel;
            boolean wifiWasEnabled;
            synchronized (wifiLock) {
                wifiWasEnabled = keepWifiCarOff;
                wifiRevision++;
                running = false;
                recoveryEnabled = false;
                keepWifiCarOff = false;
                cancel = wifiCommand;
            }
            if (cancel != null) cancelWifiCommand(cancel, "shutdown");
            if (wifiWasEnabled) {
                journal("wifi_retention_option_changed", "enabled", false, "reason", "shutdown");
            }
        }

        WifiSnapshot wifiSnapshot() {
            synchronized (wifiLock) {
                return new WifiSnapshot(running, keepWifiCarOff, wifiRevision);
            }
        }

        boolean isWifiRevisionCurrent(long revision) {
            synchronized (wifiLock) {
                return running && keepWifiCarOff && wifiRevision == revision;
            }
        }

        java.lang.Process startWifiEnableIfCurrent(long revision) throws java.io.IOException {
            synchronized (wifiLock) {
                if (!running || !keepWifiCarOff || wifiRevision != revision) return null;
                wifiCommand = WifiRetentionShell.startWifiEnable();
                return wifiCommand;
            }
        }

        void clearWifiCommand(java.lang.Process command) {
            synchronized (wifiLock) {
                if (wifiCommand == command) wifiCommand = null;
            }
        }

        synchronized void wifiStatus(String key, String reason, Object... fields) {
            if (key.equals(lastWifiStatus)) return;
            lastWifiStatus = key;
            Object[] event = new Object[fields.length + 2];
            event[0] = "reason";
            event[1] = reason;
            System.arraycopy(fields, 0, event, 2, fields.length);
            journal("wifi_retention_state", event);
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
                    boolean recording = data.readInt() != 0;
                    boolean keepWifiCarOff = data.readInt() != 0;
                    setEnabled(enabled, recording, keepWifiCarOff);
                    if (recording) logcat.start();
                    else logcat.close();
                    settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING,
                            running ? 1 : 0);
                    reply.writeNoException();
                    return true;
                }
                if (code == AvasRecoveryDaemonProtocol.TX_SHUTDOWN) {
                    shutdown();
                    settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, 0);
                    reply.writeNoException();
                    return true;
                }
                if (code == AvasRecoveryDaemonProtocol.TX_CLEAR_LOGCAT) {
                    int removed = logcat.clear();
                    journal("diagnostic_logcat_cleared", "removed", removed);
                    reply.writeNoException();
                    reply.writeInt(removed);
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

    private static final class WifiSnapshot {
        final boolean running;
        final boolean enabled;
        final long revision;

        WifiSnapshot(boolean running, boolean enabled, long revision) {
            this.running = running;
            this.enabled = enabled;
            this.revision = revision;
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
