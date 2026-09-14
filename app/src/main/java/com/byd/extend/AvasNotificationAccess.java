package com.byd.extend;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import java.util.function.BiConsumer;

/** Exact, read-back-verified access for the AVAS recovery notification listener. */
final class AvasNotificationAccess {
    static final String LISTENER_CLASS = "com.byd.extend.RecoveryNotificationListener";

    private AvasNotificationAccess() {}

    static boolean required(SharedPreferences preferences) {
        if (preferences == null) return false;
        try {
            return preferences.getBoolean(GuardRecovery.KEY_AUTO_START, true)
                    && (hasEnabledProfiles(preferences)
                        || preferences.getBoolean("adb_recovery_enabled", true));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Reads the stored AVAS JSON only; it never initializes or rewrites the configuration. */
    static boolean hasEnabledProfiles(SharedPreferences preferences) {
        if (preferences == null) return false;
        try {
            String stored = preferences.getString(AvasAudioLibrary.PREF_CONFIG, "");
            if (stored == null || stored.isEmpty()) return false;
            for (AvasConfig.Profile profile : AvasConfig.parse(stored).profiles) {
                if (profile.enabled) return true;
            }
        } catch (RuntimeException ignored) {
            // Invalid configuration is handled by its existing owner and must not be reset here.
        }
        return false;
    }

    static boolean isGranted(Context context) {
        if (context == null) return false;
        try {
            return readGranted(context.getApplicationContext());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean ensureGranted(
            Context context, String reason, BiConsumer<String, Object[]> events) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        ComponentName listener = listener(app);
        int userId = userIdForUid(Process.myUid());
        return ensureGranted(true, userId,
                listener.flattenToString(), reason, events,
                () -> readGranted(app),
                command -> LocalAdbClient.executeAuthorized(app, command, events));
    }

    static boolean exactComponentEnabled(String enabledListeners, String exactComponent) {
        String expected = normalizedComponent(exactComponent);
        if (expected == null || enabledListeners == null || enabledListeners.isEmpty()) return false;
        for (String value : enabledListeners.split(":")) {
            if (expected.equals(normalizedComponent(value))) return true;
        }
        return false;
    }

    static boolean grantedForApi(
            int sdk, boolean frameworkGranted, String enabledListeners, String exactComponent) {
        return sdk >= 27 ? frameworkGranted
                : exactComponentEnabled(enabledListeners, exactComponent);
    }

    static String grantCommand(int userId, String exactComponent) {
        return "cmd notification allow_listener " + exactComponent + " " + userId;
    }

    static int userIdForUid(int uid) {
        return uid < 0 ? 0 : uid / 100_000;
    }

    private static String normalizedComponent(String value) {
        if (value == null) return null;
        int slash = value.indexOf('/');
        if (slash <= 0 || slash != value.lastIndexOf('/') || slash == value.length() - 1) {
            return null;
        }
        String packageName = value.substring(0, slash);
        String className = value.substring(slash + 1);
        if (className.startsWith(".")) className = packageName + className;
        return packageName + "/" + className;
    }

    private static boolean readGranted(Context context) {
        ComponentName listener = listener(context);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(listener);
        }
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        return exactComponentEnabled(enabled, listener.flattenToString());
    }

    private static ComponentName listener(Context context) {
        return new ComponentName(context.getPackageName(), LISTENER_CLASS);
    }

    static boolean ensureGranted(
            boolean accessRequired,
            int userId,
            String exactComponent,
            String reason,
            BiConsumer<String, Object[]> events,
            Readback readback,
            CommandRunner commandRunner) {
        if (!accessRequired) return true;
        Boolean before = read(readback, reason, "before", events);
        if (Boolean.TRUE.equals(before)) return true;

        event(events, "avas_notification_access_requested",
                "reason", safe(reason), "user", userId, "component", exactComponent);
        LocalAdbClient.Result result = null;
        String commandError = "";
        try {
            result = commandRunner.run(grantCommand(userId, exactComponent));
            if (result == null) commandError = "missing_result";
        } catch (Throwable error) {
            commandError = error.getClass().getSimpleName();
        }
        event(events, "avas_notification_access_result",
                "reason", safe(reason), "command_ok", result != null && result.ok,
                "exit_code", result == null ? -1 : result.exitCode,
                "error", result == null ? commandError : safe(result.error));

        Boolean after = read(readback, reason, "after", events);
        boolean granted = Boolean.TRUE.equals(after);
        event(events, "avas_notification_access_readback",
                "reason", safe(reason), "granted", granted);
        return granted;
    }

    private static Boolean read(Readback readback, String reason, String stage,
            BiConsumer<String, Object[]> events) {
        try {
            return readback.read();
        } catch (Throwable error) {
            event(events, "avas_notification_access_readback_error",
                    "reason", safe(reason), "stage", stage,
                    "error", error.getClass().getSimpleName());
            return null;
        }
    }

    private static void event(BiConsumer<String, Object[]> events, String name, Object... fields) {
        if (events != null) events.accept(name, fields);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    interface Readback {
        boolean read();
    }

    interface CommandRunner {
        LocalAdbClient.Result run(String command);
    }
}
