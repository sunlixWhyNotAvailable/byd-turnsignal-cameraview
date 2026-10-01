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

    @Test public void sharedDeviceKeepsAdditiveSupersetUntilLastOwnerCloses()
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
        assertArrayEquals(new int[]{POWER, STEERING}, backend.enabled.get(1001));

        avas.close();
        assertFalse(backend.enabled.containsKey(1001));
    }

    @Test public void failedAddKeepsExistingOwnerAndDoesNotDisableItsDevice() throws Exception {
        FakeManager backend = new FakeManager();
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        RecordingListener avasListener = new RecordingListener();
        FixedBydTelemetryManager.Subscription avas = owner.subscribe(request(1001, POWER), avasListener);
        backend.failDevices.add(1004);

        try {
            owner.subscribe(new FixedBydTelemetryManager.Request[]{
                    new FixedBydTelemetryManager.Request(1001, STEERING),
                    new FixedBydTelemetryManager.Request(1004,
                            TurnSignalTelemetryController.STALK_FID)}, new RecordingListener());
            fail("expected enable failure");
        } catch (Exception expected) {}

        assertArrayEquals(new int[]{POWER, STEERING}, backend.enabled.get(1001));
        assertFalse(backend.enabled.containsKey(1004));
        assertEquals(0, avasListener.errors);
        backend.listener.onChanged(1001, POWER, 2, null);
        assertEquals(2, avasListener.lastValue);
        avas.close();
    }

    @Test public void duplicateEnableStatusOneIsAlreadySubscribedSuccess() throws Exception {
        FakeManager backend = new FakeManager();
        backend.enableExternal(1011, 555745336);
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        RecordingListener reverse = new RecordingListener();
        FixedBydTelemetryManager.Subscription subscription = owner.subscribe(
                request(1011, 555745336), reverse);
        backend.listener.onChanged(1011, 555745336, 4, null);
        assertEquals(4, reverse.lastValue);
        assertTrue(FixedBydTelemetryManager.isEnableSuccess(0));
        assertTrue(FixedBydTelemetryManager.isEnableSuccess(1));
        assertFalse(FixedBydTelemetryManager.isEnableSuccess(2));
        subscription.close();
    }

    @Test public void closingReverseOwnerKeepsEngineGearAndViceVersa() throws Exception {
        assertSharedGearOwnerLifecycle(true);
        assertSharedGearOwnerLifecycle(false);
    }

    @Test public void failedDisableKeepsTheConfirmedEnabledSet() throws Exception {
        FakeManager backend = new FakeManager();
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        FixedBydTelemetryManager.Subscription first = owner.subscribe(
                request(1011, 555745336), new RecordingListener());
        backend.disableStatus = -1;
        first.close();
        assertArrayEquals(new int[]{555745336}, backend.enabled.get(1011));

        backend.failDevices.add(1011);
        FixedBydTelemetryManager.Subscription second = owner.subscribe(
                request(1011, 555745336), new RecordingListener());
        assertArrayEquals(new int[]{555745336}, backend.enabled.get(1011));
        second.close();
    }

    private static void assertSharedGearOwnerLifecycle(boolean engineFirst) throws Exception {
        FakeManager backend = new FakeManager();
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        RecordingListener engineListener = new RecordingListener();
        RecordingListener reverseListener = new RecordingListener();
        FixedBydTelemetryManager.Subscription engine;
        FixedBydTelemetryManager.Subscription reverse;
        if (engineFirst) {
            engine = owner.subscribe(request(1011, 555745336), engineListener);
            reverse = owner.subscribe(request(1011, 555745336), reverseListener);
        } else {
            reverse = owner.subscribe(request(1011, 555745336), reverseListener);
            engine = owner.subscribe(request(1011, 555745336), engineListener);
        }
        if (engineFirst) reverse.close(); else engine.close();
        assertArrayEquals(new int[]{555745336}, backend.enabled.get(1011));
        backend.listener.onChanged(1011, 555745336, 4, null);
        if (engineFirst) assertEquals(4, engineListener.lastValue);
        else assertEquals(4, reverseListener.lastValue);

        if (engineFirst) engine.close(); else reverse.close();
        assertFalse(backend.enabled.containsKey(1011));
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
        int disableStatus;
        int unregisterCount;
        FakeOnAutoListener listener;
        public void registerListener(FakeOnAutoListener listener) { this.listener = listener; }
        public void unregisterListener(FakeOnAutoListener listener) { unregisterCount++; }
        public int enableDevice(int device, int[] fids) {
            if (!failDevices.isEmpty() && failDevices.peek() == device) {
                failDevices.remove();
                return -1;
            }
            int[] current = enabled.getOrDefault(device, new int[0]);
            boolean added = false;
            for (int fid : fids) {
                if (contains(current, fid)) continue;
                current = Arrays.copyOf(current, current.length + 1);
                current[current.length - 1] = fid;
                added = true;
            }
            if (!added) {
                return 1;
            }
            enabled.put(device, current);
            return 0;
        }
        public int disableDevice(int device) {
            if (disableStatus != 0) return disableStatus;
            enabled.remove(device);
            return 0;
        }

        void enableExternal(int device, int fid) { enabled.put(device, new int[]{fid}); }

        private static boolean contains(int[] values, int fid) {
            for (int value : values) if (value == fid) return true;
            return false;
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
