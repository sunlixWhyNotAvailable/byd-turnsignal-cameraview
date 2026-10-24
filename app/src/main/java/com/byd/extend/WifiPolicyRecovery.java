package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Reconciles the app-owned Wi-Fi policy journal through the existing authorized shell path. */
public final class WifiPolicyRecovery {
    private static final String TAG = "WifiPolicyRecovery";
    private static final String SLEEP_POLICY = "wifi_sleep_policy";
    private static final String OFF_SWITCH = "byd_off_wifi_switch";

    private final Context context;
    private final SharedPreferences preferences;
    private final BiConsumer<String, Object[]> events;

    public WifiPolicyRecovery(Context context, BiConsumer<String, Object[]> events) {
        Context supplied = Objects.requireNonNull(context, "context");
        Context appContext = supplied.getApplicationContext();
        this.context = appContext == null ? supplied : appContext;
        this.preferences = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        this.events = events;
    }

    /** Runs on the controller's serialized worker; this class creates no worker or timer. */
    public void reconcile() {
        WifiPolicyState.Request request = null;
        String step = "begin_attempt";
        String setting = null;
        try {
            if (stopped()) return;
            request = beginAttempt();
            if (request == null) return;
            event("attempt", request, "captured", request.captured);

            WifiPolicyState.Snapshot original = null;
            if (request.captured) {
                step = "decode_original_snapshot";
                original = WifiPolicyState.Snapshot.decode(request.snapshot);
            } else if (WifiPolicyState.RESTORE.equals(request.operation)) {
                step = "complete_unapplied_restore";
                if (finish(request)) {
                    event("restore_skipped", request,
                            "reason", "original_snapshot_not_captured; system policies unchanged");
                }
                return;
            }

            if (original == null) {
                step = "read_original_snapshot";
                setting = SLEEP_POLICY;
                String sleepPolicy = read(request, SLEEP_POLICY);
                setting = OFF_SWITCH;
                String offSwitch = read(request, OFF_SWITCH);
                original = new WifiPolicyState.Snapshot(sleepPolicy, offSwitch);
                step = "persist_original_snapshot";
                if (!capture(request, original)) return;
                event("snapshot_saved", request, "sleep", valueText(original.sleepPolicy),
                        "off", valueText(original.offSwitch));
            }

            WifiPolicyState.Snapshot target = WifiPolicyState.APPLY.equals(request.operation)
                    ? new WifiPolicyState.Snapshot("2", "1") : original;
            step = "reconcile_settings";
            setting = "both";
            applyBoth(request, target);

            step = "finish_operation";
            if (finish(request)) {
                event("complete", request, "operation", request.operation);
            }
        } catch (OperationSupersededException ignored) {
            // A newer toggle or user shutdown owns the next reconciliation.
        } catch (Exception failure) {
            reportFailure(step, setting, request, failure);
        }
    }

    private WifiPolicyState.Request beginAttempt() {
        synchronized (WifiPolicyState.LOCK) {
            if (stopped()) return null;
            WifiPolicyState.Journal current = WifiPolicyState.Journal.read(preferences);
            if (!current.pending() || !current.intentConsistent()
                    || !current.mayAttempt(SystemClock.elapsedRealtime())) return null;
            WifiPolicyState.Journal attempted = current.attempted(SystemClock.elapsedRealtime());
            WifiPolicyState.commit(preferences, attempted, current,
                    "Wi-Fi policy retry timestamp was not persisted: generation="
                            + current.generation + " operation=" + current.operation);
            return attempted.request();
        }
    }

    private boolean capture(WifiPolicyState.Request request, WifiPolicyState.Snapshot original) {
        synchronized (WifiPolicyState.LOCK) {
            if (stopped()) return false;
            WifiPolicyState.Journal current = WifiPolicyState.Journal.read(preferences);
            WifiPolicyState.Journal captured = current.capture(request, original);
            if (captured == null) return false;
            WifiPolicyState.commit(preferences, captured, current,
                    "Original Wi-Fi policies were not durably captured; settings unchanged"
                            + ": generation=" + request.generation);
            return true;
        }
    }

    private boolean finish(WifiPolicyState.Request request) {
        synchronized (WifiPolicyState.LOCK) {
            if (stopped()) return false;
            WifiPolicyState.Journal current = WifiPolicyState.Journal.read(preferences);
            WifiPolicyState.Journal finished = current.finish(request);
            if (finished == null) return false;
            WifiPolicyState.commit(preferences, finished, current,
                    "Wi-Fi policy completion was not persisted: generation="
                            + request.generation + " operation=" + request.operation);
            return true;
        }
    }

    private void apply(WifiPolicyState.Request request, String key, String target) throws Exception {
        String before = read(request, key);
        if (!Objects.equals(before, target)) {
            String command = target == null ? "settings delete global " + key
                    : "settings put global " + key + " " + target;
            execute(request, command);
        }
        String after = read(request, key);
        if (!Objects.equals(after, target)) {
            throw new IllegalStateException("Wi-Fi policy readback mismatch: key=" + key
                    + " before=" + valueText(before) + " target=" + valueText(target)
                    + " after=" + valueText(after));
        }
        event("verified", request, "key", key, "before", valueText(before),
                "target", valueText(target), "after", valueText(after));
    }

    private void applyBoth(WifiPolicyState.Request request,
            WifiPolicyState.Snapshot target) throws Exception {
        Exception failure = null;
        try {
            apply(request, SLEEP_POLICY, target.sleepPolicy);
        } catch (OperationSupersededException superseded) {
            throw superseded;
        } catch (Exception error) {
            failure = error;
        }

        boolean stillCurrent;
        try {
            stillCurrent = current(request);
        } catch (Exception checkFailure) {
            if (failure != null) {
                failure.addSuppressed(checkFailure);
                throw failure;
            }
            throw checkFailure;
        }
        if (!stillCurrent) {
            if (failure != null) throw failure;
            throw new OperationSupersededException();
        }

        try {
            apply(request, OFF_SWITCH, target.offSwitch);
        } catch (OperationSupersededException superseded) {
            if (failure != null) {
                failure.addSuppressed(superseded);
                throw failure;
            }
            throw superseded;
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private String read(WifiPolicyState.Request request, String key) throws Exception {
        String output = execute(request, "settings get global " + key);
        String value = output.trim();
        if ("null".equals(value)) return null;
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid Wi-Fi policy response: key=" + key
                    + " output=" + output, error);
        }
        return value;
    }

    private String execute(WifiPolicyState.Request request, String command) throws Exception {
        if (!current(request)) throw new OperationSupersededException();
        LocalAdbClient.Result result;
        try (AdbAuthorizationSession.RecoveryOperation recovery =
                     LocalAdbClient.AUTHORIZATION.beginRecovery()) {
            if (recovery == null) {
                event("deferred", request, "reason", "adb_authorization_pending",
                        "command", command);
                throw new OperationSupersededException();
            }
            result = LocalAdbClient.executeRecoveryProof(
                    context, command, recovery,
                    (kind, fields) -> forwardAdbEvent(kind, fields, request, command));
        }
        event("command", request, "command", command, "ok", result.ok,
                "authorization_required", result.authorizationRequired,
                "command_read_timeout", result.commandReadTimeout,
                "transport_unavailable", result.transportUnavailable,
                "superseded", result.superseded, "exit_code", result.exitCode,
                "error", result.error, "output", result.output);
        if (result.superseded) throw new OperationSupersededException();
        if (!result.ok) {
            throw new IOException("LocalAdbClient.executeAuthorized failed: command=" + command
                    + " authorizationRequired=" + result.authorizationRequired
                    + " commandReadTimeout=" + result.commandReadTimeout
                    + " transportUnavailable=" + result.transportUnavailable
                    + " superseded=" + result.superseded + " exitCode=" + result.exitCode
                    + " error=" + result.error + " output=" + result.output);
        }
        if (!current(request)) throw new OperationSupersededException();
        return result.output == null ? "" : result.output;
    }

    private boolean current(WifiPolicyState.Request request) {
        if (stopped()) return false;
        synchronized (WifiPolicyState.LOCK) {
            return !stopped() && WifiPolicyState.Journal.read(preferences).isCurrent(request);
        }
    }

    private boolean stopped() {
        return Thread.currentThread().isInterrupted()
                || preferences.getBoolean(GuardRecovery.KEY_USER_SHUTDOWN, false);
    }

    private void event(String stage, WifiPolicyState.Request request, Object... fields) {
        Object[] payload = new Object[fields.length + 4];
        payload[0] = "generation";
        payload[1] = request.generation;
        payload[2] = "operation";
        payload[3] = request.operation;
        System.arraycopy(fields, 0, payload, 4, fields.length);
        forwardEvent("wifi_policy_" + stage, payload);
    }

    private void forwardEvent(String kind, Object[] fields) {
        if (events == null) return;
        try {
            events.accept(kind, fields == null ? new Object[0] : fields);
        } catch (RuntimeException loggingFailure) {
            Log.e(TAG, "Could not report Wi-Fi policy event " + kind, loggingFailure);
        }
    }

    private void forwardAdbEvent(String kind, Object[] fields,
            WifiPolicyState.Request request, String command) {
        Object[] diagnostic = new Object[(fields == null ? 0 : fields.length) + 6];
        diagnostic[0] = "wifi_generation";
        diagnostic[1] = request.generation;
        diagnostic[2] = "wifi_operation";
        diagnostic[3] = request.operation;
        diagnostic[4] = "wifi_command";
        diagnostic[5] = command;
        if (fields != null) System.arraycopy(fields, 0, diagnostic, 6, fields.length);
        forwardEvent(kind, diagnostic);
    }

    private void reportFailure(String step, String setting,
            WifiPolicyState.Request request, Exception failure) {
        Object[] contextFields = {"generation", request == null ? -1L : request.generation,
                "wifi_operation", request == null ? "unknown" : request.operation,
                "setting", setting == null ? "unknown" : setting};
        try {
            AdbRecoveryDiagnostics.failure(events, "wifi_policy_failed", step, failure, contextFields);
        } catch (RuntimeException loggingFailure) {
            failure.addSuppressed(loggingFailure);
            Log.e(TAG, "Wi-Fi policy " + step + " failed and diagnostic delivery also failed", failure);
        }
    }

    private static String valueText(String value) {
        return value == null ? "<absent>" : value;
    }

    private static final class OperationSupersededException extends Exception {
        OperationSupersededException() { super("Wi-Fi policy operation superseded or Shutdown requested"); }
    }
}
