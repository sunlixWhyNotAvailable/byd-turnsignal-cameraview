package com.byd.turnsignalguard.capture;

import android.app.Application;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.content.SharedPreferences;

import junit.framework.TestCase;

import org.json.JSONObject;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class CameraLifecycleBinderTest extends TestCase {
    public void testColdResetDefersActiveReverseAndReplaysRetainedCallback() throws Exception {
        Application context = currentApplication();
        CameraHelperMain.HelperBinder helper =
                new CameraHelperMain.HelperBinder(context, line -> {});
        helper.attachInterface(null, CameraHelperMain.DESCRIPTOR);
        EventCollector events = new EventCollector(CameraHelperMain.CALLBACK_DESCRIPTOR);

        helper.emitControllerEvent("reverse_camera_start", "request_id", 41);
        assertEquals("callback_registered", registerHelperCallback(helper, events, 1));
        JSONObject snapshot = events.await("reverse_camera_state");
        assertTrue(snapshot.optBoolean("active"));
        assertEquals(41, snapshot.optInt("request_id"));

        String token = new CameraTransition().begin(
                CameraHelperMain.ACTIVITY_RESUME_COLD_RESET);
        assertEquals(CameraHelperMain.COLD_RESET_DEFERRED_REVERSE,
                closeHelper(helper, token, 0));

        helper.emitControllerEvent("reverse_camera_stopped", "request_id", 41);
        assertEquals(41, events.await("reverse_camera_stopped").optInt("request_id"));
        assertEquals("already_closed", closeHelper(helper, token, 0));

        EventCollector resumedEvents = new EventCollector(
                CameraHelperMain.CALLBACK_DESCRIPTOR);
        assertEquals("callback_registered",
                registerHelperCallback(helper, resumedEvents, 2));
        JSONObject idle = resumedEvents.await("reverse_camera_state");
        assertFalse(idle.optBoolean("active"));
        assertEquals(0, idle.optInt("request_id"));
    }

    public void testAlreadyClosedStockPreviewEmitsExactTerminal() throws Exception {
        Application context = currentApplication();
        CameraShellMain.ShellBinder shell = new CameraShellMain.ShellBinder(
                context, new Handler(Looper.getMainLooper()),
                Process.myUid(), BuildConfig.VERSION_CODE);
        shell.attachInterface(null, CameraShellProtocol.DESCRIPTOR);
        EventCollector events = new EventCollector(CameraShellProtocol.CALLBACK_DESCRIPTOR);
        registerShellCallback(shell, events);

        closeShell(shell, "activity_stopped", 73);
        JSONObject closed = events.await("camera_closed");
        assertEquals("stock_avm_shell", closed.optString("renderer"));
        assertEquals("activity_stopped", closed.optString("reason"));
        assertEquals(73, closed.optInt("request_id"));
        assertEquals("", closed.optString("error"));
    }

    public void testReverseStartPublishesOwnershipBeforePriorityClose() throws Exception {
        Application context = currentApplication();
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch prepared = new CountDownLatch(1);
        TestHelper helper = new TestHelper(context, order, prepared);
        AtomicReference<String> closeResult = new AtomicReference<>();
        AtomicReference<Throwable> closeError = new AtomicReference<>();
        String token = new CameraTransition().begin(
                CameraHelperMain.ACTIVITY_RESUME_COLD_RESET);
        SharedPreferences settings = context.getSharedPreferences("settings", 0);
        boolean hadSetting = settings.contains(ReverseCameraController.PREF_ENABLED);
        boolean oldSetting = settings.getBoolean(
                ReverseCameraController.PREF_ENABLED,
                ReverseCameraController.DEFAULT_ENABLED);
        settings.edit().putBoolean(ReverseCameraController.PREF_ENABLED, true).commit();

        ReverseCameraController controller = new ReverseCameraController(
                context, new Handler(Looper.getMainLooper()),
                (kind, fields) -> {
                    order.add(kind);
                    helper.emitControllerEvent(kind, fields);
                }, active -> {
                    if (!active) return;
                    order.add("priority_true");
                    try {
                        closeResult.set(closeHelper(helper, token, 0));
                    } catch (Throwable error) {
                        closeError.set(error);
                    }
                });
        try {
            controller.attachHelper(helper);
            controller.acceptEvent(new JSONObject()
                    .put("kind", "reverse_gear_state")
                    .put("valid", true)
                    .put("listener_ok", true)
                    .put("reverse", true)
                    .put("raw", 1)
                    .toString());
            assertTrue(prepared.await(5, TimeUnit.SECONDS));
            if (closeError.get() != null) throw new AssertionError(closeError.get());
            assertEquals(CameraHelperMain.COLD_RESET_DEFERRED_REVERSE,
                    closeResult.get());
            assertTrue(order.indexOf("reverse_camera_start")
                    < order.indexOf("priority_true"));
            assertTrue(order.indexOf("priority_true") < order.indexOf("prepare"));
        } finally {
            controller.shutdown();
            SharedPreferences.Editor edit = settings.edit();
            if (hadSetting) edit.putBoolean(
                    ReverseCameraController.PREF_ENABLED, oldSetting);
            else edit.remove(ReverseCameraController.PREF_ENABLED);
            edit.commit();
        }
    }

    public void testActivityTerminalMakesLateQueuedReplyStale() throws Exception {
        CameraProbeActivity activity = new CameraProbeActivity();
        setField(activity, "activityClosePending", true);
        setField(activity, "closingActivityCameraRequestId", 73);

        String queued = new JSONObject()
                .put("kind", "stock_avm_shell_close_queued")
                .put("error", "")
                .toString();
        invoke(activity, "handleCloseReply",
                new Class<?>[]{String.class, int.class, String.class, Throwable.class},
                "activity_stopped", 73, queued, null);
        assertTrue((Boolean) getField(activity, "activityClosePending"));

        invoke(activity, "finishActivityStoppedClose",
                new Class<?>[]{int.class, String.class}, 73, null);
        assertFalse((Boolean) getField(activity, "activityClosePending"));
        assertEquals(0, getField(activity, "closingActivityCameraRequestId"));

        invoke(activity, "handleCloseReply",
                new Class<?>[]{String.class, int.class, String.class, Throwable.class},
                "activity_stopped", 73, queued, null);
        assertFalse((Boolean) getField(activity, "activityClosePending"));
        assertEquals(0, getField(activity, "closingActivityCameraRequestId"));
    }

    public void testReverseShellFailureRetiresStoppedLifecycleLatch() throws Exception {
        CameraProbeActivity activity = new CameraProbeActivity();
        setField(activity, "automaticPreviewIntent", true);
        setField(activity, "automaticPreviewIntentTab", 3);
        setField(activity, "automaticPreviewIntentRequestId", 81);
        setField(activity, "activityClosePending", true);
        setField(activity, "closingActivityCameraRequestId", 81);
        setField(activity, "activityColdResetRequired", true);
        setField(activity, "activityColdResetInFlight", true);
        setField(activity, "activityColdResetFailed", true);
        setField(activity, "pendingReversePreviewRequestId", 81);
        setField(activity, "pendingReversePreviewGenerations", new int[]{1, 2, 3, 4});

        invoke(activity, "clearReverseShellFailureState", new Class<?>[0]);

        assertFalse((Boolean) getField(activity, "automaticPreviewIntent"));
        assertFalse((Boolean) getField(activity, "activityClosePending"));
        assertFalse((Boolean) getField(activity, "activityColdResetRequired"));
        assertFalse((Boolean) getField(activity, "activityColdResetInFlight"));
        assertFalse((Boolean) getField(activity, "activityColdResetFailed"));
        assertEquals(0, getField(activity, "closingActivityCameraRequestId"));
        assertEquals(0, getField(activity, "pendingReversePreviewRequestId"));
        assertNull(getField(activity, "pendingReversePreviewGenerations"));
    }

    public void testImmediateReverseOpenFailureDisarmsAutomaticRetry() throws Exception {
        CameraProbeActivity activity = new CameraProbeActivity();
        setField(activity, "automaticPreviewIntent", true);
        setField(activity, "automaticPreviewIntentTab", 3);
        setField(activity, "automaticPreviewIntentRequestId", 82);
        setField(activity, "requestedOpen", true);
        setField(activity, "activeActivityCameraRequestId", 82);
        setField(activity, "pendingReversePreviewRequestId", 82);
        setField(activity, "pendingReversePreviewGenerations", new int[]{1, 2, 3, 4});

        assertTrue((Boolean) invoke(activity, "failClosedReversePreviewRequest",
                new Class<?>[]{int.class}, 82));

        assertFalse((Boolean) getField(activity, "automaticPreviewIntent"));
        assertFalse((Boolean) getField(activity, "requestedOpen"));
        assertEquals(0, getField(activity, "activeActivityCameraRequestId"));
        assertEquals(0, getField(activity, "pendingReversePreviewRequestId"));
        assertNull(getField(activity, "pendingReversePreviewGenerations"));
    }

    public void testFailedTabCloseReplyKeepsTransitionAndSurfaceOwnershipPending()
            throws Exception {
        CameraProbeActivity activity = new CameraProbeActivity();
        CameraTransition transition = (CameraTransition) getField(
                activity, "cameraTransition");
        String token = transition.begin("camera_tab_changed");
        setField(activity, "closingActivityCameraRequestId", 91);

        String failed = new JSONObject()
                .put("kind", "camera_error")
                .put("error", "rmPreviewSurface returned false")
                .toString();
        invoke(activity, "handleCloseReply",
                new Class<?>[]{String.class, int.class, String.class, Throwable.class},
                token, 91, failed, null);
        assertTrue(transition.matches(token));
        assertEquals(91, getField(activity, "closingActivityCameraRequestId"));

        invoke(activity, "handleCloseReply",
                new Class<?>[]{String.class, int.class, String.class, Throwable.class},
                token, 91, null, new RemoteException("binder failed"));
        assertTrue(transition.matches(token));
        assertEquals(91, getField(activity, "closingActivityCameraRequestId"));
    }

    private static String registerHelperCallback(
            IBinder helper, IBinder callback, long generation) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeStrongBinder(callback);
            data.writeLong(generation);
            assertTrue(helper.transact(CameraHelperMain.TX_REGISTER_CALLBACK, data, reply, 0));
            reply.readException();
            return new JSONObject(reply.readString()).optString("kind");
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String closeHelper(IBinder helper, String reason, int requestId)
            throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeString(reason);
            data.writeInt(requestId);
            assertTrue(helper.transact(CameraHelperMain.TX_CLOSE, data, reply, 0));
            reply.readException();
            return new JSONObject(reply.readString()).optString("kind");
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void registerShellCallback(IBinder shell, IBinder callback) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeStrongBinder(callback);
            assertTrue(shell.transact(
                    CameraShellProtocol.TX_REGISTER_CALLBACK, data, reply, 0));
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void closeShell(IBinder shell, String reason, int requestId) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShellProtocol.DESCRIPTOR);
            data.writeString(reason);
            data.writeInt(requestId);
            assertTrue(shell.transact(CameraShellProtocol.TX_CLOSE, data, reply, 0));
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static Application currentApplication() throws Exception {
        Object value = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null);
        if (!(value instanceof Application)) {
            throw new IllegalStateException("target Application unavailable");
        }
        return (Application) value;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(
            Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static final class TestHelper extends CameraHelperMain.HelperBinder {
        private final CopyOnWriteArrayList<String> order;
        private final CountDownLatch prepared;

        TestHelper(
                Application context, CopyOnWriteArrayList<String> order,
                CountDownLatch prepared) {
            super(context, line -> {});
            this.order = order;
            this.prepared = prepared;
        }

        @Override
        void prepareReverseOverlayWindow(
                CameraShellProtocol.ReverseOverlaySpec spec,
                java.util.function.Consumer<TurnSignalController.ReverseSurfaces> surfaceSink,
                Runnable preparedSink) {
            order.add("prepare");
            prepared.countDown();
        }
    }

    private static final class EventCollector extends Binder {
        private final String descriptor;
        private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

        EventCollector(String descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != IBinder.FIRST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(descriptor);
            events.offer(data.readString());
            return true;
        }

        JSONObject await(String kind) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("Timed out waiting for " + kind);
                }
                String line = events.poll(remaining, TimeUnit.NANOSECONDS);
                if (line == null) {
                    throw new AssertionError("Timed out waiting for " + kind);
                }
                JSONObject event = new JSONObject(line);
                if (kind.equals(event.optString("kind"))) return event;
            }
        }
    }
}
