package com.byd.extend;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

final class GuardRecovery {
    private static final String TAG = "BydExtendRecovery";
    static final String ACTION_WATCHDOG =
            "com.byd.extend.action.WATCHDOG";
    static final String ACTION_SHELL_RECOVERY =
            "com.byd.extend.action.SHELL_RECOVERY";
    static final String KEY_AUTO_START = "auto_start_enabled";
    static final String KEY_USER_SHUTDOWN = "user_shutdown_active";
    private static final String KEY_ACTIVE_BOOT = "active_boot_count";
    private static Integer processBootCount;
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
        cancelLegacyWatchdog(app, alarms);
        PendingIntent pending = pending(app);
        if (!shouldRecover(app) || LegacySettingsImporter.blocksRuntime(app)) {
            alarms.cancel(pending);
            return;
        }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pending);
    }

    static boolean startService(Context context, String reason) {
        if (!shouldRecover(context) || LegacySettingsImporter.blocksRuntime(context)) {
            Log.i(TAG, "recovery_gate_blocked reason=" + reason);
            AvasRecoveryJournal.event(context, "recovery_gate_blocked", "reason", reason);
            return false;
        }
        Log.i(TAG, "recovery_gate_passed reason=" + reason);
        AvasRecoveryJournal.event(context, "recovery_gate_passed", "reason", reason);
        try {
            CameraHelperService.startPersistent(context, reason);
            Log.i(TAG, "foreground_service_start_accepted reason=" + reason);
            AvasRecoveryJournal.event(context, "foreground_service_start_accepted",
                    "reason", reason);
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "foreground_service_start_failed reason=" + reason, error);
            AvasRecoveryJournal.event(context, "foreground_service_start_failed",
                    "reason", reason, "error", error.toString());
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
        return shouldRecover(isAutoStartEnabled(context), isUserShutdownActive(context),
                hasActiveSession(context));
    }

    static boolean shouldRecover(boolean autoStart, boolean userShutdown) {
        return shouldRecover(autoStart, userShutdown, false);
    }

    static boolean shouldRecover(boolean autoStart, boolean userShutdown, boolean activeSession) {
        return !userShutdown && (autoStart || activeSession);
    }

    static boolean hasActiveSession(Context context) {
        return hasActiveSession(sessionPreferences(context), bootCount(context));
    }

    static boolean hasActiveSession(SharedPreferences session, int bootCount) {
        return bootCount >= 0 && session.getInt(KEY_ACTIVE_BOOT, -1) == bootCount;
    }

    static void sessionStarted(Context context) {
        if (!isUserShutdownActive(context)) {
            setSessionActive(sessionPreferences(context), bootCount(context), true);
        }
    }

    static void setSessionActive(SharedPreferences session, int bootCount, boolean active) {
        if (active && bootCount >= 0) {
            if (!hasActiveSession(session, bootCount)) {
                session.edit().putInt(KEY_ACTIVE_BOOT, bootCount).commit();
            }
        } else if (session.contains(KEY_ACTIVE_BOOT)) {
            session.edit().remove(KEY_ACTIVE_BOOT).commit();
        }
    }

    private static SharedPreferences sessionPreferences(Context context) {
        // Session state is not a user setting and must never travel in an imported preset.
        return context.getSharedPreferences("runtime_recovery", Context.MODE_PRIVATE);
    }

    private static synchronized int bootCount(Context context) {
        if (processBootCount == null) {
            try {
                processBootCount = Settings.Global.getInt(
                        context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            } catch (RuntimeException ignored) {
                processBootCount = -1;
            }
        }
        return processBootCount;
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
        setSessionActive(sessionPreferences(context), bootCount(context), !active);
        schedule(context);
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, GuardWatchdogReceiver.class)
                .setAction(ACTION_WATCHDOG);
        return PendingIntent.getBroadcast(context, 8713, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelLegacyWatchdog(Context context, AlarmManager alarms) {
        Intent intent = new Intent().setClassName(context.getPackageName(),
                "com.byd.turnsignalguard.capture.GuardWatchdogReceiver")
                .setAction(ACTION_WATCHDOG);
        PendingIntent old = PendingIntent.getBroadcast(context, 8713, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (old != null) {
            alarms.cancel(old);
            old.cancel();
        }
    }
}
