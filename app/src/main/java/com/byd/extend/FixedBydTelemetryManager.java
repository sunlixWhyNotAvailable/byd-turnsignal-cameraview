package com.byd.extend;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide owner for the small, fixed set of BYDAutoManager telemetry subscriptions. */
final class FixedBydTelemetryManager {
    interface Listener {
        void onValue(int device, int fid, int value, long receivedMs);
        void onError(String reason, long receivedMs);
    }

    interface Subscription { void close(); }

    enum ValueType { INTEGER, FLOAT }

    static final class Request {
        final int device;
        final int[] fids;
        final ValueType valueType;
        Request(int device, int... fids) {
            this(device, ValueType.INTEGER, fids);
        }
        Request(int device, ValueType valueType, int... fids) {
            this.device = device;
            this.valueType = valueType;
            this.fids = fids.clone();
        }
    }

    private static FixedBydTelemetryManager instance;

    static synchronized FixedBydTelemetryManager get(Context context) throws Exception {
        if (instance == null) instance = new FixedBydTelemetryManager(context);
        return instance;
    }

    private final Object manager;
    private final Class<?> listenerType;
    private final Method register;
    private final Method unregister;
    private final Method enable;
    private final Method disable;
    private final Object managerListener;
    private final Map<Long, Client> clients = new LinkedHashMap<>();
    private final Map<Integer, Set<Integer>> enabled = new LinkedHashMap<>();
    private long nextId;
    private boolean registered;

    private FixedBydTelemetryManager(Context context) throws Exception {
        this(resolveManager(context),
                Class.forName("android.hardware.BYDAutoManager$OnBYDAutoListener"));
    }

    FixedBydTelemetryManager(Object manager, Class<?> listenerType) throws Exception {
        this.manager = manager;
        this.listenerType = listenerType;
        if (manager == null) throw new IllegalStateException("BYDAutoManager unavailable");
        Class<?> managerType = manager.getClass();
        register = managerType.getMethod("registerListener", listenerType);
        unregister = managerType.getMethod("unregisterListener", listenerType);
        enable = managerType.getMethod("enableDevice", int.class, int[].class);
        disable = managerType.getMethod("disableDevice", int.class);
        managerListener = createListenerProxy();
    }

    private static Object resolveManager(Context context) {
        Context application = context.getApplicationContext();
        return (application == null ? context : application).getSystemService("auto");
    }

    private Object createListenerProxy() {
        return Proxy.newProxyInstance(FixedBydTelemetryManager.class.getClassLoader(),
                new Class<?>[]{listenerType}, (proxy, method, values) -> {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return values != null && values.length == 1
                                && proxy == values[0];
                        case "toString": return "FixedBydTelemetryListener";
                        case "onChanged":
                            if (values != null && values.length >= 3
                                    && values[0] instanceof Integer
                                    && values[1] instanceof Integer) {
                                dispatchCallback((Integer) values[0], (Integer) values[1],
                                        values[2], elapsedRealtime());
                            }
                            return null;
                        case "onError":
                            dispatchError(values == null ? "listener_error"
                                    : Arrays.toString(values), elapsedRealtime());
                            return null;
                        default: return null;
                    }
                });
    }

    synchronized Subscription subscribe(Request[] requests, Listener listener) throws Exception {
        if (requests == null || requests.length == 0) {
            throw new IllegalArgumentException("fixed requests required");
        }
        long id = ++nextId;
        Client client = new Client(requests, listener);
        clients.put(id, client);
        try {
            if (!registered) {
                register.invoke(manager, managerListener);
                registered = true;
            }
            enableMissing(union());
            client.active = true;
        } catch (Exception failure) {
            Client removed = clients.remove(id);
            if (removed != null) disableUnownedDevices(devices(removed));
            if (clients.isEmpty()) {
                if (registered) {
                    try { unregister.invoke(manager, managerListener); } catch (Exception ignored) {}
                    registered = false;
                }
            }
            throw failure;
        }
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (!open.compareAndSet(true, false)) return;
            unsubscribe(id);
        };
    }

    private synchronized void unsubscribe(long id) {
        Client removed = clients.remove(id);
        if (removed == null) return;
        disableUnownedDevices(devices(removed));
        if (clients.isEmpty() && registered) {
            try { unregister.invoke(manager, managerListener); } catch (Exception ignored) {}
            registered = false;
        }
    }

    private synchronized void dispatchCallback(
            int device, int fid, Object value, long receivedMs) {
        List<Client> snapshot = new ArrayList<>(clients.values());
        for (Client client : snapshot) {
            if (!client.active || !client.accepts(device, fid)) continue;
            ValueType expected = client.valueType(device, fid);
            Integer raw = callbackRaw(expected, value);
            try {
                if (raw != null) {
                    client.listener.onValue(device, fid, raw, receivedMs);
                } else {
                    client.listener.onError("callback_type_mismatch device=" + device
                            + " fid=" + fid + " expected=" + expected
                            + " actual=" + (value == null ? "null"
                                    : value.getClass().getSimpleName()), receivedMs);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    static Integer callbackRaw(ValueType expected, Object value) {
        if (expected == ValueType.FLOAT && value instanceof Float) {
            return Float.floatToRawIntBits((Float) value);
        }
        if (expected == ValueType.INTEGER && value instanceof Integer) return (Integer) value;
        return null;
    }

    private synchronized void dispatchError(String reason, long receivedMs) {
        for (Client client : new ArrayList<>(clients.values())) {
            if (client.active) {
                try { client.listener.onError(reason, receivedMs); }
                catch (RuntimeException ignored) {}
            }
        }
    }

    private static long elapsedRealtime() {
        try { return SystemClock.elapsedRealtime(); }
        catch (RuntimeException | LinkageError unavailableInLocalJvm) { return 0L; }
    }

    private Map<Integer, int[]> union() {
        Request[][] groups = new Request[clients.size()][];
        int group = 0;
        for (Client client : clients.values()) groups[group++] = client.requests;
        return unionForTest(groups);
    }

    static Map<Integer, int[]> unionForTest(Request[]... groups) {
        Map<Integer, Set<Integer>> sets = new LinkedHashMap<>();
        for (Request[] requests : groups) {
            for (Request request : requests) {
                Set<Integer> fids = sets.computeIfAbsent(
                        request.device, ignored -> new LinkedHashSet<>());
                for (int fid : request.fids) fids.add(fid);
            }
        }
        Map<Integer, int[]> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : sets.entrySet()) {
            int[] fids = new int[entry.getValue().size()];
            int index = 0;
            for (Integer fid : entry.getValue()) fids[index++] = fid;
            result.put(entry.getKey(), fids);
        }
        return result;
    }

    private void enableMissing(Map<Integer, int[]> desired) throws Exception {
        for (Map.Entry<Integer, int[]> entry : desired.entrySet()) {
            Set<Integer> actual = enabled.get(entry.getKey());
            int[] missing = Arrays.stream(entry.getValue())
                    .filter(fid -> actual == null || !actual.contains(fid)).toArray();
            if (missing.length == 0) continue;
            Object status = enable.invoke(manager, entry.getKey(), missing);
            if (!(status instanceof Number)
                    || !isEnableSuccess(((Number) status).intValue())) {
                throw new IllegalStateException("enableDevice failed device=" + entry.getKey()
                        + " status=" + status);
            }
            Set<Integer> updated = enabled.computeIfAbsent(
                    entry.getKey(), ignored -> new LinkedHashSet<>());
            for (int fid : missing) updated.add(fid);
        }
    }

    static boolean isEnableSuccess(int status) {
        return status == 0 || status == 1;
    }

    private void disableUnownedDevices(Set<Integer> devices) {
        for (Integer device : devices) {
            if (!enabled.containsKey(device)) continue;
            if (hasClient(device)) continue;
            try {
                Object status = disable.invoke(manager, device);
                if (status instanceof Number && ((Number) status).intValue() == 0) {
                    enabled.remove(device);
                } else {
                    logDisableFailure(device, "status=" + status);
                }
            } catch (Exception failure) {
                // Keep the confirmed enabled set when native teardown fails.
                logDisableFailure(device, failure.toString());
            }
        }
    }

    private static Set<Integer> devices(Client client) {
        Set<Integer> devices = new LinkedHashSet<>();
        for (Request request : client.requests) devices.add(request.device);
        return devices;
    }

    private static void logDisableFailure(int device, String reason) {
        String message = "disableDevice failed device=" + device + " " + reason;
        if (message.length() > 160) message = message.substring(0, 160);
        try { Log.w("FixedBydTelemetry", message); }
        catch (RuntimeException | LinkageError ignored) {}
    }

    private boolean hasClient(int device) {
        for (Client client : clients.values()) {
            for (Request request : client.requests) {
                if (request.device == device) return true;
            }
        }
        return false;
    }

    private static final class Client {
        final Request[] requests;
        final Listener listener;
        boolean active;
        Client(Request[] requests, Listener listener) {
            this.requests = requests.clone();
            this.listener = listener;
        }
        boolean accepts(int device, int fid) {
            for (Request request : requests) {
                if (request.device != device) continue;
                for (int accepted : request.fids) if (accepted == fid) return true;
            }
            return false;
        }
        ValueType valueType(int device, int fid) {
            for (Request request : requests) {
                if (request.device != device) continue;
                for (int accepted : request.fids) {
                    if (accepted == fid) return request.valueType;
                }
            }
            throw new IllegalArgumentException("unsubscribed signal");
        }
    }
}
