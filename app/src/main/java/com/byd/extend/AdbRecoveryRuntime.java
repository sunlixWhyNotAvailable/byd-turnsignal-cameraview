package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Service-owned, event-driven ADB recovery owner. Construction performs no blocking I/O. */
public final class AdbRecoveryRuntime implements AutoCloseable {
    public enum Reason { START, RECONFIGURE, BOOT, WAKE, NETWORK_CHANGE, MANUAL, ADB_FAILURE }

    public interface SnapshotListener {
        void onAdbRecoverySnapshot(AdbRecoverySnapshot snapshot);
    }

    public interface LogSink {
        void log(String event, Object... fields);
    }

    private static final String SETTINGS_PREFS = "settings";
    private static final String RECOVERY_ENABLED = "adb_recovery_enabled";
    private static final String STATE_PREFS = "adb_recovery_runtime";
    private static final String CYCLE_ID = "cycle_id";
    private static final String CYCLE_ACTIVE = "cycle_active";
    private static final String HINT_SUPPRESSED = "hint_suppressed";
    private static final String TLS_OWNED = "tls_owned";
    private static final String BOOT_COUNT = "boot_count";
    private static final String LAST_START_ELAPSED = "last_start_elapsed";
    private static final String ADB_ENABLED = "adb_enabled";
    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final String PROOF_COMMAND = "echo BYD_EXTEND_ADB_RECOVERY; id -u";
    private static final String PROOF_MARKER = "BYD_EXTEND_ADB_RECOVERY";

    private final Context context;
    private final SharedPreferences settings;
    private final SharedPreferences state;
    private final SnapshotListener listener;
    private final LogSink log;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BydAdbRecovery");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AdbRecoveryCoordinator coordinator;
    private final ConnectivityManager connectivity;
    private final ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean closed;
    private volatile boolean consentPaused;
    private final ThreadLocal<Boolean> internalProof =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final AtomicReference<Reason> pendingReason =
            new AtomicReference<>(Reason.RECONFIGURE);
    private boolean networkRegistered;
    private AdbTlsDiscovery discovery;
    private volatile long discoveryGeneration;
    private AdbAuthorizationSession.RecoveryOperation operation;
    private volatile AdbRecoverySnapshot.Stage lastPrerequisite;
    private final Runnable authorizationListener = () -> {
        if (closed) return;
        if (LocalAdbClient.AUTHORIZATION.pending()) {
            waitForPrerequisite(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION);
        } else enqueue(Reason.RECONFIGURE);
    };

    private final Runnable consentRetry = () -> {
        if (!closed && !consentPaused) enqueue(Reason.RECONFIGURE);
    };

    public AdbRecoveryRuntime(Context context, SnapshotListener listener, LogSink log) {
        this.context = Objects.requireNonNull(context).getApplicationContext();
        this.listener = Objects.requireNonNull(listener);
        LogSink suppliedLog = log == null ? (event, fields) -> { } : log;
        this.log = (event, fields) -> {
            try { suppliedLog.log(event, fields); } catch (Throwable ignored) { }
        };
        settings = this.context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE);
        state = this.context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        coordinator = new AdbRecoveryCoordinator(new PreferenceStore(state));
        connectivity = (ConnectivityManager) this.context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { enqueue(Reason.NETWORK_CHANGE); }
            @Override public void onLost(Network network) { enqueue(Reason.NETWORK_CHANGE); }
            @Override public void onCapabilitiesChanged(Network network,
                    NetworkCapabilities capabilities) {
                if (!coordinator.snapshot().authenticated5555()) enqueue(Reason.NETWORK_CHANGE);
            }
        };
        LocalAdbClient.AUTHORIZATION.addListener(authorizationListener);
    }

    public void startOrReconfigure(Reason reason) {
        if (closed) return;
        ensureNetworkCallback();
        enqueue(reason == null ? Reason.RECONFIGURE : reason);
    }

    /** Immediate user request; it never performs a forced 0-to-1 bounce. */
    public void retryWifi() {
        enqueue(Reason.MANUAL);
    }

    /** An observer hint only; true still receives a fresh command proof in the worker. */
    public void onAdbAccessChanged(boolean available) {
        if (internalProof.get()) return;
        if (available && coordinator.snapshot().authenticated5555()) return;
        enqueue(available ? Reason.RECONFIGURE : Reason.ADB_FAILURE);
    }

    public void onPermissionsChecked() { enqueue(Reason.RECONFIGURE); }

    /** Called after the UI's one-second hold; suppression is persisted for this cycle. */
    public void suppressHint() {
        execute(() -> {
            coordinator.suppressHint();
            publish();
        });
    }

    public AdbRecoverySnapshot snapshot() {
        return coordinator.snapshot();
    }

    /** Turns the feature off and cancels app-owned work; it does not revoke access or stop ADB. */
    public void disable() {
        settings.edit().putBoolean(RECOVERY_ENABLED, false).apply();
        execute(this::applyDisabled);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        LocalAdbClient.AUTHORIZATION.removeListener(authorizationListener);
        mainHandler.removeCallbacks(consentRetry);
        closeDiscovery();
        if (networkRegistered && connectivity != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException error) {
                failure("adb_wifi_callback_cleanup_failed", "unregisterNetworkCallback", error);
            }
        }
        networkRegistered = false;
        worker.shutdownNow();
    }

    private void enqueue(Reason reason) {
        if (closed) return;
        pendingReason.updateAndGet(current -> AdbRecoveryPolicy.higherPriority(current, reason));
        dirty.set(true);
        if (!queued.compareAndSet(false, true)) return;
        execute(() -> {
            try {
                do {
                    dirty.set(false);
                    Reason trigger = pendingReason.getAndSet(Reason.RECONFIGURE);
                    recover(trigger);
                } while (!closed && dirty.get());
            } finally {
                queued.set(false);
                if (dirty.get()) enqueue(pendingReason.get());
            }
        });
    }

    private void recover(Reason reason) {
        boolean enabled = settings.getBoolean(RECOVERY_ENABLED, false);
        coordinator.configure(enabled);
        if (!enabled) {
            applyDisabled();
            return;
        }
        if (deferForPrerequisite()) return;
        try (AdbAuthorizationSession.RecoveryOperation admitted =
                     LocalAdbClient.AUTHORIZATION.beginRecovery()) {
            if (admitted == null) {
                waitForPrerequisite(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION);
                return;
            }
            operation = admitted;
            recoverAdmitted(reason);
        } finally { operation = null; }
    }

    private void recoverAdmitted(Reason reason) {
        boolean wasReady = coordinator.snapshot().authenticated5555();
        if (wasReady && !AdbRecoveryPolicy.shouldRevalidateReady(reason)) {
            publish();
            return;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean detectedNewBoot = detectAndRememberBoot(reason, nowElapsed);
        boolean forceCycle = reason == Reason.BOOT || detectedNewBoot
                || reason == Reason.ADB_FAILURE && wasReady;
        if (wasReady) {
            log.log("adb_recovery_revalidate", "cycle", coordinator.snapshot().cycleId(),
                    "trigger", reason, "new_boot", detectedNewBoot);
        } else {
            coordinator.begin(forceCycle, nowElapsed);
            log.log("adb_recovery_attempt", "cycle", coordinator.snapshot().cycleId(),
                    "trigger", reason, "new_boot", detectedNewBoot);
            publish();
        }

        AdbRecoveryPolicy.Proof proof = freshClassicProof();
        if (deferForPrerequisite()) return;
        boolean preserveReady = wasReady;
        if (proof != AdbRecoveryPolicy.Proof.AVAILABLE && preserveReady) {
            beginFailedRevalidation(reason, proof);
            preserveReady = false;
        }
        if (proof == AdbRecoveryPolicy.Proof.AUTHORIZATION_REQUIRED) {
            waitForPrerequisite(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION);
            return;
        }
        if (proof == AdbRecoveryPolicy.Proof.FAILED) {
            blocked("classic_proof_failed");
            return;
        }

        boolean prepared = proof == AdbRecoveryPolicy.Proof.TRANSPORT_UNAVAILABLE;
        if (prepared) {
            coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
            publish();
        }
        SettingResult adbEnabled = ensureGlobalSetting(ADB_ENABLED, 1, false);
        if (!adbEnabled.verified) {
            blocked("adb_enabled_write_failed");
            return;
        }
        if (adbEnabled.transitionedTo(1)) {
            if (!prepared && !preserveReady) {
                coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
                publish();
                prepared = true;
            }
            proof = proveAfterUsbEnableTransition();
            if (proof == null || deferForPrerequisite()) return;
        }
        if (proof != AdbRecoveryPolicy.Proof.AVAILABLE && preserveReady) {
            beginFailedRevalidation(reason, proof);
            preserveReady = false;
            prepared = false;
        }
        if (proof == AdbRecoveryPolicy.Proof.AVAILABLE) {
            finishClassicRecovery(preserveReady, reason);
            return;
        }
        if (proof == AdbRecoveryPolicy.Proof.AUTHORIZATION_REQUIRED) {
            waitForPrerequisite(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION);
            return;
        }
        if (proof != AdbRecoveryPolicy.Proof.TRANSPORT_UNAVAILABLE) {
            blocked("classic_proof_failed");
            return;
        }
        if (!prepared) {
            coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
            publish();
        }
        continueWirelessRecovery(reason);
    }

    private void continueWirelessRecovery(Reason reason) {
        if (deferForPrerequisite()) return;
        boolean wifi = wifiConnected();
        coordinator.wifi(wifi, SystemClock.elapsedRealtime());
        publish();
        if (!wifi) {
            cancelConsentRetry();
            closeDiscovery();
            log.log("adb_recovery_wait_wifi");
            return;
        }

        boolean requestReady = reason == Reason.MANUAL
                ? requestWirelessDebugging(true) : requestWirelessDebugging(false);
        if (!requestReady) return;
        if (closed || !settings.getBoolean(RECOVERY_ENABLED, false)
                || deferForPrerequisite()) return;
        coordinator.stage(AdbRecoverySnapshot.Stage.DISCOVERING_TLS);
        publish();
        startDiscovery();
        scheduleConsentRetry();
    }

    private void beginFailedRevalidation(Reason reason, AdbRecoveryPolicy.Proof proof) {
        coordinator.begin(true, SystemClock.elapsedRealtime());
        log.log("adb_recovery_attempt", "cycle", coordinator.snapshot().cycleId(),
                "trigger", reason, "revalidation_failed", proof);
        publish();
    }

    private void finishClassicRecovery(boolean preserveReady, Reason revalidationReason) {
        if (deferForPrerequisite()) return;
        boolean tlsOwned = state.getBoolean(TLS_OWNED, false);
        SettingResult adbEnabled = ensureGlobalSetting(ADB_ENABLED, 1, false);
        if (!adbEnabled.verified) {
            blocked("adb_enabled_write_failed");
            return;
        }
        if (adbEnabled.transitionedTo(1)) {
            if (!preserveReady) {
                coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
                publish();
            }
            AdbRecoveryPolicy.Proof proof = proveAfterUsbEnableTransition();
            if (proof == null || deferForPrerequisite()) return;
            if (proof != AdbRecoveryPolicy.Proof.AVAILABLE) {
                if (preserveReady) beginFailedRevalidation(revalidationReason, proof);
                if (proof == AdbRecoveryPolicy.Proof.AUTHORIZATION_REQUIRED) {
                    waitForPrerequisite(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION);
                } else if (proof == AdbRecoveryPolicy.Proof.TRANSPORT_UNAVAILABLE) {
                    coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
                    publish();
                    continueWirelessRecovery(revalidationReason);
                } else {
                    blocked("classic_proof_failed");
                }
                return;
            }
        }
        if (tlsOwned) {
            if (!AdbRecoveryPolicy.mayCleanupOwnedTls(tlsOwned, adbEnabled.verified, true)) {
                blocked("owned_tls_cleanup_failed");
                return;
            }
            coordinator.stage(AdbRecoverySnapshot.Stage.CLEANING_UP);
            publish();
            if (!ensureGlobalSetting(ADB_WIFI_ENABLED, 0, false).verified
                    || readGlobal(ADB_WIFI_ENABLED) != 0) {
                blocked("owned_tls_cleanup_failed");
                return;
            }
            state.edit().putBoolean(TLS_OWNED, false).commit();
            coordinator.stage(AdbRecoverySnapshot.Stage.VERIFYING_5555);
            publish();
            if (freshClassicProof() != AdbRecoveryPolicy.Proof.AVAILABLE) {
                blocked("classic_proof_after_cleanup_failed");
                return;
            }
        }
        if (deferForPrerequisite()) return;
        cancelConsentRetry();
        closeDiscovery();
        if (preserveReady) coordinator.stage(AdbRecoverySnapshot.Stage.READY);
        else coordinator.ready();
        publish();
        AdbRecoverySnapshot ready = coordinator.snapshot();
        log.log("adb_recovery_ready", "cycle", ready.cycleId(),
                "outcome", ready.readyOutcome());
    }

    private AdbRecoveryPolicy.Proof proveAfterUsbEnableTransition() {
        AdbRecoveryPolicy.Proof proof = AdbRecoveryPolicy.Proof.TRANSPORT_UNAVAILABLE;
        for (int attempt = 1; attempt <= AdbRecoveryPolicy.USB_ENABLE_PROOF_ATTEMPTS; attempt++) {
            if (closed || !settings.getBoolean(RECOVERY_ENABLED, false)
                    || deferForPrerequisite()) return null;
            proof = freshClassicProof();
            if (deferForPrerequisite()) return null;
            if (!AdbRecoveryPolicy.shouldRetryUsbEnableProof(proof, attempt)) return proof;
            SystemClock.sleep(AdbRecoveryPolicy.USB_ENABLE_PROOF_INTERVAL_MS);
        }
        return proof;
    }

    private boolean requestWirelessDebugging(boolean manual) {
        if (deferForPrerequisite()) return false;
        coordinator.stage(AdbRecoverySnapshot.Stage.REQUESTING_TLS);
        publish();
        int before = readGlobal(ADB_WIFI_ENABLED);
        if (before < 0) {
            blocked("adb_wifi_read_failed");
            return false;
        }
        if (before == 0 && !state.edit().putBoolean(TLS_OWNED, true).commit()) {
            blocked("tls_ownership_persist_failed");
            return false;
        }
        if (AdbRecoveryPolicy.shouldWriteWifiOne(manual, before)) {
            boolean written = requestGlobalOne(ADB_WIFI_ENABLED);
            log.log(manual ? "adb_wifi_manual_request" : "adb_wifi_auto_request",
                    "before", before, "write_ok", written,
                    "readback", readGlobal(ADB_WIFI_ENABLED));
            if (!written && before == 0) {
                state.edit().putBoolean(TLS_OWNED, false).commit();
                blocked("adb_wifi_write_failed");
                return false;
            }
        } else {
            log.log("adb_wifi_already_requested", "value", before);
        }
        return true;
    }

    private synchronized void startDiscovery() {
        if (deferForPrerequisite()) return;
        if (discovery == null) {
            long generation = ++discoveryGeneration;
            discovery = new AdbTlsDiscovery(context,
                    port -> execute(() -> tryTlsPort(port, generation)),
                    log);
        }
        discovery.start();
    }

    private void tryTlsPort(int port, long generation) {
        if (closed || !settings.getBoolean(RECOVERY_ENABLED, false)
                || deferForPrerequisite()) return;
        try (AdbAuthorizationSession.RecoveryOperation admitted =
                     LocalAdbClient.AUTHORIZATION.beginRecovery()) {
            if (admitted == null) {
                waitForPrerequisite(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION);
                return;
            }
            operation = admitted;
            // Check after admission: RSA may have begun AND finished while this callback waited.
            if (generation != discoveryGeneration) return;
            tryTlsPortAdmitted(port);
        } finally { operation = null; }
    }

    private void tryTlsPortAdmitted(int port) {
        if (closed || !settings.getBoolean(RECOVERY_ENABLED, false)
                || consentPaused || !wifiConnected()
                || coordinator.snapshot().authenticated5555()) return;
        consentPaused = true;
        cancelConsentRetry();
        coordinator.stage(AdbRecoverySnapshot.Stage.SWITCHING_TO_5555);
        publish();
        try (LocalAdbTlsClient tls = LocalAdbTlsClient.connect(port,
                AdbTlsIdentity.load(context), operation, log::log)) {
            if (closed || !settings.getBoolean(RECOVERY_ENABLED, false)
                    || !wifiConnected() || deferForPrerequisite()) {
                consentPaused = false;
                if (!closed && !settings.getBoolean(RECOVERY_ENABLED, false)) applyDisabled();
                return;
            }
            log.log("adb_tls_authenticated", "port", port);
            LocalAdbTlsClient.TcpipResult result = tls.requestTcpip5555(operation);
            log.log("adb_tls_tcpip_result", "port", port,
                    "category", result.category, "reason", result.reason);
        } catch (Throwable error) {
            consentPaused = false;
            failure("adb_tls_failed", "tls_connect_or_tcpip5555", error, "port", port,
                    "category", LocalAdbTlsClient.failureCategory(error),
                    "reason", LocalAdbTlsClient.failureReason(error));
            if (deferForPrerequisite()) return;
            if (!settings.getBoolean(RECOVERY_ENABLED, false)) {
                applyDisabled();
                return;
            }
            scheduleConsentRetry();
            return;
        }
        coordinator.stage(AdbRecoverySnapshot.Stage.VERIFYING_5555);
        publish();
        boolean verified = false;
        for (int attempt = 0; attempt < 12 && !closed
                && settings.getBoolean(RECOVERY_ENABLED, false)
                && wifiConnected(); attempt++) {
            if (deferForPrerequisite()) { consentPaused = false; return; }
            AdbRecoveryPolicy.Proof proof = freshClassicProof();
            if (deferForPrerequisite()) { consentPaused = false; return; }
            if (proof == AdbRecoveryPolicy.Proof.AVAILABLE) {
                verified = true;
                break;
            }
            if (proof != AdbRecoveryPolicy.Proof.TRANSPORT_UNAVAILABLE) {
                consentPaused = false;
                if (proof == AdbRecoveryPolicy.Proof.AUTHORIZATION_REQUIRED) {
                    waitForPrerequisite(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION);
                } else blocked("classic_proof_failed");
                return;
            }
            SystemClock.sleep(1_000L);
        }
        consentPaused = false;
        if (closed) return;
        if (!settings.getBoolean(RECOVERY_ENABLED, false)) {
            applyDisabled();
            return;
        }
        if (verified) finishClassicRecovery(false, null);
        else {
            log.log("adb_5555_transition_unverified", "port", port);
            coordinator.stage(AdbRecoverySnapshot.Stage.DISCOVERING_TLS);
            publish();
            scheduleConsentRetry();
        }
    }

    private AdbRecoveryPolicy.Proof freshClassicProof() {
        internalProof.set(Boolean.TRUE);
        try {
            LocalAdbClient.Result result = LocalAdbClient.executeRecoveryProof(
                    context, PROOF_COMMAND, operation, noSecretsEventSink());
            AdbRecoveryPolicy.Proof proof = AdbRecoveryPolicy.classifyProof(result, PROOF_MARKER);
            log.log("adb_5555_proof", "ok", proof == AdbRecoveryPolicy.Proof.AVAILABLE,
                    "category", proof,
                    "result", result.ok ? "ok" : AdbRecoveryDiagnostics.safeText(result.error),
                    "exit_code", result.exitCode,
                    "marker_present", result.output != null && result.output.contains(PROOF_MARKER));
            return proof;
        } finally {
            internalProof.remove();
        }
    }

    private BiConsumer<String, Object[]> noSecretsEventSink() {
        return (event, fields) -> log.log("adb_transport",
                AdbRecoveryDiagnostics.transportFields(event, fields));
    }

    private SettingResult ensureGlobalSetting(String key, int value, boolean writeWhenEqual) {
        if (deferForPrerequisite()) return new SettingResult(false, -1, -1, false);
        String step = "read_before";
        int current = -1;
        boolean wrote = false;
        try {
            current = Settings.Global.getInt(context.getContentResolver(), key, -1);
            if (current == -1) log.log("adb_setting_unavailable", "operation", "read",
                    "key", key, "reason", "missing_or_minus_one");
            if (current != value || writeWhenEqual) {
                step = "write";
                boolean[] written = {false};
                boolean[] permitted = {false};
                operation.write(() -> {
                    permitted[0] = AppPermissionProvisioner.hasWriteSecureSettings(context);
                    if (permitted[0]) {
                        written[0] = Settings.Global.putInt(context.getContentResolver(), key, value);
                    }
                });
                wrote = written[0];
                if (!written[0]) {
                    log.log("adb_setting_failed", "operation", "write", "key", key,
                            "before", current, "requested", value, "wss", permitted[0],
                            "reason", permitted[0] ? "putInt_returned_false" : "wss_not_granted");
                    return new SettingResult(false, current, -1, false);
                }
            }
            step = "readback";
            int readback = Settings.Global.getInt(context.getContentResolver(), key, -1);
            log.log(readback == value ? "adb_setting_verified" : "adb_setting_failed",
                    "operation", step, "key", key, "before", current,
                    "requested", value, "readback", readback,
                    "reason", readback == value ? "matched" : "readback_mismatch");
            return new SettingResult(readback == value, current, readback, wrote);
        } catch (Exception error) {
            failure("adb_setting_failed", step, error, "key", key,
                    "before", current, "requested", value);
            if (deferForPrerequisite()) return new SettingResult(false, current, -1, false);
            return new SettingResult(false, current, -1, false);
        }
    }

    private boolean requestGlobalOne(String key) {
        if (deferForPrerequisite()) return false;
        try {
            boolean[] written = {false};
            boolean[] permitted = {false};
            operation.write(() -> {
                permitted[0] = AppPermissionProvisioner.hasWriteSecureSettings(context);
                if (permitted[0]) {
                    written[0] = Settings.Global.putInt(context.getContentResolver(), key, 1);
                }
            });
            if (!written[0]) log.log("adb_setting_failed", "operation", "request_one",
                    "key", key, "requested", 1, "wss", permitted[0],
                    "reason", permitted[0] ? "putInt_returned_false" : "wss_not_granted");
            return written[0];
        } catch (Exception error) {
            failure("adb_setting_failed", "request_one", error, "key", key, "requested", 1);
            if (deferForPrerequisite()) return false;
            return false;
        }
    }

    private int readGlobal(String key) {
        try {
            int value = Settings.Global.getInt(context.getContentResolver(), key, -1);
            if (value == -1) log.log("adb_setting_unavailable", "operation", "read",
                    "key", key, "reason", "missing_or_minus_one");
            return value;
        } catch (RuntimeException error) {
            failure("adb_setting_failed", "read", error, "key", key);
            return -1;
        }
    }

    private boolean detectAndRememberBoot(Reason reason, long nowElapsed) {
        int currentBoot = readGlobal(Settings.Global.BOOT_COUNT);
        int storedBoot = state.getInt(BOOT_COUNT, -1);
        long storedElapsed = state.getLong(LAST_START_ELAPSED, -1L);
        boolean changed = AdbRecoveryPolicy.isNewBoot(
                storedBoot, currentBoot, storedElapsed, nowElapsed);
        if (reason == Reason.START || reason == Reason.WAKE || reason == Reason.BOOT || changed) {
            SharedPreferences.Editor editor = state.edit()
                    .putLong(LAST_START_ELAPSED, nowElapsed);
            if (currentBoot >= 0) editor.putInt(BOOT_COUNT, currentBoot);
            editor.commit();
        }
        if (changed) log.log("adb_recovery_new_boot", "boot_count", currentBoot);
        return changed;
    }

    private boolean wifiConnected() {
        if (connectivity == null) return false;
        try {
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException error) {
            failure("adb_wifi_state_failed", "getAllNetworks/getNetworkCapabilities", error);
            return false;
        }
    }

    private synchronized void ensureNetworkCallback() {
        if (networkRegistered || connectivity == null || closed) return;
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            connectivity.registerNetworkCallback(request, networkCallback);
            networkRegistered = true;
        } catch (RuntimeException error) {
            failure("adb_wifi_callback_failed", "registerNetworkCallback", error);
        }
    }

    private void scheduleConsentRetry() {
        if (deferForPrerequisite()) return;
        AdbRecoverySnapshot snapshot = coordinator.snapshot();
        if (closed || !AdbRecoveryPolicy.shouldScheduleConsentRetry(
                settings.getBoolean(RECOVERY_ENABLED, false), wifiConnected(),
                snapshot.authenticated5555(), consentPaused)) return;
        mainHandler.removeCallbacks(consentRetry);
        mainHandler.postDelayed(consentRetry, AdbRecoveryPolicy.CONSENT_RETRY_MS);
    }

    private void cancelConsentRetry() {
        mainHandler.removeCallbacks(consentRetry);
    }

    private void blocked(String reason) {
        if (deferForPrerequisite()) return;
        cancelConsentRetry();
        closeDiscovery();
        if (coordinator.snapshot().authenticated5555()) {
            coordinator.begin(true, SystemClock.elapsedRealtime());
        }
        coordinator.stage(AdbRecoverySnapshot.Stage.BLOCKED);
        publish();
        log.log("adb_recovery_blocked", "reason", reason);
    }

    private void applyDisabled() {
        cancelConsentRetry();
        closeDiscovery();
        consentPaused = false;
        coordinator.configure(false);
        publish();
        log.log("adb_recovery_disabled");
    }

    private void publish() {
        try {
            listener.onAdbRecoverySnapshot(coordinator.snapshot());
        } catch (Throwable error) {
            failure("adb_snapshot_listener_failed", "publish_snapshot", error);
        }
    }

    private synchronized void closeDiscovery() {
        discoveryGeneration++;
        if (discovery != null) discovery.close();
        discovery = null;
    }

    private boolean deferForPrerequisite() {
        if (closed) return true;
        if (!settings.getBoolean(RECOVERY_ENABLED, false)) {
            applyDisabled();
            return true;
        }
        boolean pending = LocalAdbClient.AUTHORIZATION.pending();
        AdbRecoverySnapshot.Stage prerequisite = AdbRecoveryPolicy.prerequisite(pending,
                !pending && LocalAdbClient.hasAuthorizedIdentity(context, log::log),
                AppPermissionProvisioner.hasWriteSecureSettings(context));
        if (prerequisite != null) {
            waitForPrerequisite(prerequisite);
            return true;
        }
        if (operation != null && operation.cancelled()) {
            enqueue(Reason.RECONFIGURE);
            return true;
        }
        lastPrerequisite = null;
        return false;
    }

    private void waitForPrerequisite(AdbRecoverySnapshot.Stage stage) {
        cancelConsentRetry();
        closeDiscovery();
        coordinator.waitForPrerequisite(stage);
        if (lastPrerequisite != stage) {
            lastPrerequisite = stage;
            log.log("adb_recovery_deferred", "reason", stage);
        }
        publish();
    }

    private void execute(Runnable action) {
        if (closed) return;
        try {
            worker.execute(() -> {
                try { action.run(); }
                catch (RuntimeException | Error error) {
                    failure("adb_recovery_worker_failed", "worker_action", error);
                    throw error;
                }
            });
        } catch (RejectedExecutionException error) {
            failure("adb_recovery_worker_failed", "submit_action", error, "closed", closed);
        }
    }

    private void failure(String event, String operationName, Throwable error, Object... fields) {
        AdbRecoveryDiagnostics.failure(log::log, event, operationName, error, fields);
    }

    private static final class SettingResult {
        final boolean verified;
        final int before;
        final int readback;
        final boolean wrote;

        SettingResult(boolean verified, int before, int readback, boolean wrote) {
            this.verified = verified;
            this.before = before;
            this.readback = readback;
            this.wrote = wrote;
        }

        boolean transitionedTo(int value) {
            return verified && wrote && before != value && readback == value;
        }
    }

    private static final class PreferenceStore implements AdbRecoveryCoordinator.Store {
        private final SharedPreferences preferences;
        PreferenceStore(SharedPreferences preferences) { this.preferences = preferences; }
        @Override public long cycleId() { return preferences.getLong(CYCLE_ID, 0L); }
        @Override public boolean cycleActive() {
            return preferences.getBoolean(CYCLE_ACTIVE, false);
        }
        @Override public boolean hintSuppressed() {
            return preferences.getBoolean(HINT_SUPPRESSED, false);
        }
        @Override public void save(long id, boolean active, boolean suppressed) {
            preferences.edit().putLong(CYCLE_ID, id).putBoolean(CYCLE_ACTIVE, active)
                    .putBoolean(HINT_SUPPRESSED, suppressed).commit();
        }
    }
}
