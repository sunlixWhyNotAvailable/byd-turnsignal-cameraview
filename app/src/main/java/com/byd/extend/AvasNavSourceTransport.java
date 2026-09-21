package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Fixed read-only BYD NAV_SOURCE callback/GET transport. */
final class AvasNavSourceTransport implements AvasNavSourceGate.Transport {
    private final Context context;

    AvasNavSourceTransport(Context context) { this.context = context; }

    @Override public AvasNavSourceGate.Subscription subscribe(AvasNavSourceGate.Listener callback)
            throws Exception {
        Class<?> deviceClass = Class.forName("android.hardware.bydauto.audio.BYDAutoAudioDevice");
        Object device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);
        Class<?> listenerClass = Class.forName("android.hardware.IBYDAutoListener");
        Object listener = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, values) -> {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == values[0];
                        case "toString": return "AvasNavSourceListener";
                        case "onDataEventChanged":
                            if ((Integer) values[0] == AvasNavSourceGate.FID) {
                                int value = values[1].getClass().getField("intValue")
                                        .getInt(values[1]);
                                callback.changed(value);
                            }
                            return null;
                        case "onError":
                            callback.error(String.valueOf(values[0]) + ":" + String.valueOf(values[1]));
                            return null;
                        default: return null;
                    }
                });
        Method unregister = deviceClass.getMethod("unregisterListener", listenerClass);
        try {
            deviceClass.getMethod("registerListener", listenerClass, int[].class)
                    .invoke(device, listener, new int[]{AvasNavSourceGate.FID});
        } catch (Throwable failure) {
            try { unregister.invoke(device, listener); } catch (Throwable ignored) { }
            throw asException(failure);
        }
        return () -> unregister.invoke(device, listener);
    }

    @Override public AvasNavSourceGate.Snapshot read() throws Exception {
        IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "autoservice");
        if (service == null) throw new IllegalStateException("autoservice unavailable");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            data.writeInt(AvasNavSourceGate.DEVICE);
            data.writeInt(AvasNavSourceGate.FID);
            if (!service.transact(5, data, reply, 0) || reply.dataAvail() < 8) {
                throw new IllegalStateException("NAV_SOURCE GET missing/short reply");
            }
            return new AvasNavSourceGate.Snapshot(reply.readInt(), reply.readInt());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static Exception asException(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure instanceof Exception ? (Exception) failure : new Exception(failure);
    }
}
