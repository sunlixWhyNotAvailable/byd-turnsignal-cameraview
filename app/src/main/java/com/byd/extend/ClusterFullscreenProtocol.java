package com.byd.extend;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

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
    private static final long SERVICE_READY_TIMEOUT_MS = 15_000;
    private static final long TRANSACTION_TIMEOUT_MS = 3_000;
    private static final AtomicBoolean TRANSACTION_IN_FLIGHT = new AtomicBoolean();

    static final class Result {
        final String error;
        final boolean retryable, indeterminate;

        Result(String error, boolean retryable, boolean indeterminate) {
            this.error = error;
            this.retryable = retryable;
            this.indeterminate = indeterminate;
        }

        boolean succeeded() { return error.isEmpty(); }
        String state() { return succeeded() ? "success" : indeterminate ? "indeterminate" : "failed"; }
    }

    private ClusterFullscreenProtocol() {}

    static Result dispatch(Context context, BooleanSupplier current,
            CancellationSignal cancellation) {
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
        boolean submitted = false;
        try {
            cancellation.setOnCancelListener(connected::countDown);
            if (cancellation.isCanceled() || !current.getAsBoolean())
                return new Result("cancelled", false, false);
            if (TRANSACTION_IN_FLIGHT.get())
                return new Result("previous transaction still in flight", false, true);
            Intent intent = new Intent(SERVICE_ACTION).setComponent(SERVICE);
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) return new Result("bind rejected", true, false);
            long serviceDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(SERVICE_READY_TIMEOUT_MS);
            if (!await(connected, serviceDeadlineNanos)) {
                return new Result("service readiness timeout after "
                        + SERVICE_READY_TIMEOUT_MS + "ms", true, false);
            }
            if (cancellation.isCanceled() || !current.getAsBoolean())
                return new Result("cancelled", false, false);
            IBinder service = binder.get();
            if (service == null) return new Result("service disconnected", true, false);
            if (cancellation.isCanceled() || !current.getAsBoolean())
                return new Result("cancelled", false, false);
            if (!TRANSACTION_IN_FLIGHT.compareAndSet(false, true))
                return new Result("previous transaction still in flight", false, true);
            CountDownLatch transacted = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>("transaction failed");
            long transactionDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(TRANSACTION_TIMEOUT_MS);
            try {
                new Thread(() -> {
                    try {
                        result.set(!cancellation.isCanceled() && current.getAsBoolean()
                                ? transact(service, context.getPackageName()) : "cancelled");
                    } finally {
                        TRANSACTION_IN_FLIGHT.set(false);
                        transacted.countDown();
                    }
                }, "BydExtendCameraProtocol30011").start();
                submitted = true;
            } catch (RuntimeException | Error error) {
                TRANSACTION_IN_FLIGHT.set(false);
                throw error;
            }
            if (!await(transacted, transactionDeadlineNanos)) {
                return new Result("transaction outcome indeterminate after "
                        + TRANSACTION_TIMEOUT_MS + "ms",
                        false, true);
            }
            return new Result(result.get(), false, false);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result(stackTrace(error), false, submitted);
        } catch (RuntimeException error) {
            return new Result("cluster fullscreen dispatch failed:\n"
                    + stackTrace(error), false, submitted);
        } finally {
            cancellation.setOnCancelListener(null);
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (RuntimeException error) {
                    Log.e("BydExtendCameraProtocol", "cluster fullscreen unbind failed", error);
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
            return "cluster fullscreen transaction failed:\n"
                    + stackTrace(error);
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

    private static String stackTrace(Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }

}
