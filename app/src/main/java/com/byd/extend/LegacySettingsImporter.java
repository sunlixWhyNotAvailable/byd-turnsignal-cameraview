package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

import java.util.Map;
import java.util.function.BiConsumer;

/** Reads compatible BYD Turn Signal settings once, then hands ownership to this package. */
final class LegacySettingsImporter {
    static final String LEGACY_PACKAGE = "com.byd.turnsignalguard.capture";
    static final String LEGACY_VERSION_NAME = "0.52.1";
    static final int LEGACY_VERSION_CODE = 96;
    static final String BRIDGE_VERSION_NAME = "0.52.2";
    static final int BRIDGE_VERSION_CODE = 97;
    static final long MAX_INPUT_BYTES = CameraSettingsTransfer.MAX_INPUT_BYTES;
    static final String PREF_HANDOVER_BLOCKED = "legacy_handover_blocked";
    static final String PREF_HANDOVER_COMPLETE = "legacy_handover_complete";
    static final String PREF_IMPORT_OFFER_HANDLED = "legacy_import_offer_handled";

    private static final String LEGACY_SETTINGS_FILE = "shared_prefs/settings.xml";
    private static final String LEGACY_READ_COMMAND =
            "run-as " + LEGACY_PACKAGE + " cat " + LEGACY_SETTINGS_FILE;
    private static final String LEGACY_FORCE_STOP_COMMAND =
            "am force-stop --user 0 " + LEGACY_PACKAGE;
    private static final String LEGACY_ENABLE_COMMAND =
            "pm enable --user 0 " + LEGACY_PACKAGE;
    private static final String LEGACY_TURN_HELPER = "bydturnguard_helper";
    private static final String LEGACY_CAMERA_HELPER = "bydturnguard_camera";
    private static final String LEGACY_HELPER_VERIFY_COMMAND =
            "if [ -n \"$(pidof " + LEGACY_TURN_HELPER + " 2>/dev/null)\" ] || [ -n \"$(pidof "
                    + LEGACY_CAMERA_HELPER + " 2>/dev/null)\" ]; then echo helper_process_running; false; fi";

    interface HandoverOps {
        boolean pause();

        /** Stops the legacy application while retaining its package and launcher access. */
        boolean stopLegacy();

        /** Enables the legacy launcher package for an explicit access-repair action. */
        boolean enableLegacy();

        boolean stopHelpers();

        /** Final force-stop after helper cleanup closes the recovery race. */
        boolean finalStopLegacy();

        /** Verifies enabled-but-stopped package state and fixed helper cleanup. */
        boolean verifyLegacyStopped();

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
        try {
            return settings.getBoolean(PREF_HANDOVER_BLOCKED, false);
        } catch (Throwable ignored) {
            // A preference read failure remains fail-closed; package state is not a runtime gate.
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
            public boolean stopLegacy() {
                if (!enableLegacyPackageIfNeeded(context, events)) return false;
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, LEGACY_FORCE_STOP_COMMAND, eventSink(events));
                if (!result.ok) {
                    emit(events, "legacy_handover_incomplete", "stage", "stop",
                            "error", result.error);
                }
                return result.ok;
            }

            @Override
            public boolean enableLegacy() {
                return enableLegacyPackageIfNeeded(context, events);
            }

            @Override
            public boolean stopHelpers() {
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, legacyHelperCleanupCommand(),
                        eventSink(events));
                if (!result.ok) {
                    emit(events, "legacy_handover_incomplete", "stage", "helpers",
                            "error", result.error);
                }
                return result.ok;
            }

            @Override
            public boolean finalStopLegacy() {
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, LEGACY_FORCE_STOP_COMMAND, eventSink(events));
                if (!result.ok) {
                    emit(events, "legacy_handover_incomplete", "stage", "final_stop",
                            "error", result.error);
                }
                return result.ok;
            }

            @Override
            public boolean verifyLegacyStopped() {
                if (legacyPackageState(context.getPackageManager()) != LegacyPackageState.ENABLED_STOPPED) {
                    emit(events, "legacy_handover_incomplete", "stage", "verify",
                            "error", "legacy_package_not_enabled_stopped");
                    return false;
                }
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, LEGACY_HELPER_VERIFY_COMMAND, eventSink(events));
                if (!result.ok) {
                    emit(events, "legacy_handover_incomplete", "stage", "verify",
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
        if (!ops.stopLegacy()) {
            throw fail(events, "legacy_handover_incomplete", "stage", "stop",
                    "error", "legacy package force-stop failed; staged settings retained");
        }
        if (!ops.stopHelpers()) {
            throw fail(events, "legacy_handover_incomplete", "stage", "helpers",
                    "error", "legacy helper cleanup failed; staged settings retained");
        }
        if (!ops.finalStopLegacy()) {
            throw fail(events, "legacy_handover_incomplete", "stage", "final_stop",
                    "error", "legacy package final force-stop failed; staged settings retained");
        }
        if (!ops.verifyLegacyStopped()) {
            throw fail(events, "legacy_handover_incomplete", "stage", "verify",
                    "error", "legacy package/helper stop verification failed; staged settings retained");
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

    static String legacyForceStopCommand() {
        return LEGACY_FORCE_STOP_COMMAND;
    }

    static String legacyEnableCommand() {
        return LEGACY_ENABLE_COMMAND;
    }

    /** Fixed old helper cleanup; do not derive these names from the renamed package. */
    static String legacyHelperCleanupCommand() {
        return "for pid in $(pidof " + LEGACY_TURN_HELPER
                + " 2>/dev/null); do kill \"$pid\" 2>/dev/null || true; done; "
                + "for pid in $(pidof " + LEGACY_CAMERA_HELPER
                + " 2>/dev/null); do kill \"$pid\" 2>/dev/null || true; done; "
                + "wait_count=0; while [ -n \"$(pidof " + LEGACY_TURN_HELPER
                + " 2>/dev/null)\" ] || [ -n \"$(pidof " + LEGACY_CAMERA_HELPER
                + " 2>/dev/null)\" ]; do [ \"$wait_count\" -lt 30 ] || break; "
                + "sleep 0.1; wait_count=$((wait_count + 1)); done; "
                + "if [ -n \"$(pidof " + LEGACY_TURN_HELPER
                + " 2>/dev/null)\" ] || [ -n \"$(pidof " + LEGACY_CAMERA_HELPER
                + " 2>/dev/null)\" ]; then echo helper_stop_timeout; false; fi";
    }

    static boolean isCompatibleLegacy(
            String versionName, long versionCode, boolean debuggable, boolean signatureMatch) {
        return (LEGACY_VERSION_NAME.equals(versionName) && versionCode == LEGACY_VERSION_CODE
                || BRIDGE_VERSION_NAME.equals(versionName) && versionCode == BRIDGE_VERSION_CODE)
                && debuggable && signatureMatch;
    }

    static boolean shouldOfferImport(boolean compatible, boolean handled, boolean complete) {
        return compatible && !handled && !complete;
    }

    static boolean hasCompatibleLegacy(Context context) {
        if (context == null) return false;
        try {
            requireLegacyPackage(context, false);
            return true;
        } catch (RuntimeException incompatible) {
            return false;
        }
    }

    static boolean isLegacyPackageEnabled(PackageManager manager) {
        LegacyPackageState state = legacyPackageState(manager);
        return state == LegacyPackageState.ENABLED_STOPPED
                || state == LegacyPackageState.ENABLED_RUNNING;
    }

    static boolean isLegacyPackageStopped(PackageManager manager) {
        return legacyPackageState(manager) == LegacyPackageState.ENABLED_STOPPED;
    }

    /** True when a completed import needs explicit access repair (including a retryable repair). */
    static boolean needsAccessRestore(Context context) {
        if (context == null) return false;
        SharedPreferences settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!settings.getBoolean(PREF_HANDOVER_COMPLETE, false)) return false;
        try {
            requireLegacyPackage(context, false);
            LegacyPackageState state = legacyPackageState(context.getPackageManager());
            if (state == LegacyPackageState.DISABLED) return true;
            // A failed repair can leave the temporary block set after enabling the package.
            return settings.getBoolean(PREF_HANDOVER_BLOCKED, false)
                    && (state == LegacyPackageState.ENABLED_STOPPED
                    || state == LegacyPackageState.ENABLED_RUNNING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Repairs a prior disablement without re-reading or replacing any settings. */
    static boolean restoreLegacyAccess(
            Context context, BiConsumer<String, Object[]> events) {
        requireContext(context);
        SharedPreferences settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!settings.getBoolean(PREF_HANDOVER_COMPLETE, false)) {
            emit(events, "legacy_access_restore_incomplete", "stage", "preflight",
                    "error", "completed handover marker is missing");
            return false;
        }
        try {
            requireLegacyPackage(context, false);
        } catch (Throwable error) {
            emit(events, "legacy_access_restore_incomplete", "stage", "validate",
                    "error", message(error));
            return false;
        }
        LegacyPackageState initialState = legacyPackageState(context.getPackageManager());
        if (initialState != LegacyPackageState.DISABLED
                && !(settings.getBoolean(PREF_HANDOVER_BLOCKED, false)
                && (initialState == LegacyPackageState.ENABLED_STOPPED
                || initialState == LegacyPackageState.ENABLED_RUNNING))) {
            emit(events, "legacy_access_restore_incomplete", "stage", "preflight",
                    "error", "legacy package is not awaiting access restore");
            return false;
        }
        return restoreLegacyAccess(settings, new HandoverOps() {
            @Override
            public boolean pause() {
                return CameraHelperService.pauseActiveRuntime();
            }

            @Override
            public boolean stopLegacy() {
                return finalStopLegacy();
            }

            @Override
            public boolean enableLegacy() {
                if (initialState != LegacyPackageState.DISABLED) return true;
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, LEGACY_ENABLE_COMMAND, eventSink(events));
                if (!result.ok || !isLegacyPackageEnabled(context.getPackageManager())) {
                    emit(events, "legacy_access_restore_incomplete", "stage", "enable",
                            "error", result.ok ? "package_still_disabled" : result.error);
                    return false;
                }
                return true;
            }

            @Override
            public boolean stopHelpers() {
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, legacyHelperCleanupCommand(), eventSink(events));
                if (!result.ok) {
                    emit(events, "legacy_access_restore_incomplete", "stage", "helpers",
                            "error", result.error);
                }
                return result.ok;
            }

            @Override
            public boolean finalStopLegacy() {
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, LEGACY_FORCE_STOP_COMMAND, eventSink(events));
                if (!result.ok) {
                    emit(events, "legacy_access_restore_incomplete", "stage", "final_stop",
                            "error", result.error);
                }
                return result.ok;
            }

            @Override
            public boolean verifyLegacyStopped() {
                if (legacyPackageState(context.getPackageManager()) != LegacyPackageState.ENABLED_STOPPED) {
                    emit(events, "legacy_access_restore_incomplete", "stage", "verify",
                            "error", "legacy package is not enabled and stopped");
                    return false;
                }
                LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                        context, LEGACY_HELPER_VERIFY_COMMAND, eventSink(events));
                if (!result.ok) {
                    emit(events, "legacy_access_restore_incomplete", "stage", "verify",
                            "error", result.error);
                }
                return result.ok;
            }
        }, events);
    }

    /** JVM seam for the confirmed repair flow; production callers use the Context overload. */
    static boolean restoreLegacyAccess(
            SharedPreferences settings, HandoverOps ops, BiConsumer<String, Object[]> events) {
        if (settings == null || ops == null) {
            emit(events, "legacy_access_restore_incomplete", "stage", "input",
                    "error", "settings and operations are required");
            return false;
        }
        if (!settings.getBoolean(PREF_HANDOVER_COMPLETE, false)) {
            emit(events, "legacy_access_restore_incomplete", "stage", "preflight",
                    "error", "completed handover marker is missing");
            return false;
        }
        if (!settings.edit().putBoolean(PREF_HANDOVER_BLOCKED, true).commit()) {
            emit(events, "legacy_access_restore_incomplete", "stage", "stage",
                    "error", "cannot stage access restore");
            return false;
        }
        emit(events, "legacy_access_restore_staged");
        if (!ops.pause()) {
            emit(events, "legacy_access_restore_incomplete", "stage", "pause",
                    "error", "active runtime did not pause");
            return false;
        }
        if (!ops.enableLegacy()) {
            emit(events, "legacy_access_restore_incomplete", "stage", "enable",
                    "error", "legacy package enable failed; staged block retained");
            return false;
        }
        if (!ops.stopHelpers()) {
            emit(events, "legacy_access_restore_incomplete", "stage", "helpers",
                    "error", "legacy helper cleanup failed; staged block retained");
            return false;
        }
        if (!ops.finalStopLegacy()) {
            emit(events, "legacy_access_restore_incomplete", "stage", "final_stop",
                    "error", "legacy package force-stop failed; staged block retained");
            return false;
        }
        if (!ops.verifyLegacyStopped()) {
            emit(events, "legacy_access_restore_incomplete", "stage", "verify",
                    "error", "legacy package/helper verification failed; staged block retained");
            return false;
        }
        if (!ops.finalizeSettings(settings)) {
            emit(events, "legacy_access_restore_incomplete", "stage", "finalize",
                    "error", "cannot clear access-restore block");
            return false;
        }
        emit(events, "legacy_access_restore_complete");
        return true;
    }

    enum LegacyPackageState {
        MISSING,
        DISABLED,
        ENABLED_STOPPED,
        ENABLED_RUNNING,
        UNKNOWN
    }

    private static boolean enableLegacyPackageIfNeeded(
            Context context, BiConsumer<String, Object[]> events) {
        if (legacyPackageState(context.getPackageManager()) != LegacyPackageState.DISABLED) {
            return true;
        }
        LocalAdbClient.Result result = LocalAdbClient.executeAuthorized(
                context, LEGACY_ENABLE_COMMAND, eventSink(events));
        if (!result.ok || !isLegacyPackageEnabled(context.getPackageManager())) {
            emit(events, "legacy_handover_incomplete", "stage", "enable",
                    "error", result.ok ? "package_still_disabled" : result.error);
            return false;
        }
        return true;
    }

    private static LegacyPackageState legacyPackageState(PackageManager manager) {
        if (manager == null) return LegacyPackageState.UNKNOWN;
        try {
            ApplicationInfo info = manager.getApplicationInfo(LEGACY_PACKAGE, 0);
            if (info == null) return LegacyPackageState.UNKNOWN;
            int enabledSetting = manager.getApplicationEnabledSetting(LEGACY_PACKAGE);
            boolean explicitlyDisabled = enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
            if (explicitlyDisabled || !info.enabled) return LegacyPackageState.DISABLED;
            return (info.flags & ApplicationInfo.FLAG_STOPPED) != 0
                    ? LegacyPackageState.ENABLED_STOPPED
                    : LegacyPackageState.ENABLED_RUNNING;
        } catch (PackageManager.NameNotFoundException missing) {
            return LegacyPackageState.MISSING;
        } catch (Throwable ignored) {
            return LegacyPackageState.UNKNOWN;
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
