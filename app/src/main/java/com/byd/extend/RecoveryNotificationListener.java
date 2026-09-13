package com.byd.extend;

import android.service.notification.NotificationListenerService;

/** System-managed AVAS recovery entry point. Notification contents are intentionally never read. */
public final class RecoveryNotificationListener extends NotificationListenerService {
    private long createdElapsedMs;

    @Override
    public void onCreate() {
        super.onCreate();
        createdElapsedMs = android.os.SystemClock.elapsedRealtime();
        AvasRecoveryJournal.event(this, "notification_listener_create");
        recover("listener_create");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        AvasRecoveryJournal.event(this, "notification_listener_connected",
                "lifetime_ms", lifetimeMs());
        recover("listener_connected");
    }

    @Override
    public void onListenerDisconnected() {
        AvasRecoveryJournal.event(this, "notification_listener_disconnected",
                "lifetime_ms", lifetimeMs());
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        AvasRecoveryJournal.event(this, "notification_listener_destroy",
                "lifetime_ms", lifetimeMs());
        super.onDestroy();
    }

    private void recover(String reason) {
        boolean needed = GuardRecovery.shouldRecover(this)
                && AvasNotificationAccess.hasEnabledProfiles(
                        getSharedPreferences("settings", MODE_PRIVATE));
        boolean accepted = needed && GuardRecovery.startService(this, reason);
        AvasRecoveryJournal.event(this, "notification_listener_recovery",
                "reason", reason, "needed", needed, "accepted", accepted);
    }

    private long lifetimeMs() {
        long now = android.os.SystemClock.elapsedRealtime();
        return createdElapsedMs <= 0L ? 0L : Math.max(0L, now - createdElapsedMs);
    }
}
