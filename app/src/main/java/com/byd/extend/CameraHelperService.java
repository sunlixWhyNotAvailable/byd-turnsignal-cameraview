package com.byd.extend;

import com.byd.extend.remote.CameraViewingDiagnostics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.byd.extend.ui.AdbRecoveryUiBridge;
import com.byd.extend.ui.UiLanguage;

public final class CameraHelperService extends Service {
    interface AccessibilityRecoveryCallback {
        void onAccessibilityRecoveryFinished(boolean connected);
    }

    private static volatile CameraHelperService activeInstance;
    private static volatile AdbRecoverySnapshot latestAdbRecovery;
    private static final CopyOnWriteArrayList<Runnable> adbRecoveryListeners =
            new CopyOnWriteArrayList<>();
    private static final String ACTION_ADB_RECOVERY_CHANGED =
            "com.byd.extend.action.ADB_RECOVERY_CHANGED";
    private static final String ACTION_WIFI_RETENTION_CHANGED =
            "com.byd.extend.action.WIFI_RETENTION_CHANGED";
    private static final String ACTION_ADB_WIFI_RETRY =
            "com.byd.extend.action.ADB_WIFI_RETRY";
    private static final String ACTION_ADB_READY =
            "com.byd.extend.action.ADB_READY";
    private static final String CHANNEL_ID = "guard_service";
    private static final String TAG = "CameraHelperService";
    private static final int NOTIFICATION_ID = 8713;
    private static final AtomicLong SERVICE_INSTANCE_SEQUENCE = new AtomicLong();
    private static final String ACTION_START =
            "com.byd.extend.action.START_PERSISTENT";
    private static final String ACTION_ACTIVITY_OPEN =
            "com.byd.extend.action.ACTIVITY_OPEN";
    private static final String ACTION_ACTIVITY_CLOSED =
            "com.byd.extend.action.ACTIVITY_CLOSED";
    private static final String ACTION_CAMERA_PREVIEW_STARTED =
            "com.byd.extend.action.CAMERA_PREVIEW_STARTED";
    private static final String ACTION_CAMERA_PREVIEW_STOPPED =
            "com.byd.extend.action.CAMERA_PREVIEW_STOPPED";
    private static final String ACTION_CAMERA_SETTINGS_CHANGED =
            "com.byd.extend.action.CAMERA_SETTINGS_CHANGED";
    private static final String ACTION_CAMERA_WARNING_SETTINGS_CHANGED =
            "com.byd.extend.action.CAMERA_WARNING_SETTINGS_CHANGED";
    private static final String ACTION_CAMERA_TRIGGER_SETTINGS_CHANGED =
            "com.byd.extend.action.CAMERA_TRIGGER_SETTINGS_CHANGED";
    private static final String ACTION_PARKING_CAMERA_SETTINGS_CHANGED =
            "com.byd.extend.action.PARKING_CAMERA_SETTINGS_CHANGED";
    private static final String ACTION_REVERSE_SETTINGS_CHANGED =
            "com.byd.extend.action.REVERSE_SETTINGS_CHANGED";
    private static final String ACTION_MIRROR_SETTINGS_CHANGED =
            "com.byd.extend.action.MIRROR_SETTINGS_CHANGED";
    private static final String ACTION_MIRROR_BUTTON =
            "com.byd.extend.action.MIRROR_BUTTON";
    private static final String ACTION_MUSIC_SETTINGS_CHANGED =
            "com.byd.extend.action.MUSIC_SETTINGS_CHANGED";
    private static final String ACTION_WEATHER_SETTINGS_CHANGED =
            "com.byd.extend.action.WEATHER_SETTINGS_CHANGED";
    private static final String ACTION_WEATHER_REFRESH =
            "com.byd.extend.action.WEATHER_REFRESH";
    private static final String ACTION_AUTO_START_CHANGED =
            "com.byd.extend.action.AUTO_START_CHANGED";
    private static final String ACTION_FLUSH_LOGS =
            "com.byd.extend.action.FLUSH_LOGS";
    private static final String ACTION_SHUTDOWN =
            "com.byd.extend.action.SHUTDOWN";
    private static final String ACTION_SETTINGS_RELOADED =
            "com.byd.extend.action.SETTINGS_RELOADED";
    private static final String ACTION_DIAGNOSTICS_CHANGED =
            "com.byd.extend.action.DIAGNOSTICS_CHANGED";
    private static final String ACTION_AVAS_CONFIGURE =
            "com.byd.extend.action.AVAS_CONFIGURE";
    private static final String ACTION_CALLS_CONFIGURE =
            "com.byd.extend.action.CALLS_CONFIGURE";
    private static final String ACTION_HAZARD_THANKS_CONFIGURE =
            "com.byd.extend.action.HAZARD_THANKS_CONFIGURE";
    private static final String ACTION_AVAS_START_MANUAL =
            "com.byd.extend.action.AVAS_START_MANUAL";
    private static final String ACTION_AVAS_STOP_MANUAL =
            "com.byd.extend.action.AVAS_STOP_MANUAL";
    private static final String ACTION_AVAS_START_ENGINE =
            "com.byd.extend.action.AVAS_START_ENGINE";
    private static final String ACTION_AVAS_STOP_ENGINE =
            "com.byd.extend.action.AVAS_STOP_ENGINE";
    private static final String ACTION_AVAS_TOGGLE_ENGINE =
            "com.byd.extend.action.AVAS_TOGGLE_ENGINE";
    private static final String ACTION_AVAS_REPORT_STATUS =
            "com.byd.extend.action.AVAS_REPORT_STATUS";
    private static final String ACTION_AVAS_START_AUDITION =
            "com.byd.extend.action.AVAS_START_AUDITION";
    private static final String ACTION_AVAS_STOP_AUDITION =
            "com.byd.extend.action.AVAS_STOP_AUDITION";
    static final String EXTRA_ENABLED = "enabled";
    static final String EXTRA_REASON = "reason";
    static final String EXTRA_FLUSH_RECEIVER = "flush_receiver";
    static final String EXTRA_WEATHER_RECEIVER = "weather_receiver";
    static final String EXTRA_WEATHER_REASON = "weather_reason";
    static final String EXTRA_FULL_IMPORT = "full_import";
    static final String EXTRA_MIRROR_SOURCE_ACTION = "mirror_source_action";
    static final String EXTRA_MIRROR_VISIBILITY_ACTION = "mirror_visibility_action";
    static final String EXTRA_AVAS_PROFILE_ID = "avas_profile_id";
    static final String EXTRA_AVAS_ASSET_ID = "avas_asset_id";
    static final String EXTRA_AVAS_SESSION_ID = "avas_session_id";
    private static final long CAMERA_DISCOVERY_RETRY_MS = 3_000;
    private static final long LOG_FLUSH_DELAY_MS = 250;
    private static final long ACCESSIBILITY_CONNECTION_WAIT_MS = 5_000;
    static final int WEATHER_RESULT_OK = 0;
    static final int WEATHER_RESULT_FAILED = 1;
    static final int WEATHER_RESULT_BUSY = 2;
    static final String WEATHER_RESULT_MESSAGE = "weather_result_message";

    static final class RuntimeLifecycleGate {
        interface Queue {
            void clear();
            boolean post(Runnable action);
        }

        enum TeardownResult { ENQUEUED, ALREADY_CLAIMED, POST_REJECTED }

        private boolean accepting = true;
        private boolean teardownClaimed;

        synchronized boolean post(Queue queue, Runnable action) {
            return accepting && queue != null && queue.post(action);
        }

        synchronized TeardownResult beginTeardown(Queue queue, Runnable teardown) {
            if (teardownClaimed) return TeardownResult.ALREADY_CLAIMED;
            accepting = false;
            teardownClaimed = true;
            if (queue == null) return TeardownResult.POST_REJECTED;
            queue.clear();
            return queue.post(teardown)
                    ? TeardownResult.ENQUEUED : TeardownResult.POST_REJECTED;
        }
    }

    interface RuntimeSettingsSink {
        void applyGuard(boolean enabled, float outward, float center,
                int delayMs, int maxSpeedKph);
        void applyMusic(boolean enabled);
        default void applyMusic(boolean enabled, boolean captureFocusOnOpen,
                boolean engineVisualizationEnabled) {
            applyMusic(enabled);
        }
        void applyParkingRadar(boolean enabled);
    }

    static final class RuntimeSettingsSnapshot {
        final boolean guardEnabled;
        final float outward;
        final float center;
        final int delayMs;
        final int maxSpeedKph;
        final boolean musicEnabled;
        final boolean captureFocusOnOpen;
        final boolean engineVisualizationEnabled;
        final boolean parkingEnabled;

        private RuntimeSettingsSnapshot(
                boolean guardEnabled, float outward, float center,
                int delayMs, int maxSpeedKph, boolean musicEnabled,
                boolean captureFocusOnOpen, boolean engineVisualizationEnabled,
                boolean parkingEnabled) {
            this.guardEnabled = guardEnabled;
            this.outward = outward;
            this.center = center;
            this.delayMs = delayMs;
            this.maxSpeedKph = maxSpeedKph;
            this.musicEnabled = musicEnabled;
            this.captureFocusOnOpen = captureFocusOnOpen;
            this.engineVisualizationEnabled = engineVisualizationEnabled;
            this.parkingEnabled = parkingEnabled;
        }

        static RuntimeSettingsSnapshot read(
                SharedPreferences settings, boolean parkingEnabled) {
            return new RuntimeSettingsSnapshot(
                    settings.getBoolean("guard_enabled", false),
                    settings.getFloat("outward_deg", 90.0f),
                    settings.getFloat("center_deg", 10.0f),
                    settings.getInt("correction_delay_ms", 100),
                    settings.getInt("max_speed_kph", 30),
                    settings.getBoolean("music_visualizer_enabled", false),
                    settings.getBoolean("music_capture_focus_on_open", true),
                    settings.getBoolean("music_engine_visualization_enabled", false),
                    parkingEnabled);
        }

        void replay(RuntimeSettingsSink sink) {
            sink.applyGuard(guardEnabled, outward, center, delayMs, maxSpeedKph);
            sink.applyMusic(musicEnabled, captureFocusOnOpen, engineVisualizationEnabled);
            sink.applyParkingRadar(parkingEnabled);
        }
    }

    enum HelperTeardownMode { RecoveryDetach, StopAll }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RuntimeLifecycleGate runtimeLifecycle = new RuntimeLifecycleGate();
    private final ExecutorService weatherAccessibilityExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "weather-accessibility"));
    private final AccessibilityRecoveryGate weatherAccessibilityRecovery =
            new AccessibilityRecoveryGate();
    private final Object weatherAccessibilityCallbackLock = new Object();
    private final List<AccessibilityRecoveryCallback> weatherAccessibilityCallbacks =
            new ArrayList<>();
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!helperRuntimeStarted) return;
            GuardRecovery.heartbeat(CameraHelperService.this);
            CameraHelperMain.HelperBinder activeHelper = helper;
            if (activeHelper != null) activeHelper.configureAvas();
            if (motionRuntime != null) motionRuntime.refresh();
            reconcileAvasRecovery(false, "service_heartbeat");
            runtimeHandler.postDelayed(this, 30_000);
        }
    };
    private final Runnable resumeOverlay = this::resumeOverlayIfIdle;
    private final Runnable retryCameraDiscovery = new Runnable() {
        @Override
        public void run() {
            if (helper == null || !helperRuntimeStarted) return;
            CameraHelperMain.DiscoveryResult result = helper.retryCameraDiscovery();
            boolean ready = result == CameraHelperMain.DiscoveryResult.READY;
            lifecycle("camera_discovery_retry", "ready", ready);
            if (result == CameraHelperMain.DiscoveryResult.RETRYABLE_FAILURE) {
                runtimeHandler.postDelayed(this, CAMERA_DISCOVERY_RETRY_MS);
            }
        }
    };
    private HandlerThread runtimeThread;
    private Handler runtimeHandler;
    private RuntimeLifecycleGate.Queue runtimeQueue;
    private AsyncServiceLog serviceLog;
    private volatile String serviceInstanceId;
    private volatile int latestStartId = -1;
    private final AtomicLong foregroundOperationSequence = new AtomicLong();
    private AvasRecoveryDaemonController avasRecoveryDaemon;
    private PowerManager.WakeLock avasWakeLock;
    private volatile CameraHelperMain.HelperBinder helper;
    private volatile boolean helperRuntimeStarted;
    private CameraRuntimeClient cameraRuntime;
    private MotionRuntime motionRuntime;
    private WeatherRuntime weatherRuntime;
    private boolean controllersInitialized;
    private volatile boolean foreground;
    private boolean activityVisible;
    private boolean cameraPreviewActive;
    private boolean avasStartupPermissionAttempted;
    private AdbRecoveryRuntime adbRecovery;
    private AdbReminderOverlayRuntime adbReminder;
    private final AtomicBoolean permissionsProvisioning = new AtomicBoolean();
    private final LocalAdbClient.AccessStateListener serviceAdbListener = state -> {
        AdbRecoveryRuntime recovery = adbRecovery;
        if (recovery != null) recovery.onAdbAccessChanged(
                state.status == LocalAdbClient.AccessState.Status.OK);
        if (state.status == LocalAdbClient.AccessState.Status.OK) {
            provisionAppPermissions("authorized_adb");
        }
    };
    private boolean adbWasReady;
    private volatile Boolean weatherAccessibilityTarget;
    private volatile boolean runtimeNeedsReinit;

    public static AdbRecoverySnapshot adbRecoverySnapshot() { return latestAdbRecovery; }

    /** Takes ownership of the read descriptor; never starts a new helper for a stale session. */
    static boolean startAvasMicrophone(String sessionId, android.os.ParcelFileDescriptor pcm,
            int volume) {
        CameraHelperService service = activeInstance;
        if (service != null && service.postRuntime(() -> {
            if (service.helper != null && service.helperRuntimeStarted
                    && WeatherRefreshAccessibilityService.ownsMicrophone(sessionId)
                    && !GuardRecovery.isUserShutdownActive(service)) {
                service.helper.startAvasMicrophone(sessionId, pcm, volume);
            } else {
                closeMicrophoneDescriptor(pcm);
                WeatherRefreshAccessibilityService.microphoneUnavailable(sessionId);
            }
        })) return true;
        closeMicrophoneDescriptor(pcm);
        return false;
    }

    static void stopAvasMicrophone(String sessionId) {
        CameraHelperService service = activeInstance;
        if (service != null) service.postRuntime(() -> {
            if (service.helper != null) service.helper.stopAvasMicrophone(sessionId);
        });
    }

    static void setAvasMicrophoneVolume(String sessionId, int volume) {
        CameraHelperService service = activeInstance;
        if (service != null) service.postRuntime(() -> {
            if (service.helper != null) service.helper.setAvasMicrophoneVolume(sessionId, volume);
        });
    }

    private static void closeMicrophoneDescriptor(android.os.ParcelFileDescriptor pcm) {
        try { pcm.close(); } catch (java.io.IOException ignored) {}
    }

    public static void addAdbRecoveryListener(Runnable listener) {
        adbRecoveryListeners.addIfAbsent(listener);
    }

    public static void removeAdbRecoveryListener(Runnable listener) {
        adbRecoveryListeners.remove(listener);
    }

    static void noteMusicForegroundPackage(String packageName) {
        CameraHelperService service = activeInstance;
        if (service == null || !service.helperRuntimeStarted) return;
        CameraHelperMain.HelperBinder activeHelper = service.helper;
        if (activeHelper != null && activeHelper.isHealthy()) {
            activeHelper.noteMusicForegroundPackage(packageName);
        }
    }

    static void logCallsEvent(String stage, String details) {
        CameraHelperService service = activeInstance;
        if (service != null) service.lifecycle("calls_event", "stage", stage, "details", details);
    }

    public static void logViewingEvent(Context context, String stage, String provider, String details) {
        CameraHelperService service = activeInstance;
        if (service != null) service.lifecycle("viewing_event", "stage", stage,
                "provider", provider, "details", details);
        else AvasRecoveryJournal.event(context, "viewing_event", "stage", stage,
                "provider", provider, "details", details);
    }

    static void logMusicKeyDecision(int key, String reason, String foreground) {
        CameraHelperService service = activeInstance;
        if (service != null) service.lifecycle("music_media_key_ingress",
                "raw_key", key, "reason", reason, "foreground", foreground);
    }

    static boolean dispatchMusicMediaKey(String foregroundPackage, int rawKeyCode,
            int action, int repeatCount, long downTime, long eventTime) {
        CameraHelperService service = activeInstance;
        if (service == null || !service.helperRuntimeStarted) {
            logMusicKeyDecision(rawKeyCode, "runtime_unavailable", foregroundPackage);
            return false;
        }
        CameraHelperMain.HelperBinder activeHelper = service.helper;
        if (activeHelper == null || !activeHelper.isHealthy()) {
            logMusicKeyDecision(rawKeyCode, "helper_unavailable", foregroundPackage);
            return false;
        }
        try {
            return activeHelper.dispatchMusicMediaKey(foregroundPackage, rawKeyCode,
                    action, repeatCount, downTime, eventTime);
        } catch (RuntimeException unavailable) {
            service.lifecycle("music_media_key_failed", "operation", "dispatch_music_media_key",
                    "raw_key", rawKeyCode, "action", action,
                    "error", com.byd.extend.remote.CameraViewingDiagnostics.describe(unavailable));
            return false;
        }
    }

    public static void adbRecoverySettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_ADB_RECOVERY_CHANGED));
    }

    public static void wifiRetentionSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_WIFI_RETENTION_CHANGED));
    }

    public static void retryAdbWifi(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_ADB_WIFI_RETRY));
    }

    public static void suppressAdbReminder() {
        CameraHelperService active = activeInstance;
        if (active != null && active.adbRecovery != null) active.adbRecovery.suppressHint();
    }

    private void provisionAppPermissions(String reason) {
        provisionAppPermissions(reason, () -> {});
    }

    private void provisionAppPermissions(String reason, Runnable finished) {
        // The successful foreground RSA completion schedules this again after admission is released.
        if (LocalAdbClient.AUTHORIZATION.pending() || !LocalAdbClient.hasAuthorizedIdentity(this)) {
            finished.run();
            return;
        }
        boolean ownsProvisioning = permissionsProvisioning.compareAndSet(false, true);
        // A pre-authorization check must not swallow the successful RSA follow-up.
        if (!ownsProvisioning && !"foreground_adb_authorized".equals(reason)) {
            finished.run();
            return;
        }
        try {
            weatherAccessibilityExecutor.execute(() -> {
                try { AppPermissionProvisioner.ensure(this, reason, this::avasRecoveryEvent); }
                finally {
                    if (ownsProvisioning) permissionsProvisioning.set(false);
                    finished.run();
                    AdbRecoveryRuntime recovery = adbRecovery;
                    if (recovery != null) recovery.onPermissionsChecked();
                    publishAdbRecoveryUi();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (ownsProvisioning) permissionsProvisioning.set(false);
            finished.run();
        }
    }

    private void publishAdbRecoveryUi() {
        mainHandler.post(() -> {
            if (activeInstance != this) return;
            AdbRecoverySnapshot snapshot = latestAdbRecovery;
            if (snapshot != null && adbReminder != null) {
                SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
                String language = AppLanguage.read(prefs);
                UiLanguage uiLanguage = AppLanguage.isUkrainian(language) ? UiLanguage.Ukrainian
                        : AppLanguage.isChinese(language) ? UiLanguage.Chinese
                        : AppLanguage.isRussian(language) ? UiLanguage.Russian : UiLanguage.English;
                adbReminder.update(AdbRecoveryUiBridge.overlay(snapshot,
                        new AdbReminderSettings(this), uiLanguage,
                        prefs.getBoolean("ui_dark_theme", true)));
            }
            for (Runnable listener : adbRecoveryListeners) listener.run();
        });
    }

    private void onAdbRecoverySnapshot(AdbRecoverySnapshot snapshot) {
        latestAdbRecovery = snapshot;
        publishAdbRecoveryUi();
        boolean newlyReady = snapshot.authenticated5555() && !adbWasReady;
        adbWasReady = snapshot.authenticated5555();
        if (newlyReady) {
            provisionAppPermissions("adb_recovery_ready");
            postRuntime(() -> {
                handleStartCommand(ServiceRuntimeCommand.capture(
                        new Intent().setAction(ACTION_ADB_READY), 0, ACTION_START));
                if (helper != null) helper.adbTransportRestored();
            });
        }
    }

    static void foregroundAdbFinished(long requestId, boolean authorized) {
        Runnable permissionsFinished = () ->
                LocalAdbClient.AUTHORIZATION.finishForegroundPermissions(requestId);
        CameraHelperService service = activeInstance;
        if (service == null) {
            permissionsFinished.run();
            return;
        }
        if (!service.postRuntime(() -> {
            AdbAuthorizationSession.RequestSnapshot snapshot =
                    LocalAdbClient.AUTHORIZATION.foregroundSnapshot();
            if (snapshot == null || snapshot.requestId != requestId || snapshot.isActive()) return;
            if (activeInstance != service || GuardRecovery.isUserShutdownActive(service)) {
                permissionsFinished.run();
                return;
            }
            if (authorized) service.provisionAppPermissions("foreground_adb_authorized", permissionsFinished);
            if (LegacySettingsImporter.blocksRuntime(service)) return;
            if (authorized) {
                service.handleStartCommand(ServiceRuntimeCommand.capture(
                        new Intent().setAction(ACTION_ADB_READY), 0, ACTION_START));
            }
            if (service.helper != null) service.helper.foregroundAdbFinished(authorized);
        })) permissionsFinished.run();
    }

    private void resumeOverlayIfIdle() {
        if (shouldResumeOverlay(cameraPreviewActive, activityVisible)) {
            if (cameraRuntime != null) cameraRuntime.preview(false);
        }
    }

    static boolean shouldResumeOverlay(boolean cameraPreviewActive, boolean activityVisible) {
        return !cameraPreviewActive && !activityVisible;
    }

    static void startPersistent(Context context, String reason) {
        if (!GuardRecovery.shouldRecover(context)) return;
        Intent intent = new Intent(context, CameraHelperService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REASON, reason == null ? "unknown" : reason);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void activityOpened(Context context) {
        Intent intent = new Intent(context, CameraHelperService.class)
                .setAction(ACTION_ACTIVITY_OPEN);
        context.startService(intent);
    }

    static void requestWeatherAccessibilityRecovery(Context context, String reason) {
        requestWeatherAccessibilityRecovery(context, reason, null);
    }

    static void requestWeatherAccessibilityRecovery(Context context, String reason,
            AccessibilityRecoveryCallback callback) {
        if (WeatherRefreshAccessibilityService.isConnected()) {
            if (callback != null) new Handler(Looper.getMainLooper()).post(
                    () -> callback.onAccessibilityRecoveryFinished(true));
            return;
        }
        CameraHelperService service = activeInstance;
        if (service != null) {
            service.requestWeatherAccessibilityRecovery(reason, callback);
        } else if (callback != null) {
            new Handler(Looper.getMainLooper()).post(
                    () -> callback.onAccessibilityRecoveryFinished(false));
        }
    }

    static void accessibilityConnectionChanged(boolean connected) {
        CameraHelperService service = activeInstance;
        if (service == null) return;
        service.lifecycle("weather_accessibility_connection", "connected", connected);
        if (!connected && Boolean.TRUE.equals(service.weatherAccessibilityTarget)) {
            service.requestWeatherAccessibilityRecovery("disconnect");
        }
    }

    static void activityClosed(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_ACTIVITY_CLOSED));
    }

    static void cameraPreviewStarted(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_CAMERA_PREVIEW_STARTED));
    }

    static void cameraPreviewStopped(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_CAMERA_PREVIEW_STOPPED));
    }

    static void cameraSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_CAMERA_SETTINGS_CHANGED));
    }

    static void mirrorSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_MIRROR_SETTINGS_CHANGED));
    }

    /** Applies every Mirror action matched by one gesture from one old persisted state. */
    static void requestMirrorButtonAction(
            Context context, boolean source, boolean visibility) {
        if (!source && !visibility) return;
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_MIRROR_BUTTON)
                .putExtra(EXTRA_MIRROR_SOURCE_ACTION, source)
                .putExtra(EXTRA_MIRROR_VISIBILITY_ACTION, visibility));
    }

    static MirrorButtonState resolveMirrorButtonAction(
            boolean enabled, boolean frontIntegrated, boolean showFront,
            boolean manualHidden, boolean source, boolean visibility) {
        boolean sourceChanged = source && enabled && frontIntegrated;
        boolean visibilityChanged = visibility && enabled;
        return new MirrorButtonState(
                sourceChanged ? !showFront : showFront,
                visibilityChanged ? !manualHidden : manualHidden,
                sourceChanged, visibilityChanged);
    }

    static final class MirrorButtonState {
        final boolean showFront;
        final boolean manualHidden;
        final boolean sourceChanged;
        final boolean visibilityChanged;

        MirrorButtonState(boolean showFront, boolean manualHidden,
                boolean sourceChanged, boolean visibilityChanged) {
            this.showFront = showFront;
            this.manualHidden = manualHidden;
            this.sourceChanged = sourceChanged;
            this.visibilityChanged = visibilityChanged;
        }

        boolean changed() { return sourceChanged || visibilityChanged; }
    }

    /** Pause an already-running instance synchronously before preferences are replaced. */
    static boolean pauseActiveRuntime() {
        CameraHelperService service = activeInstance;
        if (service == null) return true;
        if (service.isRuntimeThread()) {
            service.pauseRuntimeForImport();
            return true;
        }
        // Import callers already run on workers. Refuse an accidental main-thread wait.
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        CountDownLatch done = new CountDownLatch(1);
        if (!service.postRuntime(() -> {
            try {
                service.pauseRuntimeForImport();
            } finally {
                done.countDown();
            }
        })) return false;
        try {
            return done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void pauseRuntimeForImport() {
        stopRuntime(true, "pause_runtime_for_import", "pause_runtime_for_import", -1);
        runtimeNeedsReinit = true;
    }

    /** Reload all consumers after a camera-only or full settings import. */
    static void settingsReloaded(Context context, boolean fullImport) {
        if (LegacySettingsImporter.blocksRuntime(context)) return;
        Intent intent = new Intent(context, CameraHelperService.class)
                .setAction(ACTION_SETTINGS_RELOADED)
                .putExtra(EXTRA_FULL_IMPORT, fullImport);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void diagnosticSettingsChanged(Context context) {
        Intent intent = new Intent(context, CameraHelperService.class).setAction(ACTION_DIAGNOSTICS_CHANGED);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void cameraWarningSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_CAMERA_WARNING_SETTINGS_CHANGED));
    }

    static void cameraTriggerSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_CAMERA_TRIGGER_SETTINGS_CHANGED));
    }

    /** Notify the persistent helper that parking-camera rules or geometry changed. */
    static void parkingCameraSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_PARKING_CAMERA_SETTINGS_CHANGED));
    }

    static void reverseCameraSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_REVERSE_SETTINGS_CHANGED));
    }

    /**
     * Routes one already-filtered steering-button press.  The accessibility service is the
     * caller's owner; this method never starts or wakes the runtime for a key event.
     */
    static void requestReverseSteeringToggle(Context context) {
        if (CameraProbeActivity.dispatchReverseSteeringToggle()) return;
        final long ownerEpoch = CameraProbeActivity.reverseOwnerEpochSnapshot();
        CameraHelperService service = activeInstance;
        if (service == null) return;
        service.postRuntime(() -> {
            // An Activity may have resumed between the initial snapshot and this queued runtime
            // action.  Abort automatic fallback if ownership changed in that interval.
            if (!CameraProbeActivity.reverseOwnerStillAbsent(ownerEpoch)) return;
            CameraRuntimeClient cameras = service.cameraRuntime;
            if (cameras != null) cameras.toggleReverse();
        });
    }

    static void musicSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_MUSIC_SETTINGS_CHANGED));
    }

    static void weatherSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_WEATHER_SETTINGS_CHANGED));
    }

    static void configureAvas(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_CONFIGURE));
    }

    static void configureCalls(Context context, boolean needed) {
        CameraHelperService service = activeInstance;
        if (!needed) {
            if (service != null) service.postRuntime(() -> {
                if (service.helper != null) service.helper.configureCalls(false);
            });
            return;
        }
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_CALLS_CONFIGURE));
    }

    static void startAvasManual(Context context, String profileId) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId)) return;
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_START_MANUAL)
                .putExtra(EXTRA_AVAS_PROFILE_ID, profileId));
    }

    public static void configureHazardThanks(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_HAZARD_THANKS_CONFIGURE));
    }

    static void requestHazardThanks(Context context, String source) {
        long requestedAtMs = SystemClock.elapsedRealtime();
        CameraHelperService service = activeInstance;
        if (service == null) {
            AvasRecoveryJournal.event(context, "hazard_thanks_rejected",
                    "reason", "service_not_ready", "source_button", source);
            return;
        }
        if (!service.postRuntime(() -> {
            CameraHelperMain.HelperBinder current = service.helper;
            if (current == null || !service.helperRuntimeStarted
                    || GuardRecovery.isUserShutdownActive(service)) {
                service.lifecycle("hazard_thanks_rejected", "reason", "runtime_not_ready",
                        "source_button", source);
                return;
            }
            current.startHazardThanks(source, requestedAtMs);
        })) {
            AvasRecoveryJournal.event(context, "hazard_thanks_rejected",
                    "reason", "service_stopping", "source_button", source);
        }
    }

    static void stopAvasManual(Context context, String profileId) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId)) return;
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_STOP_MANUAL)
                .putExtra(EXTRA_AVAS_PROFILE_ID, profileId));
    }

    static void startAvasEngine(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_START_ENGINE));
    }

    static void stopAvasEngine(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_STOP_ENGINE));
    }

    static void toggleAvasEngine(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_TOGGLE_ENGINE));
    }

    static void reportAvasStatus(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_REPORT_STATUS));
    }

    static void startAvasAudition(Context context, String profileId, String assetId, String sessionId) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId)
                || !TurnSignalShellProtocol.isAvasAssetAllowed(assetId)
                || !TurnSignalShellProtocol.isAvasSessionAllowed(sessionId)) return;
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_START_AUDITION)
                .putExtra(EXTRA_AVAS_PROFILE_ID, profileId)
                .putExtra(EXTRA_AVAS_ASSET_ID, assetId)
                .putExtra(EXTRA_AVAS_SESSION_ID, sessionId));
    }

    static void stopAvasAudition(Context context, String sessionId) {
        if (!TurnSignalShellProtocol.isAvasSessionAllowed(sessionId)) return;
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_STOP_AUDITION)
                .putExtra(EXTRA_AVAS_SESSION_ID, sessionId));
    }

    static void weatherRefreshRequested(
            Context context, String reason, ResultReceiver receiver) {
        SharedPreferences settings = context.getSharedPreferences("settings", MODE_PRIVATE);
        CameraHelperService service = activeInstance;
        boolean activeRuntime = service != null && service.helperRuntimeStarted;
        if (!canRouteWeatherRefresh(
                settings.getBoolean(WeatherRuntime.PREF_ENABLED, false),
                GuardRecovery.shouldRecover(context), activeRuntime)) {
            sendWeatherResult(receiver, WEATHER_RESULT_FAILED,
                    runtimeText(context, R.string.runtime_weather_off));
            return;
        }
        Intent intent = new Intent(context, CameraHelperService.class)
                .setAction(ACTION_WEATHER_REFRESH)
                .putExtra(EXTRA_WEATHER_REASON, reason == null ? "manual" : reason)
                .putExtra(EXTRA_WEATHER_RECEIVER, receiver);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static boolean canRouteWeatherRefresh(
            boolean weatherEnabled, boolean recoveryAllowed, boolean activeRuntime) {
        return weatherEnabled && (recoveryAllowed || activeRuntime);
    }

    private static void sendWeatherResult(
            ResultReceiver receiver, int resultCode, String message) {
        if (receiver == null) return;
        Bundle data = new Bundle();
        data.putString(WEATHER_RESULT_MESSAGE, message);
        try {
            receiver.send(resultCode, data);
        } catch (RuntimeException ignored) {
            // The requesting UI may already be gone.
        }
    }

    private static String runtimeText(Context context, int resource) {
        return AppLanguage.localizedContext(context, AppLanguage.read(
                context.getSharedPreferences("settings", MODE_PRIVATE))).getString(resource);
    }

    static void updateAutoStart(Context context, boolean enabled) {
        Intent intent = new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AUTO_START_CHANGED)
                .putExtra(EXTRA_ENABLED, enabled);
        if (enabled && Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void requestShutdown(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_SHUTDOWN));
    }

    static void flushLogs(Context context, ResultReceiver receiver) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_FLUSH_LOGS)
                .putExtra(EXTRA_FLUSH_RECEIVER, receiver));
    }

    private void initializeControllers() {
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        BlindSpotOverlayController.migrateOverlayPreferences(settings);
        weatherRuntime = new WeatherRuntime(this, settings, this::lifecycle);
        ensureHelperCreated();
        cameraRuntime.setVehiclePowerListener(weatherRuntime::vehiclePowerChanged);
        controllersInitialized = true;
    }

    private void ensureControllersInitialized() {
        if (!controllersInitialized) initializeControllers();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceInstanceId = Process.myPid() + "-" + SERVICE_INSTANCE_SEQUENCE.incrementAndGet();
        runtimeThread = new HandlerThread("service-runtime");
        runtimeThread.start();
        runtimeHandler = new Handler(runtimeThread.getLooper());
        runtimeQueue = new RuntimeLifecycleGate.Queue() {
            @Override
            public void clear() {
                runtimeHandler.removeCallbacksAndMessages(null);
            }

            @Override
            public boolean post(Runnable action) {
                return runtimeHandler.post(action);
            }
        };
        serviceLog = new AsyncServiceLog(this::createLogFile, LOG_FLUSH_DELAY_MS);
        adbRecovery = new AdbRecoveryRuntime(this, this::onAdbRecoverySnapshot,
                this::avasRecoveryEvent);
        adbReminder = new AdbReminderOverlayRuntime(this, new AdbReminderOverlayRuntime.Callback() {
            @Override public void onRetry() { adbRecovery.retryWifi(); }
            @Override public void onSuppressed() { adbRecovery.suppressHint(); }
            @Override public void onAppearanceChanged(AdbReminderAppearance appearance) {
                new AdbReminderSettings(CameraHelperService.this).saveAppearance(appearance);
                publishAdbRecoveryUi();
            }
        });
        LocalAdbClient.addAccessStateListener(serviceAdbListener);
        avasRecoveryDaemon = new AvasRecoveryDaemonController(
                this, this::lifecycle, owns -> postRuntime(() -> {
                    CameraHelperMain.HelperBinder activeHelper = helper;
                    if (activeHelper != null) {
                        activeHelper.setRecoveryEnabled(
                                AvasRecoveryPolicy.legacyHelperRecoveryEnabled(
                                        GuardRecovery.shouldRecover(this), owns));
                    }
                }));
        activeInstance = this;
        recordServiceDiagnostic("service_create", -1, foreground, foreground,
                "action", "onCreate", "reason", "service_create",
                "operation", "service_lifecycle", "phase", "entry",
                "auto_start", GuardRecovery.isAutoStartEnabled(this),
                "user_shutdown", GuardRecovery.isUserShutdownActive(this));
        AvasRecoveryJournal.event(this, "service_recovery_create",
                "auto_start", GuardRecovery.isAutoStartEnabled(this),
                "user_shutdown", GuardRecovery.isUserShutdownActive(this));
        if (GuardRecovery.shouldRecover(this) && !LegacySettingsImporter.blocksRuntime(this)) {
            startForegroundRuntime("on_create_recovery", "onCreate", "service_create", -1);
        } else if (GuardRecovery.shouldRecover(this)) {
            lifecycle("runtime_blocked", "reason", "legacy_handover");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        ServiceRuntimeCommand command =
                ServiceRuntimeCommand.capture(intent, startId, ACTION_START);
        String action = command.action;
        recordServiceDiagnostic("service_start", startId, foreground, foreground,
                "action", action == null ? "" : action,
                "reason", command.reason, "operation", "service_lifecycle", "phase", "entry",
                "start_id", startId, "flags", flags);
        boolean blocked = LegacySettingsImporter.blocksRuntime(this);
        boolean targetAutoStart = ACTION_AUTO_START_CHANGED.equals(action)
                ? command.enabled : GuardRecovery.isAutoStartEnabled(this);
        boolean targetUserShutdown = ACTION_ACTIVITY_OPEN.equals(action)
                ? false : ACTION_SHUTDOWN.equals(action)
                ? true : GuardRecovery.isUserShutdownActive(this);
        boolean willRecover = GuardRecovery.shouldRecover(targetAutoStart, targetUserShutdown,
                helperRuntimeStarted || ACTION_ACTIVITY_OPEN.equals(action)
                        || GuardRecovery.hasActiveSession(this));
        boolean manualRuntime = !targetUserShutdown
                && (activityVisible || ACTION_ACTIVITY_OPEN.equals(action));
        if ((willRecover || helperRuntimeStarted || manualRuntime) && !blocked) {
            startForegroundRuntime(command.reason, action == null ? "" : action,
                    "service_start", startId);
        }
        if (!postRuntime(() -> handleStartCommand(command))) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return blocked || !willRecover
                ? START_NOT_STICKY : START_STICKY;
    }

    private void handleStartCommand(ServiceRuntimeCommand command) {
        String action = command.action;
        // A dismissal is cleanup, even after the Activity becomes invisible. It never boots a helper.
        if (ACTION_AVAS_STOP_AUDITION.equals(action)) {
            if (helper != null) helper.stopAvasAudition(command.avasSessionId);
            return;
        }
        if (ACTION_SHUTDOWN.equals(action)) {
            LocalAdbClient.AUTHORIZATION.cancelForeground();
            WeatherRefreshAccessibilityService.stopMicrophone();
            GuardRecovery.setUserShutdownActive(this, true);
            try { MotionDetectorClient.suspendOemRetention(this); }
            catch (Exception failure) {
                CameraViewingDiagnostics.failure("oem_retention", "explicit_shutdown", failure);
                android.util.Log.e("MotionStorage", "oem_retention_shutdown_failed", failure);
            }
            if (adbRecovery != null) adbRecovery.close();
            if (adbReminder != null) adbReminder.close();
            setAvasWakeLock(false, "explicit_shutdown");
            avasRecoveryDaemon.reconcile(false, "explicit_shutdown");
            com.byd.extend.remote.CameraViewingController.get(this).explicitShutdown(() -> {
                Runnable finishShutdown = () -> {
                    stopRuntime(true, "explicit_shutdown", action, command.startId);
                    stopServiceFromRuntime(command.startId, "explicit_shutdown", action);
                };
                if (!postRuntime(finishShutdown)) {
                    new Thread(finishShutdown, "service-viewing-shutdown-teardown").start();
                }
            });
            return;
        }
        if (ACTION_FLUSH_LOGS.equals(action)) {
            ResultReceiver receiver = command.flushReceiver;
            serviceLog.flush(() -> {
                if (receiver != null) receiver.send(0, Bundle.EMPTY);
            });
        }
        if (ACTION_AUTO_START_CHANGED.equals(action)) {
            GuardRecovery.setAutoStartEnabled(this, command.enabled);
        }
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        MirrorButtonState mirrorButton = null;
        if (ACTION_MIRROR_BUTTON.equals(action)) {
            RearviewMirrorSettings.Settings old = new RearviewMirrorSettings(settings).load();
            mirrorButton = resolveMirrorButtonAction(old.enabled, old.frontIntegrated,
                    old.showFront, old.manualHidden, command.mirrorSourceAction,
                    command.mirrorVisibilityAction);
            if (mirrorButton.changed()) {
                SharedPreferences.Editor editor = settings.edit();
                if (mirrorButton.sourceChanged) {
                    RearviewMirrorSettings.writeSourceState(
                            editor, old.frontIntegrated, mirrorButton.showFront);
                }
                if (mirrorButton.visibilityChanged) {
                    RearviewMirrorSettings.writeHidden(
                            editor, mirrorButton.manualHidden, mirrorButton.manualHidden);
                }
                editor.apply();
                CameraProbeActivity.publishMirrorSettingsChanged();
                if (mirrorButton.visibilityChanged && mirrorButton.manualHidden) {
                    String language = AppLanguage.read(settings);
                    Context localized = AppLanguage.localizedContext(this, language);
                    mainHandler.post(() -> Toast.makeText(localized,
                            localized.getString(R.string.mirror_hidden_by_button),
                            Toast.LENGTH_LONG).show());
                }
            }
        }
        if (ACTION_ACTIVITY_OPEN.equals(action)) {
            GuardRecovery.setUserShutdownActive(this, false);
        }
        boolean shouldRecover = GuardRecovery.shouldRecover(this);
        boolean userShutdown = GuardRecovery.isUserShutdownActive(this);
        if (LegacySettingsImporter.blocksRuntime(this)) {
            lifecycle("runtime_blocked", "reason", "legacy_handover");
            stopRuntime(true, "legacy_handover", action, command.startId);
            runtimeNeedsReinit = true;
            syncWeatherAccessibility(false);
            if (ACTION_WEATHER_REFRESH.equals(action)) {
                sendWeatherResult(command.weatherReceiver, WEATHER_RESULT_FAILED,
                        runtimeText(this, R.string.runtime_finish_migration));
            }
            stopServiceFromRuntime(command.startId, "legacy_handover", action);
            return;
        }
        if (ACTION_DIAGNOSTICS_CHANGED.equals(action)) {
            DiagnosticLogPolicy.configure(getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean(DiagnosticLogPolicy.PREF_ENABLED, false));
            if (helper != null) helper.configureLogging();
            if (cameraRuntime != null) cameraRuntime.settingsChanged("logging");
            com.byd.extend.remote.CameraViewingController.get(this).diagnosticSettingsChanged();
            lifecycle("diagnostic_logging_changed", "extended", DiagnosticLogPolicy.extended());
            reconcileAvasRecovery(false, "diagnostic_settings_changed");
            return;
        }
        if (ACTION_WIFI_RETENTION_CHANGED.equals(action)) {
            reconcileAvasRecovery(false, "wifi_retention_settings_changed");
            publishAdbRecoveryUi();
            return;
        }
        // Rights are provisioned independently; feature execution still observes runtime gates.
        if (ACTION_START.equals(action) || ACTION_ACTIVITY_OPEN.equals(action)
                || ACTION_SETTINGS_RELOADED.equals(action) || ACTION_CALLS_CONFIGURE.equals(action)
                || ACTION_HAZARD_THANKS_CONFIGURE.equals(action)) {
            provisionAppPermissions("service_entry");
        }
        if (!userShutdown && (shouldRecover || activityVisible
                || ACTION_ACTIVITY_OPEN.equals(action))) {
            if (ACTION_ADB_WIFI_RETRY.equals(action)) adbRecovery.retryWifi();
            else if (!ACTION_ADB_READY.equals(action)) adbRecovery.startOrReconfigure(
                    AdbRecoveryPolicy.wakeReason(command.reason));
            publishAdbRecoveryUi();
        }
        if (ACTION_ADB_WIFI_RETRY.equals(action)
                || ACTION_ADB_RECOVERY_CHANGED.equals(action)) return;
        // Keep the global Accessibility filter alive for every recovering runtime and for the
        // foreground Activity, including auto-start-off sessions.  Update presence before the
        // !shouldRecover branch so ordinary settings changes cannot disable it mid-UI.
        if (ACTION_ACTIVITY_OPEN.equals(action)) activityVisible = true;
        else if (ACTION_ACTIVITY_CLOSED.equals(action)) activityVisible = false;
        boolean accessibilityTrigger = ACTION_ACTIVITY_OPEN.equals(action)
                || ACTION_START.equals(action);
        syncWeatherAccessibility(!userShutdown
                        && (shouldRecover || helperRuntimeStarted || activityVisible),
                ACTION_ACTIVITY_OPEN.equals(action) ? "activity_foreground" : "startup",
                accessibilityTrigger);
        if (!shouldAdmitFullRuntime(
                userShutdown, shouldRecover, helperRuntimeStarted, activityVisible, action)) {
            runtimeHandler.removeCallbacks(heartbeat);
            stopServiceFromRuntime(command.startId, "runtime_not_required", action);
            return;
        }
        if (runtimeNeedsReinit) {
            initializeControllers();
            runtimeNeedsReinit = false;
        } else {
            ensureControllersInitialized();
        }
        boolean refreshMusicAfterClose = false;
        if (ACTION_ACTIVITY_OPEN.equals(action)) {
            activityVisible = true;
            cameraRuntime.visibility(true);
        } else if (ACTION_ACTIVITY_CLOSED.equals(action)) {
            activityVisible = false;
            cameraRuntime.visibility(false);
            cameraPreviewActive = false;
            runtimeHandler.removeCallbacks(resumeOverlay);
            runtimeHandler.postDelayed(resumeOverlay, 250);
            refreshMusicAfterClose = true;
        } else if (ACTION_CAMERA_PREVIEW_STARTED.equals(action)) {
            cameraPreviewActive = true;
            runtimeHandler.removeCallbacks(resumeOverlay);
            cameraRuntime.preview(true);
        } else if (ACTION_CAMERA_PREVIEW_STOPPED.equals(action)) {
            cameraPreviewActive = false;
            runtimeHandler.removeCallbacks(resumeOverlay);
            if (!activityVisible) runtimeHandler.postDelayed(resumeOverlay, 250);
            cameraRuntime.preview(false);
        }
        ensureHelperStarted();
        if (ACTION_CALLS_CONFIGURE.equals(action)) {
            helper.configureCalls(CallsRuntime.requiresRescueSubscription(this));
        }
        if (ACTION_HAZARD_THANKS_CONFIGURE.equals(action)) {
            helper.configureHazardThanks();
            helper.configureCalls(CallsRuntime.requiresRescueSubscription(this));
        }
        weatherRuntime.start();
        helper.setRecoveryEnabled(AvasRecoveryPolicy.legacyHelperRecoveryEnabled(
                shouldRecover, avasRecoveryDaemon != null
                        && avasRecoveryDaemon.ownsRecovery()));
        helper.applyParkingRadar(anyParkingEnabled());
        if (refreshMusicAfterClose) {
            helper.applyMusic(settings.getBoolean("music_visualizer_enabled", false),
                    settings.getBoolean("music_capture_focus_on_open", true),
                    settings.getBoolean("music_engine_visualization_enabled", false));
        } else if (ACTION_CAMERA_SETTINGS_CHANGED.equals(action)) {
            cameraRuntime.settingsChanged("all");
        } else if (ACTION_CAMERA_WARNING_SETTINGS_CHANGED.equals(action)) {
            cameraRuntime.settingsChanged("warning");
        } else if (ACTION_CAMERA_TRIGGER_SETTINGS_CHANGED.equals(action)) {
            cameraRuntime.settingsChanged("trigger");
        } else if (ACTION_PARKING_CAMERA_SETTINGS_CHANGED.equals(action)) {
            cameraRuntime.settingsChanged("parking");
        } else if (ACTION_REVERSE_SETTINGS_CHANGED.equals(action)) {
            cameraRuntime.settingsChanged("reverse");
        } else if (ACTION_MIRROR_SETTINGS_CHANGED.equals(action)) {
            cameraRuntime.settingsChanged("mirror");
        } else if (ACTION_MIRROR_BUTTON.equals(action)
                && mirrorButton != null && mirrorButton.changed()) {
            cameraRuntime.settingsChanged("mirror");
        } else if (ACTION_MUSIC_SETTINGS_CHANGED.equals(action)) {
            helper.applyMusic(settings.getBoolean("music_visualizer_enabled", false),
                    settings.getBoolean("music_capture_focus_on_open", true),
                    settings.getBoolean("music_engine_visualization_enabled", false));
        } else if (ACTION_WEATHER_SETTINGS_CHANGED.equals(action)) {
            weatherRuntime.settingsChanged();
        } else if (isAvasAction(action)) {
            routeAvasAction(command);
        } else if (ACTION_WEATHER_REFRESH.equals(action)) {
            ResultReceiver receiver = command.weatherReceiver;
            String weatherReason = command.weatherReason;
            boolean accepted = weatherRuntime.requestNow(weatherReason, (success, error) ->
                    sendWeatherResult(receiver,
                            success ? WEATHER_RESULT_OK : WEATHER_RESULT_FAILED,
                            runtimeText(this, success ? R.string.runtime_weather_refreshed
                                    : WeatherRuntime.errorMessageResource(error))));
            if (!accepted) {
                sendWeatherResult(receiver,
                        weatherRuntime.isRequestInFlight()
                                ? WEATHER_RESULT_BUSY : WEATHER_RESULT_FAILED,
                        runtimeText(this, weatherRuntime.isRequestInFlight()
                                ? R.string.runtime_weather_busy : weatherRuntime.isEnabled() && weatherRuntime.isPowerSuspended()
                                ? R.string.runtime_weather_power_suspended : R.string.runtime_weather_off));
            }
        } else if (ACTION_SETTINGS_RELOADED.equals(action)) {
            reloadSettings(command.fullImport);
        }
        boolean avasStartupPermissionTrigger = ACTION_START.equals(action)
                && !avasStartupPermissionAttempted;
        if (avasStartupPermissionTrigger) avasStartupPermissionAttempted = true;
        reconcileAvasRecovery(avasStartupPermissionTrigger
                        || ACTION_ACTIVITY_OPEN.equals(action)
                        || ACTION_AVAS_CONFIGURE.equals(action)
                        || (ACTION_AUTO_START_CHANGED.equals(action) && command.enabled)
                        || (ACTION_SETTINGS_RELOADED.equals(action) && command.fullImport),
                action == null ? "service_start" : action);
        startHeartbeat();
    }

    private void stopServiceFromRuntime(int startId, String reason, String action) {
        postForegroundStop("stop_service", reason, action, startId, true);
    }

    static boolean shouldAdmitFullRuntime(
            boolean userShutdown, boolean recoveryAllowed, boolean runtimeStarted,
            boolean activityVisible, String action) {
        return !userShutdown && (recoveryAllowed || runtimeStarted || activityVisible
                || ACTION_ACTIVITY_OPEN.equals(action));
    }

    private static boolean isAvasAction(String action) {
        return ACTION_AVAS_CONFIGURE.equals(action)
                || ACTION_AVAS_START_MANUAL.equals(action)
                || ACTION_AVAS_STOP_MANUAL.equals(action)
                || ACTION_AVAS_START_ENGINE.equals(action)
                || ACTION_AVAS_STOP_ENGINE.equals(action)
                || ACTION_AVAS_TOGGLE_ENGINE.equals(action)
                || ACTION_AVAS_START_AUDITION.equals(action)
                || ACTION_AVAS_STOP_AUDITION.equals(action)
                || ACTION_AVAS_REPORT_STATUS.equals(action);
    }

    private void routeAvasAction(ServiceRuntimeCommand command) {
        CameraHelperMain.HelperBinder active = helper;
        if (active == null) return;
        if (ACTION_AVAS_CONFIGURE.equals(command.action)) active.configureAvas();
        else if (ACTION_AVAS_START_MANUAL.equals(command.action)) {
            active.startAvasManual(command.avasProfileId);
        } else if (ACTION_AVAS_STOP_MANUAL.equals(command.action)) {
            active.stopAvasManual(command.avasProfileId);
        } else if (ACTION_AVAS_START_ENGINE.equals(command.action)) {
            active.startAvasEngine();
        } else if (ACTION_AVAS_STOP_ENGINE.equals(command.action)) {
            active.stopAvasEngine();
        } else if (ACTION_AVAS_TOGGLE_ENGINE.equals(command.action)) {
            active.toggleAvasEngine();
        } else if (ACTION_AVAS_REPORT_STATUS.equals(command.action)) {
            active.reportAvasStatus();
        } else if (ACTION_AVAS_START_AUDITION.equals(command.action)) {
            if (activityVisible) active.startAvasAudition(command.avasProfileId,
                    command.avasAssetId, command.avasSessionId);
        } else if (ACTION_AVAS_STOP_AUDITION.equals(command.action)) {
            active.stopAvasAudition(command.avasSessionId);
        }
    }

    private void syncWeatherAccessibility(boolean enabled) {
        syncWeatherAccessibility(enabled, "registration", false);
    }

    private void syncWeatherAccessibility(
            boolean enabled, String reason, boolean explicitTrigger) {
        Boolean previous = weatherAccessibilityTarget;
        weatherAccessibilityTarget = enabled;
        if (enabled) {
            if ((!Boolean.TRUE.equals(previous) || explicitTrigger)
                    && !WeatherRefreshAccessibilityService.isConnected()) {
                requestWeatherAccessibilityRecovery(reason);
            }
            return;
        }
        List<AccessibilityRecoveryCallback> cancelledCallbacks;
        synchronized (weatherAccessibilityCallbackLock) {
            weatherAccessibilityRecovery.cancel();
            cancelledCallbacks = drainWeatherAccessibilityCallbacks();
        }
        dispatchWeatherAccessibilityCallbacks(cancelledCallbacks, false);
        if (Boolean.FALSE.equals(previous)) return;
        // Stopping a feature/runtime cancels its work, but never revokes an app permission.
    }

    private void requestWeatherAccessibilityRecovery(String reason) {
        requestWeatherAccessibilityRecovery(reason, null);
    }

    private void requestWeatherAccessibilityRecovery(String reason,
            AccessibilityRecoveryCallback callback) {
        long epoch;
        List<AccessibilityRecoveryCallback> connectedCallbacks = null;
        synchronized (weatherAccessibilityCallbackLock) {
            if (callback != null) weatherAccessibilityCallbacks.add(callback);
            weatherAccessibilityTarget = true;
            if (WeatherRefreshAccessibilityService.isConnected()) {
                connectedCallbacks = drainWeatherAccessibilityCallbacks();
                epoch = 0;
            } else {
                epoch = weatherAccessibilityRecovery.begin();
            }
        }
        if (connectedCallbacks != null) {
            dispatchWeatherAccessibilityCallbacks(connectedCallbacks, true);
            return;
        }
        if (epoch == 0) return;
        weatherAccessibilityExecutor.execute(() -> recoverWeatherAccessibility(epoch, reason));
    }

    private void recoverWeatherAccessibility(long epoch, String reason) {
        int user = AvasNotificationAccess.userIdForUid(Process.myUid());
        String correlationId = newWeatherAccessibilityCorrelationId();
        String outcome = "cancelled";
        String stage = "recovery";
        String attempt = "none";
        try {
            if (!weatherAccessibilityRecovery.isCurrent(epoch)) return;
            stage = "registration";
            attempt = "initial";
            String initialCorrelationId = correlationId + "-initial";
            if (!applyWeatherAccessibility(true, false, epoch, initialCorrelationId)) {
                outcome = weatherAccessibilityRecovery.isCurrent(epoch)
                        ? "registration_failed" : "cancelled";
                return;
            }
            stage = "bind_wait";
            if (awaitWeatherAccessibilityConnection(
                    epoch, user, initialCorrelationId, "initial")) {
                outcome = "connected";
                return;
            }
            if (!weatherAccessibilityRecovery.isCurrent(epoch)) {
                outcome = "superseded";
                return;
            }
            stage = "rebind";
            attempt = "rebind";
            String rebindCorrelationId = correlationId + "-rebind";
            if (!applyWeatherAccessibility(true, true, epoch, rebindCorrelationId)) {
                outcome = weatherAccessibilityRecovery.isCurrent(epoch)
                        ? "rebind_failed" : "cancelled";
                return;
            }
            stage = "bind_wait";
            boolean connected = awaitWeatherAccessibilityConnection(
                    epoch, user, rebindCorrelationId, "rebind");
            outcome = connected ? "rebind_connected"
                    : !weatherAccessibilityRecovery.isCurrent(epoch) ? "superseded"
                    : Thread.currentThread().isInterrupted() ? "interrupted" : "timeout";
        } finally {
            lifecycle("weather_accessibility_recovery", "reason", reason,
                    "outcome", outcome, "stage", stage, "attempt", attempt,
                    "status", outcome, "recovery_epoch", epoch, "user", user,
                    "correlation_id", correlationId);
            List<AccessibilityRecoveryCallback> callbacks = null;
            synchronized (weatherAccessibilityCallbackLock) {
                if (weatherAccessibilityRecovery.finish(epoch)) {
                    callbacks = drainWeatherAccessibilityCallbacks();
                }
            }
            dispatchWeatherAccessibilityCallbacks(callbacks,
                    WeatherRefreshAccessibilityService.isConnected());
        }
    }

    /** Caller holds weatherAccessibilityCallbackLock. */
    private List<AccessibilityRecoveryCallback> drainWeatherAccessibilityCallbacks() {
        List<AccessibilityRecoveryCallback> callbacks =
                new ArrayList<>(weatherAccessibilityCallbacks);
        weatherAccessibilityCallbacks.clear();
        return callbacks;
    }

    private void dispatchWeatherAccessibilityCallbacks(
            List<AccessibilityRecoveryCallback> callbacks, boolean connected) {
        if (callbacks == null || callbacks.isEmpty()) return;
        mainHandler.post(() -> {
            for (AccessibilityRecoveryCallback callback : callbacks) {
                try {
                    callback.onAccessibilityRecoveryFinished(connected);
                } catch (RuntimeException ignored) {
                    // A UI observer must not take down the helper runtime.
                }
            }
        });
    }

    private boolean applyWeatherAccessibility(
            boolean enabled, boolean forceRebind, long recoveryEpoch, String correlationId) {
        if (!enabled) return true;
        int user = AvasNotificationAccess.userIdForUid(Process.myUid());
        AccessibilitySettingsWriter.Failure[] operationFailure = new AccessibilitySettingsWriter.Failure[1];
        String[] operationStage = {"read_services"};
        AccessibilitySettingsWriter.Result result;
        try {
            result = AccessibilitySettingsWriter.ensureEnabled(new AccessibilitySettingsWriter.Access() {
                    @Override public void stage(String value) { operationStage[0] = value; }

                    @Override public AccessibilitySettingsWriter.Failure failure() {
                        return operationFailure[0];
                    }

                    @Override public String readServices() {
                        LocalAdbClient.Result read = executeWeatherAccessibilityOperation(
                                "settings --user " + user
                                        + " get secure enabled_accessibility_services",
                                true, 8_192, operationStage[0], user, recoveryEpoch,
                                correlationId, operationFailure);
                        if (!read.ok) throw new AccessibilitySettingsWriter.OperationFailure(
                                operationFailure[0], null);
                        return read.output;
                    }

                    @Override public boolean writeServices(String value) {
                        LocalAdbClient.Result write = executeWeatherAccessibilityOperation(
                                "settings --user " + user
                                        + " put secure enabled_accessibility_services " + quote(value),
                                false, 0, operationStage[0], user, recoveryEpoch,
                                correlationId, operationFailure);
                        return write.ok;
                    }

                    @Override public boolean enableAccessibility() {
                        stage("read_enabled_setting");
                        LocalAdbClient.Result read = executeWeatherAccessibilityOperation(
                                "settings --user " + user + " get secure accessibility_enabled",
                                true, 64, operationStage[0], user, recoveryEpoch,
                                correlationId, operationFailure);
                        if (!read.ok) return false;
                        if ("1".equals(read.output == null ? "" : read.output.trim())) return true;
                        stage("write_enabled_setting");
                        LocalAdbClient.Result write = executeWeatherAccessibilityOperation(
                                "settings --user " + user + " put secure accessibility_enabled 1",
                                false, 0, operationStage[0], user, recoveryEpoch,
                                correlationId, operationFailure);
                        if (!write.ok) return false;
                        stage("readback_enabled_setting");
                        LocalAdbClient.Result readback = executeWeatherAccessibilityOperation(
                                "settings --user " + user + " get secure accessibility_enabled",
                                true, 64, operationStage[0], user, recoveryEpoch,
                                correlationId, operationFailure);
                        if (!readback.ok) return false;
                        boolean enabled = "1".equals(readback.output == null
                                ? "" : readback.output.trim());
                        if (!enabled) operationFailure[0] = new AccessibilitySettingsWriter.Failure(
                                operationStage[0], "readback_mismatch", "",
                                "Accessibility setting readback was "
                                        + AdbRecoveryDiagnostics.safeText(readback.output));
                        return enabled;
                    }
                    }, forceRebind,
                    () -> recoveryEpoch != 0
                            && !weatherAccessibilityRecovery.isCurrent(recoveryEpoch),
                    CameraHelperService::pauseWeatherAccessibility,
                    new AccessibilitySettingsWriter.Events() {
                        @Override public void log(String event) {
                            lifecycle(event, "recovery_epoch", recoveryEpoch,
                                    "correlation_id", correlationId, "user", user);
                        }

                        @Override public void log(String event,
                                AccessibilitySettingsWriter.Failure failure) {
                            lifecycle(event, "stage", failure.stage, "status", failure.status,
                                    "error_class", failure.errorClass,
                                    "error", AdbRecoveryDiagnostics.safeText(failure.error),
                                    "recovery_epoch", recoveryEpoch,
                                    "correlation_id", correlationId, "user", user);
                            if (failure.diagnosticCause != null) {
                                AdbRecoveryDiagnostics.failure(CameraHelperService.this::lifecycle,
                                        "weather_accessibility_restore_failure_detail",
                                        failure.stage, failure.diagnosticCause,
                                        "recovery_epoch", recoveryEpoch,
                                        "correlation_id", correlationId, "user", user);
                            }
                        }
                    });
        } catch (RuntimeException failure) {
            AdbRecoveryDiagnostics.failure(this::lifecycle,
                    "weather_accessibility_failed", operationStage[0], failure,
                    "stage", operationStage[0], "status", "exception",
                    "enabled", true, "correlation_id", correlationId,
                    "recovery_epoch", recoveryEpoch, "user", user,
                    "rebind", forceRebind);
            clearWeatherAccessibilityTarget(true);
            return false;
        }
        boolean bound = WeatherRefreshAccessibilityService.isConnected();
        AccessibilitySettingsWriter.Failure detail = result.failure;
        String status = detail == null ? result.ok ? "applied" : "readback_failed" : detail.status;
        lifecycle(result.ok ? "weather_accessibility_applied" : "weather_accessibility_failed",
                "enabled", true, "listed", result.listed, "rebind", forceRebind,
                "granted", result.ok, "cancelled", result.cancelled,
                "stage", detail == null ? "apply" : detail.stage,
                "status", status,
                "error_class", detail == null ? "" : detail.errorClass,
                "error", detail == null ? "" : AdbRecoveryDiagnostics.safeText(detail.error),
                "user", user, "recovery_epoch", recoveryEpoch,
                "correlation_id", correlationId);
        if (detail != null && detail.secondary != null) {
            lifecycle("weather_accessibility_secondary_failure",
                    "stage", detail.secondary.stage, "status", detail.secondary.status,
                    "error_class", detail.secondary.errorClass,
                    "error", AdbRecoveryDiagnostics.safeText(detail.secondary.error),
                    "user", user, "recovery_epoch", recoveryEpoch,
                    "correlation_id", correlationId);
        }
        if (detail != null && detail.diagnosticCause != null) {
            AdbRecoveryDiagnostics.failure(this::lifecycle,
                    "weather_accessibility_failure_detail", detail.stage,
                    detail.diagnosticCause, "correlation_id", correlationId,
                    "recovery_epoch", recoveryEpoch, "user", user,
                    "rebind", forceRebind);
        }
        lifecycle("weather_accessibility_binding_readback", "bound", bound,
                "stage", "bind_readback", "status", bound ? "bound" : "not_bound",
                "rebind", forceRebind, "user", user,
                "recovery_epoch", recoveryEpoch, "correlation_id", correlationId);
        if (!result.ok && !result.cancelled) clearWeatherAccessibilityTarget(true);
        return result.ok;
    }

    private void clearWeatherAccessibilityTarget(boolean attemptedValue) {
        Boolean target = weatherAccessibilityTarget;
        if (target != null && target.booleanValue() == attemptedValue) {
            weatherAccessibilityTarget = null;
        }
    }

    private LocalAdbClient.Result executeWeatherAccessibilityOperation(
            String command, boolean text, int maxBytes, String stage, int user,
            long recoveryEpoch, String correlationId,
            AccessibilitySettingsWriter.Failure[] operationFailure) {
        final boolean[] deferred = {false};
        java.util.function.BiConsumer<String, Object[]> events = (event, fields) -> {
            if ("background_deferred".equals(event)
                    || hasStage(fields, "background_deferred")) deferred[0] = true;
            lifecycle(event, weatherAccessibilityFields(fields, correlationId,
                    stage, user, recoveryEpoch));
        };
        final LocalAdbClient.Result result;
        try {
            result = text
                    ? LocalAdbClient.executeAuthorizedText(this, command, maxBytes, events)
                    : LocalAdbClient.executeAuthorized(this, command, events);
        } catch (RuntimeException failure) {
            AdbRecoveryDiagnostics.failure(events,
                    "weather_accessibility_operation_exception", stage, failure);
            operationFailure[0] = new AccessibilitySettingsWriter.Failure(stage,
                    "exception", failure.getClass().getName(), failure.getMessage());
            throw new AccessibilitySettingsWriter.OperationFailure(operationFailure[0], failure);
        }
        operationFailure[0] = result.ok ? null : weatherAccessibilityFailure(stage, result, deferred[0]);
        return result;
    }

    private static AccessibilitySettingsWriter.Failure weatherAccessibilityFailure(
            String stage, LocalAdbClient.Result result, boolean deferred) {
        String status;
        if (deferred) status = "deferred";
        else if (result.superseded) status = "superseded";
        else if (result.authorizationRequired) status = "authorization_required";
        else if (result.transportUnavailable) status = "transport_unavailable";
        else if (result.commandReadTimeout) status = "command_read_timeout";
        else if (result.exitCode >= 0) status = "command_failure";
        else status = "operation_failure";
        return new AccessibilitySettingsWriter.Failure(stage, status, "",
                AdbRecoveryDiagnostics.safeText(result.error));
    }

    private static Object[] weatherAccessibilityFields(Object[] fields, String correlationId,
            String stage, int user, long recoveryEpoch) {
        Object[] source = fields == null ? new Object[0] : fields;
        Object[] result = Arrays.copyOf(source, source.length + 8);
        int offset = source.length;
        result[offset] = "correlation_id";
        result[offset + 1] = correlationId;
        result[offset + 2] = "accessibility_stage";
        result[offset + 3] = stage;
        result[offset + 4] = "android_user";
        result[offset + 5] = user;
        result[offset + 6] = "recovery_epoch";
        result[offset + 7] = recoveryEpoch;
        return result;
    }

    private static boolean hasStage(Object[] fields, String expected) {
        for (int i = 0; fields != null && i + 1 < fields.length; i += 2) {
            if ("stage".equals(fields[i]) && expected.equals(fields[i + 1])) return true;
        }
        return false;
    }

    private static String newWeatherAccessibilityCorrelationId() {
        return "weather-accessibility-" + Process.myPid() + "-"
                + Long.toHexString(SystemClock.elapsedRealtimeNanos());
    }

    private boolean awaitWeatherAccessibilityConnection(long epoch, int user,
            String correlationId, String attempt) {
        boolean connected = WeatherRefreshAccessibilityService.awaitConnection(
                ACCESSIBILITY_CONNECTION_WAIT_MS);
        lifecycle("weather_accessibility_bind_wait", "stage", "bind_wait",
                "status", connected ? "bound"
                        : !weatherAccessibilityRecovery.isCurrent(epoch) ? "superseded"
                        : Thread.currentThread().isInterrupted() ? "interrupted" : "timeout",
                "attempt", attempt, "timeout_ms", ACCESSIBILITY_CONNECTION_WAIT_MS,
                "user", user, "recovery_epoch", epoch,
                "correlation_id", correlationId);
        return connected;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean pauseWeatherAccessibility() {
        try {
            Thread.sleep(300);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (LegacySettingsImporter.blocksRuntime(this)) return null;
        ensureHelperCreated();
        CameraHelperMain.HelperBinder boundHelper = helper;
        if (boundHelper == null) return null;
        cameraRuntime.setUiState(true, cameraPreviewActive);
        postRuntime(() -> {
            if (LegacySettingsImporter.blocksRuntime(this)
                    || GuardRecovery.isUserShutdownActive(this)) return;
            // A bound Activity is the foreground owner even when auto-start is disabled; keep the
            // Accessibility key filter enabled for the lifetime of this binding.
            syncWeatherAccessibility(true, "activity_bind", true);
            ensureControllersInitialized();
            ensureHelperStarted();
            CameraHelperMain.HelperBinder activeHelper = helper;
            if (activeHelper != null) {
                SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
                activeHelper.setRecoveryEnabled(AvasRecoveryPolicy.legacyHelperRecoveryEnabled(
                        GuardRecovery.shouldRecover(this), avasRecoveryDaemon != null
                                && avasRecoveryDaemon.ownsRecovery()));
                activeHelper.configureAvas();
            }
        });
        return boundHelper;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return false;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        lifecycle("service_task_removed", "recover", GuardRecovery.shouldRecover(this));
        postRuntime(() -> {
            if (GuardRecovery.shouldRecover(this)
                    && !LegacySettingsImporter.blocksRuntime(this)) {
                GuardRecovery.scheduleSoon(this);
            }
        });
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        recordServiceDiagnostic("service_destroy_callback", -1, foreground, foreground,
                "action", "onDestroy", "reason", "service_destroy",
                "operation", "service_lifecycle", "phase", "entry");
        WeatherRefreshAccessibilityService.stopMicrophone();
        LocalAdbClient.removeAccessStateListener(serviceAdbListener);
        if (adbRecovery != null) adbRecovery.close();
        if (adbReminder != null) adbReminder.close();
        RuntimeLifecycleGate.TeardownResult teardown =
                runtimeLifecycle.beginTeardown(runtimeQueue, this::destroyRuntime);
        stopForegroundRuntime("service_destroy", "onDestroy", "on_destroy", -1, null, null);
        if (activeInstance == this) {
            activeInstance = null;
            // Teardown may discard a queued permission attempt before its finally can run.
            AdbAuthorizationSession.RequestSnapshot request = LocalAdbClient.AUTHORIZATION.foregroundSnapshot();
            if (request != null) LocalAdbClient.AUTHORIZATION.finishForegroundPermissions(request.requestId);
        }
        if (teardown == RuntimeLifecycleGate.TeardownResult.POST_REJECTED) {
            Thread fallback = new Thread(this::destroyRuntime, "service-runtime-teardown");
            fallback.start();
        }
        super.onDestroy();
    }

    private void destroyRuntime() {
        com.byd.extend.remote.CameraViewingController.get(this).runtimeStopped();
        runtimeHandler.removeCallbacksAndMessages(null);
        boolean recover = GuardRecovery.shouldRecover(this);
        boolean explicitShutdown = GuardRecovery.isUserShutdownActive(this);
        recordServiceDiagnostic("service_destroy", -1, foreground, foreground,
                "action", "destroyRuntime", "reason", "on_destroy",
                "operation", "service_lifecycle", "phase", "runtime_teardown",
                "recover", recover);
        if (motionRuntime != null) { motionRuntime.close(); motionRuntime = null; }
        setAvasWakeLock(false, "service_teardown");
        if (avasRecoveryDaemon != null) {
            SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
            boolean keepWifi = wifiRetentionRequested();
            avasRecoveryDaemon.reconcile(avasDaemonRequired(settings) || (keepWifi && recover),
                    logcatRecordingRequested(settings), keepWifi, "service_teardown");
            avasRecoveryDaemon.close();
        }
        if (explicitShutdown && cameraRuntime != null) cameraRuntime.shutdown();
        if (weatherRuntime != null) weatherRuntime.shutdown();
        HelperTeardownMode teardownMode = helperTeardownMode(recover, explicitShutdown);
        if (helper != null) {
            if (teardownMode == HelperTeardownMode.StopAll) helper.shutdown(true);
            else helper.shutdown(false);
        }
        controllersInitialized = false;
        helper = null;
        helperRuntimeStarted = false;
        weatherAccessibilityExecutor.shutdownNow();
        List<AccessibilityRecoveryCallback> cancelledCallbacks;
        synchronized (weatherAccessibilityCallbackLock) {
            weatherAccessibilityRecovery.cancel();
            cancelledCallbacks = drainWeatherAccessibilityCallbacks();
        }
        dispatchWeatherAccessibilityCallbacks(cancelledCallbacks, false);
        if (recover) GuardRecovery.scheduleSoon(this);
        if (serviceLog != null) serviceLog.close();
        runtimeHandler.removeCallbacksAndMessages(null);
        runtimeThread.quit();
    }

    static HelperTeardownMode helperTeardownMode(boolean recover, boolean explicitShutdown) {
        if (explicitShutdown) return HelperTeardownMode.StopAll;
        return HelperTeardownMode.RecoveryDetach;
    }

    private synchronized void ensureHelperCreated() {
        if (helper != null) return;
        helper = new CameraHelperMain.HelperBinder(
                getApplicationContext(), runtimeHandler, this::acceptHelperLine);
        cameraRuntime = helper.cameraRuntime();
        cameraRuntime.setUiState(activityVisible, cameraPreviewActive);
        helperRuntimeStarted = false;
    }

    private void ensureHelperStarted() {
        if (LegacySettingsImporter.blocksRuntime(this)) return;
        ensureHelperCreated();
        if (helperRuntimeStarted) return;
        GuardRecovery.sessionStarted(this);
        helperRuntimeStarted = true;
        CameraHelperMain.DiscoveryResult discoveryResult = helper.discoverCameraWithResult();
        if (motionRuntime == null)
            motionRuntime = new MotionRuntime(this, cameraRuntime, this::acceptHelperLine);
        else motionRuntime.requestPublish();
        com.byd.extend.remote.CameraViewingController.get(this).runtimeStarted();
        helper.startGuardRuntime();
        helper.configureAvas();
        helper.configureCalls(CallsRuntime.requiresRescueSubscription(this));

        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        helper.configureHazardThanks();
        RuntimeSettingsSnapshot.read(settings, anyParkingEnabled()).replay(helper);
        cameraRuntime.visibility(activityVisible);
        if (cameraPreviewActive) cameraRuntime.preview(true);
        if (shouldRetryCameraDiscovery(discoveryResult, helperRuntimeStarted)) {
            scheduleCameraDiscoveryRetry(CAMERA_DISCOVERY_RETRY_MS);
        }
    }

    private void stopRuntime(
            boolean terminateShells, String reason, String action, int requestStartId) {
        com.byd.extend.remote.CameraViewingController.get(this).runtimeStopped();
        runtimeHandler.removeCallbacks(heartbeat);
        runtimeHandler.removeCallbacks(resumeOverlay);
        runtimeHandler.removeCallbacks(retryCameraDiscovery);
        if (motionRuntime != null) { motionRuntime.close(); motionRuntime = null; }
        if (terminateShells && cameraRuntime != null) cameraRuntime.shutdown();
        if (weatherRuntime != null) weatherRuntime.shutdown();
        if (helper != null) helper.shutdown(terminateShells);
        cameraRuntime = null;
        weatherRuntime = null;
        controllersInitialized = false;
        helper = null;
        helperRuntimeStarted = false;
        setAvasWakeLock(false, terminateShells ? "runtime_shutdown" : "runtime_stop");
        if (avasRecoveryDaemon != null && (terminateShells || (!avasDaemonRequired(
                getSharedPreferences("settings", MODE_PRIVATE)) && !logcatRecordingRequested(
                getSharedPreferences("settings", MODE_PRIVATE)) && !wifiRetentionRequested()))) {
            avasRecoveryDaemon.reconcile(false, "runtime_stop");
        }
        postForegroundStop("stop_runtime", reason, action, requestStartId, false);
        GuardRecovery.schedule(this);
    }

    private void reloadSettings(boolean fullImport) {
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        DiagnosticLogPolicy.configure(settings.getBoolean(DiagnosticLogPolicy.PREF_ENABLED, false));
        if (helper != null) {
            helper.configureLogging();
            RuntimeSettingsSnapshot.read(settings, anyParkingEnabled()).replay(helper);
            helper.configureAvas();
            helper.configureCalls(CallsRuntime.requiresRescueSubscription(this));
            helper.configureHazardThanks();
        }
        cameraRuntime.settingsChanged("all");
        if (fullImport) {
            GuardRecovery.setAutoStartEnabled(this,
                    settings.getBoolean("auto_start_enabled", true));
            weatherRuntime.settingsChanged();
            syncWeatherAccessibility(!GuardRecovery.isUserShutdownActive(this)
                    && (GuardRecovery.shouldRecover(this)
                    || helperRuntimeStarted || activityVisible));
        }
        lifecycle("settings_reloaded", "full_import", fullImport);
    }

    private void startHeartbeat() {
        runtimeHandler.removeCallbacks(heartbeat);
        heartbeat.run();
    }

    private boolean avasDaemonRequired(SharedPreferences settings) {
        return !LegacySettingsImporter.blocksRuntime(this)
                && AvasRecoveryPolicy.daemonRequired(
                GuardRecovery.shouldRecover(this),
                GuardRecovery.isUserShutdownActive(this),
                AvasNotificationAccess.hasEnabledAvasAudio(settings) || MotionConfig.read(this).anyEnabled());
    }

    private boolean logcatRecordingRequested(SharedPreferences settings) {
        return !LegacySettingsImporter.blocksRuntime(this)
                && !GuardRecovery.isUserShutdownActive(this)
                && settings.getBoolean(ContinuousLogcatRecorder.PREF_ENABLED, false);
    }

    private boolean wifiRetentionRequested() {
        return !LegacySettingsImporter.blocksRuntime(this)
                && !GuardRecovery.isUserShutdownActive(this)
                && new WifiRetentionSettings(this).enabled();
    }

    private void reconcileAvasRecovery(boolean permissionTrigger, String reason) {
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        boolean anyProfile = AvasNotificationAccess.hasEnabledAvasAudio(settings);
        boolean daemonRequired = avasDaemonRequired(settings);
        boolean keepWifi = wifiRetentionRequested();
        boolean wakeRequired = AvasRecoveryPolicy.wakeRequired(
                helperRuntimeStarted, GuardRecovery.isUserShutdownActive(this), anyProfile);
        setAvasWakeLock(wakeRequired, reason);
        if (avasRecoveryDaemon != null) avasRecoveryDaemon.reconcile(
                daemonRequired || (keepWifi && GuardRecovery.shouldRecover(this)),
                logcatRecordingRequested(settings), keepWifi, reason);
        if (permissionTrigger) provisionAppPermissions(reason);
    }

    private void avasRecoveryEvent(String kind, Object[] fields) {
        Object[] safeFields = fields == null ? new Object[0] : fields;
        lifecycle(kind, safeFields);
        AvasRecoveryJournal.event(this, kind, safeFields);
    }

    private void setAvasWakeLock(boolean required, String reason) {
        try {
            if (required) {
                if (avasWakeLock == null) {
                    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                    if (power == null) throw new IllegalStateException("PowerManager unavailable");
                    avasWakeLock = power.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK, "com.byd.extend:avas-recovery");
                    avasWakeLock.setReferenceCounted(false);
                }
                if (!avasWakeLock.isHeld()) {
                    avasWakeLock.acquire();
                    lifecycle("avas_wake_lock", "held", true, "reason", reason);
                    AvasRecoveryJournal.event(this, "avas_wake_lock",
                            "held", true, "reason", reason);
                }
            } else if (avasWakeLock != null && avasWakeLock.isHeld()) {
                avasWakeLock.release();
                lifecycle("avas_wake_lock", "held", false, "reason", reason);
                AvasRecoveryJournal.event(this, "avas_wake_lock",
                        "held", false, "reason", reason);
            }
        } catch (Throwable failure) {
            lifecycle("avas_wake_lock_failed", "required", required,
                    "reason", reason, "error", failure.toString());
            AvasRecoveryJournal.event(this, "avas_wake_lock_failed",
                    "required", required, "reason", reason, "error", failure.toString());
        }
    }

    private void scheduleCameraDiscoveryRetry(long delayMs) {
        runtimeHandler.removeCallbacks(retryCameraDiscovery);
        runtimeHandler.postDelayed(retryCameraDiscovery, Math.max(0, delayMs));
    }

    static boolean shouldRetryCameraDiscovery(
            CameraHelperMain.DiscoveryResult result, boolean runtimeActive) {
        return result == CameraHelperMain.DiscoveryResult.RETRYABLE_FAILURE && runtimeActive;
    }

    private void startForegroundRuntime(
            String reason, String action, String requestOperation, int requestStartId) {
        String operationId = nextForegroundOperationId();
        boolean foregroundBefore = foreground;
        if (foregroundBefore) {
            recordForegroundTransition(operationId, "startForeground", requestOperation,
                    "start", action, reason, requestStartId, "call", "skipped",
                    foregroundBefore, foregroundBefore, null, null,
                    "skip_reason", "cached_foreground_true");
            return;
        }
        recordForegroundTransition(operationId, "startForeground", requestOperation,
                "start", action, reason, requestStartId, "call", "entry",
                foregroundBefore, foregroundBefore, null, null);
        long startedAt = SystemClock.elapsedRealtimeNanos();
        try {
            startForeground(NOTIFICATION_ID, notification());
            foreground = true;
            recordForegroundTransition(operationId, "startForeground", requestOperation,
                    "start", action, reason, requestStartId, "call", "returned",
                    foregroundBefore, foreground, durationMs(startedAt), null);
        } catch (RuntimeException | Error error) {
            recordForegroundTransition(operationId, "startForeground", requestOperation,
                    "start", action, reason, requestStartId, "call", "exception",
                    foregroundBefore, foreground, durationMs(startedAt), null,
                    "exception_class", error.getClass().getName(),
                    "exception_message", error.getMessage() == null ? "" : error.getMessage());
            Log.e(TAG, "Foreground start operation " + operationId + " failed", error);
            throw error;
        }
    }

    private void stopForegroundRuntime(
            String reason, String action, String requestOperation, int requestStartId,
            String operationId, Long queueDelayMs) {
        if (operationId == null) operationId = nextForegroundOperationId();
        boolean foregroundBefore = foreground;
        if (!foregroundBefore) {
            recordForegroundTransition(operationId, "stopForeground", requestOperation,
                    "stop", action, reason, requestStartId, "call", "skipped",
                    foregroundBefore, foregroundBefore, null, queueDelayMs,
                    "skip_reason", "cached_foreground_false");
            return;
        }
        recordForegroundTransition(operationId, "stopForeground", requestOperation,
                "stop", action, reason, requestStartId, "call", "entry",
                foregroundBefore, foregroundBefore, null, queueDelayMs);
        long startedAt = SystemClock.elapsedRealtimeNanos();
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            foreground = false;
            recordForegroundTransition(operationId, "stopForeground", requestOperation,
                    "stop", action, reason, requestStartId, "call", "returned",
                    foregroundBefore, foreground, durationMs(startedAt), queueDelayMs);
        } catch (RuntimeException | Error error) {
            recordForegroundTransition(operationId, "stopForeground", requestOperation,
                    "stop", action, reason, requestStartId, "call", "exception",
                    foregroundBefore, foreground, durationMs(startedAt), queueDelayMs,
                    "exception_class", error.getClass().getName(),
                    "exception_message", error.getMessage() == null ? "" : error.getMessage());
            Log.e(TAG, "Foreground stop operation " + operationId + " failed", error);
            throw error;
        }
    }

    private void postForegroundStop(
            String operation, String reason, String action, int requestStartId,
            boolean stopService) {
        String operationId = nextForegroundOperationId();
        long scheduledAt = SystemClock.elapsedRealtimeNanos();
        boolean scheduledForeground = foreground;
        recordForegroundTransition(operationId, operation, operation, "stop", action, reason,
                requestStartId, "schedule", "posting", scheduledForeground, scheduledForeground, null, null);
        boolean accepted = mainHandler.post(() -> {
            long queueDelayMs = Math.max(0L,
                    (SystemClock.elapsedRealtimeNanos() - scheduledAt) / 1_000_000L);
            recordForegroundTransition(operationId, operation, operation, "stop", action, reason,
                    requestStartId, "execute", "entry", foreground, foreground, null,
                    queueDelayMs);
            stopForegroundRuntime(reason, action, operation, requestStartId,
                    operationId, queueDelayMs);
            if (stopService) {
                boolean before = foreground;
                stopSelf(requestStartId);
                recordForegroundTransition(operationId, operation, operation, "stop_self",
                        action, reason, requestStartId, "call", "returned", before, foreground,
                        null, queueDelayMs);
            }
        });
        boolean postedForeground = foreground;
        recordForegroundTransition(operationId, operation, operation, "stop", action, reason,
                requestStartId, "post_result", accepted ? "accepted" : "rejected",
                postedForeground, postedForeground, null, null);
    }

    private String nextForegroundOperationId() {
        return serviceInstanceId + "-fg-" + foregroundOperationSequence.incrementAndGet();
    }

    private static long durationMs(long startedAtNanos) {
        return Math.max(0L, (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L);
    }

    private void recordForegroundTransition(
            String operationId, String operation, String requestOperation,
            String transition, String action, String reason, int requestStartId,
            String phase, String outcome, boolean foregroundBefore, boolean foregroundAfter,
            Long durationMs, Long queueDelayMs, Object... extraFields) {
        List<Object> fields = new ArrayList<>();
        fields.add("operation_id"); fields.add(operationId);
        fields.add("operation"); fields.add(operation);
        fields.add("request_operation"); fields.add(requestOperation);
        fields.add("transition"); fields.add(transition);
        fields.add("action"); fields.add(action == null ? "" : action);
        fields.add("reason"); fields.add(reason == null ? "" : reason);
        fields.add("phase"); fields.add(phase);
        fields.add("outcome"); fields.add(outcome);
        if (durationMs != null) { fields.add("duration_ms"); fields.add(durationMs); }
        if (queueDelayMs != null) { fields.add("queue_delay_ms"); fields.add(queueDelayMs); }
        if (extraFields != null) {
            for (Object field : extraFields) fields.add(field);
        }
        recordServiceDiagnostic("foreground_transition", requestStartId,
                foregroundBefore, foregroundAfter, fields.toArray());
    }

    private void recordServiceDiagnostic(
            String kind, int requestStartId, boolean foregroundBefore,
            boolean foregroundAfter, Object... fields) {
        String line = ForegroundServiceDiagnostics.event(kind, Process.myPid(),
                serviceInstanceId == null ? "" : serviceInstanceId,
                Thread.currentThread().getName(), Thread.currentThread().getId(),
                SystemClock.elapsedRealtime(), requestStartId, latestStartId,
                foregroundBefore, foregroundAfter, fields);
        if (line == null) return;
        Log.i(TAG, line);
        AsyncServiceLog activeLog = serviceLog;
        if (activeLog != null) activeLog.appendRaw(line);
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "BYD Extend", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, CameraProbeActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("BYD Extend")
                .setContentText(AppLanguage.localizedContext(this,
                        AppLanguage.read(getSharedPreferences("settings", MODE_PRIVATE)))
                        .getString(R.string.service_active))
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private File createLogFile() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File captures = new File(base, "captures");
        if (!captures.isDirectory() && !captures.mkdirs()) captures = getFilesDir();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return new File(captures, "helper-service-" + stamp + ".jsonl");
    }

    private boolean postRuntime(Runnable action) {
        return runtimeLifecycle.post(runtimeQueue, action);
    }

    private boolean isRuntimeThread() {
        Handler activeHandler = runtimeHandler;
        return activeHandler != null && Looper.myLooper() == activeHandler.getLooper();
    }

    private void acceptHelperLine(String line) {
        if (line == null) return;
        if (line.contains("\"rescue_button\"") || line.contains("\"rescue_transport\"")
                || line.contains("\"helper_death\"") || line.contains("\"helper_ping_failed\""))
            CallsRuntime.acceptRescueEvent(line);
        if (line.contains("avas_microphone_status") || line.contains("avas_status")
                || line.contains("helper_ping_failed"))
            WeatherRefreshAccessibilityService.microphoneEvent(line);
        postRuntime(() -> {
            if (cameraRuntime != null) cameraRuntime.acceptPlatformEvent(line);
            if (cameraRuntime != null && line.contains("\"helper_ping\"")) {
                try {
                    org.json.JSONObject event = new org.json.JSONObject(line);
                    if ("helper_ping".equals(event.optString("kind")) && event.optBoolean("ok"))
                        cameraRuntime.connect();
                } catch (org.json.JSONException ignored) { }
            }
            serviceLog.appendRaw(line);
        });
    }

    private boolean anyParkingEnabled() {
        return anyParkingEnabled(getSharedPreferences("settings", MODE_PRIVATE));
    }

    static boolean anyParkingEnabled(SharedPreferences preferences) {
        for (ParkingCameraSettings.Rule rule : ParkingCameraSettings.readRules(preferences)) {
            if (rule != null && rule.enabled) return true;
        }
        return false;
    }

    private void lifecycle(String kind, Object... fields) {
        AsyncServiceLog activeLog = serviceLog;
        if (activeLog != null) {
            activeLog.appendLifecycle(kind, SystemClock.elapsedRealtime(), fields);
        }
    }
}
