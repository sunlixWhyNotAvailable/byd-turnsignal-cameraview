package com.byd.turnsignalguard.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ClusterFullscreenProtocol {
    private static final ComponentName SERVICE = new ComponentName(
            "com.byd.launchermap", "com.autosdk.protocol.service.ProtocolService");
    private static final String SERVICE_ACTION =
            "action.com.autosdk.protocol.ProtocolService";
    private static final String DESCRIPTOR =
            "com.autosdk.protocol.IProtocolAidlInterface";
    private static final int TRANSACTION_SET_PROTOCOL_MODEL = 1;
    private static final int PROTOCOL_INSTRUMENT_NAVIGATION_TYPE = 30011;
    private static final int ACTION_SET = 1;
    private static final int OPERATION_FULLSCREEN = 4;
    private static final long TIMEOUT_MS = 3_000;

    private ClusterFullscreenProtocol() {}

    static String dispatch(Context context) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IBinder> binder = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder.set(service);
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                binder.set(null);
                connected.countDown();
            }
        };
        boolean bound = false;
        try {
            Intent intent = new Intent(SERVICE_ACTION).setComponent(SERVICE);
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) return "bind rejected";
            if (!await(connected, deadlineNanos)) {
                return "bind timeout after " + TIMEOUT_MS + "ms";
            }
            IBinder service = binder.get();
            if (service == null) return "service disconnected";
            CountDownLatch transacted = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>("transaction failed");
            new Thread(() -> {
                try {
                    result.set(transact(service, context.getPackageName()));
                } finally {
                    transacted.countDown();
                }
            }, "BydTurnCameraProtocol30011").start();
            if (!await(transacted, deadlineNanos)) {
                return "transaction outcome indeterminate after " + TIMEOUT_MS + "ms";
            }
            return result.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (RuntimeException error) {
            return error.getClass().getSimpleName() + ": " + safe(error.getMessage());
        } finally {
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static boolean await(CountDownLatch latch, long deadlineNanos)
            throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 && latch.await(remaining, TimeUnit.NANOSECONDS);
    }

    private static String transact(IBinder binder, String packageName) {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt(1);
            writeProtocolModel(request, packageName);
            if (!binder.transact(TRANSACTION_SET_PROTOCOL_MODEL, request, reply, 0)) {
                return "transaction rejected";
            }
            reply.readException();
            return "";
        } catch (Exception error) {
            return error.getClass().getSimpleName() + ": " + safe(error.getMessage());
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private static void writeProtocolModel(Parcel parcel, String packageName) {
        parcel.writeInt(PROTOCOL_INSTRUMENT_NAVIGATION_TYPE);
        parcel.writeLong(System.currentTimeMillis());
        parcel.writeInt(0);
        parcel.writeString("0");
        parcel.writeString(packageName);
        parcel.writeString("");
        parcel.writeInt(ACTION_SET);
        parcel.writeInt(OPERATION_FULLSCREEN);
        parcel.writeString("");
        parcel.writeString("");
        parcel.writeInt(0);
        parcel.writeString(null);
        parcel.writeString(null);
        parcel.writeString("");
        parcel.writeString(null);
        parcel.writeString(null);
        parcel.writeInt(1);
        parcel.writeInt(0);
        parcel.writeInt(0);
        parcel.writeInt(1);
    }

    static int protocolForTest() {
        return PROTOCOL_INSTRUMENT_NAVIGATION_TYPE;
    }

    static int operationForTest() {
        return OPERATION_FULLSCREEN;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
