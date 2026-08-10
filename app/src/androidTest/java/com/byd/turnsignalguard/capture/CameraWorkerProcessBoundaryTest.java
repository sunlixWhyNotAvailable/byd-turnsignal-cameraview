package com.byd.turnsignalguard.capture;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import junit.framework.TestCase;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CameraWorkerProcessBoundaryTest extends TestCase {
    public void testSurfaceCrossesWorkerDeathAndReplacementConnection()
            throws Exception {
        Context context = currentApplication();
        ComponentName component = new ComponentName(context, CameraWorkerService.class);
        ServiceInfo info = context.getPackageManager().getServiceInfo(component, 0);
        assertFalse(info.exported);
        assertEquals(":camera", info.processName.substring(info.processName.indexOf(':')));

        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch replaced = new CountDownLatch(1);
        CountDownLatch firstLifecycle = new CountDownLatch(2);
        CountDownLatch secondLifecycle = new CountDownLatch(2);
        AtomicReference<JSONObject> handshake = new AtomicReference<>();
        AtomicReference<JSONObject> replacement = new AtomicReference<>();
        AtomicReference<JSONObject> firstAttached = new AtomicReference<>();
        AtomicReference<JSONObject> firstClosed = new AtomicReference<>();
        AtomicReference<JSONObject> secondAttached = new AtomicReference<>();
        AtomicReference<JSONObject> secondClosed = new AtomicReference<>();
        CameraWorkerClient client = new CameraWorkerClient(
                context, new Handler(Looper.getMainLooper()), line -> {
            try {
                JSONObject event = new JSONObject(line);
                String kind = event.optString("kind");
                if ("camera_worker_handshake".equals(kind)) {
                    handshake.set(event);
                    connected.countDown();
                } else if ("camera_worker_epoch_changed".equals(kind)) {
                    replacement.set(event);
                    replaced.countDown();
                } else if ("camera_consumer_attached".equals(kind)) {
                    if (event.optInt("request_id") == 401) {
                        firstAttached.set(event);
                        firstLifecycle.countDown();
                    } else if (event.optInt("request_id") == 402) {
                        secondAttached.set(event);
                        secondLifecycle.countDown();
                    }
                } else if ("camera_closed".equals(kind)
                        && "debug_surface_probe".equals(event.optString("reason"))) {
                    if (event.optInt("request_id") == 401) {
                        firstClosed.set(event);
                        firstLifecycle.countDown();
                    } else if (event.optInt("request_id") == 402) {
                        secondClosed.set(event);
                        secondLifecycle.countDown();
                    }
                }
            } catch (Throwable error) {
                fail("invalid worker event: " + error);
            }
        });
        SurfaceTexture firstTexture = null;
        SurfaceTexture secondTexture = null;
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            JSONObject connectedEvent = handshake.get();
            assertNotNull(connectedEvent);
            assertEquals(CameraWorkerProtocol.VERSION,
                    connectedEvent.getInt("protocol"));
            assertEquals(BuildConfig.VERSION_CODE, connectedEvent.getInt("build"));
            assertTrue(connectedEvent.getLong("worker_epoch") != 0);
            assertTrue(connectedEvent.getLong("connection_generation") > 0);
            assertTrue(CameraWorkerClient.isRemoteWorkerPid(
                    connectedEvent.getInt("pid"), Process.myPid()));

            CameraWorkerClient.ConnectionSnapshot firstConnection =
                    client.debugConnectionSnapshot();
            assertNotNull(firstConnection);
            firstTexture = new SurfaceTexture(0);
            assertSurfaceResult(client.debugSendSurface(
                    new Surface(firstTexture), 401));
            assertTrue(firstLifecycle.await(5, TimeUnit.SECONDS));
            CameraEventKey firstKey = assertLifecycle(
                    firstAttached.get(), firstClosed.get(), 401);
            assertEquals(connectedEvent.getLong("worker_epoch"),
                    firstKey.workerEpoch());
            assertEquals(connectedEvent.getLong("connection_generation"),
                    firstKey.connectionGeneration());

            assertTrue(client.debugKillWorker(firstConnection));
            assertTrue(replaced.await(10, TimeUnit.SECONDS));
            JSONObject replacementEvent = replacement.get();
            assertNotNull(replacementEvent);
            assertTrue(replacementEvent.getInt("pid") != connectedEvent.getInt("pid"));
            assertTrue(replacementEvent.getLong("worker_epoch")
                    != connectedEvent.getLong("worker_epoch"));
            assertTrue(replacementEvent.getLong("connection_generation")
                    > connectedEvent.getLong("connection_generation"));
            CameraWorkerClient.ConnectionSnapshot secondConnection =
                    client.debugConnectionSnapshot();
            assertNotNull(secondConnection);
            assertFalse(CameraWorkerClient.matchesSnapshot(
                    secondConnection, firstConnection));
            assertFalse(client.debugApplyStaleFailure(firstConnection));
            assertTrue(CameraWorkerClient.matchesSnapshot(
                    secondConnection, client.debugConnectionSnapshot()));

            secondTexture = new SurfaceTexture(0);
            assertSurfaceResult(client.debugSendSurface(
                    new Surface(secondTexture), 402));
            assertTrue(secondLifecycle.await(5, TimeUnit.SECONDS));
            CameraEventKey secondKey = assertLifecycle(
                    secondAttached.get(), secondClosed.get(), 402);
            assertEquals(replacementEvent.getLong("worker_epoch"),
                    secondKey.workerEpoch());
            assertEquals(replacementEvent.getLong("connection_generation"),
                    secondKey.connectionGeneration());
        } finally {
            client.shutdown("instrumentation_complete");
            if (firstTexture != null) firstTexture.release();
            if (secondTexture != null) secondTexture.release();
        }
    }

    private static void assertSurfaceResult(String line) throws Exception {
        JSONObject result = new JSONObject(line);
        assertEquals("camera_opened", result.getString("kind"));
        assertTrue(result.getBoolean("debug_surface_valid"));
        assertTrue(result.getBoolean("debug_surface_released"));
    }

    private static CameraEventKey assertLifecycle(
            JSONObject attached, JSONObject closed, int requestId) throws Exception {
        assertNotNull(attached);
        assertNotNull(closed);
        assertTrue(attached.getBoolean("debug_surface_valid"));
        assertTrue(closed.getBoolean("debug_surface_released"));
        CameraEventKey attachedKey = CameraEventKey.fromEvent(attached);
        CameraEventKey closedKey = CameraEventKey.fromEvent(closed);
        assertNotNull(attachedKey);
        assertEquals(attachedKey, closedKey);
        assertEquals(CameraHelperMain.CAMERA_OWNER_ACTIVITY, attachedKey.owner());
        assertEquals(requestId, attachedKey.requestId());
        assertTrue(attachedKey.producerEpoch() > 0);
        return attachedKey;
    }

    private static Context currentApplication() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
        currentApplication.setAccessible(true);
        Object value = currentApplication.invoke(null);
        if (!(value instanceof Application)) {
            throw new IllegalStateException("target Application is unavailable");
        }
        return ((Application) value).getApplicationContext();
    }

}
