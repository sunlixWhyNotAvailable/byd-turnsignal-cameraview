package com.byd.turnsignalguard.capture;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

final class GuardRecovery {
    private static final String TAG = "BydTurnGuardRecovery";
    static final String ACTION_WATCHDOG =
            "com.byd.turnsignalguard.capture.action.WATCHDOG";
    static final String ACTION_SHELL_RECOVERY =
            "com.byd.turnsignalguard.capture.action.SHELL_RECOVERY";
    static final String KEY_AUTO_START = "auto_start_enabled";
    static final String KEY_USER_SHUTDOWN = "user_shutdown_active";
    private static final long WATCHDOG_MS = 60_000;
    private static final long RECOVERY_SOON_MS = 5_000;
    private static final long STALE_MS = 90_000;

    private GuardRecovery() {}

    static void heartbeat(Context context) {
        Context app = context.getApplicationContext();
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putLong("service_heartbeat_ms", SystemClock.elapsedRealtime()).apply();
        schedule(app);
    }

    static boolean stale(Context context) {
        long heartbeat = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getLong("service_heartbeat_ms", 0);
        return stale(SystemClock.elapsedRealtime(), heartbeat);
    }

    static boolean stale(long now, long heartbeat) {
        return heartbeat <= 0 || heartbeat > now || now - heartbeat > STALE_MS;
    }

    static void schedule(Context context) {
        schedule(context, WATCHDOG_MS);
    }

    static void scheduleSoon(Context context) {
        schedule(context, RECOVERY_SOON_MS);
    }

    private static void schedule(Context context, long delayMs) {
        Context app = context.getApplicationContext();
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent pending = pending(app);
        if (!shouldRecover(app)) {
            alarms.cancel(pending);
            return;
        }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pending);
    }

    static boolean startService(Context context, String reason) {
        if (!shouldRecover(context)) {
            Log.i(TAG, "recovery_gate_blocked reason=" + reason);
            return false;
        }
        Log.i(TAG, "recovery_gate_passed reason=" + reason);
        try {
            CameraHelperService.startPersistent(context, reason);
            Log.i(TAG, "foreground_service_start_accepted reason=" + reason);
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "foreground_service_start_failed reason=" + reason, error);
            return false;
        }
    }

    static boolean isAutoStartEnabled(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, true);
    }

    static boolean isUserShutdownActive(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_USER_SHUTDOWN, false);
    }

    static boolean shouldRecover(Context context) {
        return shouldRecover(isAutoStartEnabled(context), isUserShutdownActive(context));
    }

    static boolean shouldRecover(boolean autoStart, boolean userShutdown) {
        return autoStart && !userShutdown;
    }

    static boolean shouldAttemptWatchdogRecovery(
            boolean recoveryAllowed, boolean heartbeatStale) {
        return recoveryAllowed && heartbeatStale;
    }

    static void setAutoStartEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_START, enabled).commit();
        schedule(context);
    }

    static void setUserShutdownActive(Context context, boolean active) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_USER_SHUTDOWN, active).commit();
        schedule(context);
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, GuardWatchdogReceiver.class)
                .setAction(ACTION_WATCHDOG);
        return PendingIntent.getBroadcast(context, 8713, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
