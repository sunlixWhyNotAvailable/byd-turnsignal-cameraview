package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class GuardRecoverySessionTest {
    @Test
    public void manualSessionSurvivesProcessRecreationWithAutoStartOff() {
        TestSharedPreferences persisted = new TestSharedPreferences();
        assertFalse(recover(persisted, 10, false, false));
        GuardRecovery.setSessionActive(persisted, 10, true);
        // A new reader has no in-memory Activity/helper state, only the persisted boot marker.
        assertTrue(recover(persisted, 10, false, false));
        assertEquals(1, persisted.transactions);
        GuardRecovery.setSessionActive(persisted, 10, true);
        assertEquals(1, persisted.transactions);
    }

    @Test
    public void autoStartChangesDoNotEndTheCurrentSession() {
        TestSharedPreferences persisted = new TestSharedPreferences();
        GuardRecovery.setSessionActive(persisted, 10, true);
        assertTrue(recover(persisted, 10, true, false));
        assertTrue(recover(persisted, 10, false, false));
        assertTrue(GuardRecovery.hasActiveSession(persisted, 10));
        assertEquals(1, persisted.transactions);
    }

    @Test
    public void rebootRequiresAutoStartAgain() {
        TestSharedPreferences persisted = new TestSharedPreferences();
        GuardRecovery.setSessionActive(persisted, 10, true);
        assertFalse(recover(persisted, 11, false, false));
        assertTrue(recover(persisted, 11, true, false));
        GuardRecovery.setSessionActive(persisted, 11, true);
        assertTrue(recover(persisted, 11, false, false));
        assertFalse(GuardRecovery.hasActiveSession(persisted, 10));
    }

    @Test
    public void explicitShutdownWinsAndManualReopenStartsANewSession() {
        TestSharedPreferences persisted = new TestSharedPreferences();
        GuardRecovery.setSessionActive(persisted, 10, true);
        assertFalse(recover(persisted, 10, true, true));
        assertFalse(recover(persisted, 10, false, true));
        GuardRecovery.setSessionActive(persisted, 10, false);
        assertFalse(recover(persisted, 10, false, false));
        assertTrue(persisted.getAll().isEmpty());
        GuardRecovery.setSessionActive(persisted, 10, true);
        assertTrue(recover(persisted, 10, false, false));
    }

    @Test
    public void unknownBootNeverRestoresAnOldSession() {
        TestSharedPreferences persisted = new TestSharedPreferences();
        GuardRecovery.setSessionActive(persisted, 10, true);
        assertFalse(recover(persisted, -1, false, false));
        GuardRecovery.setSessionActive(persisted, -1, true);
        assertFalse(GuardRecovery.hasActiveSession(persisted, -1));
        assertTrue(persisted.getAll().isEmpty());
    }

    @Test
    public void recentsDismissalDoesNotStopOrRestartTheLiveRuntime() throws Exception {
        String manifest = source("AndroidManifest.xml");
        assertFalse(manifest.contains("android:excludeFromRecents=\"true\""));
        assertFalse(manifest.contains("android:autoRemoveFromRecents=\"true\""));
        assertTrue(manifest.contains("android:stopWithTask=\"false\""));
        String service = source("java/com/byd/extend/CameraHelperService.java");
        String removed = between(service, "public void onTaskRemoved", "public void onDestroy");
        assertTrue(removed.contains("GuardRecovery.scheduleSoon(this)"));
        assertFalse(removed.contains("stopRuntime"));
        assertFalse(removed.contains("stopSelf"));
        assertFalse(removed.contains("setUserShutdownActive"));
        assertFalse(removed.contains("startPersistent"));
        String activity = source("java/com/byd/extend/CameraProbeActivity.java");
        assertEquals(1, activity.split("finishAndRemoveTask\\(\\)", -1).length - 1);
        assertTrue(activity.contains("CameraHelperService.requestShutdown(this)"));
    }

    @Test
    public void allRecoveryOwnersUseTheSessionGateWithoutNewServices() throws Exception {
        String service = source("java/com/byd/extend/CameraHelperService.java");
        String start = between(service, "public int onStartCommand", "private void handleStartCommand");
        assertTrue(start.contains("helperRuntimeStarted || ACTION_ACTIVITY_OPEN.equals(action)"));
        assertTrue(start.contains("GuardRecovery.hasActiveSession(this)"));
        assertTrue(start.contains("? START_NOT_STICKY : START_STICKY"));
        String helperStart = between(service, "private void ensureHelperStarted", "private void stopRuntime");
        assertTrue(helperStart.indexOf("GuardRecovery.sessionStarted(this)")
                < helperStart.indexOf("helper.discoverCamera()"));
        String daemon = between(service, "private boolean avasDaemonRequired", "private boolean logcatRecordingRequested");
        assertTrue(daemon.contains("GuardRecovery.shouldRecover(this)"));
        assertFalse(daemon.contains("GuardRecovery.isAutoStartEnabled(this)"));
        String listener = source("java/com/byd/extend/RecoveryNotificationListener.java");
        assertTrue(listener.contains("GuardRecovery.shouldRecover(this)"));
        assertTrue(listener.contains("GuardRecovery.hasActiveSession(this) ||"));
        String recovery = source("java/com/byd/extend/GuardRecovery.java");
        assertTrue(recovery.contains("getSharedPreferences(\"runtime_recovery\""));
        assertTrue(recovery.contains("setSessionActive(sessionPreferences(context), bootCount(context), !active)"));
        String auto = between(recovery, "static void setAutoStartEnabled", "static void setUserShutdownActive");
        assertFalse(auto.contains("setSessionActive"));
    }

    private static boolean recover(TestSharedPreferences session, int boot,
            boolean autoStart, boolean shutdown) {
        return GuardRecovery.shouldRecover(autoStart, shutdown,
                GuardRecovery.hasActiveSession(session, boot));
    }

    private static String source(String relative) throws Exception {
        Path path = Path.of("src/main", relative);
        if (!Files.exists(path)) path = Path.of("app/src/main", relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        return source.substring(from, source.indexOf(end, from));
    }
}
