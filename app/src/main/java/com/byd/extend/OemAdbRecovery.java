package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One app-UID OEM lane. A logical deadline never claims to cancel a server-side write. */
final class OemAdbRecovery {
    interface Listener { void finished(Attempt attempt, boolean completed, boolean late); }
    private static OemAdbRecovery instance;

    static synchronized OemAdbRecovery get(Context context) {
        if (instance == null) instance = new OemAdbRecovery(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences settings;
    private final OemAdbRecoveryState state;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "BydOemAdbRecovery");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Attempt> current = new AtomicReference<>();
    private final AtomicReference<Observer> observer = new AtomicReference<>();

    private static final class Observer {
        final Object owner;
        final Listener listener;
        Observer(Object owner, Listener listener) { this.owner = owner; this.listener = listener; }
    }

    private OemAdbRecovery(Context context) {
        this.context = context;
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        state = new OemAdbRecoveryState(context.getSharedPreferences("adb_recovery_runtime", Context.MODE_PRIVATE));
    }

    void observe(Object owner, Listener listener) {
        observer.set(new Observer(owner, listener));
    }

    synchronized Attempt start(long cycle, boolean explicitRetry, Object owner, Listener listener,
            AdbRecoveryRuntime.LogSink log) throws IOException {
        Attempt pending = current.get();
        if (pending != null) {
            log.log("adb_oem_skipped", "reason", "call_still_pending", "attempt", pending.ticket.id,
                    "stage", pending.stage);
            return null;
        }
        String boot = "";
        int bootCount = -1;
        try {
            boot = new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),
                    StandardCharsets.US_ASCII).trim();
        } catch (IOException | RuntimeException error) {
            AdbRecoveryDiagnostics.failure(log::log, "adb_oem_boot_identity_failed", "read_boot_id", error);
        }
        try { bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException error) {
            AdbRecoveryDiagnostics.failure(log::log, "adb_oem_boot_identity_failed", "read_boot_count", error);
        }
        if (!state.eligible(cycle, explicitRetry, boot, bootCount)) {
            log.log("adb_oem_skipped", "reason", "attempt_consumed_or_mutation_unresolved",
                    "pending_stage", state.pendingStage(), "cycle", cycle);
            return null;
        }
        AdbAuthorizationSession.RecoveryOperation admission = LocalAdbClient.AUTHORIZATION.beginRecovery();
        if (admission == null) return null;
        Attempt attempt = new Attempt(cycle, boot, bootCount, owner, listener, log, admission);
        current.set(attempt);
        try {
            state.attempted(cycle);
            admission.register(attempt.cancellation);
            main.postDelayed(attempt.timeout, OemAdbRecoveryState.Ticket.BUDGET_MS);
            worker.execute(() -> run(attempt));
            return attempt;
        } catch (IOException | RuntimeException error) {
            current.compareAndSet(attempt, null);
            main.removeCallbacks(attempt.timeout);
            admission.unregister(attempt.cancellation);
            admission.close();
            throw error;
        }
    }

    void cancel(Object owner, String reason, boolean detach) {
        if (detach) {
            Observer registered = observer.get();
            if (registered != null && registered.owner == owner) observer.compareAndSet(registered, null);
        }
        Attempt attempt = current.get();
        if (attempt != null && attempt.owner == owner) {
            if (detach) {
                attempt.listener = null;
                attempt.owner = null;
                attempt.log = (event, fields) -> AvasRecoveryJournal.event(context, event, fields);
            }
            attempt.invalidate(reason);
        }
    }

    void restored() throws IOException { state.restored(); }

    private void run(Attempt attempt) {
        Throwable failure = null;
        try {
            attempt.event("adb_oem_started", "uid", Process.myUid(), "user", Process.myUid() / 100000,
                    "budget_ms", OemAdbRecoveryState.Ticket.BUDGET_MS);
            new OemAdbBridge(context, attempt).enable();
        } catch (Exception | LinkageError error) {
            failure = error;
        } finally {
            // An intent written before cancellation but never dispatched can be retired safely.
            if (!attempt.mutationOutstanding) {
                try { state.completed(attempt.ticket.id); }
                catch (IOException error) {
                    if (failure != null) failure.addSuppressed(error); else failure = error;
                }
            }
            if (failure != null) attempt.failure("adb_oem_failed", failure);
            boolean completed = attempt.ticket.valid(SystemClock.elapsedRealtime())
                    && !attempt.admission.cancelled();
            attempt.event("adb_oem_returned", "success", failure == null,
                    "late", !completed || attempt.reported.get(),
                    "mutation_unresolved", attempt.mutationOutstanding);
            main.removeCallbacks(attempt.timeout);
            current.compareAndSet(attempt, null);
            main.post(() -> attempt.report(completed));
        }
    }

    final class Attempt {
        final OemAdbRecoveryState.Ticket ticket = new OemAdbRecoveryState.Ticket(SystemClock.elapsedRealtime());
        final long cycle;
        final String boot;
        final int bootCount;
        volatile Object owner;
        final long expiresWallSeconds = (System.currentTimeMillis() + OemAdbRecoveryState.Ticket.BUDGET_MS) / 1000;
        final AdbAuthorizationSession.RecoveryOperation admission;
        volatile AdbRecoveryRuntime.LogSink log;
        final AtomicBoolean reported = new AtomicBoolean();
        final AtomicReference<Closeable> output = new AtomicReference<>();
        volatile Listener listener;
        volatile String stage = "admission";
        volatile boolean mutationOutstanding;
        final Closeable cancellation = () -> invalidate("foreground_rsa");
        final Runnable timeout = () -> {
            if (!ticket.valid(SystemClock.elapsedRealtime())) {
                report(false);
            }
        };

        Attempt(long cycle, String boot, int bootCount, Object owner, Listener listener,
                AdbRecoveryRuntime.LogSink log, AdbAuthorizationSession.RecoveryOperation admission) {
            this.cycle = cycle;
            this.boot = boot;
            this.bootCount = bootCount;
            this.owner = owner;
            this.listener = listener;
            this.log = log;
            this.admission = admission;
        }

        boolean waiting() { return !reported.get() && ticket.valid(SystemClock.elapsedRealtime()); }

        void invalidate(String reason) {
            if (ticket.invalidate(reason)) {
                // Called under the RSA monitor: only invalidate and post, never wait or do I/O.
                main.post(() -> report(false));
            }
        }

        void check() {
            if (admission.cancelled()) ticket.invalidate("foreground_rsa");
            if (!settings.getBoolean("adb_recovery_enabled", false)) ticket.invalidate("disabled");
            if (!ticket.valid(SystemClock.elapsedRealtime())) {
                throw new CancellationException("OEM recovery no longer admitted: " + ticket.invalidation());
            }
        }

        void before(String step, boolean mutation) throws IOException {
            check();
            stage = step;
            if (mutation) state.dispatch(ticket.id, stage, boot, bootCount);
            event("adb_oem_call", "mutation", mutation);
            // Admission is short; the Binder call itself MUST remain outside this monitor.
            admission.write(() -> {
                check();
                if (mutation) mutationOutstanding = true;
            });
        }

        void replied(boolean mutation) throws IOException {
            if (mutation) {
                mutationOutstanding = false;
                state.completed(ticket.id);
            }
        }

        void attachOutput(Closeable receiver) {
            output.set(receiver);
            try { check(); }
            catch (RuntimeException error) { closeOutput(); throw error; }
        }

        void closeOutput() {
            Closeable receiver = output.getAndSet(null);
            if (receiver == null) return;
            try { receiver.close(); }
            catch (IOException error) { failure("adb_oem_output_cleanup_failed", error); }
        }

        void report(boolean completed) {
            boolean late = !reported.compareAndSet(false, true);
            main.removeCallbacks(timeout);
            closeOutput();
            admission.unregister(cancellation);
            admission.close();
            Listener target = listener;
            if (target != null) target.finished(this, completed, late);
            else {
                Observer registered = observer.get();
                if (registered != null) registered.listener.finished(this, false, true);
            }
        }

        void event(String event, Object... fields) {
            Object[] contextFields = {"attempt", ticket.id, "cycle", cycle, "stage", stage,
                    "elapsed_ms", SystemClock.elapsedRealtime() - ticket.started};
            Object[] combined = new Object[contextFields.length + fields.length];
            System.arraycopy(contextFields, 0, combined, 0, contextFields.length);
            System.arraycopy(fields, 0, combined, contextFields.length, fields.length);
            log.log(event, combined);
        }

        void failure(String event, Throwable error) {
            AdbRecoveryDiagnostics.failure(this::event, event, stage, error);
        }
    }
}
