package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Bounded app-side reconciliation for the shell recovery singleton. */
final class AvasRecoveryDaemonController implements AutoCloseable {
    private static final long READY_TIMEOUT_MS = 3_000L;
    private static final long FAILURE_BACKOFF_MS = 30_000L;

    private final Context context;
    private final BiConsumer<String, Object[]> events;
    private final Consumer<Boolean> ownershipChanged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "avas-recovery-control"));
    private final String apkIdentity;
    private long retryAfterMs;
    private boolean closed;
    private long desiredGeneration;
    private boolean desiredRequired;
    private volatile boolean ownsRecovery;

    AvasRecoveryDaemonController(Context context, BiConsumer<String, Object[]> events,
            Consumer<Boolean> ownershipChanged) {
        this.context = context.getApplicationContext();
        this.events = events;
        this.ownershipChanged = ownershipChanged;
        apkIdentity = currentApkIdentity(this.context);
    }

    synchronized void reconcile(boolean required, String reason) {
        if (closed) return;
        if (desiredRequired != required) {
            desiredRequired = required;
            desiredGeneration++;
        }
        long generation = desiredGeneration;
        worker.execute(() -> reconcileOnWorker(required, reason, generation));
    }

    private void reconcileOnWorker(boolean required, String reason, long generation) {
        if (!isCurrent(required, generation)) return;
        DaemonPing current = ping(resolve());
        setOwnsRecovery(required && current.healthy(apkIdentity));
        if (!required) {
            retryAfterMs = 0L;
            if (current.binder != null) control(current.binder, false, true);
            emit("avas_recovery_daemon_reconcile", "required", false,
                    "reason", reason, "pid", current.pid, "status", current.error);
            return;
        }
        if (current.healthy(apkIdentity)) {
            retryAfterMs = 0L;
            control(current.binder, true, false);
            emit("avas_recovery_daemon_reconcile", "required", true,
                    "reason", reason, "pid", current.pid, "status", "ready");
            return;
        }
        if (current.binder != null) control(current.binder, false, true);
        if (!isCurrent(true, generation)) return;
        long now = SystemClock.elapsedRealtime();
        if (now < retryAfterMs) {
            emit("avas_recovery_daemon_deferred", "reason", reason,
                    "remaining_ms", retryAfterMs - now, "status", current.error);
            return;
        }
        LocalAdbClient.Result launch = LocalAdbClient.executeAuthorized(
                context, launchCommand(context.getApplicationInfo().sourceDir,
                        android.os.Process.myUid(), apkIdentity), this::emit);
        if (!isCurrent(true, generation)) {
            DaemonPing staleLaunch = ping(resolve());
            if (staleLaunch.binder != null) control(staleLaunch.binder, false, true);
            setOwnsRecovery(false);
            return;
        }
        if (!launch.ok) {
            retryAfterMs = now + FAILURE_BACKOFF_MS;
            emit("avas_recovery_daemon_launch", "ok", false,
                    "authorization_required", launch.authorizationRequired,
                    "error", launch.error, "output", launch.output);
            return;
        }
        long deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS;
        do {
            DaemonPing started = ping(resolve());
            if (started.healthy(apkIdentity)) {
                retryAfterMs = 0L;
                setOwnsRecovery(true);
                emit("avas_recovery_daemon_launch", "ok", true,
                        "pid", started.pid, "output", launch.output);
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (SystemClock.elapsedRealtime() < deadline);
        retryAfterMs = SystemClock.elapsedRealtime() + FAILURE_BACKOFF_MS;
        emit("avas_recovery_daemon_launch", "ok", false,
                "error", "readiness_timeout", "output", launch.output);
    }

    boolean ownsRecovery() {
        return ownsRecovery;
    }

    private synchronized boolean isCurrent(boolean required, long generation) {
        return desiredRequired == required && desiredGeneration == generation;
    }

    private void setOwnsRecovery(boolean owns) {
        if (ownsRecovery == owns) return;
        ownsRecovery = owns;
        if (ownershipChanged != null) ownershipChanged.accept(owns);
    }

    private void control(IBinder binder, boolean enabled, boolean shutdown) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(AvasRecoveryDaemonProtocol.DESCRIPTOR);
            int transaction = shutdown ? AvasRecoveryDaemonProtocol.TX_SHUTDOWN
                    : AvasRecoveryDaemonProtocol.TX_SET_ENABLED;
            if (!shutdown) data.writeInt(enabled ? 1 : 0);
            if (!binder.transact(transaction, data, reply, 0)) {
                throw new IllegalStateException("daemon transaction rejected");
            }
            reply.readException();
        } catch (Throwable failure) {
            emit("avas_recovery_daemon_control_failed", "shutdown", shutdown,
                    "enabled", enabled, "error", summary(failure));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private DaemonPing ping(IBinder binder) {
        if (binder == null || !binder.isBinderAlive()) return DaemonPing.failed("unavailable");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(AvasRecoveryDaemonProtocol.DESCRIPTOR);
            if (!binder.transact(AvasRecoveryDaemonProtocol.TX_PING, data, reply, 0)) {
                return DaemonPing.failed("ping_rejected");
            }
            reply.readException();
            int version = reply.readInt();
            int pid = reply.readInt();
            String identity = reply.readString();
            if (version != AvasRecoveryDaemonProtocol.VERSION) {
                return new DaemonPing(binder, pid, identity, "protocol_mismatch");
            }
            return new DaemonPing(binder, pid, identity, "");
        } catch (Throwable failure) {
            return DaemonPing.failed("ping_failed: " + summary(failure));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static IBinder resolve() {
        try {
            Method getService = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, AvasRecoveryDaemonProtocol.SERVICE_NAME);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String currentApkIdentity(Context context) {
        try {
            long update = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            return context.getApplicationInfo().sourceDir + ":" + update;
        } catch (Throwable ignored) {
            return context.getApplicationInfo().sourceDir + ":unknown";
        }
    }

    static String launchCommand(String apkPath, int appUid, String identity) {
        String apk = shellQuote(apkPath);
        String quotedIdentity = shellQuote(identity);
        String process = AvasRecoveryDaemonProtocol.PROCESS_NAME;
        return "for pid in $(pidof " + process
                + " 2>/dev/null); do kill \"$pid\" 2>/dev/null || true; done; "
                + "wait_count=0; while [ -n \"$(pidof " + process + " 2>/dev/null)\" ] "
                + "&& [ \"$wait_count\" -lt 30 ]; do sleep 0.1; "
                + "wait_count=$((wait_count + 1)); done; "
                + "if [ -n \"$(pidof " + process + " 2>/dev/null)\" ]; then "
                + "echo daemon_stop_timeout; false; else "
                + "rm -f " + AvasRecoveryDaemonProtocol.LOCK_PATH + "; "
                + ": >" + AvasRecoveryDaemonProtocol.BOOT_LOG_PATH + "; "
                + "trap '' HUP; CLASSPATH=" + apk
                + " setsid app_process /system/bin --nice-name=" + process + " "
                + AvasRecoveryDaemonProtocol.MAIN_CLASS + " " + appUid + " " + quotedIdentity
                + " </dev/null >>" + AvasRecoveryDaemonProtocol.BOOT_LOG_PATH + " 2>&1 & "
                + "for i in 1 2 3 4 5 6; do service list 2>/dev/null | grep -q "
                + AvasRecoveryDaemonProtocol.SERVICE_NAME
                + " && { echo READY; break; }; sleep 0.5; done; "
                + "service list 2>/dev/null | grep -q "
                + AvasRecoveryDaemonProtocol.SERVICE_NAME + "; fi";
    }

    private static String shellQuote(String value) {
        return "'" + String.valueOf(value).replace("'", "'\\''") + "'";
    }

    private void emit(String kind, Object... fields) {
        AvasRecoveryJournal.event(context, kind, fields);
        if (events != null) events.accept(kind, fields == null ? new Object[0] : fields);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!desiredRequired) {
            worker.execute(() -> {
                DaemonPing current = ping(resolve());
                if (current.binder != null) control(current.binder, false, true);
                setOwnsRecovery(false);
            });
        }
        worker.shutdown();
    }

    static final class DaemonPing {
        final IBinder binder;
        final int pid;
        final String identity;
        final String error;

        DaemonPing(IBinder binder, int pid, String identity, String error) {
            this.binder = binder;
            this.pid = pid;
            this.identity = identity == null ? "" : identity;
            this.error = error;
        }

        static DaemonPing failed(String error) {
            return new DaemonPing(null, -1, "", error);
        }

        boolean healthy(String expectedIdentity) {
            return binder != null && error.isEmpty() && identity.equals(expectedIdentity);
        }
    }

    private static String summary(Throwable error) {
        String message = error == null ? "unknown" : error.getMessage();
        return error == null ? "unknown" : error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
