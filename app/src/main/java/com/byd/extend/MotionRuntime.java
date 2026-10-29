package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** App-side Motion settings publisher and optional observer; the shell worker owns the session. */
public final class MotionRuntime implements AutoCloseable {
    private static final String TAG = "BydMotion";
    private static final Object APPLY_STATUS_LOCK = new Object();
    private static final Object ACTIVE_LOCK = new Object();
    private static final ScheduledExecutorService BACKGROUND_PUBLISHER = Executors.newSingleThreadScheduledExecutor(r ->
            new Thread(r, "motion-config-publisher"));
    private static final MotionConfigRetry CONFIG_RETRY = new MotionConfigRetry();
    private static ScheduledFuture<?> pendingRetry;
    private static long retryGeneration;
    private static MotionDetectorClient backgroundClient;
    private static String backgroundInFlight;
    private static String applyConfig;
    private static String applyState;
    private static String applyError;
    private static String applyErrorRef;
    private static volatile MotionRuntime active;

    private final Context context;
    private final Consumer<String> events;
    private final HandlerThread thread = new HandlerThread("motion-runtime-observer");
    private final Handler handler;
    private final MotionOemRetentionObserver retentionObserver;
    private final SharedPreferences screenPrefs;
    private final SharedPreferences recordingPrefs;
    private final SharedPreferences chargePrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed =
            (prefs, key) -> refresh();

    private MotionConfig config;
    private MotionDetectorClient detector;
    private String submittedConfig;
    private volatile String inFlightConfig;
    private volatile boolean closed;
    private boolean closing;
    private boolean publishAfterClose;

    MotionRuntime(Context context, CameraRuntimeClient ignoredCameras, Consumer<String> events) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.events = events == null ? ignored -> { } : events;
        screenPrefs = this.context.getSharedPreferences(MotionConfig.SCREEN_PREFS, Context.MODE_PRIVATE);
        recordingPrefs = this.context.getSharedPreferences(MotionConfig.RECORDING_PREFS, Context.MODE_PRIVATE);
        chargePrefs = this.context.getSharedPreferences(CameraChargeSettings.PREFS, Context.MODE_PRIVATE);
        normalizeStoredLockMode(screenPrefs);
        thread.start();
        handler = new Handler(thread.getLooper());
        retentionObserver = new MotionOemRetentionObserver(this.context, handler, this::recheckOemRetention);
        screenPrefs.registerOnSharedPreferenceChangeListener(changed);
        recordingPrefs.registerOnSharedPreferenceChangeListener(changed);
        chargePrefs.registerOnSharedPreferenceChangeListener(changed);
        synchronized (ACTIVE_LOCK) { active = this; }
        synchronized (APPLY_STATUS_LOCK) { cancelRetry(); CONFIG_RETRY.recovered(); }
        BACKGROUND_PUBLISHER.execute(() -> {
            if (backgroundClient != null) {
                backgroundClient.close();
                backgroundClient = null;
                backgroundInFlight = null;
            }
        });
        refresh();
    }

    /** Called by settings UI after edits; enabling starts the existing authorized helper path. */
    public static void settingsChanged(Context context) {
        if (context == null) return;
        Context target = applicationContext(context);
        MotionConfig current = MotionConfig.read(target);
        String desired = current.toJson();
        target.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("motion_monitoring_enabled", current.anyEnabled()).apply();
        if (!markPendingForSchedule(desired)) return;

        MotionRuntime runtime = active;
        if (runtime != null) {
            runtime.requestPublish();
            return;
        }
        enqueueBackgroundPublish(target);
    }

    /** Commits only the existing mode key, then schedules application without blocking the caller. */
    public static void setModeEnabled(Context context, String mode, boolean enabled)
            throws CameraModeClient.ModeException {
        if (context == null) throw modeFailure("invalid_request",
                new IllegalArgumentException("context required"));
        Context target = applicationContext(context);
        CameraModeSettings.persistModePreference(target, mode, enabled);
        settingsChanged(target);
    }

    /** Returns in-process acknowledgement only; a fresh app process truthfully starts as pending. */
    public static JSONObject applyStatus(String desiredConfig) throws JSONException {
        synchronized (APPLY_STATUS_LOCK) {
            boolean matches = desiredConfig != null && desiredConfig.equals(applyConfig);
            String state = matches && applyState != null ? applyState : "pending";
            JSONObject result = new JSONObject().put("applyState", state);
            if (matches && "error".equals(state)) {
                if (applyError != null) result.put("error", applyError);
                if (applyErrorRef != null) result.put("errorRef", applyErrorRef);
            }
            return result;
        }
    }

    /** Persists remote viewing and bootstraps the existing shell worker when it becomes enabled. */
    public static void setRemoteViewingEnabled(Context context, boolean enabled) {
        if (context == null) throw new IllegalArgumentException("context required");
        Context target = applicationContext(context);
        SharedPreferences preferences = target.getSharedPreferences(
                CameraChargeSettings.PREFS, Context.MODE_PRIVATE);
        if (preferences.getBoolean(CameraChargeSettings.KEY_REMOTE_VIEWING_ENABLED, false) == enabled)
            return;
        preferences.edit().putBoolean(CameraChargeSettings.KEY_REMOTE_VIEWING_ENABLED, enabled).apply();
        settingsChanged(target);
    }

    void refresh() {
        scheduleRefresh(false);
    }

    void requestPublish() {
        synchronized (APPLY_STATUS_LOCK) { cancelRetry(); CONFIG_RETRY.recovered(); }
        scheduleRefresh(true);
    }

    private void scheduleRefresh(boolean explicitPublish) {
        boolean publishInBackground = false;
        boolean postFailed = false;
        synchronized (ACTIVE_LOCK) {
            if (closing || closed) {
                if (active == this) publishAfterClose = true;
                else publishInBackground = true;
            } else if (!handler.post(() -> {
                if (closed) {
                    publishAfterObserverClose();
                    return;
                }
                refreshOnObserver(explicitPublish);
            })) {
                // If this observer is later detached, the close path will retry once using
                // the ordinary background publisher; until then preserve the scheduling error.
                if (active == this) publishAfterClose = true;
                postFailed = true;
            }
        }
        if (publishInBackground) enqueueBackgroundPublish(context);
        if (postFailed) {
            String desired = MotionConfig.read(context).toJson();
            IllegalStateException failure = new IllegalStateException(explicitPublish
                    ? "Motion observer is not accepting config"
                    : "Motion observer is not accepting updates");
            markApplyErrorIfCurrent(context, desired, "runtime_apply_failed", null);
            Log.e(TAG, explicitPublish
                    ? "motion_config_publish_not_scheduled"
                    : "motion_config_refresh_not_scheduled", failure);
        }
    }

    private void publishAfterObserverClose() {
        synchronized (ACTIVE_LOCK) {
            if (active == this) {
                publishAfterClose = true;
                return;
            }
        }
        enqueueBackgroundPublish(context);
    }

    private void recheckOemRetention(int reason) {
        if (closed || GuardRecovery.isUserShutdownActive(context) || detector == null) return;
        try { detector.recheckOemRetention(reason); }
        catch (RuntimeException failure) { retentionObserver.failure("notify_worker", failure); }
    }

    private void refreshOnObserver(boolean explicitPublish) {
        if (closed || GuardRecovery.isUserShutdownActive(context)) {
            retentionObserver.close();
            return;
        }
        MotionConfig next = MotionConfig.read(context);
        retentionObserver.setEnabled(next.oemCleanup);
        String nextJson = next.toJson();
        boolean modified = !nextJson.equals(submittedConfig);
        config = next;
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("motion_monitoring_enabled", next.anyEnabled()).apply();

        if (detector == null) detector = new MotionDetectorClient(context);
        if (!modified && (!explicitPublish || nextJson.equals(inFlightConfig))) return;

        submittedConfig = nextJson;
        inFlightConfig = nextJson;
        markPending(nextJson);
        event("motion_config_publish", "enabled", next.anyEnabled(), "config", nextJson);
        final String requestedConfig = nextJson;
        MotionDetectorClient client = detector;
        try {
            client.configure(requestedConfig, new MotionDetectorClient.Listener() {
                @Override public void onPrepared(android.os.IBinder workerBinder) {
                    event("motion_worker_prepared", "uid", 2000);
                }

                @Override public void onConfigured(boolean retainViewing, String storageError) {
                    if (!retainViewing)
                        com.byd.extend.remote.CameraViewingController.get(context).workerReconfigured();
                    if (storageError != null) {
                        onError("storage_configure", storageError, SystemClock.elapsedRealtime());
                        return;
                    }
                    clearInFlight(requestedConfig);
                    markAppliedIfCurrent(context, requestedConfig);
                }

                @Override public void onStatus(String state, String detail, long elapsedMs) {
                    if ("configured_disabled".equals(state)) {
                        event("motion_worker_disabled", "detail", detail, "elapsed_ms", elapsedMs);
                        publishCurrentChargeState();
                        return;
                    }
                    event("motion_worker_state", "state", state, "detail", detail,
                            "elapsed_ms", elapsedMs);
                }

                @Override public void onDetection(int level, long elapsedMs, int rawResult,
                        int nativeState, long frameNumber, long frameTimestampNs, long warningUntilMs) {
                    event("motion_detected", "level", level, "raw", rawResult,
                            "native_state", nativeState, "frame", frameNumber,
                            "frame_timestamp_ns", frameTimestampNs, "elapsed_ms", elapsedMs,
                            "warning_until_ms", warningUntilMs);
                }

                @Override public void onError(String stage, String detail, long elapsedMs) {
                    String reference = diagnosticReference(detail);
                    boolean deliveryFailure = requestedConfig.equals(inFlightConfig)
                            || "worker_died".equals(stage);
                    if (deliveryFailure) {
                        clearInFlight(requestedConfig);
                        markApplyErrorIfCurrent(context, requestedConfig, "runtime_apply_failed", reference);
                    }
                    Log.e(TAG, "motion_config_apply_failed stage=" + stage
                            + (reference == null ? "" : " diagnostic_ref=" + reference)
                            + " detail=" + detail);
                    event("motion_error", "stage", stage, "error", detail,
                            "automatic_retry", deliveryFailure && retryDelivery(context, requestedConfig),
                            "elapsed_ms", elapsedMs);
                }

                @Override public void onScreenState(String state, String detail, long elapsedMs) {
                    event("motion_screen_state", "state", state, "detail", detail,
                            "elapsed_ms", elapsedMs);
                }

                @Override public void onChargeState(CameraChargePolicy.State state) {
                    CameraChargeTelemetry.publish(state);
                }
            });
        } catch (Throwable failure) {
            clearInFlight(requestedConfig);
            markApplyErrorIfCurrent(context, requestedConfig, "runtime_apply_failed", null);
            Log.e(TAG, "motion_config_submit_failed config=" + requestedConfig, failure);
            event("motion_config_submit_failed", "config", requestedConfig,
                    "error", failure.toString());
            retryDelivery(context, requestedConfig);
        }
    }

    private static boolean retryDelivery(Context context, String desired) {
        if (!isCurrentDesired(context, desired) || GuardRecovery.isUserShutdownActive(context)) return false;
        synchronized (APPLY_STATUS_LOCK) {
            if (!desired.equals(applyConfig) || pendingRetry != null) return false;
            long delay = CONFIG_RETRY.afterFailure(desired);
            if (delay < 0) return false;
            long generation = retryGeneration;
            pendingRetry = BACKGROUND_PUBLISHER.schedule(() -> {
                synchronized (APPLY_STATUS_LOCK) {
                    if (generation != retryGeneration || !desired.equals(applyConfig)
                            || "applied".equals(applyState)) return;
                    pendingRetry = null;
                }
                if (GuardRecovery.isUserShutdownActive(context)) return;
                MotionRuntime runtime = active;
                if (runtime != null) runtime.scheduleRefresh(true);
                else publishWithoutObserver(context);
            }, delay, TimeUnit.MILLISECONDS);
            return true;
        }
    }

    private static void enqueueBackgroundPublish(Context context) {
        try {
            BACKGROUND_PUBLISHER.execute(() -> publishWithoutObserver(context));
        } catch (Throwable failure) {
            String desired = MotionConfig.read(context).toJson();
            markApplyErrorIfCurrent(context, desired, "runtime_apply_failed", null);
            Log.e(TAG, "motion_background_publish_not_scheduled", failure);
        }
    }

    private static void publishWithoutObserver(Context context) {
        if (GuardRecovery.isUserShutdownActive(context)) return;
        MotionRuntime existing = active;
        if (existing != null) {
            existing.requestPublish();
            return;
        }
        MotionConfig current = MotionConfig.read(context);
        String requestedConfig = current.toJson();
        if (!markPendingForSchedule(requestedConfig)) return;
        try {
            if (current.anyEnabled()) {
                CameraHelperService.startPersistent(context, "motion_bootstrap");
                return;
            }
            // A worker may outlive this app process. Send an explicit disabled snapshot without
            // starting an Activity or retaining an observer in the app.
            if (requestedConfig.equals(backgroundInFlight)) return;
            if (backgroundClient == null) backgroundClient = new MotionDetectorClient(context);
            MotionDetectorClient oneShot = backgroundClient;
            backgroundInFlight = requestedConfig;
            try {
                oneShot.configure(requestedConfig, new MotionDetectorClient.Listener() {
                    @Override public void onConfigured(boolean retainViewing) {
                        markAppliedIfCurrent(context, requestedConfig);
                        finishBackgroundPublish(oneShot, requestedConfig);
                    }

                    @Override public void onError(String stage, String detail, long elapsedMs) {
                        String reference = diagnosticReference(detail);
                        markApplyErrorIfCurrent(context, requestedConfig, "runtime_apply_failed", reference);
                        Log.e(TAG, "motion_one_shot_apply_failed stage=" + stage
                                + (reference == null ? "" : " diagnostic_ref=" + reference)
                                + " detail=" + detail);
                        BACKGROUND_PUBLISHER.execute(() -> {
                            if (backgroundClient == oneShot && requestedConfig.equals(backgroundInFlight))
                                backgroundInFlight = null;
                        });
                        retryDelivery(context, requestedConfig);
                    }
                });
            } catch (Throwable failure) {
                finishBackgroundPublish(oneShot, requestedConfig);
                throw failure;
            }
        } catch (Throwable failure) {
            markApplyErrorIfCurrent(context, requestedConfig, "runtime_apply_failed", null);
            Log.e(TAG, "motion_background_publish_failed config=" + requestedConfig, failure);
            retryDelivery(context, requestedConfig);
        }
    }

    private static void finishBackgroundPublish(MotionDetectorClient client, String desired) {
        BACKGROUND_PUBLISHER.execute(() -> {
            if (backgroundClient != client
                    || backgroundInFlight != null && !desired.equals(backgroundInFlight)) return;
            backgroundInFlight = null;
            backgroundClient = null;
            client.close();
        });
    }

    private static boolean markPendingForSchedule(String desired) {
        synchronized (APPLY_STATUS_LOCK) {
            selectRetryConfig(desired);
            if (!desired.equals(applyConfig)) {
                applyConfig = desired;
                applyState = "pending";
                applyError = null;
                applyErrorRef = null;
            } else if ("error".equals(applyState)) {
                applyState = "pending";
                applyError = null;
                applyErrorRef = null;
            }
            return !"applied".equals(applyState);
        }
    }

    private static void markPending(String desired) {
        synchronized (APPLY_STATUS_LOCK) {
            selectRetryConfig(desired);
            if (!desired.equals(applyConfig) || !"pending".equals(applyState)) {
                applyConfig = desired;
                applyState = "pending";
                applyError = null;
                applyErrorRef = null;
            }
        }
    }

    private static void markAppliedIfCurrent(Context context, String desired) {
        if (!isCurrentDesired(context, desired)) return;
        synchronized (APPLY_STATUS_LOCK) {
            if (!desired.equals(applyConfig)) return;
            applyState = "applied";
            cancelRetry();
            CONFIG_RETRY.recovered();
            applyError = null;
            applyErrorRef = null;
        }
    }

    private static void markApplyErrorIfCurrent(Context context, String desired,
            String error, String errorRef) {
        if (!isCurrentDesired(context, desired)) return;
        synchronized (APPLY_STATUS_LOCK) {
            if (!desired.equals(applyConfig)) return;
            applyState = "error";
            applyError = error;
            applyErrorRef = errorRef;
        }
    }

    private static boolean isCurrentDesired(Context context, String desired) {
        try { return desired.equals(MotionConfig.read(applicationContext(context)).toJson()); }
        catch (Throwable failure) { return false; }
    }

    private static void selectRetryConfig(String desired) {
        if (CONFIG_RETRY.select(desired)) cancelRetry();
    }

    private static void cancelRetry() {
        retryGeneration++;
        if (pendingRetry != null) pendingRetry.cancel(false);
        pendingRetry = null;
    }

    private static void clearInFlight(String desired) {
        MotionRuntime runtime = active;
        if (runtime == null) return;
        if (desired.equals(runtime.inFlightConfig)) runtime.inFlightConfig = null;
    }

    private static Context applicationContext(Context context) {
        Context application = context.getApplicationContext();
        return application == null ? context : application;
    }

    private static CameraModeClient.ModeException modeFailure(String code, Throwable cause) {
        return new CameraModeClient.ModeException(code, null, cause);
    }

    private static String diagnosticReference(String detail) {
        if (detail == null) return null;
        String marker = "diagnostic ref ";
        int start = detail.lastIndexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = start;
        while (end < detail.length()) {
            char c = detail.charAt(end);
            if (Character.isWhitespace(c) || c == ')' || c == ',' || c == ';') break;
            end++;
        }
        return end > start ? detail.substring(start, end) : null;
    }

    private void publishCurrentChargeState() {
        try { CameraChargeTelemetry.publish(MotionDetectorClient.readChargeState(context)); }
        catch (Throwable ignored) { }
    }

    private static void normalizeStoredLockMode(SharedPreferences screen) {
        Map<String, ?> values = screen.getAll();
        Object stored = values.get("lock");
        int raw = stored instanceof Integer ? (Integer) stored : 1;
        int normalized = MotionConfig.normalizeLockMode(raw);
        if (!(stored instanceof Integer) || raw != normalized)
            screen.edit().putInt("lock", normalized).apply();
    }

    private void event(String kind, Object... fields) {
        try {
            JSONObject json = new JSONObject().put("kind", kind)
                    .put("elapsed_ms", SystemClock.elapsedRealtime())
                    .put("wall_ms", System.currentTimeMillis());
            for (int i = 0; i + 1 < fields.length; i += 2)
                json.put(String.valueOf(fields[i]), fields[i + 1]);
            events.accept(json.toString());
        } catch (Exception ignored) { Log.w(TAG, kind); }
    }

    @Override public void close() {
        if (GuardRecovery.isUserShutdownActive(context)) {
            synchronized (APPLY_STATUS_LOCK) { cancelRetry(); }
        }
        screenPrefs.unregisterOnSharedPreferenceChangeListener(changed);
        recordingPrefs.unregisterOnSharedPreferenceChangeListener(changed);
        chargePrefs.unregisterOnSharedPreferenceChangeListener(changed);
        FutureTask<Void> task = new FutureTask<>(() -> {
            retentionObserver.close();
            MotionDetectorClient old = detector;
            detector = null;
            if (old != null) old.close(); // Detaches only this observer; worker session survives.
            boolean publish;
            synchronized (ACTIVE_LOCK) {
                closed = true;
                if (active == this) active = null;
                publish = publishAfterClose;
                publishAfterClose = false;
            }
            if (publish) enqueueBackgroundPublish(context);
            return null;
        });
        boolean posted;
        synchronized (ACTIVE_LOCK) {
            closing = true;
            posted = handler.post(task);
        }
        if (!posted) {
            Log.e(TAG, "motion_observer_close_not_scheduled",
                    new IllegalStateException("Motion observer rejected close task"));
            task.run();
        }
        try { task.get(10, TimeUnit.SECONDS); }
        catch (Exception failure) { event("motion_observer_close_error", "error", failure.toString()); }
        thread.quitSafely();
    }
}
