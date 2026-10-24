package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed four-signal guard telemetry transport; it exposes no caller-selected FIDs. */
final class TurnSignalTelemetryTransport implements TurnSignalTelemetryController.Transport {
    private static final FixedBydTelemetryManager.Request[] REQUESTS = {
            new FixedBydTelemetryManager.Request(
                    TurnSignalTelemetryController.STALK_DEVICE,
                    TurnSignalTelemetryController.STALK_FID,
                    TurnSignalTelemetryController.BLINK_FID),
            new FixedBydTelemetryManager.Request(TurnSignalTelemetryController.STEERING_DEVICE,
                    FixedBydTelemetryManager.ValueType.FLOAT,
                    TurnSignalTelemetryController.STEERING_FID),
            new FixedBydTelemetryManager.Request(TurnSignalTelemetryController.SPEED_DEVICE,
                    FixedBydTelemetryManager.ValueType.FLOAT,
                    TurnSignalTelemetryController.SPEED_FID)
    };

    private final Context context;
    private IBinder service;
    private volatile TurnSignalTelemetryController.DiagnosticSink diagnosticSink =
            (event, fields) -> {};

    TurnSignalTelemetryTransport(Context context) { this.context = context; }

    @Override public void setDiagnosticSink(
            TurnSignalTelemetryController.DiagnosticSink sink) {
        diagnosticSink = sink == null ? (event, fields) -> {} : sink;
    }

    @Override public TurnSignalTelemetryController.Subscription subscribe(
            TurnSignalTelemetryController.Listener listener) throws Exception {
        return subscribe(FixedBydTelemetryManager.get(context), listener);
    }

    private TurnSignalTelemetryController.Subscription subscribe(
            FixedBydTelemetryManager manager, TurnSignalTelemetryController.Listener listener)
            throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        FixedBydTelemetryManager.Subscription subscription =
                manager.subscribe(REQUESTS,
                        new FixedBydTelemetryManager.Listener() {
                            @Override public void onValue(
                                    int device, int fid, int value, long receivedMs) {
                                if (open.get()) listener.onValue(device, fid, value, receivedMs);
                            }
                            @Override public void onError(String reason, long receivedMs) {
                                if (open.get()) listener.onError(reason, receivedMs);
                            }
                            @Override public void onDispatchFailure(String operation,
                                    int device, int fid, long receivedMs, RuntimeException failure) {
                                if (!open.get()) return;
                                diagnosticSink.onDiagnostic("turn_telemetry_listener_dispatch_error",
                                        "operation", operation, "device", device, "fid", fid,
                                        "received_t_ms", receivedMs,
                                        "exception_class", failure.getClass().getName(),
                                        "exception_message", failure.getMessage(),
                                        "stack_trace", stackTrace(failure));
                            }
                        });
        return () -> {
            open.set(false);
            subscription.close();
        };
    }

    @Override public TurnSignalTelemetryController.Snapshot read() {
        return new TurnSignalTelemetryController.Snapshot(
                readInt(TurnSignalTelemetryController.STALK_DEVICE,
                        TurnSignalTelemetryController.STALK_FID),
                readInt(TurnSignalTelemetryController.STEERING_DEVICE,
                        TurnSignalTelemetryController.STEERING_FID),
                readInt(TurnSignalTelemetryController.BLINK_DEVICE,
                        TurnSignalTelemetryController.BLINK_FID),
                readInt(TurnSignalTelemetryController.SPEED_DEVICE,
                        TurnSignalTelemetryController.SPEED_FID));
    }

    private int readInt(int device, int fid) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        String phase = "service_lookup";
        try {
            IBinder binder = service();
            phase = "interface_token";
            data.writeInterfaceToken(binder.getInterfaceDescriptor());
            data.writeInt(device);
            data.writeInt(fid);
            phase = "binder_transact";
            boolean transacted = binder.transact(
                    device == TurnSignalTelemetryController.STEERING_DEVICE
                            || device == TurnSignalTelemetryController.SPEED_DEVICE ? 7 : 5,
                    data, reply, 0);
            int replyBytes = reply.dataAvail();
            if (!transacted) {
                reportReadFailure(device, fid, "binder_transact", "transact",
                        "transact", false,
                        "reply_bytes", replyBytes, "expected_reply_bytes", 8);
                return TurnSignalTelemetryController.MISSING;
            }
            if (replyBytes < 8) {
                boolean statusPresent = replyBytes >= 4;
                if (statusPresent) phase = "reply_status";
                int status = statusPresent ? reply.readInt() : 0;
                if (statusPresent) {
                    reportReadFailure(device, fid, "short_reply", "transact",
                            "transact", true, "reply_bytes", replyBytes,
                            "expected_reply_bytes", 8, "status_present", true,
                            "oem_status", status);
                } else {
                    reportReadFailure(device, fid, "short_reply", "transact",
                            "transact", true, "reply_bytes", replyBytes,
                            "expected_reply_bytes", 8, "status_present", false);
                }
                return TurnSignalTelemetryController.MISSING;
            }
            phase = "reply_parse";
            int status = reply.readInt();
            int value = reply.readInt();
            if (status != 0) {
                reportReadFailure(device, fid, "oem_status", "oem_status",
                        "oem_status", status, "raw_value", value);
            }
            return status == 0 ? value : TurnSignalTelemetryController.MISSING;
        } catch (Exception failure) {
            synchronized (this) { service = null; }
            reportReadException(device, fid, phase, failure);
            return TurnSignalTelemetryController.MISSING;
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

    private void reportReadFailure(int device, int fid, String failure,
            String phase, Object... details) {
        Object[] fields = new Object[10 + details.length];
        int index = put(fields, 0, "operation", "GET");
        index = put(fields, index, "device", device);
        index = put(fields, index, "fid", fid);
        index = put(fields, index, "phase", phase);
        index = put(fields, index, "failure", failure);
        System.arraycopy(details, 0, fields, index, details.length);
        diagnosticSink.onDiagnostic("turn_telemetry_get_error", fields);
    }

    private void reportReadException(int device, int fid, String phase, Exception failure) {
        diagnosticSink.onDiagnostic("turn_telemetry_get_error",
                "operation", "GET", "device", device, "fid", fid,
                "phase", phase, "exception_class", failure.getClass().getName(),
                "exception_message", failure.getMessage() == null ? "" : failure.getMessage(),
                "stack_trace", stackTrace(failure));
    }

    private static int put(Object[] fields, int index, Object name, Object value) {
        fields[index] = name;
        fields[index + 1] = value;
        return index + 2;
    }

    private static String stackTrace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
