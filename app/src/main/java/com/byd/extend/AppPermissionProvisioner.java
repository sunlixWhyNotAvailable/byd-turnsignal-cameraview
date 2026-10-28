package com.byd.extend;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.Build;
import android.provider.Settings;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/** Provisioning is independent of feature activation. Call only from an app worker. */
final class AppPermissionProvisioner {
    private AppPermissionProvisioner() {}

    static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasBluetoothCallAccess(Context context) {
        if ((Build.VERSION.SDK_INT < 31 || context.getApplicationInfo().targetSdkVersion <= 30)
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH)
                        != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
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
        boolean wssGranted = runProvisioning(
                () -> hasWriteSecureSettings(app),
                () -> applyAdbConnectionTimePolicy(app, reason, events),
                () -> run(app, "pm grant --user " + user + " " + app.getPackageName()
                        + " android.permission.WRITE_SECURE_SETTINGS", "wss", reason, events),
                () -> AvasNotificationAccess.ensureGranted(app, reason, events),
                () -> ensureAccessibility(app, user, reason, events));
        event(events, "app_permission_readback", "permission", "wss", "reason", reason,
                "granted", wssGranted);
        if (Build.VERSION.SDK_INT >= 31 && CallsRuntime.requiresBluetoothConnect(app)) {
            event(events, "app_permission_readback", "permission", "bluetooth_calls",
                    "reason", reason, "granted", hasBluetoothCallAccess(app));
        }
    }

    static boolean runProvisioning(BooleanSupplier hasWss, Runnable globalPolicy,
            Runnable wssRepair, Runnable notificationRepair, Runnable accessibilityRepair) {
        boolean granted = safeGet(hasWss);
        if (granted) runIndependent(globalPolicy);
        else runIndependent(wssRepair);
        boolean readback = safeGet(hasWss);
        if (!granted && readback) runIndependent(globalPolicy);
        runIndependent(notificationRepair);
        runIndependent(accessibilityRepair);
        return readback;
    }

    private static boolean safeGet(BooleanSupplier value) {
        try { return value.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    private static void runIndependent(Runnable action) {
        try { action.run(); }
        catch (RuntimeException ignored) { /* The remaining checks are independent. */ }
    }

    private static void applyAdbConnectionTimePolicy(Context app, String reason,
            BiConsumer<String, Object[]> events) {
        try {
            String key = "adb_allowed_connection_time";
            if (Settings.Global.getLong(app.getContentResolver(), key, -1L) != 0L) {
                Settings.Global.putLong(app.getContentResolver(), key, 0L);
            }
            event(events, "adb_connection_time_policy", "reason", reason,
                    "readback", Settings.Global.getLong(app.getContentResolver(), key, -1L));
        } catch (RuntimeException failure) {
            AdbRecoveryDiagnostics.failure(events, "adb_connection_time_policy_failed",
                    "settings_global_adb_allowed_connection_time", failure, "reason", reason);
        }
    }

    private static void ensureAccessibility(Context app, int user, String reason,
            BiConsumer<String, Object[]> events) {
        try {
            AccessibilitySettingsWriter.Result result = AccessibilitySettingsWriter.ensureEnabled(
                    new AccessibilitySettingsWriter.Access() {
                        @Override public String readServices() {
                            return Settings.Secure.getString(app.getContentResolver(),
                                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                        }

                        @Override public boolean writeServices(String value) {
                            return run(app, "settings --user " + user
                                    + " put secure enabled_accessibility_services " + quote(value),
                                    "accessibility", reason, events);
                        }

                        @Override public boolean enableAccessibility() {
                            if (Settings.Secure.getInt(app.getContentResolver(),
                                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1) return true;
                            if (!run(app, "settings --user " + user
                                    + " put secure accessibility_enabled 1",
                                    "accessibility", reason, events)) return false;
                            return Settings.Secure.getInt(app.getContentResolver(),
                                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
                        }
                    }, false, () -> false, () -> true,
                    event -> event(events, event, "reason", reason));
            event(events, "app_permission_readback", "permission", "accessibility",
                    "reason", reason, "granted", hasAccessibilityAccess(app),
                    "listed", result.listed, "ok", result.ok);
            event(events, "app_accessibility_binding_readback", "reason", reason,
                    "bound", WeatherRefreshAccessibilityService.isConnected());
        } catch (RuntimeException failure) {
            // A failed read must never turn into overwriting another app's service list.
            AdbRecoveryDiagnostics.failure(events, "app_permission_readback_failed",
                    "accessibility_readback", failure, "permission", "accessibility", "reason", reason);
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static boolean run(Context app, String command, String permission, String reason,
            BiConsumer<String, Object[]> events) {
        try {
            LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(app, command, events);
            boolean ok = result != null && result.ok;
            event(events, "app_permission_command", "permission", permission, "reason", reason,
                    "ok", ok);
            return ok;
        } catch (RuntimeException failure) {
            AdbRecoveryDiagnostics.failure(events, "app_permission_command_failed",
                    "permission_provision", failure, "permission", permission, "reason", reason);
            return false;
        }
    }

    private static void event(BiConsumer<String, Object[]> events, String kind, Object... fields) {
        if (events != null) events.accept(kind, fields);
    }
}
