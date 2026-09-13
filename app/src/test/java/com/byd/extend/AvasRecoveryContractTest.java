package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AvasRecoveryContractTest {
    @Test
    public void manifestDeclaresWakeAndExactListenerService() throws Exception {
        String manifest = source("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"));
        assertTrue(manifest.contains("com.byd.extend.RecoveryNotificationListener"));
        assertTrue(manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"));
        assertTrue(manifest.contains(
                "android.service.notification.NotificationListenerService"));
    }

    @Test
    public void listenerNeverReadsNotificationContentsAndUsesGatedRecovery() throws Exception {
        String listener = source(
                "app/src/main/java/com/byd/extend/RecoveryNotificationListener.java");
        assertTrue(listener.contains("onCreate()"));
        assertTrue(listener.contains("onListenerConnected()"));
        assertTrue(listener.contains("onListenerDisconnected()"));
        assertTrue(listener.contains("GuardRecovery.startService"));
        assertTrue(listener.contains("hasEnabledProfiles"));
        assertFalse(listener.contains("onNotificationPosted"));
        assertFalse(listener.contains("StatusBarNotification"));
    }

    @Test
    public void daemonIsFixedUidRestrictedNonInteractiveAndProviderBacked() throws Exception {
        String daemon = source(
                "app/src/main/java/com/byd/extend/AvasRecoveryShellMain.java");
        assertTrue(daemon.contains("Process.myUid() != 2000"));
        assertTrue(daemon.contains("Application identity mismatch"));
        assertFalse(daemon.contains("createContextAsUser("));
        assertFalse(daemon.contains("UserHandle.of("));
        assertTrue(daemon.contains("PackageManager.class.getMethod("));
        assertTrue(daemon.contains(
                "\"getPackageInfoAsUser\", String.class, int.class, int.class"));
        assertTrue(daemon.contains("AvasRecoveryPolicy.userIdForUid(expectedUid)"));
        int identityCheck = daemon.indexOf(
                "installedIdentityMatches(binder.context, binder.appUid");
        int broadcast = daemon.indexOf("new ProcessBuilder(", identityCheck);
        assertTrue(identityCheck >= 0);
        assertTrue(broadcast > identityCheck);
        String identityGate = daemon.substring(identityCheck, broadcast);
        assertTrue(identityGate.contains(
                "settings.putInt(AvasRecoveryDaemonProtocol.ENABLED_SETTING, 0)"));
        assertTrue(identityGate.contains("installation_disable_failed"));
        assertTrue(identityGate.contains("break;"));
        assertTrue(daemon.contains("isCallerAllowed(Binder.getCallingUid(), appUid)"));
        assertTrue(daemon.contains("Binder.clearCallingIdentity()"));
        assertTrue(daemon.contains("Binder.restoreCallingIdentity(identity)"));
        assertTrue(daemon.contains("new AvasShellSettings(context)"));
        assertTrue(daemon.contains("GuardRecovery.ACTION_SHELL_RECOVERY"));
        assertTrue(daemon.contains("\"broadcast\", \"--user\""));
        assertTrue(daemon.contains(
                "Integer.toString(AvasRecoveryPolicy.userIdForUid(binder.appUid))"));
        assertTrue(daemon.contains("Thread.sleep(Math.min(remaining, 1_000L))"));
        assertFalse(daemon.contains("isInteractive"));
        assertFalse(daemon.contains("POWER_FID"));
        assertFalse(daemon.contains("LOCK_FID"));
        assertFalse(daemon.contains("Settings.Global"));
    }

    @Test
    public void launchIsNoHupSingletonIdentityBoundAndReadinessChecked() throws Exception {
        String command = AvasRecoveryDaemonController.launchCommand(
                "/data/app/example/base.apk", 10123, "/data/app/example/base.apk:99");
        assertTrue(command.contains("trap '' HUP"));
        assertTrue(command.contains("setsid app_process"));
        assertTrue(command.contains(AvasRecoveryDaemonProtocol.LOCK_PATH));
        assertTrue(command.contains(AvasRecoveryDaemonProtocol.SERVICE_NAME));
        assertTrue(command.contains("service list"));
        assertTrue(command.contains("/data/app/example/base.apk:99"));
        assertTrue(command.contains("</dev/null"));

        String controller = source(
                "app/src/main/java/com/byd/extend/AvasRecoveryDaemonController.java");
        assertTrue(controller.contains("lastUpdateTime"));
        assertTrue(controller.contains("FAILURE_BACKOFF_MS = 30_000L"));
        assertTrue(controller.contains("isCurrent(true, generation)"));
    }

    @Test
    public void serviceUsesOneThirtySecondReconcileAndExistingSixtySecondAlarm()
            throws Exception {
        String service = source(
                "app/src/main/java/com/byd/extend/CameraHelperService.java");
        assertTrue(service.contains("runtimeHandler.postDelayed(this, 30_000)"));
        assertTrue(service.contains("reconcileAvasRecovery(false, \"service_heartbeat\")"));
        assertTrue(service.contains("activeHelper.configureAvas()"));
        assertTrue(service.contains("PowerManager.PARTIAL_WAKE_LOCK"));
        assertTrue(service.contains("avasWakeLock.acquire()"));
        assertFalse(service.contains("isInteractive"));

        String recovery = source("app/src/main/java/com/byd/extend/GuardRecovery.java");
        assertTrue(recovery.contains("WATCHDOG_MS = 60_000"));
    }

    @Test
    public void daemonActionStartProvisioningIsOncePerServiceInstance() throws Exception {
        String service = source(
                "app/src/main/java/com/byd/extend/CameraHelperService.java");
        assertTrue(service.contains("boolean avasStartupPermissionAttempted;"));
        int claim = service.indexOf("boolean avasStartupPermissionTrigger =");
        int reconcile = service.indexOf(
                "reconcileAvasRecovery(avasStartupPermissionTrigger", claim);
        assertTrue(claim >= 0);
        assertTrue(reconcile > claim);
        String claimBlock = service.substring(claim, reconcile);
        assertTrue(claimBlock.contains("ACTION_START.equals(action)"));
        assertTrue(claimBlock.contains("!avasStartupPermissionAttempted"));
        assertTrue(claimBlock.contains(
                "if (avasStartupPermissionTrigger) avasStartupPermissionAttempted = true;"));
        assertTrue(service.contains("reconcileAvasRecovery(avasStartupPermissionTrigger"));
        assertTrue(service.contains("reconcileAvasRecovery(false, \"service_heartbeat\")"));
        assertTrue(service.contains("|| ACTION_ACTIVITY_OPEN.equals(action)"));
        assertTrue(service.contains("|| ACTION_AVAS_CONFIGURE.equals(action)"));
        assertTrue(service.contains("ACTION_AUTO_START_CHANGED.equals(action) && command.enabled"));
        assertTrue(service.contains("ACTION_SETTINGS_RELOADED.equals(action) && command.fullImport"));
        int daemonGate = service.indexOf("private boolean avasDaemonRequired(");
        int daemonReconcile = service.indexOf("private void reconcileAvasRecovery(", daemonGate);
        assertTrue(daemonGate >= 0);
        assertTrue(daemonReconcile > daemonGate);
        String daemonGateBlock = service.substring(daemonGate, daemonReconcile);
        assertTrue(daemonGateBlock.contains("!LegacySettingsImporter.blocksRuntime(this)"));
        assertTrue(daemonGateBlock.contains("AvasRecoveryPolicy.daemonRequired("));
        assertTrue(service.contains("boolean daemonRequired = avasDaemonRequired(settings);"));
    }

    @Test
    public void helperLaunchAlsoIgnoresHupWithoutChangingCameraLaunch() {
        String helperCommand = TurnSignalController.launchCommand(
                "/data/app/example/base.apk", 10123, 103);
        String cameraCommand = TurnSignalController.cameraLaunchCommand(
                "/data/app/example/base.apk", 10123, 103);
        assertTrue(helperCommand.contains("trap '' HUP"));
        assertFalse(cameraCommand.contains("trap '' HUP"));
    }

    private static String source(String relative) throws Exception {
        Path path = Path.of(relative);
        if (!Files.exists(path) && relative.startsWith("app/")) {
            path = Path.of(relative.substring("app/".length()));
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
