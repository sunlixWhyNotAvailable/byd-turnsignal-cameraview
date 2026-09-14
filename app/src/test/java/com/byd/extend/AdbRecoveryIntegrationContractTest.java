package com.byd.extend;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Bounded native/UI wiring checks supplement the executable coordinator and geometry tests. */
public final class AdbRecoveryIntegrationContractTest {
    @Test public void reminderEditorHasLocalRetryToggleWithoutChangingRuntimeGates() throws Exception {
        String screen = source("kotlin/com/byd/extend/ui/AdbRecoveryScreen.kt");
        String editor = screen.substring(screen.indexOf("private fun AdbReminderEditor("));
        assertTrue(editor.contains("var showRetry by rememberSaveable { mutableStateOf(false) }"));
        assertTrue(editor.contains("Show expanding Retry action (editor)"));
        assertTrue(editor.contains("\"\", showRetry, { showRetry = it }, colors"));
        assertTrue(editor.contains("logicalWidth, showRetry, onChange"));
        assertTrue(editor.contains("AnimatedVisibility(showRetry,"));
        assertFalse(editor.contains("AnimatedVisibility(true,"));
        assertFalse(editor.contains("AdbRecoveryUiAction.Retry"));
        assertTrue(screen.contains("enabled = state.enabled && !state.authenticated5555"));
    }

    @Test public void appRecoveryStartsBeforeDependentHelperAndKeepsItsOwnObserver() throws Exception {
        String service = source("java/com/byd/extend/CameraHelperService.java");
        String handle = service.substring(service.indexOf("private void handleStartCommand("),
                service.indexOf("private void stopServiceFromRuntime("));
        assertTrue(handle.indexOf("adbRecovery.startOrReconfigure(")
                < handle.indexOf("ensureHelperStarted()"));
        assertTrue(service.contains("LocalAdbClient.addAccessStateListener(serviceAdbListener)"));
        assertTrue(service.contains("LocalAdbClient.removeAccessStateListener(serviceAdbListener)"));
        assertFalse(service.contains("LocalAdbClient.setAccessStateListener("));
        assertTrue(service.contains("AppPermissionProvisioner.ensure(this, reason"));
        assertTrue(service.contains("if (!enabled) return true;"));
        assertFalse(service.contains("execute(() -> applyWeatherAccessibility(false"));
    }

    @Test public void nativeRightsReadbackAndNewActionsAreWiredWithoutPublicIpc() throws Exception {
        String activity = source("java/com/byd/extend/CameraProbeActivity.java");
        assertTrue(activity.contains("AppPermissionProvisioner.hasWriteSecureSettings(this)"));
        assertTrue(activity.contains("AppPermissionProvisioner.hasAccessibilityAccess(this)"));
        assertTrue(activity.contains("AvasNotificationAccess.isGranted(this)"));
        assertTrue(activity.contains("CameraHelperService.retryAdbWifi(this)"));
        assertTrue(activity.contains("CameraHelperService.removeAdbRecoveryListener"));
    }

    @Test public void settingsOperationDetailsAreLoggedWithoutHidingBusyState() throws Exception {
        String activity = source("java/com/byd/extend/CameraProbeActivity.java");
        assertTrue(activity.contains("diagnosticOnly ? \"\" : localized"));
        assertTrue(activity.contains("!diagnosticOnly), pending, claimFeedback"));
        for (String message : new String[]{"runtime_logs_failed", "runtime_share_menu_failed",
                "runtime_preset_saved", "runtime_preset_loaded_short", "runtime_car_compat_failed"}) {
            assertFalse(activity.contains("Toast.makeText(this, runtimeText(R.string." + message));
        }
        assertTrue(activity.contains("record(\"settings_operation\""));
        String exporter = source("java/com/byd/extend/DiagnosticLogExporter.java");
        assertTrue(exporter.contains("avas-recovery-journal.jsonl"));
    }

    private static String source(String relative) throws Exception {
        Path path = Path.of("app/src/main", relative);
        if (!Files.exists(path)) path = Path.of("src/main", relative);
        return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }
}
