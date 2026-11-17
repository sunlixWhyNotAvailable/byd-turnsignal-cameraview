package com.byd.extend;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import java.util.concurrent.*;

/** Keeps an explicitly requested app-only export alive when its Activity is closed. */
public final class LogShareService extends Service {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "log-share-status"));
    private volatile int latestStartId;
    @Override public void onCreate() {
        super.onCreate();
        android.content.Context localized = AppLanguage.localizedContext(this,
                AppLanguage.read(getSharedPreferences("settings", MODE_PRIVATE)));
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("log_share", localized.getString(R.string.runtime_logs_share_title), NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, CameraProbeActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 9201, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(9201, new Notification.Builder(this, "log_share")
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("BYD Extend")
                .setContentText(localized.getString(R.string.runtime_logs_progress)).setOngoing(true).setContentIntent(pending).build());
        worker.scheduleWithFixedDelay(() -> {
            int observedStartId = latestStartId;
            LogShareRuntime runtime = LogShareRuntime.get(this);
            runtime.refresh(false);
            if (!runtime.active() && observedStartId > 0) stopSelfResult(observedStartId);
        }, 1, 1, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { latestStartId = startId; return START_NOT_STICKY; }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
