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
    public enum Reason { START, RECONFIGURE, BOOT, NETWORK_CHANGE, MANUAL, ADB_FAILURE }

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
                    NetworkCapabilities capabilities) { enqueue(Reason.NETWORK_CHANGE); }
        };
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

    public void onAdbFailure() {
        enqueue(Reason.ADB_FAILURE);
    }

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
        mainHandler.removeCallbacks(consentRetry);
        closeDiscovery();
        if (networkRegistered && connectivity != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) { }
        }
        networkRegistered = false;
        worker.shutdownNow();
    }

    private void enqueue(Reason reason) {
        if (closed) return;
        pendingReason.updateAndGet(current -> higherPriority(current, reason));
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
        boolean enabled = settings.getBoolean(RECOVERY_ENABLED, true);
        coordinator.configure(enabled);
        if (!enabled) {
            applyDisabled();
            return;
        }
        if (coordinator.snapshot().authenticated5555()
                && reason != Reason.ADB_FAILURE && reason != Reason.BOOT) {
            publish();
            return;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean detectedNewBoot = detectAndRememberBoot(reason, nowElapsed);
        boolean forceCycle = reason == Reason.BOOT || detectedNewBoot
                || reason == Reason.ADB_FAILURE
                && coordinator.snapshot().authenticated5555();
        coordinator.begin(forceCycle, nowElapsed);
        publish();

        if (freshClassicProof()) {
            finishClassicRecovery();
            return;
        }

        coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
        publish();
        if (!ensureGlobalSetting(ADB_ENABLED, 1, false)) {
            blocked("adb_enabled_write_failed");
            return;
        }

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
        if (closed || !settings.getBoolean(RECOVERY_ENABLED, true)) return;
        coordinator.stage(AdbRecoverySnapshot.Stage.DISCOVERING_TLS);
        publish();
        startDiscovery();
        scheduleConsentRetry();
    }

    private void finishClassicRecovery() {
        if (AdbRecoveryPolicy.mayCleanupOwnedTls(
                state.getBoolean(TLS_OWNED, false), true)) {
            coordinator.stage(AdbRecoverySnapshot.Stage.CLEANING_UP);
            publish();
            if (!ensureGlobalSetting(ADB_WIFI_ENABLED, 0, false)
                    || readGlobal(ADB_WIFI_ENABLED) != 0) {
                blocked("owned_tls_cleanup_failed");
                return;
            }
            state.edit().putBoolean(TLS_OWNED, false).commit();
            coordinator.stage(AdbRecoverySnapshot.Stage.VERIFYING_5555);
            publish();
            if (!freshClassicProof()) {
                blocked("classic_proof_after_cleanup_failed");
                return;
            }
        }
        cancelConsentRetry();
        closeDiscovery();
        coordinator.ready();
        publish();
        AdbRecoverySnapshot ready = coordinator.snapshot();
        log.log("adb_recovery_ready", "cycle", ready.cycleId(),
                "outcome", ready.readyOutcome());
    }

    private boolean requestWirelessDebugging(boolean manual) {
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

    private void startDiscovery() {
        if (discovery == null) {
            discovery = new AdbTlsDiscovery(context,
                    port -> execute(() -> tryTlsPort(port)),
                    message -> log.log("adb_recovery_discovery", "message", message));
        }
        discovery.start();
    }

    private void tryTlsPort(int port) {
        if (closed || !settings.getBoolean(RECOVERY_ENABLED, true)
                || consentPaused || !wifiConnected()
                || coordinator.snapshot().authenticated5555()) return;
        consentPaused = true;
        cancelConsentRetry();
        coordinator.stage(AdbRecoverySnapshot.Stage.SWITCHING_TO_5555);
        publish();
        try (LocalAdbTlsClient tls = LocalAdbTlsClient.connect(port,
                AdbTlsIdentity.load(context))) {
            if (closed || !settings.getBoolean(RECOVERY_ENABLED, true)
                    || !wifiConnected()) {
                consentPaused = false;
                if (!closed && !settings.getBoolean(RECOVERY_ENABLED, true)) applyDisabled();
                return;
            }
            log.log("adb_tls_authenticated", "port", port);
            tls.requestTcpip5555();
        } catch (Throwable error) {
            log.log("adb_tls_failed", "port", port,
                    "error", error.getClass().getSimpleName());
            consentPaused = false;
            if (!settings.getBoolean(RECOVERY_ENABLED, true)) {
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
                && settings.getBoolean(RECOVERY_ENABLED, true)
                && wifiConnected(); attempt++) {
            if (freshClassicProof()) {
                verified = true;
                break;
            }
            SystemClock.sleep(1_000L);
        }
        consentPaused = false;
        if (closed) return;
        if (!settings.getBoolean(RECOVERY_ENABLED, true)) {
            applyDisabled();
            return;
        }
        if (verified) finishClassicRecovery();
        else {
            log.log("adb_5555_transition_unverified", "port", port);
            coordinator.stage(AdbRecoverySnapshot.Stage.DISCOVERING_TLS);
            publish();
            scheduleConsentRetry();
        }
    }

    private boolean freshClassicProof() {
        internalProof.set(Boolean.TRUE);
        try {
            LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                    context, PROOF_COMMAND, noSecretsEventSink());
            boolean ok = result.ok && result.output != null && result.output.contains(PROOF_MARKER);
            log.log("adb_5555_proof", "ok", ok,
                    "result", result.ok ? "ok" : result.error);
            return ok;
        } finally {
            internalProof.remove();
        }
    }

    private BiConsumer<String, Object[]> noSecretsEventSink() {
        return (event, fields) -> log.log("adb_transport", "event", event);
    }

    private boolean ensureGlobalSetting(String key, int value, boolean writeWhenEqual) {
        try {
            int current = Settings.Global.getInt(context.getContentResolver(), key, -1);
            if (current != value || writeWhenEqual) {
                if (!Settings.Global.putInt(context.getContentResolver(), key, value)) return false;
            }
            return Settings.Global.getInt(context.getContentResolver(), key, -1) == value;
        } catch (RuntimeException error) {
            log.log("adb_setting_failed", "key", key,
                    "error", error.getClass().getSimpleName());
            return false;
        }
    }

    private boolean requestGlobalOne(String key) {
        try {
            return Settings.Global.putInt(context.getContentResolver(), key, 1);
        } catch (RuntimeException error) {
            log.log("adb_setting_failed", "key", key,
                    "error", error.getClass().getSimpleName());
            return false;
        }
    }

    private int readGlobal(String key) {
        try { return Settings.Global.getInt(context.getContentResolver(), key, -1); }
        catch (RuntimeException error) { return -1; }
    }

    private boolean detectAndRememberBoot(Reason reason, long nowElapsed) {
        int currentBoot = readGlobal(Settings.Global.BOOT_COUNT);
        int storedBoot = state.getInt(BOOT_COUNT, -1);
        long storedElapsed = state.getLong(LAST_START_ELAPSED, -1L);
        boolean changed = AdbRecoveryPolicy.isNewBoot(
                storedBoot, currentBoot, storedElapsed, nowElapsed);
        if (reason == Reason.START || reason == Reason.BOOT || changed) {
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
            log.log("adb_wifi_state_failed", "error", error.getClass().getSimpleName());
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
            log.log("adb_wifi_callback_failed", "error", error.getClass().getSimpleName());
        }
    }

    private void scheduleConsentRetry() {
        AdbRecoverySnapshot snapshot = coordinator.snapshot();
        if (closed || !AdbRecoveryPolicy.shouldScheduleConsentRetry(
                settings.getBoolean(RECOVERY_ENABLED, true), wifiConnected(),
                snapshot.authenticated5555(), consentPaused)) return;
        mainHandler.removeCallbacks(consentRetry);
        mainHandler.postDelayed(consentRetry, AdbRecoveryPolicy.CONSENT_RETRY_MS);
    }

    private void cancelConsentRetry() {
        mainHandler.removeCallbacks(consentRetry);
    }

    private void blocked(String reason) {
        cancelConsentRetry();
        closeDiscovery();
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
            log.log("adb_snapshot_listener_failed", "error",
                    error.getClass().getSimpleName());
        }
    }

    private synchronized void closeDiscovery() {
        if (discovery != null) discovery.close();
        discovery = null;
    }

    private void execute(Runnable action) {
        if (closed) return;
        try { worker.execute(action); } catch (RejectedExecutionException ignored) { }
    }

    private static Reason higherPriority(Reason first, Reason second) {
        return priority(second) > priority(first) ? second : first;
    }

    private static int priority(Reason reason) {
        if (reason == Reason.BOOT) return 5;
        if (reason == Reason.ADB_FAILURE) return 4;
        if (reason == Reason.MANUAL) return 3;
        if (reason == Reason.NETWORK_CHANGE) return 2;
        if (reason == Reason.START) return 1;
        return 0;
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
