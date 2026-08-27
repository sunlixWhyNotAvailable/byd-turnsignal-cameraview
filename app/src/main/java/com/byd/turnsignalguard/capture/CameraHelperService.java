package com.byd.turnsignalguard.capture;

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
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CameraHelperService extends Service {
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
    private static final String EXTRA_ENABLED = "enabled";
    private static final String EXTRA_REASON = "reason";
    private static final String EXTRA_FLUSH_RECEIVER = "flush_receiver";
    private static final String EXTRA_WEATHER_RECEIVER = "weather_receiver";
    private static final String EXTRA_WEATHER_REASON = "weather_reason";
    private static final String EXTRA_FULL_IMPORT = "full_import";
    private static final long CAMERA_DISCOVERY_RETRY_MS = 3_000;
    private static final long LOG_FLUSH_DELAY_MS = 250;
    static final int WEATHER_RESULT_OK = 0;
    static final int WEATHER_RESULT_FAILED = 1;
    static final int WEATHER_RESULT_BUSY = 2;
    static final String WEATHER_RESULT_MESSAGE = "weather_result_message";

    private final Object logLock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService weatherAccessibilityExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "weather-accessibility"));
    private final Runnable flushLog = this::flushLogWriter;
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!GuardRecovery.shouldRecover(CameraHelperService.this)) return;
            GuardRecovery.heartbeat(CameraHelperService.this);
            handler.postDelayed(this, 30_000);
        }
    };
    private final Runnable resumeOverlay = this::resumeOverlayIfIdle;
    private final Runnable retryCameraDiscovery = new Runnable() {
        @Override
        public void run() {
            if (helper == null || !GuardRecovery.shouldRecover(CameraHelperService.this)) return;
            boolean ready = helper.discoverCamera();
            lifecycle("camera_discovery_retry", "ready", ready);
            if (!ready) handler.postDelayed(this, CAMERA_DISCOVERY_RETRY_MS);
        }
    };
    private CameraHelperMain.HelperBinder helper;
    private BlindSpotOverlayController overlay;
    private ParkingCameraController parkingCameras;
    private ReverseCameraController reverseCameras;
    private ClusterFullscreenController clusterFullscreen;
    private WeatherRuntime weatherRuntime;
    private File logFile;
    private BufferedWriter logWriter;
    private boolean logFlushScheduled;
    private boolean logClosed;
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
        GuardRecovery.setUserShutdownActive(context, false);
        Intent intent = new Intent(context, CameraHelperService.class)
                .setAction(ACTION_ACTIVITY_OPEN);
        context.startService(intent);
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

    /** Pause an already-running instance synchronously before preferences are replaced. */
    static boolean pauseActiveRuntime() {
        CameraHelperService service = activeInstance;
        if (service == null) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            service.stopRuntime(true);
            service.runtimeNeedsReinit = true;
            return true;
        }
        CountDownLatch done = new CountDownLatch(1);
        if (!service.handler.post(() -> {
            try {
                service.stopRuntime(true);
                service.runtimeNeedsReinit = true;
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

    static void musicSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_MUSIC_SETTINGS_CHANGED));
    }

    static void weatherSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_WEATHER_SETTINGS_CHANGED));
    }

    static void weatherRefreshRequested(
            Context context, String reason, ResultReceiver receiver) {
        SharedPreferences settings = context.getSharedPreferences("settings", MODE_PRIVATE);
        if (!settings.getBoolean(WeatherRuntime.PREF_ENABLED, false)
                || !GuardRecovery.shouldRecover(context)) {
            sendWeatherResult(receiver, WEATHER_RESULT_FAILED,
                    "Погода вимкнена");
            return;
        }
        Intent intent = new Intent(context, CameraHelperService.class)
                .setAction(ACTION_WEATHER_REFRESH)
                .putExtra(EXTRA_WEATHER_REASON, reason == null ? "manual" : reason)
                .putExtra(EXTRA_WEATHER_RECEIVER, receiver);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
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

    static void updateAutoStart(Context context, boolean enabled) {
        GuardRecovery.setAutoStartEnabled(context, enabled);
        Intent intent = new Intent(context, CameraHelperService.class)
                .setAction(ACTION_AUTO_START_CHANGED)
                .putExtra(EXTRA_ENABLED, enabled);
        if (enabled && Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void requestShutdown(Context context) {
        GuardRecovery.setUserShutdownActive(context, true);
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
        overlay = new BlindSpotOverlayController(this, handler, this::lifecycle);
        parkingCameras = new ParkingCameraController(this, handler, this::parkingEvent);
        reverseCameras = new ReverseCameraController(
                this, handler, this::reverseEvent, value -> {
                    overlay.setReversePriority(value);
                    if (parkingCameras != null) parkingCameras.setReversePriority(value);
                });
        clusterFullscreen = new ClusterFullscreenController(this, settings, this::lifecycle);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        createLogFile();
        initializeControllers();
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        lifecycle("service_create", "auto_start", GuardRecovery.isAutoStartEnabled(this),
                "user_shutdown", GuardRecovery.isUserShutdownActive(this));
        if (GuardRecovery.shouldRecover(this) && !LegacySettingsImporter.blocksRuntime(this)) {
            startForegroundRuntime();
            ensureHelperStarted();
            weatherRuntime.start();
            startHeartbeat();
        } else if (GuardRecovery.shouldRecover(this)
                && settings.getBoolean(WeatherRuntime.PREF_ENABLED, false)) {
            startForegroundRuntime();
            syncWeatherAccessibility(true);
            weatherRuntime.start();
        } else if (GuardRecovery.shouldRecover(this)) {
            lifecycle("runtime_blocked", "reason", "legacy_handover");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        String reason = intent == null ? "" : intent.getStringExtra(EXTRA_REASON);
        boolean refreshMusicAfterClose = false;
        lifecycle("service_start", "action", action == null ? "" : action,
                "reason", reason == null ? "" : reason, "start_id", startId);
        if (ACTION_SHUTDOWN.equals(action)) {
            GuardRecovery.setUserShutdownActive(this, true);
            stopRuntime(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_FLUSH_LOGS.equals(action)) {
            flushLogWriter();
            ResultReceiver receiver = intent == null
                    ? null : intent.getParcelableExtra(EXTRA_FLUSH_RECEIVER);
            if (receiver != null) receiver.send(0, Bundle.EMPTY);
        } else if (ACTION_ACTIVITY_OPEN.equals(action)) {
            GuardRecovery.setUserShutdownActive(this, false);
            activityVisible = true;
            overlay.setUiHidden(true);
            parkingCameras.setUiHidden(true);
        } else if (ACTION_ACTIVITY_CLOSED.equals(action)) {
            activityVisible = false;
            cameraPreviewActive = false;
            overlay.setUiHidden(false);
            parkingCameras.setUiHidden(false);
            handler.removeCallbacks(resumeOverlay);
            handler.postDelayed(resumeOverlay, 250);
            refreshMusicAfterClose = true;
        } else if (ACTION_CAMERA_PREVIEW_STARTED.equals(action)) {
            cameraPreviewActive = true;
            handler.removeCallbacks(resumeOverlay);
            overlay.setSuspended(true);
            parkingCameras.setSuspended(true);
        } else if (ACTION_CAMERA_PREVIEW_STOPPED.equals(action)) {
            cameraPreviewActive = false;
            handler.removeCallbacks(resumeOverlay);
            if (!activityVisible) handler.postDelayed(resumeOverlay, 250);
            parkingCameras.setSuspended(false);
        } else if (ACTION_AUTO_START_CHANGED.equals(action)) {
            GuardRecovery.setAutoStartEnabled(this,
                    intent.getBooleanExtra(EXTRA_ENABLED, true));
        }
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        boolean shouldRecover = GuardRecovery.shouldRecover(this);
        if (LegacySettingsImporter.blocksRuntime(this)) {
            if (handleBlockedWeatherAction(action, intent, settings)) {
                return START_STICKY;
            }
            lifecycle("runtime_blocked", "reason", "legacy_handover");
            if (shouldRecover && settings.getBoolean(WeatherRuntime.PREF_ENABLED, false)) {
                if (runtimeNeedsReinit) {
                    initializeControllers();
                    runtimeNeedsReinit = false;
                }
                startForegroundRuntime();
                weatherRuntime.start();
                return START_STICKY;
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        syncWeatherAccessibility(
                shouldRecover && settings.getBoolean(WeatherRuntime.PREF_ENABLED, false));
        if (!shouldRecover) {
            if (helper != null) helper.setRecoveryEnabled(false);
            handler.removeCallbacks(heartbeat);
            stopForegroundRuntime();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (runtimeNeedsReinit) {
            initializeControllers();
            runtimeNeedsReinit = false;
        }
        startForegroundRuntime();
        ensureHelperStarted();
        weatherRuntime.start();
        helper.setRecoveryEnabled(true);
        helper.configureParkingRadar(anyParkingEnabled());
        if (refreshMusicAfterClose) {
            helper.configureMusic(settings
                    .getBoolean("music_visualizer_enabled", false));
        } else if (ACTION_CAMERA_SETTINGS_CHANGED.equals(action)) {
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
        } else if (ACTION_MUSIC_SETTINGS_CHANGED.equals(action)) {
            helper.configureMusic(settings
                    .getBoolean("music_visualizer_enabled", false));
        } else if (ACTION_WEATHER_SETTINGS_CHANGED.equals(action)) {
            weatherRuntime.settingsChanged();
        } else if (ACTION_WEATHER_REFRESH.equals(action)) {
            ResultReceiver receiver = intent == null
                    ? null : intent.getParcelableExtra(EXTRA_WEATHER_RECEIVER);
            String weatherReason = intent == null
                    ? "manual" : intent.getStringExtra(EXTRA_WEATHER_REASON);
            boolean accepted = weatherRuntime.requestNow(weatherReason, (success, error) ->
                    sendWeatherResult(receiver,
                            success ? WEATHER_RESULT_OK : WEATHER_RESULT_FAILED,
                            success ? "Погоду оновлено" : "Не вдалося оновити погоду"));
            if (!accepted) {
                sendWeatherResult(receiver,
                        weatherRuntime.isRequestInFlight()
                                ? WEATHER_RESULT_BUSY : WEATHER_RESULT_FAILED,
                        weatherRuntime.isRequestInFlight()
                                ? "Оновлення вже виконується" : "Погода вимкнена");
            }
        } else if (ACTION_SETTINGS_RELOADED.equals(action)) {
            reloadSettings(intent != null && intent.getBooleanExtra(EXTRA_FULL_IMPORT, false));
        }
        startHeartbeat();
        return START_STICKY;
    }

    private boolean handleBlockedWeatherAction(
            String action, Intent intent, SharedPreferences settings) {
        if (!ACTION_WEATHER_SETTINGS_CHANGED.equals(action)
                && !ACTION_WEATHER_REFRESH.equals(action)) return false;
        boolean enabled = settings.getBoolean(WeatherRuntime.PREF_ENABLED, false);
        syncWeatherAccessibility(enabled && GuardRecovery.shouldRecover(this));
        if (ACTION_WEATHER_SETTINGS_CHANGED.equals(action)) {
            weatherRuntime.settingsChanged();
            if (!enabled || !GuardRecovery.shouldRecover(this)) return true;
        } else if (!enabled || !GuardRecovery.shouldRecover(this)) {
            ResultReceiver receiver = intent == null
                    ? null : intent.getParcelableExtra(EXTRA_WEATHER_RECEIVER);
            sendWeatherResult(receiver, WEATHER_RESULT_FAILED, "Погода вимкнена");
            return true;
        }
        if (runtimeNeedsReinit) {
            initializeControllers();
            runtimeNeedsReinit = false;
        }
        startForegroundRuntime();
        weatherRuntime.start();
        if (ACTION_WEATHER_SETTINGS_CHANGED.equals(action)) {
            return true;
        }
        ResultReceiver receiver = intent == null
                ? null : intent.getParcelableExtra(EXTRA_WEATHER_RECEIVER);
        String reason = intent == null ? "manual" : intent.getStringExtra(EXTRA_WEATHER_REASON);
        boolean accepted = weatherRuntime.requestNow(reason, (success, error) ->
                sendWeatherResult(receiver,
                        success ? WEATHER_RESULT_OK : WEATHER_RESULT_FAILED,
                        success ? "Погоду оновлено" : "Не вдалося оновити погоду"));
        if (!accepted) {
            sendWeatherResult(receiver,
                    weatherRuntime.isRequestInFlight()
                            ? WEATHER_RESULT_BUSY : WEATHER_RESULT_FAILED,
                    weatherRuntime.isRequestInFlight()
                            ? "Оновлення вже виконується" : "Погода вимкнена");
        }
        return true;
    }

    private void syncWeatherAccessibility(boolean enabled) {
        if (weatherAccessibilityTarget != null
                && weatherAccessibilityTarget.booleanValue() == enabled) return;
        weatherAccessibilityTarget = enabled;
        weatherAccessibilityExecutor.execute(() -> applyWeatherAccessibility(enabled));
    }

    private void applyWeatherAccessibility(boolean enabled) {
        LocalAdbClient.Result currentResult = LocalAdbClient.executeAuthorizedText(
                this, "settings get secure enabled_accessibility_services", 8_192,
                this::lifecycle);
        if (!currentResult.ok) {
            lifecycle("weather_accessibility_failed", "enabled", enabled,
                    "error", currentResult.error);
            clearWeatherAccessibilityTarget(enabled);
            return;
        }
        String current = currentResult.output == null ? "" : currentResult.output.trim();
        if ("null".equalsIgnoreCase(current)) current = "";
        boolean installed = WeatherAccessibilitySettings.hasOwnService(current);
        String value = WeatherAccessibilitySettings.transformEnabledAccessibilityServices(
                current, enabled);
        if (!enabled && !installed) {
            lifecycle("weather_accessibility_applied", "enabled", false,
                    "other_services", value.isEmpty() ? 0 : 1);
            return;
        }
        boolean ok = true;
        if (installed != enabled) {
            if (enabled) {
                String withoutOwn = WeatherAccessibilitySettings
                        .transformEnabledAccessibilityServices(current, false);
                ok = runWeatherAccessibilityCommand(
                        "settings put secure enabled_accessibility_services ':" + withoutOwn + "'")
                        && pauseWeatherAccessibility()
                        && runWeatherAccessibilityCommand(
                        "settings put secure enabled_accessibility_services ':" + value + "'")
                        && pauseWeatherAccessibility();
            } else {
                ok = runWeatherAccessibilityCommand(
                        "settings put secure enabled_accessibility_services ':" + value + "'")
                        && pauseWeatherAccessibility();
            }
        }
        if (ok) {
            ok = runWeatherAccessibilityCommand(
                    "settings put secure accessibility_enabled "
                            + (enabled || !value.isEmpty() ? "1" : "0"));
        }
        lifecycle(ok ? "weather_accessibility_applied" : "weather_accessibility_failed",
                "enabled", enabled, "other_services", value.isEmpty() ? 0 : 1);
        if (!ok) clearWeatherAccessibilityTarget(enabled);
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
        ensureHelperStarted();
        helper.setRecoveryEnabled(GuardRecovery.shouldRecover(this));
        return helper;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (!GuardRecovery.shouldRecover(this)) stopSelf();
        return false;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        lifecycle("service_task_removed", "recover", GuardRecovery.shouldRecover(this));
        if (GuardRecovery.shouldRecover(this) && !LegacySettingsImporter.blocksRuntime(this)) {
            GuardRecovery.scheduleSoon(this);
            startPersistent(this, "task_removed");
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(heartbeat);
        handler.removeCallbacks(retryCameraDiscovery);
        boolean recover = GuardRecovery.shouldRecover(this);
        lifecycle("service_destroy", "recover", recover);
        if (reverseCameras != null) reverseCameras.shutdown();
        if (overlay != null) overlay.shutdown();
        if (parkingCameras != null) parkingCameras.shutdown();
        if (clusterFullscreen != null) clusterFullscreen.shutdown();
        if (weatherRuntime != null) weatherRuntime.shutdown();
        if (helper != null) helper.shutdown(!recover);
        helper = null;
        weatherAccessibilityExecutor.shutdownNow();
        stopForegroundRuntime();
        if (recover) GuardRecovery.scheduleSoon(this);
        closeLogWriter();
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    private void ensureHelperStarted() {
        if (LegacySettingsImporter.blocksRuntime(this)) return;
        if (helper != null) return;
        helper = new CameraHelperMain.HelperBinder(getApplicationContext(), this::acceptHelperLine);
        boolean cameraReady = helper.discoverCamera();
        helper.startGuardRuntime();

        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        helper.configureGuard(
                settings.getBoolean("guard_enabled", false),
                settings.getFloat("outward_deg", 90.0f),
                settings.getFloat("center_deg", 10.0f),
                settings.getInt("correction_delay_ms", 100),
                settings.getInt("max_speed_kph", 30));
        overlay.attachHelper(helper);
        parkingCameras.attachHelper(helper);
        parkingCameras.setUiHidden(activityVisible);
        parkingCameras.setSuspended(cameraPreviewActive);
        helper.configureParkingRadar(anyParkingEnabled());
        reverseCameras.attachHelper(helper);
        overlay.setUiHidden(activityVisible);
        overlay.setSuspended(cameraPreviewActive);
        if (shouldRetryCameraDiscovery(cameraReady, GuardRecovery.shouldRecover(this))) {
            scheduleCameraDiscoveryRetry(CAMERA_DISCOVERY_RETRY_MS);
        }
    }

    private void stopRuntime(boolean terminateShells) {
        handler.removeCallbacks(heartbeat);
        handler.removeCallbacks(resumeOverlay);
        handler.removeCallbacks(retryCameraDiscovery);
        if (reverseCameras != null) reverseCameras.shutdown();
        if (overlay != null) overlay.shutdown();
        if (parkingCameras != null) parkingCameras.shutdown();
        if (clusterFullscreen != null) clusterFullscreen.shutdown();
        if (weatherRuntime != null) weatherRuntime.shutdown();
        if (helper != null) helper.shutdown(terminateShells);
        helper = null;
        stopForegroundRuntime();
        GuardRecovery.schedule(this);
    }

    private void reloadSettings(boolean fullImport) {
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        if (helper != null) {
            helper.configureGuard(
                    settings.getBoolean("guard_enabled", false),
                    settings.getFloat("outward_deg", 90.0f),
                    settings.getFloat("center_deg", 10.0f),
                    settings.getInt("correction_delay_ms", 100),
                    settings.getInt("max_speed_kph", 30));
            helper.configureMusic(settings.getBoolean("music_visualizer_enabled", false));
            helper.configureParkingRadar(anyParkingEnabled());
        }
        overlay.applySettings();
        overlay.applyWarningSettings();
        overlay.applyTriggerSettings();
        parkingCameras.settingsChanged();
        reverseCameras.settingsChanged();
        clusterFullscreen.settingsChanged();
        if (fullImport) {
            GuardRecovery.setAutoStartEnabled(this,
                    settings.getBoolean("auto_start_enabled", true));
            weatherRuntime.settingsChanged();
            syncWeatherAccessibility(
                    settings.getBoolean(WeatherRuntime.PREF_ENABLED, false));
        }
        lifecycle("settings_reloaded", "full_import", fullImport);
    }

    private void startHeartbeat() {
        handler.removeCallbacks(heartbeat);
        heartbeat.run();
    }

    private void scheduleCameraDiscoveryRetry(long delayMs) {
        handler.removeCallbacks(retryCameraDiscovery);
        handler.postDelayed(retryCameraDiscovery, Math.max(0, delayMs));
    }

    static boolean shouldRetryCameraDiscovery(boolean cameraReady, boolean recover) {
        return !cameraReady && recover;
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
                .setContentText("BYD Extend service active")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void createLogFile() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File captures = new File(base, "captures");
        if (!captures.isDirectory() && !captures.mkdirs()) captures = getFilesDir();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        logFile = new File(captures, "helper-service-" + stamp + ".jsonl");
    }

    private void writeLine(String line) {
        synchronized (logLock) {
            if (logClosed) return;
            try {
                if (shouldReopenLog(logWriter != null, logFile.exists())) {
                    closeLogWriterLocked();
                }
                if (logWriter == null) {
                    logWriter = new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(logFile, true), StandardCharsets.UTF_8));
                }
                logWriter.write(line);
                logWriter.newLine();
                if (!logFlushScheduled) {
                    logFlushScheduled = true;
                    if (!handler.postDelayed(flushLog, LOG_FLUSH_DELAY_MS)) {
                        logFlushScheduled = false;
                        logWriter.flush();
                    }
                }
            } catch (Throwable ignored) {
                // Logcat still receives the same helper event.
                closeLogWriterLocked();
            }
        }
    }

    static boolean shouldReopenLog(boolean writerOpen, boolean fileExists) {
        return writerOpen && !fileExists;
    }

    private void flushLogWriter() {
        synchronized (logLock) {
            logFlushScheduled = false;
            if (logWriter == null) return;
            try {
                logWriter.flush();
            } catch (Throwable ignored) {
                closeLogWriterLocked();
            }
        }
    }

    private void closeLogWriter() {
        handler.removeCallbacks(flushLog);
        synchronized (logLock) {
            logClosed = true;
            closeLogWriterLocked();
        }
    }

    private void closeLogWriterLocked() {
        logFlushScheduled = false;
        if (logWriter == null) return;
        try {
            logWriter.flush();
        } catch (Throwable ignored) {
        }
        try {
            logWriter.close();
        } catch (Throwable ignored) {
        }
        logWriter = null;
    }

    private void acceptHelperLine(String line) {
        if (overlay != null) overlay.acceptEvent(line);
        if (parkingCameras != null) parkingCameras.acceptEvent(line);
        if (reverseCameras != null) reverseCameras.acceptEvent(line);
        if (clusterFullscreen != null) clusterFullscreen.acceptEvent(line);
        writeLine(line);
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
        try {
            JSONObject event = new JSONObject();
            event.put("kind", kind);
            event.put("source", "helper_service");
            event.put("t_ms", SystemClock.elapsedRealtime());
            for (int i = 0; i + 1 < fields.length; i += 2) {
                event.put(String.valueOf(fields[i]), fields[i + 1]);
            }
            writeLine(event.toString());
        } catch (Throwable ignored) {
            // Lifecycle logging must not affect service recovery.
        }
    }
}
