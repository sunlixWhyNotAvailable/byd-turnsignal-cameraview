package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

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
        FixedBydTelemetryManager.Subscription subscription =
                FixedBydTelemetryManager.get(context).subscribe(
                        new FixedBydTelemetryManager.Request[]{
                                new FixedBydTelemetryManager.Request(POWER_DEVICE, POWER_FID),
                                new FixedBydTelemetryManager.Request(LOCK_DEVICE, LOCK_FID)},
                        new FixedBydTelemetryManager.Listener() {
                            @Override public void onValue(
                                    int device, int fid, int value, long receivedMs) {
                                callback.onValue(device, fid, value, receivedMs);
                            }
                            @Override public void onError(String reason, long receivedMs) {
                                callback.onError(reason, receivedMs);
                            }
                        });
        return subscription::close;
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
