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
        AvasTelemetryController.ReadResult power = readInt(POWER_DEVICE, POWER_FID);
        AvasTelemetryController.ReadResult lock = readInt(LOCK_DEVICE, LOCK_FID);
        return new AvasTelemetryController.Snapshot(
                power.value, lock.value, power.failure, lock.failure);
    }

    private AvasTelemetryController.ReadResult readInt(int device, int fid) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder autoservice = service();
            data.writeInterfaceToken(autoservice.getInterfaceDescriptor());
            data.writeInt(device);
            data.writeInt(fid);
            if (!autoservice.transact(5, data, reply, 0))
                return AvasTelemetryController.ReadResult.failure(
                        new IllegalStateException("vehicle GET transaction 5 rejected"));
            if (reply.dataAvail() < 8)
                return AvasTelemetryController.ReadResult.failure(new IllegalStateException(
                        "vehicle GET reply incomplete: " + reply.dataAvail() + " bytes"));
            int status = reply.readInt();
            int value = reply.readInt();
            if (status != 0)
                return AvasTelemetryController.ReadResult.failure(new IllegalStateException(
                        "vehicle GET returned status " + status + " with value " + value));
            return AvasTelemetryController.ReadResult.value(value);
        } catch (Exception failure) {
            synchronized (this) { service = null; }
            return AvasTelemetryController.ReadResult.failure(failure);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private synchronized IBinder service() throws Exception {
        return service = AvasAutoService.resolve(service);
    }
}
