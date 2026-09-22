package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

import org.junit.Test;

public final class FixedBydTelemetryManagerTest {
    private static final int POWER = AvasTelemetryController.POWER_FID;
    private static final int STEERING = TurnSignalTelemetryController.STEERING_FID;
    private static final int SPEED = TurnSignalTelemetryController.SPEED_FID;

    @Test public void closeReconfiguresOverlappingDeviceWithoutDisablingOtherOwner()
            throws Exception {
        FakeManager backend = new FakeManager();
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        FixedBydTelemetryManager.Subscription avas = owner.subscribe(request(1001, POWER),
                new RecordingListener());
        FixedBydTelemetryManager.Subscription guard = owner.subscribe(request(1001, STEERING),
                new RecordingListener());

        assertArrayEquals(new int[]{POWER, STEERING}, backend.enabled.get(1001));
        guard.close();
        assertArrayEquals(new int[]{POWER}, backend.enabled.get(1001));
        assertEquals(0, backend.disable1001Count);
        assertEquals(0, backend.unregisterCount);

        avas.close();
        assertFalse(backend.enabled.containsKey(1001));
        assertEquals(1, backend.disable1001Count);
        assertEquals(1, backend.unregisterCount);
    }

    @Test public void failedAddRollsBackAndLeavesExistingOwnerSubscribed() throws Exception {
        FakeManager backend = new FakeManager();
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        RecordingListener avasListener = new RecordingListener();
        FixedBydTelemetryManager.Subscription avas = owner.subscribe(request(1001, POWER),
                avasListener);
        backend.failDevices.add(1004);

        try {
            owner.subscribe(new FixedBydTelemetryManager.Request[]{
                    new FixedBydTelemetryManager.Request(1001, STEERING),
                    new FixedBydTelemetryManager.Request(1004,
                            TurnSignalTelemetryController.STALK_FID)}, new RecordingListener());
            fail("expected enable failure");
        } catch (Exception expected) {}

        assertArrayEquals(new int[]{POWER}, backend.enabled.get(1001));
        assertFalse(backend.enabled.containsKey(1004));
        assertEquals(0, avasListener.errors);
        assertEquals(0, backend.unregisterCount);
        avas.close();
    }

    @Test public void rollbackFailureNotifiesExistingOwnerToEnterSafeFallback() throws Exception {
        FakeManager backend = new FakeManager();
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        RecordingListener avasListener = new RecordingListener();
        FixedBydTelemetryManager.Subscription avas = owner.subscribe(request(1001, POWER),
                avasListener);
        backend.failDevices.add(1004); // Fail after the device-1001 union was applied.
        backend.failDevices.add(1001); // Then fail restoring AVAS-only ownership.

        try {
            owner.subscribe(new FixedBydTelemetryManager.Request[]{
                    new FixedBydTelemetryManager.Request(1001, STEERING),
                    new FixedBydTelemetryManager.Request(1004,
                            TurnSignalTelemetryController.STALK_FID)}, new RecordingListener());
            fail("expected enable failure");
        } catch (Exception expected) {}

        assertEquals(1, avasListener.errors);
        assertTrue(avasListener.lastError.startsWith("subscription_rollback_failed:"));
        avas.close();
    }

    @Test public void floatAndIntegerOverloadsReachMatchingConsumersWithoutConversionLoss()
            throws Exception {
        FakeManager backend = new FakeManager();
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        RecordingListener guard = new RecordingListener();
        RecordingListener avas = new RecordingListener();
        FixedBydTelemetryManager.Subscription guardSubscription = owner.subscribe(
                new FixedBydTelemetryManager.Request[]{
                        new FixedBydTelemetryManager.Request(1001,
                                FixedBydTelemetryManager.ValueType.FLOAT, STEERING),
                        new FixedBydTelemetryManager.Request(1013,
                                FixedBydTelemetryManager.ValueType.FLOAT, SPEED)}, guard);
        FixedBydTelemetryManager.Subscription avasSubscription = owner.subscribe(
                request(1001, POWER), avas);

        backend.listener.onChanged(1001, STEERING, -2.4f, null);
        assertEquals(Float.floatToRawIntBits(-2.4f), guard.lastValue);
        backend.listener.onChanged(1013, SPEED, 21.5f, null);
        assertEquals(Float.floatToRawIntBits(21.5f), guard.lastValue);
        backend.listener.onChanged(1001, POWER, 2, null);

        assertEquals(2, avas.lastValue);
        assertEquals(0, guard.errors);
        assertEquals(0, avas.errors);
        guardSubscription.close();
        avasSubscription.close();
    }

    @Test public void callbackTypeMismatchNotifiesOnlyConsumerOfThatSignal() throws Exception {
        FakeManager backend = new FakeManager();
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        RecordingListener guard = new RecordingListener();
        RecordingListener avas = new RecordingListener();
        FixedBydTelemetryManager.Subscription guardSubscription = owner.subscribe(
                new FixedBydTelemetryManager.Request[]{
                        new FixedBydTelemetryManager.Request(1001,
                                FixedBydTelemetryManager.ValueType.FLOAT, STEERING)}, guard);
        FixedBydTelemetryManager.Subscription avasSubscription = owner.subscribe(
                request(1001, POWER), avas);

        backend.listener.onChanged(1001, STEERING, 12, null);

        assertEquals(1, guard.errors);
        assertTrue(guard.lastError.contains("expected=FLOAT"));
        assertEquals(0, avas.errors);
        guardSubscription.close();
        avasSubscription.close();
    }

    private static FixedBydTelemetryManager.Request[] request(int device, int fid) {
        return new FixedBydTelemetryManager.Request[]{
                new FixedBydTelemetryManager.Request(device, fid)};
    }

    public interface FakeOnAutoListener {
        void onChanged(int device, int fid, int value, Object client);
        void onChanged(int device, int fid, float value, Object client);
        void onError(int code, String message);
    }

    public static final class FakeManager {
        final Map<Integer, int[]> enabled = new LinkedHashMap<>();
        final Queue<Integer> failDevices = new ArrayDeque<>();
        int disable1001Count;
        int unregisterCount;
        FakeOnAutoListener listener;
        public void registerListener(FakeOnAutoListener listener) { this.listener = listener; }
        public void unregisterListener(FakeOnAutoListener listener) { unregisterCount++; }
        public int enableDevice(int device, int[] fids) {
            if (!failDevices.isEmpty() && failDevices.peek() == device) {
                failDevices.remove();
                return -1;
            }
            enabled.put(device, Arrays.copyOf(fids, fids.length));
            return 0;
        }
        public int disableDevice(int device) {
            enabled.remove(device);
            if (device == 1001) disable1001Count++;
            return 0;
        }
    }

    private static final class RecordingListener implements FixedBydTelemetryManager.Listener {
        int errors;
        int lastValue;
        String lastError = "";
        @Override public void onValue(int device, int fid, int value, long receivedMs) {
            lastValue = value;
        }
        @Override public void onError(String reason, long receivedMs) {
            errors++;
            lastError = reason;
        }
    }
}
