package com.byd.extend;

import android.os.IBinder;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class CameraShellEpochReplayTest {
    @Test
    public void newActivityRegistrationReplaysLiveEpochsBeforeStrictClose() {
        CameraProbeActivity.HelperCallbackRegistration<Object> registration =
                new CameraProbeActivity.HelperCallbackRegistration<>();
        CameraHelperMain.CallbackSlot<Consumer<ReplayEvent>> callbacks =
                new CameraHelperMain.CallbackSlot<>();
        Object helper = new Object();
        List<ReplayEvent> events = new ArrayList<>();
        Consumer<ReplayEvent> activityCallback = events::add;

        assertNull(registration.start());
        assertSame(helper, registration.connected(helper));
        long generation = CameraProbeActivity.nextHelperCallbackRegistrationGeneration();
        CameraProbeActivity.HelperCallbackRegistration.Operation<Object> operation =
                registration.queue(helper, generation);
        assertTrue(callbacks.register(activityCallback, generation));
        assertTrue(registration.registered(operation));

        BinderProbe camera = new BinderProbe(true);
        BinderProbe avm = new BinderProbe(true);
        replayToCurrentCallback(callbacks, camera.binder(), 7, avm.binder(), 11);

        assertEquals(Arrays.asList("stock_avm_shell_attached", "camera_shell_attached"),
                kinds(events));
        assertEquals(11L, events.get(0).field("avm_shell_epoch"));
        assertEquals(7L, events.get(1).field("camera_shell_epoch"));
        assertEquals(0, camera.pingCalls);
        assertEquals(0, camera.transactCalls);
        assertEquals(0, avm.pingCalls);
        assertEquals(0, avm.transactCalls);

        long activityCameraEpoch = 0;
        long activityAvmEpoch = 0;
        for (ReplayEvent event : events) {
            if ("camera_shell_attached".equals(event.kind)) {
                long epoch = event.field("camera_shell_epoch");
                assertTrue(CameraProbeActivity.shouldAcceptActivityCameraShellAttach(
                        activityCameraEpoch, epoch, false));
                activityCameraEpoch = epoch;
            }
            activityAvmEpoch = CameraProbeActivity.nextActivityAvmShellEpoch(
                    activityAvmEpoch, event.kind, event.fieldString("renderer"),
                    event.field("avm_shell_epoch"), false);
        }
        assertEquals(7L, activityCameraEpoch);
        assertEquals(11L, activityAvmEpoch);

        CameraTransition transition = new CameraTransition();
        String token = transition.begin("camera_tab_changed");
        int requestId = 27;
        assertFalse(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, token, "stock_avm_shell", "camera_closed", "",
                requestId, requestId - 1, activityCameraEpoch, activityCameraEpoch,
                activityAvmEpoch, activityAvmEpoch));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, token, "stock_avm_shell", "camera_closed", "",
                requestId, requestId, activityCameraEpoch, activityCameraEpoch - 1,
                activityAvmEpoch, activityAvmEpoch));
        assertFalse(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, token, "stock_avm_shell", "camera_closed", "",
                requestId, requestId, activityCameraEpoch, activityCameraEpoch,
                activityAvmEpoch, activityAvmEpoch - 1));
        assertTrue(CameraProbeActivity.isMatchingPendingActivityTransitionShellClose(
                token, token, "stock_avm_shell", "camera_closed", "",
                requestId, requestId, activityCameraEpoch, activityCameraEpoch,
                activityAvmEpoch, activityAvmEpoch));
        assertTrue(transition.complete(token));
        assertFalse(transition.complete(token));
    }

    @Test
    public void deadOrMissingShellEpochsDoNotReplayOrResurrectDetachedActivity() {
        CameraHelperMain.CallbackSlot<Consumer<ReplayEvent>> callbacks =
                new CameraHelperMain.CallbackSlot<>();
        List<ReplayEvent> events = new ArrayList<>();
        Consumer<ReplayEvent> activityCallback = events::add;
        long generation = CameraProbeActivity.nextHelperCallbackRegistrationGeneration();
        assertTrue(callbacks.register(activityCallback, generation));

        BinderProbe deadCamera = new BinderProbe(false);
        BinderProbe deadAvm = new BinderProbe(false);
        replayToCurrentCallback(callbacks, deadCamera.binder(), 7, deadAvm.binder(), 11);
        replayToCurrentCallback(callbacks, null, 8, null, 12);

        assertTrue(events.isEmpty());
        assertEquals(0, deadCamera.pingCalls);
        assertEquals(0, deadCamera.transactCalls);
        assertEquals(0, deadAvm.pingCalls);
        assertEquals(0, deadAvm.transactCalls);

        assertTrue(callbacks.detach(activityCallback, generation));
        replayToCurrentCallback(callbacks, new BinderProbe(true).binder(), 9,
                new BinderProbe(true).binder(), 13);
        assertTrue(events.isEmpty());
        assertNull(callbacks.current());
    }

    @Test
    public void helperRegistrationReplaysStateInsideSynchronizedTransaction() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/CameraHelperMain.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/CameraHelperMain.java");
        }
        assertTrue("CameraHelperMain source unavailable", Files.exists(source));
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        int transactStart = text.indexOf("protected synchronized boolean onTransact(");
        int registrationCall = text.indexOf("boolean accepted = registerCallback(", transactStart);
        int registerStart = text.indexOf("private boolean registerCallback(");
        int registerEnd = text.indexOf("private static int requestId(", registerStart);
        assertTrue(transactStart >= 0);
        assertTrue(registrationCall > transactStart);
        assertTrue(registerStart > registrationCall);
        assertTrue(registerEnd > registerStart);
        String registration = text.substring(registerStart, registerEnd);
        int callbackAccepted = registration.indexOf("callbacks.register(");
        int replay = registration.indexOf("turnController.reportCameraShellState()");
        int reverseState = registration.indexOf("emit(\"reverse_camera_state\"");
        assertTrue(callbackAccepted >= 0);
        assertTrue(replay > callbackAccepted);
        assertTrue(reverseState > replay);
    }

    @Test
    public void replaySerializesShutdownWhileDeathEmitsStayOutsideStateLock() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/TurnSignalController.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/TurnSignalController.java");
        }
        assertTrue("TurnSignalController source unavailable", Files.exists(source));
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        int replayStart = text.indexOf("synchronized void reportCameraShellState()");
        int replayEnd = text.indexOf("    static void replayCameraShellState(", replayStart);
        assertTrue(replayStart >= 0);
        assertTrue(replayEnd > replayStart);
        String replay = text.substring(replayStart, replayEnd);
        assertTrue(replay.contains("if (stopped) return;"));
        assertTrue(replay.contains("cameraHelper, cameraHelperEpoch, avmShell, avmShellEpoch"));
        assertTrue(replay.indexOf("if (stopped) return;")
                < replay.indexOf("replayCameraShellState("));

        int shutdownStart = text.indexOf("void shutdown(boolean terminateShells)");
        int shutdownLock = text.indexOf("synchronized (this)", shutdownStart);
        int shutdownLockEnd = text.indexOf("\n        }\n", shutdownLock);
        int stoppedWrite = text.indexOf("stopped = true;", shutdownStart);
        assertTrue(shutdownStart >= 0);
        assertTrue(shutdownLock >= 0);
        assertTrue(shutdownLockEnd > shutdownLock);
        assertTrue(stoppedWrite > shutdownLock && stoppedWrite < shutdownLockEnd);

        int deathStart = text.indexOf("private void helperDied(");
        int deathEnd = text.indexOf("private boolean sendConfig(", deathStart);
        assertTrue(deathStart >= 0);
        assertTrue(deathEnd > deathStart);
        String death = text.substring(deathStart, deathEnd);
        int deathLock = death.indexOf("synchronized (this)");
        int deathLockEnd = death.indexOf("\n        }\n", deathLock);
        int staleEmit = death.indexOf("emit(\"helper_death_ignored\",");
        int currentEmit = death.indexOf("emit(\"helper_death\",");
        assertTrue(deathLock >= 0);
        assertTrue(deathLockEnd > deathLock);
        int staleCheck = death.indexOf("stale = helper != deadHelper;");
        int helperClear = death.indexOf("helper = null;");
        assertTrue(staleCheck > deathLock && staleCheck < deathLockEnd);
        assertTrue(helperClear > deathLock && helperClear < deathLockEnd);
        assertTrue(staleEmit > deathLockEnd);
        assertTrue(currentEmit > deathLockEnd);
    }

    private static void replayToCurrentCallback(
            CameraHelperMain.CallbackSlot<Consumer<ReplayEvent>> callbacks,
            IBinder camera, long cameraEpoch, IBinder avm, long avmEpoch) {
        TurnSignalController.replayCameraShellState(
                camera, cameraEpoch, avm, avmEpoch,
                (kind, fields) -> {
                    Consumer<ReplayEvent> callback = callbacks.current();
                    if (callback != null) callback.accept(new ReplayEvent(kind, fields));
                });
    }

    private static List<String> kinds(List<ReplayEvent> events) {
        List<String> kinds = new ArrayList<>();
        for (ReplayEvent event : events) kinds.add(event.kind);
        return kinds;
    }

    private static final class ReplayEvent {
        final String kind;
        final Object[] fields;

        ReplayEvent(String kind, Object[] fields) {
            this.kind = kind;
            this.fields = fields == null ? new Object[0] : fields.clone();
        }

        long field(String name) {
            for (int i = 0; i + 1 < fields.length; i += 2) {
                if (name.equals(String.valueOf(fields[i])) && fields[i + 1] instanceof Number) {
                    return ((Number) fields[i + 1]).longValue();
                }
            }
            return 0;
        }

        String fieldString(String name) {
            for (int i = 0; i + 1 < fields.length; i += 2) {
                if (name.equals(String.valueOf(fields[i]))) return String.valueOf(fields[i + 1]);
            }
            return "";
        }
    }

    private static final class BinderProbe implements InvocationHandler {
        final boolean alive;
        int pingCalls;
        int transactCalls;

        BinderProbe(boolean alive) {
            this.alive = alive;
        }

        IBinder binder() {
            return (IBinder) Proxy.newProxyInstance(
                    CameraShellEpochReplayTest.class.getClassLoader(),
                    new Class<?>[]{IBinder.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "isBinderAlive":
                    return alive;
                case "pingBinder":
                    pingCalls++;
                    return false;
                case "transact":
                    transactCalls++;
                    return false;
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "BinderProbe[" + alive + "]";
                default:
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
            }
        }
    }
}
