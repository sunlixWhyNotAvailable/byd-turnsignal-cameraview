package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

import java.util.Map;
import java.util.function.BiConsumer;

/** Reads the installed 0.52.1 settings once, then hands ownership to this package. */
final class LegacySettingsImporter {
    static final String LEGACY_PACKAGE = "com.byd.turnsignalguard.capture";
    static final String LEGACY_VERSION_NAME = "0.52.1";
    static final int LEGACY_VERSION_CODE = 96;
    static final long MAX_INPUT_BYTES = CameraSettingsTransfer.MAX_INPUT_BYTES;
    static final String PREF_HANDOVER_BLOCKED = "legacy_handover_blocked";
    static final String PREF_HANDOVER_COMPLETE = "legacy_handover_complete";

    private static final String LEGACY_SETTINGS_FILE = "shared_prefs/settings.xml";
    private static final String LEGACY_READ_COMMAND =
            "run-as " + LEGACY_PACKAGE + " cat " + LEGACY_SETTINGS_FILE;
    private static final String LEGACY_DISABLE_COMMAND =
            "pm disable-user --user 0 " + LEGACY_PACKAGE;

    interface HandoverOps {
        boolean pause();
        boolean disable();
        boolean stopHelpers();

        default boolean finalizeSettings(SharedPreferences settings) {
            return settings.edit()
                    .putBoolean(PREF_HANDOVER_COMPLETE, true)
                    .putBoolean(PREF_HANDOVER_BLOCKED, false)
                    .commit();
        }
    }

    private LegacySettingsImporter() {}

    static boolean blocksRuntime(Context context) {
        if (context == null) return true;
        SharedPreferences settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (settings.getBoolean(PREF_HANDOVER_BLOCKED, false)) return true;
        try {
            return isLegacyPackageEnabled(context.getPackageManager());
        } catch (Throwable ignored) {
            // Unknown package state is unsafe: do not start a competing singleton runtime.
            return true;
        }
    }

    static Map<String, Object> readSettings(
            Context context, BiConsumer<String, Object[]> events) {
        requireContext(context);
        SharedPreferences settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        try {
            requireLegacyPackage(context, false);
        } catch (RuntimeException error) {
            throw fail(events, "legacy_read_failed", "stage", "validate",
                    "error", message(error));
        }
        LocalAdbClient.Result authorization = LocalAdbClient.authorize(
                context, LocalAdbClient.PromptMode.FORCE, eventSink(events));
        if (!authorization.ok) {
            throw fail(events, "legacy_read_failed", "stage", "adb_authorize",
                    "error", authorization.error,
                    "authorization_required", authorization.authorizationRequired);
        }
        LocalAdbClient.Result result = LocalAdbClient.executeAuthorizedText(
                context, LEGACY_READ_COMMAND, MAX_INPUT_BYTES, eventSink(events));
        if (!result.ok) {
            throw fail(events, "legacy_read_failed", "stage", "settings_xml",
                    "error", result.error);
        }
        try {
            Map<String, Object> values = CameraSettingsTransfer.parseLegacySettings(result.output);
            if (values == null) throw new IllegalArgumentException("legacy settings are null");
            emit(events, "legacy_read_complete", "keys", values.size());
            return values;
        } catch (Throwable error) {
            throw fail(events, "legacy_read_failed", "stage", "parse",
                    "error", message(error));
        }
    }

    static void applyAndHandover(
            Context context,
            Map<String, Object> values,
            BiConsumer<String, Object[]> events) {
        requireContext(context);
        if (values == null) {
            throw fail(events, "legacy_handover_failed", "stage", "input",
                    "error", "settings are null");
        }
        try {
            requireLegacyPackage(context, false);
        } catch (RuntimeException error) {
            throw fail(events, "legacy_handover_failed", "stage", "validate",
                    "error", message(error));
        }
        SharedPreferences settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        applyAndHandover(settings, values, new HandoverOps() {
            @Override
            public boolean pause() {
                return CameraHelperService.pauseActiveRuntime();
            }

            @Override
            public boolean disable() {
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, LEGACY_DISABLE_COMMAND, eventSink(events));
                boolean disabled = result.ok && !isLegacyPackageEnabled(context.getPackageManager());
                if (!disabled) {
                    emit(events, "legacy_handover_incomplete", "stage", "disable",
                            "error", result.ok ? "package_still_enabled" : result.error);
                }
                return disabled;
            }

            @Override
            public boolean stopHelpers() {
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, TurnSignalController.stopKnownHelpersCommand(),
                        eventSink(events));
                if (!result.ok) {
                    emit(events, "legacy_handover_incomplete", "stage", "helpers",
                            "error", result.error);
                }
                return result.ok;
            }
        }, events);
    }

    static void applyAndHandover(
            SharedPreferences settings,
            Map<String, Object> values,
            HandoverOps ops,
            BiConsumer<String, Object[]> events) {
        if (settings == null || values == null || ops == null) {
            throw fail(events, "legacy_handover_failed", "stage", "input",
                    "error", "settings, values and operations are required");
        }
        if (!settings.edit().putBoolean(PREF_HANDOVER_BLOCKED, true).commit()) {
            throw fail(events, "legacy_handover_failed", "stage", "stage",
                    "error", "cannot stage handover");
        }
        emit(events, "legacy_handover_staged", "keys", values.size());
        if (!ops.pause()) {
            throw fail(events, "legacy_handover_incomplete", "stage", "pause",
                    "error", "active runtime did not pause");
        }
        try {
            CameraSettingsTransfer.applyLegacySettings(settings, values);
        } catch (Throwable error) {
            throw fail(events, "legacy_handover_failed", "stage", "apply",
                    "error", message(error));
        }
        if (!ops.disable()) {
            throw fail(events, "legacy_handover_incomplete", "stage", "disable",
                    "error", "legacy package disable failed; staged settings retained");
        }
        if (!ops.stopHelpers()) {
            throw fail(events, "legacy_handover_incomplete", "stage", "helpers",
                    "error", "legacy helper cleanup failed; staged settings retained");
        }
        if (!ops.finalizeSettings(settings)) {
            throw fail(events, "legacy_handover_incomplete", "stage", "finalize",
                    "error", "cannot finalize handover");
        }
        emit(events, "legacy_handover_complete", "keys", values.size());
    }

    static String legacyReadCommandForTest() {
        return LEGACY_READ_COMMAND;
    }

    static String legacyDisableCommandForTest() {
        return LEGACY_DISABLE_COMMAND;
    }

    static boolean isCompatibleLegacy(
            String versionName, long versionCode, boolean debuggable, boolean signatureMatch) {
        return LEGACY_VERSION_NAME.equals(versionName)
                && versionCode == LEGACY_VERSION_CODE
                && debuggable && signatureMatch;
    }

    static boolean isLegacyPackageEnabled(PackageManager manager) {
        try {
            ApplicationInfo info = manager.getApplicationInfo(LEGACY_PACKAGE, 0);
            int state = manager.getApplicationEnabledSetting(LEGACY_PACKAGE);
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                    && info.enabled;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    private static void requireContext(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
    }

    private static void requireLegacyPackage(Context context, boolean enabled) {
        requirePrimaryUser(isPrimaryUser());
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo legacy = manager.getPackageInfo(LEGACY_PACKAGE,
                    PackageManager.GET_SIGNATURES);
            ApplicationInfo app = legacy.applicationInfo;
            boolean metadataOk = isCompatibleLegacy(
                    legacy.versionName, versionCode(legacy), app != null
                            && (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0, true);
            boolean signatureOk = manager.checkSignatures(
                    context.getPackageName(), LEGACY_PACKAGE) == PackageManager.SIGNATURE_MATCH;
            if (!metadataOk || !signatureOk) {
                throw new IllegalStateException("legacy package is not compatible");
            }
            if (enabled && !isLegacyPackageEnabled(manager)) {
                throw new IllegalStateException("legacy package is disabled");
            }
        } catch (PackageManager.NameNotFoundException missing) {
            throw new IllegalStateException("legacy package is not installed");
        }
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    static void requirePrimaryUser(boolean primaryUser) {
        if (!primaryUser) {
            throw new IllegalStateException("legacy import is only supported for the primary Android user");
        }
    }

    private static boolean isPrimaryUser() {
        try {
            Class<?> userHandle = Class.forName("android.os.UserHandle");
            return ((Integer) userHandle.getMethod("myUserId").invoke(null)) == 0;
        } catch (Throwable error) {
            // Android's per-user UID range keeps primary-user app UIDs below 100000.
            return Process.myUid() >= 0 && Process.myUid() < 100000;
        }
    }

    private static BiConsumer<String, Object[]> eventSink(BiConsumer<String, Object[]> events) {
        return events == null ? (kind, fields) -> {} : events;
    }

    private static void emit(BiConsumer<String, Object[]> events, String kind, Object... fields) {
        if (events != null) events.accept(kind, fields);
    }

    private static IllegalStateException fail(
            BiConsumer<String, Object[]> events, String kind, Object... fields) {
        emit(events, kind, fields);
        String stage = "unknown";
        String error = kind;
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if ("stage".equals(String.valueOf(fields[i]))) {
                stage = String.valueOf(fields[i + 1]);
            } else if ("error".equals(String.valueOf(fields[i]))) {
                error = String.valueOf(fields[i + 1]);
            }
        }
        return new IllegalStateException(kind + " stage=" + stage + ": " + error);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
