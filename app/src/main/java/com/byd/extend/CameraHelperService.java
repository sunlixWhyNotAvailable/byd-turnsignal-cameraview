package com.byd.extend;

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
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CameraHelperService extends Service {
    interface AccessibilityRecoveryCallback {
        void onAccessibilityRecoveryFinished(boolean connected);
    }

    private static volatile CameraHelperService activeInstance;
    private static final String CHANNEL_ID = "guard_service";
    private static final int NOTIFICATION_ID = 8713;
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
    private static final String ACTION_AVAS_CONFIGURE =
            "com.byd.extend.action.AVAS_CONFIGURE";
    private static final String ACTION_AVAS_START_MANUAL =
            "com.byd.extend.action.AVAS_START_MANUAL";
    private static final String ACTION_AVAS_STOP_MANUAL =
            "com.byd.extend.action.AVAS_STOP_MANUAL";
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
        void applyParkingRadar(boolean enabled);
    }

    static final class RuntimeSettingsSnapshot {
        final boolean guardEnabled;
        final float outward;
        final float center;
        final int delayMs;
        final int maxSpeedKph;
        final boolean musicEnabled;
        final boolean parkingEnabled;

        private RuntimeSettingsSnapshot(
                boolean guardEnabled, float outward, float center,
                int delayMs, int maxSpeedKph, boolean musicEnabled,
                boolean parkingEnabled) {
            this.guardEnabled = guardEnabled;
            this.outward = outward;
            this.center = center;
            this.delayMs = delayMs;
            this.maxSpeedKph = maxSpeedKph;
            this.musicEnabled = musicEnabled;
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
                    parkingEnabled);
        }

        void replay(RuntimeSettingsSink sink) {
            sink.applyGuard(guardEnabled, outward, center, delayMs, maxSpeedKph);
            sink.applyMusic(musicEnabled);
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
            runtimeHandler.postDelayed(this, 30_000);
        }
    };
    private final Runnable resumeOverlay = this::resumeOverlayIfIdle;
    private final Runnable retryCameraDiscovery = new Runnable() {
        @Override
        public void run() {
            if (helper == null || !helperRuntimeStarted) return;
            boolean ready = helper.discoverCamera();
            lifecycle("camera_discovery_retry", "ready", ready);
            if (!ready) runtimeHandler.postDelayed(this, CAMERA_DISCOVERY_RETRY_MS);
        }
    };
    private HandlerThread runtimeThread;
    private Handler runtimeHandler;
    private RuntimeLifecycleGate.Queue runtimeQueue;
    private OemCameraVisibilityRuntime oemCameraVisibility;
    private AsyncServiceLog serviceLog;
    private volatile CameraHelperMain.HelperBinder helper;
    private volatile boolean helperRuntimeStarted;
    private BlindSpotOverlayController overlay;
    private ParkingCameraController parkingCameras;
    private ReverseCameraController reverseCameras;
    private RearviewMirrorController mirror;
    private ClusterFullscreenController clusterFullscreen;
    private WeatherRuntime weatherRuntime;
    private boolean controllersInitialized;
    private boolean foreground;
    private boolean activityVisible;
    private boolean cameraPreviewActive;
    private volatile Boolean weatherAccessibilityTarget;
    private volatile boolean runtimeNeedsReinit;

    private void resumeOverlayIfIdle() {
        if (shouldResumeOverlay(cameraPreviewActive, activityVisible)) {
            if (overlay != null) overlay.setSuspended(false);
            if (parkingCameras != null) parkingCameras.setSuspended(false);
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
        stopRuntime(true);
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
            ReverseCameraController controller = service.reverseCameras;
            if (controller != null) controller.requestSteeringToggle(ownerEpoch);
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

    static void startAvasManual(Context context, String profileId) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId)) return;
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_START_MANUAL)
                .putExtra(EXTRA_AVAS_PROFILE_ID, profileId));
    }

    static void stopAvasManual(Context context, String profileId) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId)) return;
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AVAS_STOP_MANUAL)
                .putExtra(EXTRA_AVAS_PROFILE_ID, profileId));
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
        overlay = new BlindSpotOverlayController(this, runtimeHandler, this::lifecycle);
        parkingCameras = new ParkingCameraController(this, runtimeHandler, this::parkingEvent);
        reverseCameras = new ReverseCameraController(
                this, runtimeHandler, this::reverseEvent, value -> {
                    overlay.setReversePriority(value);
                    if (parkingCameras != null) parkingCameras.setReversePriority(value);
                });
        clusterFullscreen = new ClusterFullscreenController(this, settings, this::lifecycle);
        mirror = new RearviewMirrorController(this, runtimeHandler, this::lifecycle);
        mirror.appVisibility(activityVisible);
        controllersInitialized = true;
        oemCameraVisibility.reportStatus();
    }

    private void ensureControllersInitialized() {
        if (!controllersInitialized) initializeControllers();
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
        oemCameraVisibility = new OemCameraVisibilityRuntime(
                getApplicationContext(), runtimeHandler, this::oemVisibilityChanged,
                this::lifecycle);
        oemCameraVisibility.start();
        activeInstance = this;
        lifecycle("service_create", "auto_start", GuardRecovery.isAutoStartEnabled(this),
                "user_shutdown", GuardRecovery.isUserShutdownActive(this));
        if (GuardRecovery.shouldRecover(this) && !LegacySettingsImporter.blocksRuntime(this)) {
            startForegroundRuntime();
        } else if (GuardRecovery.shouldRecover(this)) {
            lifecycle("runtime_blocked", "reason", "legacy_handover");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServiceRuntimeCommand command =
                ServiceRuntimeCommand.capture(intent, startId, ACTION_START);
        String action = command.action;
        lifecycle("service_start", "action", action == null ? "" : action,
                "reason", command.reason, "start_id", startId);
        boolean blocked = LegacySettingsImporter.blocksRuntime(this);
        boolean targetAutoStart = ACTION_AUTO_START_CHANGED.equals(action)
                ? command.enabled : GuardRecovery.isAutoStartEnabled(this);
        boolean targetUserShutdown = ACTION_ACTIVITY_OPEN.equals(action)
                ? false : ACTION_SHUTDOWN.equals(action)
                ? true : GuardRecovery.isUserShutdownActive(this);
        boolean willRecover = GuardRecovery.shouldRecover(targetAutoStart, targetUserShutdown);
        boolean manualRuntime = !targetUserShutdown
                && (activityVisible || ACTION_ACTIVITY_OPEN.equals(action));
        if ((willRecover || helperRuntimeStarted || manualRuntime) && !blocked) {
            startForegroundRuntime();
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
            GuardRecovery.setUserShutdownActive(this, true);
            stopRuntime(true);
            stopServiceFromRuntime(command.startId);
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
            stopRuntime(true);
            runtimeNeedsReinit = true;
            syncWeatherAccessibility(false);
            if (ACTION_WEATHER_REFRESH.equals(action)) {
                sendWeatherResult(command.weatherReceiver, WEATHER_RESULT_FAILED,
                        runtimeText(this, R.string.runtime_finish_migration));
            }
            stopServiceFromRuntime(command.startId);
            return;
        }
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
            stopServiceFromRuntime(command.startId);
            return;
        }
        if (runtimeNeedsReinit) {
            initializeControllers();
            runtimeNeedsReinit = false;
        } else {
            ensureControllersInitialized();
        }
        mirror.setRuntimeAllowed(true);
        boolean refreshMusicAfterClose = false;
        if (ACTION_ACTIVITY_OPEN.equals(action)) {
            activityVisible = true;
            mirror.appVisibility(true);
            overlay.setUiHidden(true);
            parkingCameras.setUiHidden(true);
        } else if (ACTION_ACTIVITY_CLOSED.equals(action)) {
            activityVisible = false;
            mirror.appVisibility(false);
            cameraPreviewActive = false;
            overlay.setUiHidden(false);
            parkingCameras.setUiHidden(false);
            runtimeHandler.removeCallbacks(resumeOverlay);
            runtimeHandler.postDelayed(resumeOverlay, 250);
            refreshMusicAfterClose = true;
        } else if (ACTION_CAMERA_PREVIEW_STARTED.equals(action)) {
            cameraPreviewActive = true;
            runtimeHandler.removeCallbacks(resumeOverlay);
            overlay.setSuspended(true);
            parkingCameras.setSuspended(true);
        } else if (ACTION_CAMERA_PREVIEW_STOPPED.equals(action)) {
            cameraPreviewActive = false;
            runtimeHandler.removeCallbacks(resumeOverlay);
            if (!activityVisible) runtimeHandler.postDelayed(resumeOverlay, 250);
            parkingCameras.setSuspended(false);
        }
        ensureHelperStarted();
        weatherRuntime.start();
        helper.setRecoveryEnabled(shouldRecover);
        helper.applyParkingRadar(anyParkingEnabled());
        if (refreshMusicAfterClose) {
            helper.applyMusic(settings
                    .getBoolean("music_visualizer_enabled", false));
        } else if (ACTION_CAMERA_SETTINGS_CHANGED.equals(action)) {
            mirror.settingsChanged();
            overlay.applySettings();
            parkingCameras.settingsChanged();
            reverseCameras.settingsChanged();
            clusterFullscreen.settingsChanged();
        } else if (ACTION_CAMERA_WARNING_SETTINGS_CHANGED.equals(action)) {
            overlay.applyWarningSettings();
        } else if (ACTION_CAMERA_TRIGGER_SETTINGS_CHANGED.equals(action)) {
            overlay.applyTriggerSettings();
        } else if (ACTION_PARKING_CAMERA_SETTINGS_CHANGED.equals(action)) {
            if (parkingCameras != null) parkingCameras.settingsChanged();
        } else if (ACTION_REVERSE_SETTINGS_CHANGED.equals(action)) {
            reverseCameras.settingsChanged();
        } else if (ACTION_MIRROR_SETTINGS_CHANGED.equals(action)) {
            mirror.settingsChanged();
            clusterFullscreen.settingsChanged();
        } else if (ACTION_MIRROR_BUTTON.equals(action)
                && mirrorButton != null && mirrorButton.changed()) {
            mirror.settingsChanged();
            clusterFullscreen.settingsChanged();
        } else if (ACTION_MUSIC_SETTINGS_CHANGED.equals(action)) {
            helper.applyMusic(settings
                    .getBoolean("music_visualizer_enabled", false));
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
                                    : R.string.runtime_weather_failed)));
            if (!accepted) {
                sendWeatherResult(receiver,
                        weatherRuntime.isRequestInFlight()
                                ? WEATHER_RESULT_BUSY : WEATHER_RESULT_FAILED,
                        runtimeText(this, weatherRuntime.isRequestInFlight()
                                ? R.string.runtime_weather_busy : R.string.runtime_weather_off));
            }
        } else if (ACTION_SETTINGS_RELOADED.equals(action)) {
            reloadSettings(command.fullImport);
        }
        startHeartbeat();
    }

    private void stopServiceFromRuntime(int startId) {
        mainHandler.post(() -> {
            stopForegroundRuntime();
            stopSelf(startId);
        });
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
        } else if (ACTION_AVAS_REPORT_STATUS.equals(command.action)) {
            active.reportAvasStatus();
        } else if (ACTION_AVAS_START_AUDITION.equals(command.action)) {
            if (activityVisible) active.startAvasAudition(command.avasProfileId,
                    command.avasAssetId, command.avasSessionId);
        } else if (ACTION_AVAS_STOP_AUDITION.equals(command.action)) {
            active.stopAvasAudition(command.avasSessionId);
        }
    }

    static void routeManualMirrorSettingsChange(
            String action, Runnable mirrorSettingsChanged, Runnable clusterSettingsChanged) {
        if (!ACTION_MIRROR_SETTINGS_CHANGED.equals(action)
                && !ACTION_CAMERA_SETTINGS_CHANGED.equals(action)
                && !ACTION_SETTINGS_RELOADED.equals(action)) return;
        mirrorSettingsChanged.run();
        clusterSettingsChanged.run();
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
        weatherAccessibilityExecutor.execute(() -> applyWeatherAccessibility(false, false));
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
        String outcome = "cancelled";
        try {
            if (!weatherAccessibilityRecovery.isCurrent(epoch)) return;
            if (!applyWeatherAccessibility(true, false, epoch)) {
                outcome = weatherAccessibilityRecovery.isCurrent(epoch)
                        ? "registration_failed" : "cancelled";
                return;
            }
            if (WeatherRefreshAccessibilityService.awaitConnection(
                    ACCESSIBILITY_CONNECTION_WAIT_MS)) {
                outcome = "connected";
                return;
            }
            if (!weatherAccessibilityRecovery.isCurrent(epoch)) return;
            if (!applyWeatherAccessibility(true, true, epoch)) {
                outcome = weatherAccessibilityRecovery.isCurrent(epoch)
                        ? "rebind_failed" : "cancelled";
                return;
            }
            outcome = WeatherRefreshAccessibilityService.awaitConnection(
                    ACCESSIBILITY_CONNECTION_WAIT_MS) ? "rebind_connected" : "timeout";
        } finally {
            lifecycle("weather_accessibility_recovery", "reason", reason,
                    "outcome", outcome);
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

    private boolean applyWeatherAccessibility(boolean enabled, boolean forceRebind) {
        return applyWeatherAccessibility(enabled, forceRebind, 0);
    }

    private boolean applyWeatherAccessibility(
            boolean enabled, boolean forceRebind, long recoveryEpoch) {
        LocalAdbClient.Result currentResult = LocalAdbClient.executeAuthorizedText(
                this, "settings get secure enabled_accessibility_services", 8_192,
                this::lifecycle);
        if (!currentResult.ok) {
            lifecycle("weather_accessibility_failed", "enabled", enabled,
                    "error", currentResult.error);
            clearWeatherAccessibilityTarget(enabled);
            return false;
        }
        String current = currentResult.output == null ? "" : currentResult.output.trim();
        if ("null".equalsIgnoreCase(current)) current = "";
        if (enabled && recoveryEpoch != 0
                && !weatherAccessibilityRecovery.isCurrent(recoveryEpoch)) return false;
        boolean installed = WeatherAccessibilitySettings.hasOwnService(current);
        boolean legacyInstalled = WeatherAccessibilitySettings.hasLegacyService(current);
        String value = WeatherAccessibilitySettings.transformEnabledAccessibilityServices(
                current, enabled);
        if (!enabled && !installed && !legacyInstalled) {
            lifecycle("weather_accessibility_applied", "enabled", false,
                    "other_services", value.isEmpty() ? 0 : 1);
            return true;
        }
        boolean ok = true;
        if (installed != enabled || legacyInstalled || forceRebind) {
            if (enabled) {
                String withoutOwn = WeatherAccessibilitySettings
                        .transformEnabledAccessibilityServices(current, false);
                ok = runWeatherAccessibilityCommand(
                        "settings put secure enabled_accessibility_services ':" + withoutOwn + "'")
                        && pauseWeatherAccessibility();
                if (ok && recoveryEpoch != 0
                        && !weatherAccessibilityRecovery.isCurrent(recoveryEpoch)) return false;
                ok = ok && runWeatherAccessibilityCommand(
                        "settings put secure enabled_accessibility_services ':" + value + "'")
                        && pauseWeatherAccessibility();
            } else {
                ok = runWeatherAccessibilityCommand(
                        "settings put secure enabled_accessibility_services ':" + value + "'")
                        && pauseWeatherAccessibility();
            }
        }
        if (ok && enabled && (recoveryEpoch == 0
                || weatherAccessibilityRecovery.isCurrent(recoveryEpoch))) {
            ok = runWeatherAccessibilityCommand(
                    "settings put secure accessibility_enabled 1");
        }
        lifecycle(ok ? "weather_accessibility_applied" : "weather_accessibility_failed",
                "enabled", enabled, "listed", enabled && WeatherAccessibilitySettings
                        .hasOwnService(value), "rebind", forceRebind,
                "other_services", value.isEmpty() ? 0 : 1);
        if (!ok) clearWeatherAccessibilityTarget(enabled);
        return ok;
    }

    private void clearWeatherAccessibilityTarget(boolean attemptedValue) {
        Boolean target = weatherAccessibilityTarget;
        if (target != null && target.booleanValue() == attemptedValue) {
            weatherAccessibilityTarget = null;
        }
    }

    private boolean runWeatherAccessibilityCommand(String command) {
        LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                this, command, this::lifecycle);
        if (!result.ok) {
            lifecycle("weather_accessibility_command_failed", "error", result.error);
        }
        return result.ok;
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
                activeHelper.setRecoveryEnabled(GuardRecovery.shouldRecover(this));
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
                startPersistent(this, "task_removed");
            }
        });
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        RuntimeLifecycleGate.TeardownResult teardown =
                runtimeLifecycle.beginTeardown(runtimeQueue, this::destroyRuntime);
        stopForegroundRuntime();
        if (activeInstance == this) activeInstance = null;
        if (teardown == RuntimeLifecycleGate.TeardownResult.POST_REJECTED) {
            Thread fallback = new Thread(this::destroyRuntime, "service-runtime-teardown");
            fallback.start();
        }
        super.onDestroy();
    }

    private void destroyRuntime() {
        runtimeHandler.removeCallbacksAndMessages(null);
        boolean recover = GuardRecovery.shouldRecover(this);
        boolean explicitShutdown = GuardRecovery.isUserShutdownActive(this);
        lifecycle("service_destroy", "recover", recover);
        if (oemCameraVisibility != null) oemCameraVisibility.stopForTeardown();
        if (reverseCameras != null) reverseCameras.shutdown();
        if (mirror != null) mirror.shutdown();
        if (overlay != null) overlay.shutdown();
        if (parkingCameras != null) parkingCameras.shutdown();
        if (clusterFullscreen != null) clusterFullscreen.shutdown();
        if (weatherRuntime != null) weatherRuntime.shutdown();
        HelperTeardownMode teardownMode = helperTeardownMode(recover, explicitShutdown);
        if (helper != null) {
            if (teardownMode == HelperTeardownMode.StopAll) helper.shutdown(true);
            else helper.shutdown(false);
        }
        controllersInitialized = false;
        helper = null;
        helperRuntimeStarted = false;
        oemCameraVisibility = null;
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
        helperRuntimeStarted = false;
    }

    private void ensureHelperStarted() {
        if (LegacySettingsImporter.blocksRuntime(this)) return;
        ensureHelperCreated();
        if (helperRuntimeStarted) return;
        helperRuntimeStarted = true;
        boolean cameraReady = helper.discoverCamera();
        mirror.attachHelper(helper);
        helper.startGuardRuntime();
        helper.configureAvas();

        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        RuntimeSettingsSnapshot.read(settings, anyParkingEnabled()).replay(helper);
        overlay.attachHelper(helper);
        parkingCameras.attachHelper(helper);
        parkingCameras.setUiHidden(activityVisible);
        parkingCameras.setSuspended(cameraPreviewActive);
        reverseCameras.attachHelper(helper);
        overlay.setUiHidden(activityVisible);
        overlay.setSuspended(cameraPreviewActive);
        if (shouldRetryCameraDiscovery(cameraReady, helperRuntimeStarted)) {
            scheduleCameraDiscoveryRetry(CAMERA_DISCOVERY_RETRY_MS);
        }
    }

    private void stopRuntime(boolean terminateShells) {
        runtimeHandler.removeCallbacks(heartbeat);
        runtimeHandler.removeCallbacks(resumeOverlay);
        runtimeHandler.removeCallbacks(retryCameraDiscovery);
        if (reverseCameras != null) reverseCameras.shutdown();
        if (mirror != null) mirror.shutdown();
        if (overlay != null) overlay.shutdown();
        if (parkingCameras != null) parkingCameras.shutdown();
        if (clusterFullscreen != null) clusterFullscreen.shutdown();
        if (weatherRuntime != null) weatherRuntime.shutdown();
        if (helper != null) helper.shutdown(terminateShells);
        overlay = null;
        parkingCameras = null;
        reverseCameras = null;
        mirror = null;
        clusterFullscreen = null;
        weatherRuntime = null;
        controllersInitialized = false;
        helper = null;
        helperRuntimeStarted = false;
        mainHandler.post(this::stopForegroundRuntime);
        GuardRecovery.schedule(this);
    }

    private void reloadSettings(boolean fullImport) {
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        if (helper != null) {
            RuntimeSettingsSnapshot.read(settings, anyParkingEnabled()).replay(helper);
            helper.configureAvas();
        }
        overlay.applySettings();
        overlay.applyWarningSettings();
        overlay.applyTriggerSettings();
        parkingCameras.settingsChanged();
        reverseCameras.settingsChanged();
        if (mirror != null) mirror.settingsChanged();
        clusterFullscreen.settingsChanged();
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

    private void scheduleCameraDiscoveryRetry(long delayMs) {
        runtimeHandler.removeCallbacks(retryCameraDiscovery);
        runtimeHandler.postDelayed(retryCameraDiscovery, Math.max(0, delayMs));
    }

    static boolean shouldRetryCameraDiscovery(boolean cameraReady, boolean runtimeActive) {
        return !cameraReady && runtimeActive;
    }

    private void startForegroundRuntime() {
        if (foreground) return;
        startForeground(NOTIFICATION_ID, notification());
        foreground = true;
    }

    private void stopForegroundRuntime() {
        if (!foreground) return;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        foreground = false;
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

    static boolean shouldReopenLog(boolean writerOpen, boolean fileExists) {
        return writerOpen && !fileExists;
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
        postRuntime(() -> {
            if (isShellOemVisibilityEvent(line)) {
                lifecycle("oem_camera_visibility_upstream_ignored");
                return;
            }
            if (overlay != null) overlay.acceptEvent(line);
            if (parkingCameras != null) parkingCameras.acceptEvent(line);
            if (reverseCameras != null) reverseCameras.acceptEvent(line);
            if (mirror != null) mirror.acceptEvent(line);
            if (clusterFullscreen != null) clusterFullscreen.acceptEvent(line);
            serviceLog.appendRaw(line);
        });
    }

    private void oemVisibilityChanged(boolean known, boolean visible, String source) {
        if (overlay != null) overlay.oemVisibility(known, visible);
        if (reverseCameras != null) reverseCameras.oemVisibility(known, visible, source);
        if (mirror != null) mirror.oemVisibility(known, visible);
    }

    static boolean isShellOemVisibilityEvent(String line) {
        if (line == null || !line.contains("oem_camera_visibility")) return false;
        try {
            String kind = new org.json.JSONObject(line).optString("kind");
            return "oem_camera_visibility".equals(kind)
                    || "oem_camera_visibility_listener".equals(kind);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void parkingEvent(String kind, Object... fields) {
        lifecycle(kind, fields);
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

    private void reverseEvent(String kind, Object... fields) {
        CameraHelperMain.HelperBinder activeHelper = helper;
        if (activeHelper != null) activeHelper.emitControllerEvent(kind, fields);
        else lifecycle(kind, fields);
    }

    private void lifecycle(String kind, Object... fields) {
        AsyncServiceLog activeLog = serviceLog;
        if (activeLog != null) {
            activeLog.appendLifecycle(kind, SystemClock.elapsedRealtime(), fields);
        }
    }
}
