package com.byd.extend;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;
import java.util.function.BiConsumer;

/** Provisioning is independent of feature activation. Call only from an app worker. */
final class AppPermissionProvisioner {
    private AppPermissionProvisioner() {}

    static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Native app-UID readback works even while ADB is unavailable. Binding is separate. */
    static boolean hasAccessibilityAccess(Context context) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                    && WeatherAccessibilitySettings.hasOwnService(Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static synchronized void ensure(Context context, String reason,
            BiConsumer<String, Object[]> events) {
        Context app = context.getApplicationContext();
        int user = AvasNotificationAccess.userIdForUid(Process.myUid());
        if (!hasWriteSecureSettings(app)) {
            run(app, "pm grant --user " + user + " " + app.getPackageName()
                    + " android.permission.WRITE_SECURE_SETTINGS", "wss", reason, events);
        }
        event(events, "app_permission_readback", "permission", "wss", "reason", reason,
                "granted", hasWriteSecureSettings(app));
        if (hasWriteSecureSettings(app)) {
            try {
                String key = "adb_allowed_connection_time";
                if (Settings.Global.getLong(app.getContentResolver(), key, -1L) != 0L) {
                    Settings.Global.putLong(app.getContentResolver(), key, 0L);
                }
                event(events, "adb_connection_time_policy", "reason", reason,
                        "readback", Settings.Global.getLong(app.getContentResolver(), key, -1L));
            } catch (RuntimeException failure) {
                event(events, "adb_connection_time_policy_failed", "reason", reason,
                        "error", failure.getClass().getSimpleName());
            }
        }
        AvasNotificationAccess.ensureGranted(app, reason, events);
        ensureAccessibility(app, user, reason, events);
    }

    private static void ensureAccessibility(Context app, int user, String reason,
            BiConsumer<String, Object[]> events) {
        try {
            String current = Settings.Secure.getString(app.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            // Never remove/re-add an already listed service merely to provision access.
            if (!WeatherAccessibilitySettings.hasOwnService(current)) {
                String updated = appendAccessibility(current);
                run(app, "settings --user " + user + " put secure enabled_accessibility_services "
                        + quote(updated), "accessibility", reason, events);
            }
            if (Settings.Secure.getInt(app.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                run(app, "settings --user " + user + " put secure accessibility_enabled 1",
                        "accessibility", reason, events);
            }
            event(events, "app_permission_readback", "permission", "accessibility",
                    "reason", reason, "granted", hasAccessibilityAccess(app),
                    "bound", WeatherRefreshAccessibilityService.isConnected());
        } catch (RuntimeException failure) {
            // A failed read must never turn into overwriting another app's service list.
            event(events, "app_permission_readback_failed", "permission", "accessibility",
                    "reason", reason, "error", failure.getClass().getSimpleName());
        }
    }

    static String appendAccessibility(String current) {
        if (WeatherAccessibilitySettings.hasOwnService(current)) return current;
        String value = current == null || "null".equals(current.trim()) ? "" : current;
        return value.isEmpty() ? WeatherAccessibilitySettings.SERVICE_COMPONENT
                : value + ":" + WeatherAccessibilitySettings.SERVICE_COMPONENT;
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static void run(Context app, String command, String permission, String reason,
            BiConsumer<String, Object[]> events) {
        try {
            LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(app, command, events);
            event(events, "app_permission_command", "permission", permission, "reason", reason,
                    "ok", result != null && result.ok);
        } catch (RuntimeException failure) {
            event(events, "app_permission_command_failed", "permission", permission,
                    "reason", reason, "error", failure.getClass().getSimpleName());
        }
    }

    private static void event(BiConsumer<String, Object[]> events, String kind, Object... fields) {
        if (events != null) events.accept(kind, fields);
    }
}
