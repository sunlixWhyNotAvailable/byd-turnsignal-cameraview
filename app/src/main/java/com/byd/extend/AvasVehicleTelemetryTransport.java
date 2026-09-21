package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed Power/Lock BYDAutoManager subscription plus raw transaction-5 GET snapshots. */
final class AvasVehicleTelemetryTransport implements AvasTelemetryController.Transport {
    static final int POWER_DEVICE = AvasTelemetryController.POWER_DEVICE;
    static final int POWER_FID = AvasTelemetryController.POWER_FID;
    static final int LOCK_DEVICE = AvasTelemetryController.LOCK_DEVICE;
    static final int LOCK_FID = AvasTelemetryController.LOCK_FID;

    private final Context context;
    private IBinder service;

    AvasVehicleTelemetryTransport(Context context) { this.context = context; }

    @Override public AvasTelemetryController.Subscription subscribe(
            AvasTelemetryController.Listener callback) throws Exception {
        Object manager = context.getSystemService("auto");
        if (manager == null) throw new IllegalStateException("BYDAutoManager unavailable");
        Class<?> listenerType = Class.forName("android.hardware.BYDAutoManager$OnBYDAutoListener");
        AtomicBoolean open = new AtomicBoolean(true);
        Object listener = Proxy.newProxyInstance(AvasVehicleTelemetryTransport.class.getClassLoader(),
                new Class<?>[]{listenerType}, (proxy, method, values) -> {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return values != null && values.length == 1 && proxy == values[0];
                        case "toString": return "AvasPowerLockListener";
                        case "onChanged":
                            long received = SystemClock.elapsedRealtime();
                            if (open.get() && values != null && values.length >= 3
                                    && values[0] instanceof Integer && values[1] instanceof Integer
                                    && values[2] instanceof Integer) {
                                callback.onValue((Integer) values[0], (Integer) values[1],
                                        (Integer) values[2], received);
                            }
                            return null;
                        case "onError":
                            if (open.get()) callback.onError(values == null ? "listener_error"
                                    : java.util.Arrays.toString(values), SystemClock.elapsedRealtime());
                            return null;
                        default: return null;
                    }
                });
        Class<?> managerType = manager.getClass();
        Method unregister = managerType.getMethod("unregisterListener", listenerType);
        Method enable = managerType.getMethod("enableDevice", int.class, int[].class);
        Method disable = managerType.getMethod("disableDevice", int.class);
        Set<Integer> enabledDevices = new LinkedHashSet<>();
        managerType.getMethod("registerListener", listenerType).invoke(manager, listener);
        try {
            requireEnabled(enable, manager, POWER_DEVICE, POWER_FID);
            enabledDevices.add(POWER_DEVICE);
            requireEnabled(enable, manager, LOCK_DEVICE, LOCK_FID);
            enabledDevices.add(LOCK_DEVICE);
        } catch (Exception failure) {
            open.set(false);
            for (Integer device : enabledDevices) {
                try { disable.invoke(manager, device); } catch (Exception ignored) {}
            }
            try { unregister.invoke(manager, listener); } catch (Exception ignored) {}
            throw failure;
        }
        return () -> {
            if (!open.compareAndSet(true, false)) return;
            for (Integer device : enabledDevices) {
                try { disable.invoke(manager, device); } catch (Exception ignored) {}
            }
            try { unregister.invoke(manager, listener); } catch (Exception ignored) {}
        };
    }

    private static void requireEnabled(Method enable, Object manager, int device, int fid)
            throws Exception {
        Object result = enable.invoke(manager, device, new int[]{fid});
        if (!(result instanceof Number) || ((Number) result).intValue() != 0) {
            throw new IllegalStateException("enableDevice failed device=" + device
                    + " fid=" + fid + " status=" + result);
        }
    }

    @Override public AvasTelemetryController.Snapshot read() {
        return new AvasTelemetryController.Snapshot(readInt(POWER_DEVICE, POWER_FID),
                readInt(LOCK_DEVICE, LOCK_FID));
    }

    private int readInt(int device, int fid) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder autoservice = service();
            data.writeInterfaceToken(autoservice.getInterfaceDescriptor());
            data.writeInt(device);
            data.writeInt(fid);
            if (!autoservice.transact(5, data, reply, 0) || reply.dataAvail() < 8) return -1;
            int status = reply.readInt();
            int value = reply.readInt();
            return status == 0 ? value : -1;
        } catch (Exception ignored) {
            synchronized (this) { service = null; }
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private synchronized IBinder service() throws Exception {
        if (service == null || !service.isBinderAlive()) {
            service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "autoservice");
        }
        if (service == null) throw new IllegalStateException("autoservice unavailable");
        return service;
    }
}
