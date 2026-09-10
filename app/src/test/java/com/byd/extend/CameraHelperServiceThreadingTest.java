package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

public final class CameraHelperServiceThreadingTest {
    @Test
    public void teardownClearsQueueRejectsLateWorkAndIsPerInstance() {
        CameraHelperService.RuntimeLifecycleGate gate =
                new CameraHelperService.RuntimeLifecycleGate();
        FakeQueue queue = new FakeQueue();
        List<String> events = new ArrayList<>();

        assertTrue(gate.post(queue, () -> events.add("completed")));
        queue.runNext();
        assertTrue(gate.post(queue, () -> events.add("cleared_first")));
        assertTrue(gate.post(queue, () -> events.add("cleared_second")));
        assertEquals(2, queue.size());
        assertEquals(CameraHelperService.RuntimeLifecycleGate.TeardownResult.ENQUEUED,
                gate.beginTeardown(queue, () -> events.add("teardown")));
        assertEquals(1, queue.size());
        assertFalse(gate.post(queue, () -> events.add("late")));
        assertEquals(CameraHelperService.RuntimeLifecycleGate.TeardownResult.ALREADY_CLAIMED,
                gate.beginTeardown(queue, () -> events.add("duplicate")));

        queue.runAll();
        assertEquals(List.of("completed", "teardown"), events);

        CameraHelperService.RuntimeLifecycleGate nextInstance =
                new CameraHelperService.RuntimeLifecycleGate();
        FakeQueue nextQueue = new FakeQueue();
        assertTrue(nextInstance.post(nextQueue, () -> events.add("next")));
        nextQueue.runAll();
        assertEquals(List.of("completed", "teardown", "next"), events);
    }

    @Test
    public void startCommandOnlySnapshotsForegroundsAndQueuesRuntimeWork() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/CameraHelperService.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/CameraHelperService.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String method = text.substring(
                text.indexOf("public int onStartCommand"),
                text.indexOf("private void handleStartCommand"));

        assertTrue(method.contains("ServiceRuntimeCommand.capture"));
        assertTrue(method.contains("postRuntime(() -> handleStartCommand(command))"));
        assertFalse(method.contains("ensureHelperStarted()"));
        assertFalse(method.contains("stopRuntime("));
        assertFalse(method.contains("reloadSettings("));
        assertFalse(method.contains("pauseActiveRuntime("));
        assertFalse(method.contains("flush("));
    }

    @Test
    public void recoveryWrappersOnlyEnqueueAndRuntimeOwnsPersistence() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/CameraHelperService.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/CameraHelperService.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String activity = text.substring(
                text.indexOf("static void activityOpened"),
                text.indexOf("static void activityClosed"));
        String autoStart = text.substring(
                text.indexOf("static void updateAutoStart"),
                text.indexOf("static void requestShutdown"));
        String shutdown = text.substring(
                text.indexOf("static void requestShutdown"),
                text.indexOf("static void flushLogs"));

        assertFalse(activity.contains("GuardRecovery."));
        assertFalse(autoStart.contains("GuardRecovery."));
        assertFalse(shutdown.contains("GuardRecovery."));
        assertTrue(activity.contains("context.startService(intent)"));
        assertTrue(autoStart.contains("context.startForegroundService(intent)"));
        assertTrue(autoStart.contains("context.startService(intent)"));
        assertTrue(shutdown.contains("context.startService"));

        String runtime = text.substring(
                text.indexOf("private void handleStartCommand"),
                text.indexOf("boolean shouldRecover =", text.indexOf(
                        "private void handleStartCommand")));
        assertTrue(runtime.contains("setUserShutdownActive(this, true)"));
        assertTrue(runtime.contains("setAutoStartEnabled(this, command.enabled)"));
        assertTrue(runtime.contains("setUserShutdownActive(this, false)"));
    }

    @Test
    public void queuedStartCommandIsImmutable() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/ServiceRuntimeCommand.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/ServiceRuntimeCommand.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(text.contains("final class ServiceRuntimeCommand"));
        assertTrue(text.contains("final String action;"));
        assertTrue(text.contains("final String reason;"));
        assertTrue(text.contains("final int startId;"));
        assertTrue(text.contains("final boolean enabled;"));
        assertTrue(text.contains("final boolean fullImport;"));
        assertTrue(text.contains("final String weatherReason;"));
        assertTrue(text.contains("final ResultReceiver flushReceiver;"));
        assertTrue(text.contains("final ResultReceiver weatherReceiver;"));
    }

    @Test
    public void controllerQueueAndImportPauseDoNotWaitOnMain() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/CameraHelperService.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/CameraHelperService.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String controllers = text.substring(
                text.indexOf("private void initializeControllers"),
                text.indexOf("public void onCreate"));
        assertFalse(controllers.contains(", mainHandler,"));
        assertTrue(controllers.contains("BlindSpotOverlayController(this, runtimeHandler"));
        assertTrue(controllers.contains("ParkingCameraController(this, runtimeHandler"));
        assertTrue(controllers.contains("this, runtimeHandler, this::reverseEvent"));

        String helperCreation = text.substring(
                text.indexOf("private synchronized void ensureHelperCreated"),
                text.indexOf("private void ensureHelperStarted"));
        assertTrue(helperCreation.contains(
                "getApplicationContext(), runtimeHandler, this::acceptHelperLine"));

        Path helperSource = Path.of("app/src/main/java/com/byd/extend/CameraHelperMain.java");
        if (!Files.exists(helperSource)) {
            helperSource = Path.of("src/main/java/com/byd/extend/CameraHelperMain.java");
        }
        String helperText = new String(
                Files.readAllBytes(helperSource), StandardCharsets.UTF_8);
        assertTrue(helperText.contains(
                "HelperBinder(Context context, Handler callbackHandler, Consumer<String> logSink)"));
        assertTrue(helperText.contains(
                "context, callbackHandler, this::acceptShellEvent, this::acceptControllerEvent"));
        assertFalse(helperText.contains("private final Handler mainHandler"));

        String pause = text.substring(
                text.indexOf("static boolean pauseActiveRuntime"),
                text.indexOf("static void settingsReloaded"));
        int mainRefusal = pause.indexOf(
                "if (Looper.myLooper() == Looper.getMainLooper()) return false");
        int waitSetup = pause.indexOf("new CountDownLatch");
        assertTrue(mainRefusal >= 0);
        assertTrue(waitSetup > mainRefusal);
    }

    @Test
    public void manualMirrorSettingsActionsNotifyClusterWithAwakeSessionDedup() {
        List<String> notifications = new ArrayList<>();
        int[] preparationAttempts = {0};
        int[] attemptedSession = {6};
        boolean mirrorOnlyCluster = ClusterFullscreenController.hasEnabledClusterTarget(
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                true, CameraDisplayTarget.CLUSTER);
        Runnable clusterSettingsChanged = () -> {
            notifications.add("cluster");
            if (ClusterFullscreenController.shouldAttempt(
                    mirrorOnlyCluster, true, 7, attemptedSession[0])) {
                preparationAttempts[0]++;
                attemptedSession[0] = 7;
            }
        };

        for (String action : new String[]{
                "com.byd.extend.action.MIRROR_SETTINGS_CHANGED",
                "com.byd.extend.action.CAMERA_SETTINGS_CHANGED",
                "com.byd.extend.action.SETTINGS_RELOADED"}) {
            CameraHelperService.routeManualMirrorSettingsChange(
                    action, () -> notifications.add("mirror"), clusterSettingsChanged);
        }
        CameraHelperService.routeManualMirrorSettingsChange(
                "com.byd.extend.action.CAMERA_WARNING_SETTINGS_CHANGED",
                () -> notifications.add("mirror"), clusterSettingsChanged);
        CameraHelperService.routeManualMirrorSettingsChange(
                null, () -> notifications.add("mirror"), clusterSettingsChanged);

        assertEquals(List.of("mirror", "cluster", "mirror", "cluster",
                "mirror", "cluster"), notifications);
        assertEquals(1, preparationAttempts[0]);
    }

    @Test
    public void serviceOwnsVisibilityReplayAndRejectsRetainedShellCache() throws Exception {
        assertTrue(CameraHelperService.isShellOemVisibilityEvent(
                new JSONObject().put("kind", "oem_camera_visibility").toString()));
        assertTrue(CameraHelperService.isShellOemVisibilityEvent(
                new JSONObject().put("kind", "oem_camera_visibility_listener").toString()));
        assertFalse(CameraHelperService.isShellOemVisibilityEvent(
                new JSONObject().put("kind", "reverse_gear_state").toString()));
        assertFalse(CameraHelperService.isShellOemVisibilityEvent(
                new JSONObject().put("kind", "reverse_gear_state")
                        .put("error", "oem_camera_visibility").toString()));

        Path source = Path.of("app/src/main/java/com/byd/extend/CameraHelperService.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/CameraHelperService.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(text.contains("oemCameraVisibility = new OemCameraVisibilityRuntime("));
        String controllers = text.substring(
                text.indexOf("private void initializeControllers"),
                text.indexOf("private void ensureControllersInitialized"));
        assertTrue(controllers.contains("oemCameraVisibility.reportStatus()"));
        String teardown = text.substring(
                text.indexOf("private void destroyRuntime"),
                text.indexOf("private synchronized void ensureHelperCreated"));
        assertTrue(teardown.contains("oemCameraVisibility.stopForTeardown()"));

        Path shellSource = Path.of("app/src/main/java/com/byd/extend/TurnSignalShellMain.java");
        if (!Files.exists(shellSource)) {
            shellSource = Path.of("src/main/java/com/byd/extend/TurnSignalShellMain.java");
        }
        String shellText = new String(Files.readAllBytes(shellSource), StandardCharsets.UTF_8);
        assertFalse(shellText.contains("OemCameraVisibilityRuntime"));
    }

    private static final class FakeQueue implements CameraHelperService.RuntimeLifecycleGate.Queue {
        private final ArrayDeque<Runnable> actions = new ArrayDeque<>();

        @Override
        public void clear() {
            actions.clear();
        }

        @Override
        public boolean post(Runnable action) {
            actions.add(action);
            return true;
        }

        int size() {
            return actions.size();
        }

        void runNext() {
            actions.removeFirst().run();
        }

        void runAll() {
            while (!actions.isEmpty()) actions.removeFirst().run();
        }
    }
}
