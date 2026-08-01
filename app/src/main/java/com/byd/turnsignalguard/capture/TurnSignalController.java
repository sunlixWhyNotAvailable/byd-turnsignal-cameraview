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
    private volatile LocalAdbClient.PromptMode authorizationMode;
    private volatile IBinder helper;
    private volatile IBinder cameraHelper;
    private IBinder.DeathRecipient helperDeathRecipient;
    private IBinder.DeathRecipient cameraHelperDeathRecipient;
    private final PendingOverlay[] pendingOverlays = new PendingOverlay[CameraProfile.COUNT];
    private volatile int pendingReverseRequestId;
    private volatile Consumer<ReverseSurfaces> pendingReverseSurfaceSink;
    private final AuthorizationGate authorizationRequests = new AuthorizationGate();
    private long lastLaunchFailureAt;
    private volatile String automaticAuthorizationBlockedFor = "";
    private volatile boolean automaticAuthorizationBlockReported;
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
            for (CameraProfile profile : CameraProfile.values()) {
                closeCameraOverlayNow(profile.id, "controller_shutdown");
            }
            if (!closeReverseOverlayNow("controller_shutdown")) {
                shutdownCameraHelper();
            }
        }
        clearHelper(null);
        clearCameraHelper(null);
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

    void configureMusic(boolean enabled) {
        settings.edit().putBoolean("music_visualizer_enabled", enabled).apply();
        worker.execute(() -> {
            if (!sendMusicConfig()) ensureRunning(LocalAdbClient.PromptMode.NEVER, false);
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
                clearCameraHelper(null);
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

    void prepareCameraOverlay(
            CameraShellProtocol.OverlaySpec spec,
            Consumer<OverlaySurface> surfaceSink,
            Runnable preparedSink) {
        if (spec == null || surfaceSink == null || preparedSink == null) {
            throw new IllegalArgumentException("overlay spec/sinks required");
        }
        worker.execute(() -> {
            pendingOverlays[spec.cameraId] = new PendingOverlay(spec.requestId, surfaceSink);
            try {
                IBinder value = ensureCameraHelper();
                transactOverlayPrepare(value, spec);
                emit("camera_overlay_prepare", "camera_id", spec.cameraId,
                        "request_id", spec.requestId,
                        "target", CameraDisplayTarget.name(spec.target),
                        "width", spec.width, "height", spec.height,
                        "x", spec.x, "y", spec.y);
                if (!handler.post(preparedSink)) {
                    throw new IllegalStateException("main handler rejected overlay prepare");
                }
            } catch (Throwable error) {
                clearPendingOverlaySurface(spec.cameraId, spec.requestId);
                clearCameraHelper(null);
                emit("camera_overlay_error", "stage", "prepare",
                        "camera_id", spec.cameraId,
                        "request_id", spec.requestId, "error", summary(error));
            }
        });
    }

    void armCameraOverlayFrame(int cameraId, int requestId, int surfaceGeneration) {
        worker.execute(() -> {
            try {
                IBinder value = ensureCameraHelper();
                transactOverlayFrame(value, cameraId, requestId, surfaceGeneration);
            } catch (Throwable error) {
                emit("camera_overlay_error", "stage", "arm_first_frame",
                        "camera_id", cameraId, "request_id", requestId,
                        "surface_generation", surfaceGeneration,
                        "error", summary(error));
            }
        });
    }

    void setCameraOverlayVisible(
            int cameraId, int requestId, int surfaceGeneration, boolean visible) {
        setCameraOverlayVisible(cameraId, requestId, surfaceGeneration, visible, null);
    }

    void setCameraOverlayVisible(
            int cameraId, int requestId, int surfaceGeneration,
            boolean visible, Runnable completion) {
        worker.execute(() -> {
            try {
                IBinder value = ensureCameraHelper();
                transactOverlayVisibility(
                        value, cameraId, requestId, surfaceGeneration, visible);
            } catch (Throwable error) {
                emit("camera_overlay_error", "stage", visible ? "show" : "hide",
                        "camera_id", cameraId, "request_id", requestId,
                        "surface_generation", surfaceGeneration,
                        "error", summary(error));
            } finally {
                if (completion != null && !handler.post(completion)) {
                    emit("camera_overlay_error", "stage", "visibility_completion",
                            "request_id", requestId);
                }
            }
        });
    }

    void setCameraOverlayWarning(
            int cameraId, int requestId, int surfaceGeneration, int edge, int mode) {
        worker.execute(() -> {
            try {
                IBinder value = ensureCameraHelper();
                transactOverlayWarning(
                        value, cameraId, requestId, surfaceGeneration, edge, mode);
            } catch (Throwable error) {
                emit("camera_overlay_warning_error", "stage", "set_warning",
                        "camera_id", cameraId, "request_id", requestId,
                        "surface_generation", surfaceGeneration,
                        "edge", edge, "mode", mode,
                        "error", summary(error));
            }
        });
    }

    void closeCameraOverlay(int cameraId, String reason) {
        CameraProfile.of(cameraId);
        worker.execute(() -> closeCameraOverlayNow(cameraId, reason));
    }

    void closeCameraOverlays(String reason) {
        worker.execute(() -> {
            for (CameraProfile profile : CameraProfile.values()) {
                closeCameraOverlayNow(profile.id, reason);
            }
        });
    }

    void prepareReverseOverlay(
            CameraShellProtocol.ReverseOverlaySpec spec,
            Consumer<ReverseSurfaces> surfaceSink,
            Runnable preparedSink) {
        if (spec == null || surfaceSink == null || preparedSink == null) {
            throw new IllegalArgumentException("reverse spec/sinks required");
        }
        worker.execute(() -> {
            clearPendingOverlays();
            pendingReverseRequestId = spec.requestId;
            pendingReverseSurfaceSink = surfaceSink;
            try {
                IBinder value = ensureCameraHelper();
                transactReversePrepare(value, spec);
                emit("reverse_overlay_prepare", "request_id", spec.requestId);
                if (!handler.post(preparedSink)) {
                    throw new IllegalStateException("main handler rejected reverse prepare");
                }
            } catch (Throwable error) {
                clearPendingReverseSurfaces(spec.requestId);
                clearCameraHelper(null);
                emit("reverse_overlay_error", "stage", "prepare",
                        "request_id", spec.requestId, "error", summary(error));
            }
        });
    }

    void armReverseOverlayFrames(int requestId, int[] generations) {
        worker.execute(() -> {
            try {
                IBinder value = ensureCameraHelper();
                transactReverseFrames(value, requestId, generations);
            } catch (Throwable error) {
                emit("reverse_overlay_error", "stage", "arm_first_frames",
                        "request_id", requestId, "error", summary(error));
            }
        });
    }

    void setReverseOverlayVisible(
            int requestId, int[] generations, boolean visible,
            Consumer<Boolean> completion) {
        worker.execute(() -> {
            boolean success = false;
            try {
                IBinder value = ensureCameraHelper();
                transactReverseVisibility(value, requestId, generations, visible);
                success = true;
            } catch (Throwable error) {
                emit("reverse_overlay_error", "stage", visible ? "show" : "hide",
                        "request_id", requestId, "error", summary(error));
            } finally {
                postCompletion(completion, success, "reverse_visibility", requestId);
            }
        });
    }

    void closeReverseOverlay(String reason, Consumer<Boolean> completion) {
        worker.execute(() -> {
            boolean success = closeReverseOverlayNow(reason);
            postCompletion(completion, success, "reverse_close", 0);
        });
    }

    private void postCompletion(
            Consumer<Boolean> completion, boolean success, String stage, int requestId) {
        if (completion != null && !handler.post(() -> completion.accept(success))) {
            emit("reverse_overlay_error", "stage", stage + "_completion",
                    "request_id", requestId);
        }
    }

    void reportStatus() {
        emit("adb_auth_state", "pending", authorizationPending,
                "mode", modeName(authorizationMode));
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

    AuthorizationRequestAction requestAuthorization(LocalAdbClient.PromptMode mode) {
        if (mode != LocalAdbClient.PromptMode.AUTO_ONCE
                && mode != LocalAdbClient.PromptMode.FORCE) {
            throw new IllegalArgumentException("Unsupported authorization mode: " + mode);
        }
        AuthorizationRequestAction action = authorizationRequests.request(mode);
        if (action == AuthorizationRequestAction.COALESCED) {
            emit("adb_authorization_request", "mode", mode.name(),
                    "result", action.wireName(),
                    "active_mode", modeName(authorizationRequests.mode()));
            return action;
        }
        if (mode == LocalAdbClient.PromptMode.FORCE) {
            automaticAuthorizationBlockedFor = "";
            automaticAuthorizationBlockReported = false;
        }
        boolean socketClosed = mode == LocalAdbClient.PromptMode.FORCE
                && LocalAdbClient.cancelPendingAuthorization();
        long cancellationToken = LocalAdbClient.cancellationToken();
        authorizationPending = true;
        authorizationMode = authorizationRequests.mode();
        emit("adb_authorization_request", "mode", mode.name(),
                "result", action.wireName(),
                "cancelled_pending_socket", socketClosed,
                "active_mode", modeName(authorizationMode));
        emitAuthorizationState();
        worker.execute(() -> {
            try {
                if (!authorizationRequests.isCurrent(mode)) {
                    emit("authorization_superseded", "mode", mode.name(),
                            "next_mode", modeName(authorizationRequests.mode()),
                            "stage", "queued");
                    return;
                }
                ensureRunning(mode, true, cancellationToken);
            } finally {
                authorizationRequests.finish(mode);
                syncRequestedAuthorizationState();
            }
        });
        return action;
    }

    private void checkHealth() {
        Ping ping = ping(resolveHelper());
        if (ping.healthy() && !shouldReplaceInstalledHelper()) {
            if (!healthy || helper != ping.binder) {
                attach(ping);
                return;
            }
            try {
                transactNoArgs(ping.binder, TurnSignalShellProtocol.TX_REPORT_STATUS);
                return;
            } catch (Throwable error) {
                ping = Ping.failed("status_binder_error: " + summary(error));
            }
        }
        if (healthy) {
            healthy = false;
            primaryError = ping.error;
            emit("helper_ping_failed", "error", ping.error);
        }
        ensureRunning(LocalAdbClient.PromptMode.NEVER, false);
    }

    private void ensureRunning(LocalAdbClient.PromptMode mode, boolean ignoreBackoff) {
        ensureRunning(mode, ignoreBackoff, LocalAdbClient.cancellationToken());
    }

    private void ensureRunning(
            LocalAdbClient.PromptMode mode, boolean ignoreBackoff, long cancellationToken) {
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
        LocalAdbClient.PromptMode requestedMode = authorizationRequests.mode();
        if (isSupersededByRequest(mode, requestedMode)) {
            authorizationPending = true;
            authorizationMode = requestedMode;
            emit("adb_auth_deferred", "mode", mode.name(),
                    "active_mode", modeName(requestedMode));
            return;
        }
        synchronized (LAUNCH_LOCK) {
            Ping current = ping(resolveHelper());
            boolean replaceInstalledApk = shouldReplaceInstalledHelper();
            boolean helperReady = current.healthy() && !replaceInstalledApk;
            if (canReuseHealthyHelperBeforeAuthorization(helperReady, mode)) {
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
            authorizationMode = authorizationRequests.active()
                    ? authorizationRequests.mode() : mode;
            emit("adb_auth_start", "mode", mode.name(),
                    "endpoint", LocalAdbClient.endpointForTest());
            LocalAdbClient.Result authorization = LocalAdbClient.authorize(
                    context, mode, cancellationToken, this::emit);
            if (authorization.superseded) {
                authorizationPending = authorizationRequests.active();
                authorizationMode = authorizationRequests.mode();
                emit("authorization_superseded", "mode", mode.name(),
                        "next_mode", modeName(authorizationMode),
                        "stage", "socket");
                return;
            }
            emit("adb_auth_result", "ok", authorization.ok,
                    "mode", mode.name(),
                    "authorization_required", authorization.authorizationRequired,
                    "public_key_sent", authorization.publicKeySent,
                    "fingerprint", authorization.fingerprint,
                    "error", authorization.error);
            LocalAdbClient.PromptMode activeRequestedMode = authorizationRequests.mode();
            if (isSupersededByRequest(mode, activeRequestedMode)) {
                emit("authorization_superseded", "mode", mode.name(),
                        "next_mode", modeName(activeRequestedMode),
                        "stage", "result");
                return;
            }
            if (!authorizationRequests.active()) {
                authorizationPending = false;
                authorizationMode = null;
                emitAuthorizationState();
            }
            if (shouldRememberAutomaticAuthorizationBlock(mode, authorization)) {
                automaticAuthorizationBlockedFor = BuildConfig.VERSION_CODE + ":"
                        + authorization.fingerprint;
                automaticAuthorizationBlockReported = false;
            } else if (authorization.ok) {
                automaticAuthorizationBlockedFor = "";
                automaticAuthorizationBlockReported = false;
            }
            if (shouldRecordLaunchFailure(authorization)) {
                String error = authorization.authorizationRequired
                        ? authorization.error : "adb_authorization_failed: " + authorization.error;
                if (helperReady) {
                    emit("adb_authorization_failed_helper_preserved",
                            "mode", mode.name(), "error", error,
                            "helper_pid", current.pid);
                    attach(current);
                } else {
                    launchFailed(error);
                }
                return;
            }
            if (helperReady) {
                lastLaunchFailureAt = 0;
                attach(current);
                return;
            }

            LocalAdbClient.Result launch = LocalAdbClient.executeAuthorized(
                    context, launchCommand(context.getApplicationInfo().sourceDir,
                            Process.myUid(), BuildConfig.VERSION_CODE),
                    cancellationToken, this::emit);
            if (launch.superseded) {
                emit("authorization_superseded", "mode", mode.name(),
                        "next_mode", modeName(authorizationRequests.mode()),
                        "stage", "helper_launch");
                return;
            }
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

    static boolean shouldRememberAutomaticAuthorizationBlock(
            LocalAdbClient.PromptMode mode, LocalAdbClient.Result result) {
        return mode != LocalAdbClient.PromptMode.NEVER && result.authorizationRequired;
    }

    static boolean canReuseHealthyHelperBeforeAuthorization(
            boolean helperReady, LocalAdbClient.PromptMode mode) {
        return helperReady && mode != LocalAdbClient.PromptMode.FORCE;
    }

    static boolean isSupersededByRequest(
            LocalAdbClient.PromptMode mode, LocalAdbClient.PromptMode requestedMode) {
        return requestedMode != null && mode != requestedMode;
    }

    static boolean shouldBlockAutomaticAuthorization(
            LocalAdbClient.PromptMode mode, String blockedIdentity, String currentIdentity) {
        return mode != LocalAdbClient.PromptMode.FORCE
                && !blockedIdentity.isEmpty()
                && blockedIdentity.equals(currentIdentity);
    }

    private void syncRequestedAuthorizationState() {
        authorizationMode = authorizationRequests.mode();
        authorizationPending = authorizationMode != null;
        emitAuthorizationState();
    }

    private void emitAuthorizationState() {
        emit("adb_auth_state", "pending", authorizationPending,
                "mode", modeName(authorizationMode));
    }

    private static String modeName(LocalAdbClient.PromptMode mode) {
        return mode == null ? "NONE" : mode.name();
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
            transactCallback(value);
            transactConfig(value);
            transactMusicConfig(value);
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

    private boolean sendMusicConfig() {
        IBinder value = helper;
        if (!healthy || value == null) return false;
        try {
            transactMusicConfig(value);
            return true;
        } catch (Throwable error) {
            clearHelper(value);
            healthy = false;
            primaryError = "music_config_binder_error: " + summary(error);
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

    private void transactMusicConfig(IBinder value) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(settings.getBoolean("music_visualizer_enabled", false) ? 1 : 0);
            requireTransact(value, TurnSignalShellProtocol.TX_CONFIGURE_MUSIC, data, reply);
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
        if (cameraHelper != value) {
            transactCameraCallback(value);
            installCameraHelper(value);
        }
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

    private static void transactOverlayPrepare(
            IBinder value, CameraShellProtocol.OverlaySpec spec) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            spec.writeToParcel(data);
            requireTransact(value, CameraShellProtocol.TX_OVERLAY_PREPARE, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static OverlaySurface transactOverlayAcquire(
            IBinder value, int cameraId, int requestId) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeInt(cameraId);
            data.writeInt(requestId);
            requireTransact(
                    value, CameraShellProtocol.TX_OVERLAY_ACQUIRE_SURFACE, data, reply);
            int returnedCameraId = reply.readInt();
            int returnedRequestId = reply.readInt();
            int surfaceGeneration = reply.readInt();
            Surface surface = Surface.CREATOR.createFromParcel(reply);
            if (returnedCameraId != cameraId || returnedRequestId != requestId
                    || surfaceGeneration <= 0
                    || surface == null || !surface.isValid()) {
                if (surface != null) surface.release();
                throw new IllegalStateException("camera helper returned stale overlay Surface");
            }
            return new OverlaySurface(
                    returnedCameraId, returnedRequestId, surfaceGeneration, surface);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void transactOverlayFrame(
            IBinder value, int cameraId, int requestId, int surfaceGeneration) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeInt(cameraId);
            data.writeInt(requestId);
            data.writeInt(surfaceGeneration);
            requireTransact(
                    value, CameraShellProtocol.TX_OVERLAY_ARM_FRAME, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void transactOverlayVisibility(
            IBinder value, int cameraId, int requestId,
            int surfaceGeneration, boolean visible)
            throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeInt(cameraId);
            data.writeInt(requestId);
            data.writeInt(surfaceGeneration);
            data.writeInt(visible ? 1 : 0);
            requireTransact(
                    value, CameraShellProtocol.TX_OVERLAY_SET_VISIBLE, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void transactOverlayClose(
            IBinder value, int cameraId, String reason) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeInt(cameraId);
            data.writeString(reason == null ? "unknown" : reason);
            requireTransact(value, CameraShellProtocol.TX_OVERLAY_CLOSE, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void transactOverlayWarning(
            IBinder value, int cameraId, int requestId,
            int surfaceGeneration, int edge, int mode)
            throws Exception {
        CameraShellProtocol.validateWarning(
                cameraId, requestId, surfaceGeneration, edge, mode);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeInt(cameraId);
            data.writeInt(requestId);
            data.writeInt(surfaceGeneration);
            data.writeInt(edge);
            data.writeInt(mode);
            requireTransact(
                    value, CameraShellProtocol.TX_OVERLAY_SET_WARNING, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void transactReversePrepare(
            IBinder value, CameraShellProtocol.ReverseOverlaySpec spec) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            spec.writeToParcel(data);
            requireTransact(value, CameraShellProtocol.TX_REVERSE_PREPARE, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static ReverseSurfaces transactReverseAcquire(
            IBinder value, int requestId) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        Surface[] surfaces = new Surface[3];
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeInt(requestId);
            requireTransact(value, CameraShellProtocol.TX_REVERSE_ACQUIRE_SURFACES, data, reply);
            int returnedRequestId = reply.readInt();
            int count = reply.readInt();
            if (returnedRequestId != requestId || count != surfaces.length) {
                throw new IllegalStateException("camera helper returned stale reverse Surfaces");
            }
            int[] generations = new int[count];
            for (int i = 0; i < count; i++) {
                generations[i] = reply.readInt();
                if (generations[i] <= 0) {
                    throw new IllegalStateException("invalid reverse Surface generation");
                }
            }
            for (int i = 0; i < count; i++) {
                surfaces[i] = Surface.CREATOR.createFromParcel(reply);
                if (surfaces[i] == null || !surfaces[i].isValid()) {
                    throw new IllegalStateException("invalid reverse Surface");
                }
            }
            return new ReverseSurfaces(returnedRequestId, generations, surfaces);
        } catch (Throwable error) {
            releaseSurfaces(surfaces);
            throw error;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void transactReverseFrames(
            IBinder value, int requestId, int[] generations) throws Exception {
        transactReverseIdentity(value, CameraShellProtocol.TX_REVERSE_ARM_FRAMES,
                requestId, generations, false);
    }

    private static void transactReverseVisibility(
            IBinder value, int requestId, int[] generations, boolean visible) throws Exception {
        transactReverseIdentity(value, CameraShellProtocol.TX_REVERSE_SET_VISIBLE,
                requestId, generations, visible);
    }

    private static void transactReverseIdentity(
            IBinder value, int transaction, int requestId, int[] generations,
            boolean visible) throws Exception {
        if (requestId <= 0 || generations == null || generations.length != 3) {
            throw new IllegalArgumentException("invalid reverse request identity");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeInt(requestId);
            data.writeInt(generations.length);
            for (int generation : generations) data.writeInt(generation);
            if (transaction == CameraShellProtocol.TX_REVERSE_SET_VISIBLE) {
                data.writeInt(visible ? 1 : 0);
            }
            requireTransact(value, transaction, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void transactReverseClose(IBinder value, String reason) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeString(reason == null ? "unknown" : reason);
            requireTransact(value, CameraShellProtocol.TX_REVERSE_CLOSE, data, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void acquireCameraOverlaySurface(
            int cameraId, int requestId, int reportedGeneration) {
        if (!CameraProfile.isValid(cameraId)) return;
        PendingOverlay pending = pendingOverlays[cameraId];
        if (pending == null || pending.sink == null
                || requestId != pending.requestId || reportedGeneration <= 0) {
            return;
        }
        try {
            IBinder value = cameraHelper;
            if (!cameraPing(value)) value = ensureCameraHelper();
            OverlaySurface result = transactOverlayAcquire(value, cameraId, requestId);
            if (result.surfaceGeneration != reportedGeneration) {
                result.surface.release();
                throw new IllegalStateException("overlay Surface generation changed");
            }
            clearPendingOverlaySurface(cameraId, requestId);
            if (!handler.post(() -> pending.sink.accept(result))) {
                result.surface.release();
                throw new IllegalStateException("main handler rejected overlay Surface");
            }
        } catch (Throwable error) {
            clearPendingOverlaySurface(cameraId, requestId);
            emit("camera_overlay_error", "stage", "acquire_surface",
                    "camera_id", cameraId, "request_id", requestId,
                    "surface_generation", reportedGeneration,
                    "error", summary(error));
        }
    }

    private void clearPendingOverlaySurface(int cameraId, int requestId) {
        if (!CameraProfile.isValid(cameraId)) return;
        PendingOverlay pending = pendingOverlays[cameraId];
        if (pending == null || requestId != pending.requestId) return;
        pendingOverlays[cameraId] = null;
    }

    private void clearPendingOverlays() {
        for (int i = 0; i < pendingOverlays.length; i++) pendingOverlays[i] = null;
    }

    private void acquireReverseSurfaces(int requestId) {
        Consumer<ReverseSurfaces> sink = pendingReverseSurfaceSink;
        if (sink == null || requestId != pendingReverseRequestId) return;
        try {
            IBinder value = cameraHelper;
            if (!cameraPing(value)) value = ensureCameraHelper();
            ReverseSurfaces result = transactReverseAcquire(value, requestId);
            clearPendingReverseSurfaces(requestId);
            if (!handler.post(() -> sink.accept(result))) {
                releaseSurfaces(result.surfaces);
                throw new IllegalStateException("main handler rejected reverse Surfaces");
            }
        } catch (Throwable error) {
            clearPendingReverseSurfaces(requestId);
            emit("reverse_overlay_error", "stage", "acquire_surfaces",
                    "request_id", requestId, "error", summary(error));
        }
    }

    private void clearPendingReverseSurfaces(int requestId) {
        if (requestId != pendingReverseRequestId) return;
        pendingReverseRequestId = 0;
        pendingReverseSurfaceSink = null;
    }

    private static void releaseSurfaces(Surface[] surfaces) {
        if (surfaces == null) return;
        for (Surface surface : surfaces) {
            if (surface != null) surface.release();
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
        } catch (Throwable error) {
            emit("camera_error", "renderer", "stock_avm_shell",
                    "stage", "close", "error", summary(error));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void closeCameraOverlayNow(int cameraId, String reason) {
        CameraProfile.of(cameraId);
        pendingOverlays[cameraId] = null;
        IBinder value = cameraHelper;
        if (!cameraPing(value)) value = resolveCameraHelper();
        if (!cameraPing(value)) return;
        try {
            transactOverlayClose(value, cameraId, reason);
        } catch (Throwable error) {
            emit("camera_overlay_error", "stage", "close",
                    "camera_id", cameraId, "error", summary(error));
        }
    }

    private boolean closeReverseOverlayNow(String reason) {
        pendingReverseRequestId = 0;
        pendingReverseSurfaceSink = null;
        IBinder value = cameraHelper;
        if (!cameraPing(value)) value = resolveCameraHelper();
        if (!cameraPing(value)) return true;
        try {
            transactReverseClose(value, reason);
            return true;
        } catch (Throwable error) {
            emit("reverse_overlay_error", "stage", "close", "error", summary(error));
            return false;
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
            clearCameraHelper(value);
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
            } else if ("camera_overlay_surface".equals(kind)
                    && "ready".equals(event.optString("state"))) {
                int cameraId = event.optInt("camera_id", -1);
                int requestId = event.optInt("request_id", -1);
                int surfaceGeneration = event.optInt("surface_generation", -1);
                worker.execute(() -> acquireCameraOverlaySurface(
                        cameraId, requestId, surfaceGeneration));
            } else if ("reverse_overlay_surface".equals(kind)
                    && "ready".equals(event.optString("state"))) {
                int requestId = event.optInt("request_id", -1);
                worker.execute(() -> acquireReverseSurfaces(requestId));
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

    private synchronized void installCameraHelper(IBinder value) throws Exception {
        if (cameraHelper == value) return;
        IBinder.DeathRecipient recipient = () -> cameraHelperDied(value);
        value.linkToDeath(recipient, 0);
        IBinder previous = cameraHelper;
        IBinder.DeathRecipient previousRecipient = cameraHelperDeathRecipient;
        cameraHelper = value;
        cameraHelperDeathRecipient = recipient;
        unlinkDeathRecipient(previous, previousRecipient);
    }

    private void cameraHelperDied(IBinder value) {
        clearCameraHelper(value);
        clearPendingOverlays();
        pendingReverseRequestId = 0;
        pendingReverseSurfaceSink = null;
        emit("camera_overlay_error", "stage", "camera_shell_died",
                "error", "camera helper Binder died");
        emit("reverse_overlay_error", "stage", "camera_shell_died",
                "error", "camera helper Binder died");
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

    private void clearCameraHelper(IBinder expected) {
        IBinder previous;
        IBinder.DeathRecipient previousRecipient;
        synchronized (this) {
            if (expected != null && cameraHelper != expected) return;
            previous = cameraHelper;
            previousRecipient = cameraHelperDeathRecipient;
            cameraHelper = null;
            cameraHelperDeathRecipient = null;
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

    static final class OverlaySurface {
        final int cameraId;
        final int requestId;
        final int surfaceGeneration;
        final Surface surface;

        OverlaySurface(
                int cameraId, int requestId, int surfaceGeneration, Surface surface) {
            this.cameraId = cameraId;
            this.requestId = requestId;
            this.surfaceGeneration = surfaceGeneration;
            this.surface = surface;
        }
    }

    private static final class PendingOverlay {
        final int requestId;
        final Consumer<OverlaySurface> sink;

        PendingOverlay(int requestId, Consumer<OverlaySurface> sink) {
            this.requestId = requestId;
            this.sink = sink;
        }
    }

    static final class ReverseSurfaces {
        final int requestId;
        final int[] generations;
        final Surface[] surfaces;

        ReverseSurfaces(int requestId, int[] generations, Surface[] surfaces) {
            this.requestId = requestId;
            this.generations = generations;
            this.surfaces = surfaces;
        }
    }

    enum AuthorizationRequestAction {
        ACCEPTED("accepted"),
        REPLACED_AUTO("accepted"),
        COALESCED("coalesced");

        private final String wireName;

        AuthorizationRequestAction(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }

        boolean replacedAuto() {
            return this == REPLACED_AUTO;
        }
    }

    static final class AuthorizationGate {
        private LocalAdbClient.PromptMode mode;

        synchronized AuthorizationRequestAction request(LocalAdbClient.PromptMode requested) {
            if (requested != LocalAdbClient.PromptMode.AUTO_ONCE
                    && requested != LocalAdbClient.PromptMode.FORCE) {
                throw new IllegalArgumentException("Unsupported authorization mode: " + requested);
            }
            if (mode == null) {
                mode = requested;
                return AuthorizationRequestAction.ACCEPTED;
            }
            if (requested == LocalAdbClient.PromptMode.FORCE
                    && mode == LocalAdbClient.PromptMode.AUTO_ONCE) {
                mode = LocalAdbClient.PromptMode.FORCE;
                return AuthorizationRequestAction.REPLACED_AUTO;
            }
            return AuthorizationRequestAction.COALESCED;
        }

        synchronized void finish(LocalAdbClient.PromptMode finished) {
            if (mode == finished) mode = null;
        }

        synchronized boolean active() {
            return mode != null;
        }

        synchronized boolean isCurrent(LocalAdbClient.PromptMode expected) {
            return mode == expected;
        }

        synchronized LocalAdbClient.PromptMode mode() {
            return mode;
        }
    }
}
