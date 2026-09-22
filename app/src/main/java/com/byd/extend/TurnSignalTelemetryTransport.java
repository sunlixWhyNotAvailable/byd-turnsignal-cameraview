package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

/** Fixed four-signal guard telemetry transport; it exposes no caller-selected FIDs. */
final class TurnSignalTelemetryTransport implements TurnSignalTelemetryController.Transport {
    private static final FixedBydTelemetryManager.Request[] REQUESTS = {
            new FixedBydTelemetryManager.Request(1004, 321912876, 950009900),
            new FixedBydTelemetryManager.Request(1001,
                    FixedBydTelemetryManager.ValueType.FLOAT, 300941320),
            new FixedBydTelemetryManager.Request(1013,
                    FixedBydTelemetryManager.ValueType.FLOAT, -1807745016)
    };

    private final Context context;
    private IBinder service;

    TurnSignalTelemetryTransport(Context context) { this.context = context; }

    @Override public TurnSignalTelemetryController.Subscription subscribe(
            TurnSignalTelemetryController.Listener listener) throws Exception {
        FixedBydTelemetryManager.Subscription subscription =
                FixedBydTelemetryManager.get(context).subscribe(REQUESTS,
                        new FixedBydTelemetryManager.Listener() {
                            @Override public void onValue(
                                    int device, int fid, int value, long receivedMs) {
                                listener.onValue(device, fid, value, receivedMs);
                            }
                            @Override public void onError(String reason, long receivedMs) {
                                listener.onError(reason, receivedMs);
                            }
                        });
        return subscription::close;
    }

    @Override public TurnSignalTelemetryController.Snapshot read() {
        return new TurnSignalTelemetryController.Snapshot(
                readInt(1004, 321912876), readInt(1001, 300941320),
                readInt(1004, 950009900), readInt(1013, -1807745016));
    }

    private int readInt(int device, int fid) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder binder = service();
            data.writeInterfaceToken(binder.getInterfaceDescriptor());
            data.writeInt(device);
            data.writeInt(fid);
            if (!binder.transact(device == 1001 || device == 1013 ? 7 : 5,
                    data, reply, 0) || reply.dataAvail() < 8) {
                return TurnSignalTelemetryController.MISSING;
            }
            int status = reply.readInt();
            int value = reply.readInt();
            return status == 0 ? value : TurnSignalTelemetryController.MISSING;
        } catch (Exception ignored) {
            synchronized (this) { service = null; }
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
}
