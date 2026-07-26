package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class TurnSignalController {
    private static final long PING_MS = 5_000;
    private static final long RETRY_BACKOFF_MS = 30_000;
    private static final Object LAUNCH_LOCK = new Object();

    private final Context context;
    private final Handler handler;
    private final Consumer<String> shellEventSink;
    private final BiConsumer<String, Object[]> eventSink;
    private final SharedPreferences settings;
    private final String apkMarker;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Binder controllerToken = new Binder();
    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != TurnSignalShellProtocol.CB_EVENT) return false;
            data.enforceInterface(TurnSignalShellProtocol.CALLBACK_DESCRIPTOR);
            acceptShellEvent(data.readString());
            return true;
        }
    };
    private final Binder cameraCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != CameraShellProtocol.CB_EVENT) return false;
            data.enforceInterface(CameraShellProtocol.CALLBACK_DESCRIPTOR);
            acceptShellEvent(data.readString());
            return true;
        }
    };
    private final Runnable pingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!stopped) worker.execute(TurnSignalController.this::checkHealth);
            if (!stopped) handler.postDelayed(this, PING_MS);
        }
    };

    private volatile boolean stopped;
    private volatile boolean healthy;
    private volatile boolean authorizationPending;
    private volatile IBinder helper;
    private volatile IBinder cameraHelper;
    private IBinder.DeathRecipient helperDeathRecipient;
    private final RetryGate authorizationRetry = new RetryGate();
    private long lastLaunchFailureAt;
    private String automaticAuthorizationBlockedFor = "";
    private boolean automaticAuthorizationBlockReported;
    private volatile String primaryError = "helper_not_started";

    TurnSignalController(
            Context context,
            Handler handler,
            Consumer<String> shellEventSink,
            BiConsumer<String, Object[]> eventSink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.shellEventSink = shellEventSink;
        this.eventSink = eventSink;
        settings = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        apkMarker = currentApkMarker(this.context);
        migrateLegacyLatchState();
    }

    void start() {
        worker.execute(() -> ensureRunning(LocalAdbClient.PromptMode.NEVER, false));
        handler.postDelayed(pingRunnable, PING_MS);
    }

    void shutdown(boolean terminateShells) {
        stopped = true;
        handler.removeCallbacks(pingRunnable);
        LocalAdbClient.cancelPendingAuthorization();
        if (terminateShells) {
            shutdownTurnHelper();
            shutdownCameraHelper();
        } else {
            closeStockAvmNow("controller_shutdown");
        }
        clearHelper(null);
        cameraHelper = null;
        healthy = false;
        worker.shutdownNow();
    }

    void setRecoveryEnabled(boolean enabled) {
        IBinder value = helper;
        if (value != null && value.isBinderAlive()) {
            try {
                transactAttach(value, enabled);
                emit("helper_recovery_configured", "enabled", enabled);
                return;
            } catch (Throwable error) {
                emit("helper_recovery_config_failed", "enabled", enabled,
                        "error", summary(error));
            }
        }
        if (enabled) worker.execute(
                () -> ensureRunning(LocalAdbClient.PromptMode.NEVER, false));
    }

    void configure(
            boolean enabled, float outward, float center, int delayMs, int maxSpeedKph) {
        settings.edit()
                .putBoolean("guard_enabled", enabled)
                .putFloat("outward_deg", outward)
                .putFloat("center_deg", center)
                .putInt("correction_delay_ms", delayMs)
                .putInt("max_speed_kph", maxSpeedKph)
                .apply();
        GuardRecovery.schedule(context);
        worker.execute(() -> {
            if (!sendConfig()) ensureRunning(LocalAdbClient.PromptMode.NEVER, false);
        });
    }

    void setManualState(int payload) {
        if (payload < 0 || payload > 3) throw new IllegalArgumentException("payload not whitelisted");
        worker.execute(() -> {
            if (!healthy || !transactManual(payload)) {
                emitUnavailable("manual_turn_state_rejected");
            }
        });
    }

    void openStockAvm(
            Surface surface, int viewpoint, boolean horizontal,
            Consumer<Surface> inputSurfaceSink) {
        if (!StockAvmPreview.isAllowedViewpoint(viewpoint)) {
            surface.release();
            throw new IllegalArgumentException("viewpoint not whitelisted");
        }
        if (!handler.post(() -> readCameraConfigAndOpen(
                surface, viewpoint, horizontal, inputSurfaceSink))) {
            surface.release();
            emit("camera_error", "renderer", "stock_avm_shell",
                    "stage", "config_main_handler_unavailable",
                    "viewpoint", viewpoint, "error", "main handler rejected task");
        }
    }

    private void readCameraConfigAndOpen(
            Surface surface, int viewpoint, boolean horizontal,
            Consumer<Surface> inputSurfaceSink) {
        if (stopped) {
            surface.release();
            return;
        }
        final StockAvmPreview.Config config;
        try {
            config = StockAvmPreview.readConfig(context,
                    (stage, detail) -> emit("stock_avm_config_stage",
                            "stage", stage, "detail", detail));
            emit("camera_config_read", "ok", true,
                    "detail", config.detail(), "viewpoint", viewpoint,
                    "orientation", horizontal ? "horizontal" : "vertical");
        } catch (Throwable error) {
            String stage = error instanceof StockAvmPreview.StageException
                    ? ((StockAvmPreview.StageException) error).stage : "camera_config_read";
            emit("camera_error", "renderer", "stock_avm_shell",
                    "stage", stage, "view", StockAvmPreview.viewName(viewpoint),
                    "viewpoint", viewpoint, "error", summary(error));
            surface.release();
            return;
        }
        worker.execute(() -> {
            try {
                IBinder value = ensureCameraHelper();
                Surface inputSurface = transactCameraOpen(
                        value, surface, viewpoint, horizontal, config);
                boolean inputSurfaceValid = inputSurface.isValid();
                try {
                    inputSurfaceSink.accept(inputSurface);
                } catch (Throwable error) {
                    inputSurface.release();
                    throw error;
                }
                emit("camera_shell_opened", "viewpoint", viewpoint,
                        "orientation", horizontal ? "horizontal" : "vertical",
                        "input_surface_valid", inputSurfaceValid);
            } catch (Throwable error) {
                cameraHelper = null;
                emit("camera_error", "renderer", "stock_avm_shell",
                        "stage", "camera_shell_launch_or_open",
                        "view", StockAvmPreview.viewName(viewpoint),
                        "viewpoint", viewpoint, "error", summary(error));
            } finally {
                surface.release();
            }
        });
    }

    void closeStockAvm(String reason) {
        worker.execute(() -> closeStockAvmNow(reason));
    }

    void reportStatus() {
        emit("adb_auth_state", "pending", authorizationPending);
        worker.execute(() -> {
            if (authorizationPending) return;
            IBinder value = helper;
            if (healthy && value != null) {
                try {
                    transactNoArgs(value, TurnSignalShellProtocol.TX_REPORT_STATUS);
                    return;
                } catch (Throwable error) {
                    primaryError = "status_binder_error: " + summary(error);
                }
            }
            emitUnavailable("helper_status_unavailable");
        });
    }

    void retryAuthorization() {
        if (!authorizationRetry.begin()) {
            emit("adb_authorization_retry_coalesced");
            return;
        }
        automaticAuthorizationBlockedFor = "";
        automaticAuthorizationBlockReported = false;
        authorizationPending = true;
        boolean socketClosed = LocalAdbClient.cancelPendingAuthorization();
        emit("adb_authorization_retry_queued", "cancelled_pending_socket", socketClosed);
        worker.execute(() -> {
            try {
                ensureRunning(LocalAdbClient.PromptMode.FORCE, true);
            } finally {
                authorizationRetry.end();
                authorizationPending = false;
                emit("adb_auth_state", "pending", false);
            }
        });
    }

    private void checkHealth() {
        Ping ping = ping(resolveHelper());
        if (ping.healthy() && !shouldReplaceInstalledHelper()) {
            if (!healthy || helper != ping.binder) attach(ping);
            return;
        }
        if (healthy) {
            healthy = false;
            primaryError = ping.error;
            emit("helper_ping_failed", "error", ping.error);
        }
        ensureRunning(LocalAdbClient.PromptMode.NEVER, false);
    }

    private void ensureRunning(LocalAdbClient.PromptMode mode, boolean ignoreBackoff) {
        if (stopped) return;
        String authorizationIdentity = authorizationIdentity();
        if (shouldBlockAutomaticAuthorization(
                mode, automaticAuthorizationBlockedFor, authorizationIdentity)) {
            if (!automaticAuthorizationBlockReported) {
                emit("adb_auth_auto_blocked", "identity", authorizationIdentity,
                        "reason", "authorization_required");
                automaticAuthorizationBlockReported = true;
            }
            return;
        }
        if (!automaticAuthorizationBlockedFor.isEmpty()
                && !automaticAuthorizationBlockedFor.equals(authorizationIdentity)) {
            automaticAuthorizationBlockedFor = "";
            automaticAuthorizationBlockReported = false;
            lastLaunchFailureAt = 0;
        }
        if (mode != LocalAdbClient.PromptMode.FORCE && authorizationRetry.active()) {
            authorizationPending = true;
            emit("adb_auth_deferred", "reason", "force_retry_pending");
            return;
        }
        synchronized (LAUNCH_LOCK) {
            Ping current = ping(resolveHelper());
            boolean replaceInstalledApk = shouldReplaceInstalledHelper();
            if (current.healthy() && !replaceInstalledApk) {
                attach(current);
                return;
            }
            int replacedPid = replaceInstalledApk && current.healthy() ? current.pid : -1;
            long now = SystemClock.elapsedRealtime();
            if (!ignoreBackoff && lastLaunchFailureAt != 0
                    && now - lastLaunchFailureAt < RETRY_BACKOFF_MS) {
                emit("helper_launch_backoff", "remaining_ms",
                        RETRY_BACKOFF_MS - (now - lastLaunchFailureAt),
                        "error", primaryError);
                return;
            }

            authorizationPending = true;
            emit("adb_auth_start", "mode", mode.name(),
                    "endpoint", LocalAdbClient.endpointForTest());
            LocalAdbClient.Result authorization = LocalAdbClient.authorize(
                    context, mode, this::emit);
            if (authorization.superseded) {
                authorizationPending = authorizationRetry.active();
                emit("authorization_superseded", "mode", mode.name());
                return;
            }
            authorizationPending = false;
            emit("adb_auth_result", "ok", authorization.ok,
                    "authorization_required", authorization.authorizationRequired,
                    "public_key_sent", authorization.publicKeySent,
                    "fingerprint", authorization.fingerprint,
                    "error", authorization.error);
            if (authorization.authorizationRequired) {
                automaticAuthorizationBlockedFor = BuildConfig.VERSION_CODE + ":"
                        + authorization.fingerprint;
                automaticAuthorizationBlockReported = false;
            } else if (authorization.ok) {
                automaticAuthorizationBlockedFor = "";
                automaticAuthorizationBlockReported = false;
            }
            if (shouldRecordLaunchFailure(authorization)) {
                launchFailed(authorization.authorizationRequired
                        ? authorization.error : "adb_authorization_failed: " + authorization.error);
                return;
            }

            LocalAdbClient.Result launch = LocalAdbClient.executeAuthorized(
                    context, launchCommand(context.getApplicationInfo().sourceDir,
                            Process.myUid(), BuildConfig.VERSION_CODE), this::emit);
            if (!launch.ok) {
                launchFailed("helper_launch_failed: " + launch.error
                        + (launch.output.isEmpty() ? "" : ": " + launch.output));
                return;
            }
            emit("helper_launch", "ok", true, "output", launch.output);

            long deadline = SystemClock.elapsedRealtime() + 3_000;
            do {
                Ping started = ping(resolveHelper());
                if (started.healthy() && started.pid != replacedPid) {
                    lastLaunchFailureAt = 0;
                    attach(started);
                    return;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } while (SystemClock.elapsedRealtime() < deadline);
            launchFailed("helper_binder_timeout");
        }
    }

    private void launchFailed(String error) {
        healthy = false;
        clearHelper(null);
        primaryError = error;
        lastLaunchFailureAt = SystemClock.elapsedRealtime();
        emit("helper_launch", "ok", false, "error", error);
        emit("telemetry_ready", "ok", false, "listener_ok", false,
                "poll_ok", false, "control_ready", false, "error", error);
    }

    static boolean shouldRecordLaunchFailure(LocalAdbClient.Result result) {
        return !result.ok && !result.superseded;
    }

    static boolean shouldBlockAutomaticAuthorization(
            LocalAdbClient.PromptMode mode, String blockedIdentity, String currentIdentity) {
        return mode != LocalAdbClient.PromptMode.FORCE
                && !blockedIdentity.isEmpty()
                && blockedIdentity.equals(currentIdentity);
    }

    private String authorizationIdentity() {
        return BuildConfig.VERSION_CODE + ":" + LocalAdbClient.keyFingerprint(context);
    }

    private void attach(Ping ping) {
        if (stopped || !ping.healthy()) return;
        IBinder value = ping.binder;
        if (helper != value) {
            try {
                installHelper(value, ping.pid);
            } catch (Throwable error) {
                launchFailed("helper_link_to_death_failed: " + summary(error));
                return;
            }
        }
        try {
            transactAttach(value, GuardRecovery.shouldRecover(context));
            transactConfig(value);
            transactCallback(value);
            transactNoArgs(value, TurnSignalShellProtocol.TX_REPORT_STATUS);
            healthy = true;
            primaryError = "";
            settings.edit().putString("helper_apk_marker", apkMarker).apply();
            emit("helper_ping", "ok", true, "protocol", ping.protocol,
                    "build", ping.build, "pid", ping.pid);
        } catch (Throwable error) {
            clearHelper(value);
            healthy = false;
            primaryError = "helper_attach_failed: " + summary(error);
            emit("helper_attach", "ok", false, "error", primaryError);
            emit("telemetry_ready", "ok", false, "listener_ok", false,
                    "poll_ok", false, "control_ready", false, "error", primaryError);
        }
    }

    private void helperDied(IBinder deadHelper, int deadPid) {
        synchronized (this) {
            if (helper != deadHelper) {
                emit("helper_death_ignored", "pid", deadPid, "reason", "stale_binder");
                return;
            }
            helper = null;
            helperDeathRecipient = null;
            healthy = false;
            primaryError = "helper_binder_died";
            lastLaunchFailureAt = 0;
        }
        emit("helper_death", "pid", deadPid, "error", primaryError);
        if (!stopped) worker.execute(() -> ensureRunning(LocalAdbClient.PromptMode.NEVER, true));
    }

    private boolean sendConfig() {
        IBinder value = helper;
        if (!healthy || value == null) return false;
        try {
            transactConfig(value);
            return true;
        } catch (Throwable error) {
            clearHelper(value);
            healthy = false;
            primaryError = "guard_config_binder_error: " + summary(error);
            emit("helper_ping_failed", "error", primaryError);
            return false;
        }
    }

    private boolean transactManual(int payload) {
        IBinder value = helper;
        if (value == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(payload);
            requireTransact(value, TurnSignalShellProtocol.TX_SET_MANUAL_STATE, data, reply);
            return true;
        } catch (Throwable error) {
            primaryError = "manual_binder_error: " + summary(error);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void transactAttach(IBinder value, boolean recoveryEnabled) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeStrongBinder(controllerToken);
            data.writeInt(recoveryEnabled ? 1 : 0);
            requireTransact(value, TurnSignalShellProtocol.TX_ATTACH_CONTROLLER, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void transactConfig(IBinder value) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(settings.getBoolean("guard_enabled", false) ? 1 : 0);
            data.writeFloat(settings.getFloat("outward_deg", 90.0f));
            data.writeFloat(settings.getFloat("center_deg", 10.0f));
            data.writeInt(settings.getInt("correction_delay_ms", 100));
            data.writeInt(settings.getInt("max_speed_kph", 30));
            data.writeInt(settings.getInt("assumed_latch_state", -1));
            requireTransact(value, TurnSignalShellProtocol.TX_CONFIGURE_GUARD, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void transactCallback(IBinder value) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeStrongBinder(callback);
            requireTransact(value, TurnSignalShellProtocol.TX_REGISTER_CALLBACK, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void transactNoArgs(IBinder value, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            requireTransact(value, code, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void requireTransact(IBinder binder, int code, Parcel data, Parcel reply)
            throws Exception {
        if (!binder.transact(code, data, reply, 0)) {
            throw new IllegalStateException("binder transaction rejected: " + code);
        }
        reply.readException();
    }

    private Ping ping(IBinder value) {
        if (value == null || !value.isBinderAlive()) return Ping.failed("helper_unavailable");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            if (!value.transact(TurnSignalShellProtocol.TX_PING, data, reply, 0)) {
                return Ping.failed("ping_rejected");
            }
            reply.readException();
            int protocol = reply.readInt();
            int build = reply.readInt();
            int pid = reply.readInt();
            if (protocol != TurnSignalShellProtocol.VERSION) {
                return new Ping(value, protocol, build, pid, "protocol_mismatch");
            }
            if (build != BuildConfig.VERSION_CODE) {
                return new Ping(value, protocol, build, pid, "build_mismatch");
            }
            return new Ping(value, protocol, build, pid, "");
        } catch (Throwable error) {
            return Ping.failed("ping_failed: " + summary(error));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private IBinder resolveHelper() {
        IBinder cached = helper;
        if (cached != null && cached.isBinderAlive()) return cached;
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method getService = manager.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, TurnSignalShellProtocol.SERVICE_NAME);
        } catch (Throwable error) {
            primaryError = "service_manager_error: " + summary(error);
            return null;
        }
    }

    private IBinder ensureCameraHelper() throws Exception {
        IBinder value = cameraHelper;
        if (!cameraPing(value)) {
            LocalAdbClient.Result launch = LocalAdbClient.executeAuthorized(
                    context, cameraLaunchCommand(context.getApplicationInfo().sourceDir,
                            Process.myUid(), BuildConfig.VERSION_CODE), this::emit);
            if (!launch.ok) {
                throw new IllegalStateException("camera_shell_launch_failed: " + launch.error
                        + (launch.output.isEmpty() ? "" : ": " + launch.output));
            }
            emit("camera_shell_launch", "ok", true, "output", launch.output);
            long deadline = SystemClock.elapsedRealtime() + 3_000;
            do {
                value = resolveCameraHelper();
                if (cameraPing(value)) break;
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
        }
        if (!cameraPing(value)) throw new IllegalStateException("camera_shell_binder_timeout");
        cameraHelper = value;
        transactCameraCallback(value);
        return value;
    }

    private IBinder resolveCameraHelper() {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method getService = manager.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, CameraShellProtocol.SERVICE_NAME);
        } catch (Throwable error) {
            emit("camera_shell_resolve_error", "error", summary(error));
            return null;
        }
    }

    private boolean cameraPing(IBinder value) {
        if (value == null || !value.isBinderAlive()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            if (!value.transact(CameraShellProtocol.TX_PING, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() == CameraShellProtocol.VERSION
                    && reply.readInt() == BuildConfig.VERSION_CODE;
        } catch (Throwable error) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void transactCameraCallback(IBinder value) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeStrongBinder(cameraCallback);
            requireTransact(value, CameraShellProtocol.TX_REGISTER_CALLBACK, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static Surface transactCameraOpen(
            IBinder value,
            Surface surface,
            int viewpoint,
            boolean horizontal,
            StockAvmPreview.Config config) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            surface.writeToParcel(data, 0);
            data.writeInt(viewpoint);
            data.writeInt(horizontal ? 1 : 0);
            config.writeToParcel(data);
            requireTransact(value, CameraShellProtocol.TX_OPEN, data, reply);
            Surface inputSurface = Surface.CREATOR.createFromParcel(reply);
            if (inputSurface == null || !inputSurface.isValid()) {
                if (inputSurface != null) inputSurface.release();
                throw new IllegalStateException("camera helper returned invalid input Surface");
            }
            return inputSurface;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void closeStockAvmNow(String reason) {
        IBinder value = cameraHelper;
        if (!cameraPing(value)) value = resolveCameraHelper();
        if (!cameraPing(value)) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeString(reason == null ? "unknown" : reason);
            requireTransact(value, CameraShellProtocol.TX_CLOSE, data, reply);
            cameraHelper = null;
        } catch (Throwable error) {
            emit("camera_error", "renderer", "stock_avm_shell",
                    "stage", "close", "error", summary(error));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void shutdownTurnHelper() {
        IBinder value = helper;
        if (value == null || !value.isBinderAlive()) value = resolveHelper();
        if (value == null || !value.isBinderAlive()) return;
        try {
            transactAttach(value, false);
            transactNoArgs(value, TurnSignalShellProtocol.TX_SHUTDOWN);
            emit("shell_shutdown_requested", "helper", "turn");
        } catch (Throwable error) {
            emit("shell_shutdown_failed", "helper", "turn", "error", summary(error));
        }
    }

    private void shutdownCameraHelper() {
        IBinder value = cameraHelper;
        if (value == null || !value.isBinderAlive()) value = resolveCameraHelper();
        if (value == null || !value.isBinderAlive()) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            requireTransact(value, CameraShellProtocol.TX_SHUTDOWN, data, reply);
            emit("shell_shutdown_requested", "helper", "camera");
        } catch (Throwable error) {
            emit("shell_shutdown_failed", "helper", "camera", "error", summary(error));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void acceptShellEvent(String line) {
        if (line == null) return;
        try {
            JSONObject event = new JSONObject(line);
            String kind = event.optString("kind");
            if ("turn_state_write".equals(kind) && event.optInt("framework_status", -1) == 0) {
                settings.edit().putInt("assumed_latch_state",
                        event.optInt("assumed_latch_state_after", -1)).apply();
            } else if ("turn_state_status".equals(kind)) {
                settings.edit().putInt("assumed_latch_state",
                        event.optInt("assumed_latch_state", -1)).apply();
            }
        } catch (Throwable error) {
            emit("shell_event_parse_error", "error", summary(error));
        }
        shellEventSink.accept(line);
    }

    private void emitUnavailable(String kind) {
        emit(kind, "reason", primaryError.isEmpty() ? "helper_unavailable" : primaryError);
        emit("telemetry_ready", "ok", false, "listener_ok", false,
                "poll_ok", false, "control_ready", false,
                "error", primaryError.isEmpty() ? "helper_unavailable" : primaryError);
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private void migrateLegacyLatchState() {
        if (settings.contains("assumed_latch_state")) return;
        SharedPreferences legacy = context.getSharedPreferences(
                "control_state", Context.MODE_PRIVATE);
        int state = legacy.getInt("assumed_latch_state", -1);
        if (state >= -1 && state <= 3) {
            settings.edit().putInt("assumed_latch_state", state).apply();
        }
        legacy.edit().remove("assumed_latch_state").apply();
    }

    private boolean shouldReplaceInstalledHelper() {
        String previous = settings.getString("helper_apk_marker", "");
        return !previous.isEmpty() && !previous.equals(apkMarker);
    }

    private static String currentApkMarker(Context context) {
        try {
            long updated = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            return context.getApplicationInfo().sourceDir + ":" + updated;
        } catch (Throwable error) {
            return context.getApplicationInfo().sourceDir + ":unknown";
        }
    }

    static String launchCommand(String apkPath, int appUid, int versionCode) {
        String apk = shellQuote(apkPath);
        String process = TurnSignalShellProtocol.PROCESS_NAME;
        return "for pid in $(pidof " + process
                + " 2>/dev/null); do kill \"$pid\" 2>/dev/null || true; done; "
                + "wait_count=0; while [ -n \"$(pidof " + process + " 2>/dev/null)\" ] "
                + "&& [ \"$wait_count\" -lt 30 ]; do sleep 0.1; "
                + "wait_count=$((wait_count + 1)); done; "
                + "if [ -n \"$(pidof " + process + " 2>/dev/null)\" ]; then "
                + "echo helper_stop_timeout; false; else "
                + "rm -f " + TurnSignalShellProtocol.LOCK_PATH + "; "
                + "CLASSPATH=" + apk
                + " setsid app_process /system/bin --nice-name=" + process + " "
                + TurnSignalShellProtocol.HELPER_CLASS + " " + appUid + " " + apk + " "
                + versionCode + " </dev/null >" + TurnSignalShellProtocol.LOG_PATH
                + " 2>&1 & "
                + "for i in 1 2 3; do service list 2>/dev/null | grep -q "
                + TurnSignalShellProtocol.SERVICE_NAME + " && break; sleep 1; done; fi";
    }

    static String cameraLaunchCommand(String apkPath, int appUid, int versionCode) {
        String apk = shellQuote(apkPath);
        String process = CameraShellProtocol.PROCESS_NAME;
        return "for pid in $(pidof " + process
                + " 2>/dev/null); do kill \"$pid\" 2>/dev/null || true; done; "
                + "rm -f " + CameraShellProtocol.LOCK_PATH + "; "
                + "CLASSPATH=" + apk
                + " setsid app_process /system/bin --nice-name=" + process + " "
                + CameraShellProtocol.HELPER_CLASS + " " + appUid + " " + versionCode
                + " </dev/null >" + CameraShellProtocol.LOG_PATH + " 2>&1 & "
                + "for i in 1 2 3; do service list 2>/dev/null | grep -q "
                + CameraShellProtocol.SERVICE_NAME + " && break; sleep 1; done";
    }

    private synchronized void installHelper(IBinder value, int pid) throws Exception {
        if (helper == value) return;
        IBinder.DeathRecipient recipient = () -> helperDied(value, pid);
        value.linkToDeath(recipient, 0);
        IBinder previous = helper;
        IBinder.DeathRecipient previousRecipient = helperDeathRecipient;
        helper = value;
        helperDeathRecipient = recipient;
        unlinkDeathRecipient(previous, previousRecipient);
    }

    private void clearHelper(IBinder expected) {
        IBinder previous;
        IBinder.DeathRecipient previousRecipient;
        synchronized (this) {
            if (expected != null && helper != expected) return;
            previous = helper;
            previousRecipient = helperDeathRecipient;
            helper = null;
            helperDeathRecipient = null;
        }
        unlinkDeathRecipient(previous, previousRecipient);
    }

    private static void unlinkDeathRecipient(
            IBinder binder, IBinder.DeathRecipient recipient) {
        if (binder == null || recipient == null) return;
        try {
            binder.unlinkToDeath(recipient, 0);
        } catch (Throwable ignored) {
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class Ping {
        final IBinder binder;
        final int protocol;
        final int build;
        final int pid;
        final String error;

        Ping(IBinder binder, int protocol, int build, int pid, String error) {
            this.binder = binder;
            this.protocol = protocol;
            this.build = build;
            this.pid = pid;
            this.error = error;
        }

        static Ping failed(String error) {
            return new Ping(null, -1, -1, -1, error);
        }

        boolean healthy() {
            return binder != null && error.isEmpty();
        }
    }

    static final class RetryGate {
        private final AtomicBoolean active = new AtomicBoolean();

        boolean begin() {
            return active.compareAndSet(false, true);
        }

        void end() {
            active.set(false);
        }

        boolean active() {
            return active.get();
        }
    }
}
