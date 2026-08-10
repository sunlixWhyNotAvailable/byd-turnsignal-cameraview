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

public final class CameraHelperService extends Service {
    private static final String CHANNEL_ID = "guard_service";
    private static final int NOTIFICATION_ID = 8713;
    private static final String ACTION_START =
            "com.byd.turnsignalguard.capture.action.START_PERSISTENT";
    private static final String ACTION_ACTIVITY_OPEN =
            "com.byd.turnsignalguard.capture.action.ACTIVITY_OPEN";
    private static final String ACTION_ACTIVITY_CLOSED =
            "com.byd.turnsignalguard.capture.action.ACTIVITY_CLOSED";
    private static final String ACTION_CAMERA_PREVIEW_STARTED =
            "com.byd.turnsignalguard.capture.action.CAMERA_PREVIEW_STARTED";
    private static final String ACTION_CAMERA_PREVIEW_STOPPED =
            "com.byd.turnsignalguard.capture.action.CAMERA_PREVIEW_STOPPED";
    private static final String ACTION_CAMERA_SETTINGS_CHANGED =
            "com.byd.turnsignalguard.capture.action.CAMERA_SETTINGS_CHANGED";
    private static final String ACTION_CAMERA_WARNING_SETTINGS_CHANGED =
            "com.byd.turnsignalguard.capture.action.CAMERA_WARNING_SETTINGS_CHANGED";
    private static final String ACTION_CAMERA_TRIGGER_SETTINGS_CHANGED =
            "com.byd.turnsignalguard.capture.action.CAMERA_TRIGGER_SETTINGS_CHANGED";
    private static final String ACTION_REVERSE_SETTINGS_CHANGED =
            "com.byd.turnsignalguard.capture.action.REVERSE_SETTINGS_CHANGED";
    private static final String ACTION_MUSIC_SETTINGS_CHANGED =
            "com.byd.turnsignalguard.capture.action.MUSIC_SETTINGS_CHANGED";
    private static final String ACTION_AUTO_START_CHANGED =
            "com.byd.turnsignalguard.capture.action.AUTO_START_CHANGED";
    private static final String ACTION_FLUSH_LOGS =
            "com.byd.turnsignalguard.capture.action.FLUSH_LOGS";
    private static final String ACTION_SHUTDOWN =
            "com.byd.turnsignalguard.capture.action.SHUTDOWN";
    private static final String EXTRA_ENABLED = "enabled";
    private static final String EXTRA_REASON = "reason";
    private static final String EXTRA_FLUSH_RECEIVER = "flush_receiver";
    private static final long CAMERA_DISCOVERY_RETRY_MS = 3_000;
    private static final long LOG_FLUSH_DELAY_MS = 250;

    private final Object logLock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
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
    private ReverseCameraController reverseCameras;
    private ClusterFullscreenController clusterFullscreen;
    private File logFile;
    private BufferedWriter logWriter;
    private boolean logFlushScheduled;
    private boolean logClosed;
    private boolean foreground;
    private boolean activityVisible;
    private boolean cameraPreviewActive;

    private void resumeOverlayIfIdle() {
        if (shouldResumeOverlay(cameraPreviewActive, activityVisible) && overlay != null) {
            overlay.setSuspended(false);
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

    static void cameraWarningSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_CAMERA_WARNING_SETTINGS_CHANGED));
    }

    static void cameraTriggerSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_CAMERA_TRIGGER_SETTINGS_CHANGED));
    }

    static void reverseCameraSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_REVERSE_SETTINGS_CHANGED));
    }

    static void musicSettingsChanged(Context context) {
        context.startService(new Intent(context, CameraHelperService.class)
                .setAction(ACTION_MUSIC_SETTINGS_CHANGED));
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

    @Override
    public void onCreate() {
        super.onCreate();
        createLogFile();
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        BlindSpotOverlayController.migrateOverlayPreferences(settings);
        overlay = new BlindSpotOverlayController(this, handler, this::lifecycle);
        reverseCameras = new ReverseCameraController(
                this, handler, this::reverseEvent, overlay::setReversePriority);
        clusterFullscreen = new ClusterFullscreenController(this, settings, this::lifecycle);
        lifecycle("service_create", "auto_start", GuardRecovery.isAutoStartEnabled(this),
                "user_shutdown", GuardRecovery.isUserShutdownActive(this));
        if (GuardRecovery.shouldRecover(this)) {
            startForegroundRuntime();
            ensureHelperStarted();
            startHeartbeat();
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
        } else if (ACTION_ACTIVITY_CLOSED.equals(action)) {
            activityVisible = false;
            cameraPreviewActive = false;
            overlay.setUiHidden(false);
            handler.removeCallbacks(resumeOverlay);
            handler.postDelayed(resumeOverlay, 250);
            refreshMusicAfterClose = true;
        } else if (ACTION_CAMERA_PREVIEW_STARTED.equals(action)) {
            cameraPreviewActive = true;
            handler.removeCallbacks(resumeOverlay);
            overlay.setSuspended(true);
        } else if (ACTION_CAMERA_PREVIEW_STOPPED.equals(action)) {
            cameraPreviewActive = false;
            handler.removeCallbacks(resumeOverlay);
            if (!activityVisible) handler.postDelayed(resumeOverlay, 250);
        } else if (ACTION_AUTO_START_CHANGED.equals(action)) {
            GuardRecovery.setAutoStartEnabled(this,
                    intent.getBooleanExtra(EXTRA_ENABLED, true));
        }
        if (!GuardRecovery.shouldRecover(this)) {
            if (helper != null) helper.setRecoveryEnabled(false);
            handler.removeCallbacks(heartbeat);
            stopForegroundRuntime();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForegroundRuntime();
        ensureHelperStarted();
        helper.setRecoveryEnabled(true);
        if (refreshMusicAfterClose) {
            helper.configureMusic(getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("music_visualizer_enabled", false));
        } else if (ACTION_CAMERA_SETTINGS_CHANGED.equals(action)) {
            overlay.applySettings();
            reverseCameras.settingsChanged();
            clusterFullscreen.settingsChanged();
        } else if (ACTION_CAMERA_WARNING_SETTINGS_CHANGED.equals(action)) {
            overlay.applyWarningSettings();
        } else if (ACTION_CAMERA_TRIGGER_SETTINGS_CHANGED.equals(action)) {
            overlay.applyTriggerSettings();
        } else if (ACTION_REVERSE_SETTINGS_CHANGED.equals(action)) {
            reverseCameras.settingsChanged();
        } else if (ACTION_MUSIC_SETTINGS_CHANGED.equals(action)) {
            helper.configureMusic(getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("music_visualizer_enabled", false));
        }
        startHeartbeat();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
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
        if (GuardRecovery.shouldRecover(this)) {
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
        if (clusterFullscreen != null) clusterFullscreen.shutdown();
        if (helper != null) helper.shutdown(!recover);
        helper = null;
        stopForegroundRuntime();
        if (recover) GuardRecovery.scheduleSoon(this);
        closeLogWriter();
        super.onDestroy();
    }

    private void ensureHelperStarted() {
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
        if (clusterFullscreen != null) clusterFullscreen.shutdown();
        if (helper != null) helper.shutdown(terminateShells);
        helper = null;
        stopForegroundRuntime();
        GuardRecovery.schedule(this);
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
                CHANNEL_ID, "BYD Turn Signal Guard", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, CameraProbeActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("BYD Turn Signal Guard")
                .setContentText("Guard service active")
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
        if (reverseCameras != null) reverseCameras.acceptEvent(line);
        if (clusterFullscreen != null) clusterFullscreen.acceptEvent(line);
        writeLine(line);
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
