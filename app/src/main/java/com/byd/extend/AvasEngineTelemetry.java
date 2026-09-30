package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Read-only fixed drivetrain inputs. Unchanged callback values remain valid. */
final class AvasEngineTelemetry implements AutoCloseable {
    static final int MISSING = Integer.MIN_VALUE;
    private final Context context;
    private final ScheduledExecutorService executor;
    private final Consumer<JSONObject> events;
    private final int speedFid = speedFid();
    private final int[] fids = {speedFid, 303038487, 874512392, 874512408,
            874512400, 874512409, 555745336};
    private final int[] values = new int[fids.length];
    private final long[] arrivals = new long[fids.length];
    private volatile boolean closed;
    private volatile boolean enabled;
    private volatile long generation;
    private volatile boolean subscriptionFailed;
    private FixedBydTelemetryManager.Subscription subscription;
    private ScheduledFuture<?> next;
    private IBinder service;
    private long lastSubscribeAttempt = Long.MIN_VALUE;

    AvasEngineTelemetry(Context context, ScheduledExecutorService executor,
            Consumer<JSONObject> events) {
        this.context = context;
        this.executor = executor;
        this.events = events;
        Arrays.fill(values, MISSING);
    }

    void setEnabled(boolean value) {
        if (closed) return;
        executor.execute(() -> {
            if (closed || enabled == value) return;
            enabled = value;
            generation++;
            cancel();
            invalidate();
            lastSubscribeAttempt = Long.MIN_VALUE;
            if (value) tick();
        });
    }

    private void tick() {
        if (!enabled || closed) return;
        if (subscriptionFailed) {
            if (subscription != null) subscription.close();
            subscription = null;
            subscriptionFailed = false;
            generation++;
        }
        long now = SystemClock.elapsedRealtime();
        if (subscription == null && (lastSubscribeAttempt == Long.MIN_VALUE
                || now - lastSubscribeAttempt >= 60_000)) subscribe();
        for (int i = 0; i < fids.length && enabled && !closed; i++) {
            long before;
            synchronized (this) { before = arrivals[i]; }
            int raw = read(i);
            synchronized (this) {
                if (before == arrivals[i]) values[i] = raw;
            }
        }
        if (enabled && !closed) next = executor.schedule(this::tick,
                subscription == null ? 1000 : 60_000, TimeUnit.MILLISECONDS);
    }

    private void subscribe() {
        lastSubscribeAttempt = SystemClock.elapsedRealtime();
        long session = generation;
        try {
            subscription = FixedBydTelemetryManager.get(context).subscribe(
                    new FixedBydTelemetryManager.Request[]{
                        new FixedBydTelemetryManager.Request(1013,
                                FixedBydTelemetryManager.ValueType.FLOAT, speedFid),
                        new FixedBydTelemetryManager.Request(1013, 303038487, 874512392,
                                874512408, 874512400, 874512409),
                        new FixedBydTelemetryManager.Request(1011, 555745336)},
                    new FixedBydTelemetryManager.Listener() {
                        @Override public void onValue(int device, int fid, int raw, long at) {
                            synchronized (AvasEngineTelemetry.this) {
                                if (session != generation || !enabled || closed) return;
                                for (int i = 0; i < fids.length; i++) {
                                    if (fids[i] == fid && device == (i == 6 ? 1011 : 1013)) {
                                        arrivals[i]++;
                                        values[i] = raw;
                                        break;
                                    }
                                }
                            }
                        }
                        @Override public void onError(String reason, long at) {
                            if (session != generation || closed) return;
                            executor.execute(() -> retryNow(
                                    session == generation && enabled && !closed, next, () -> {
                                        invalidate();
                                        subscriptionFailed = true;
                                        log("error", reason);
                                        tick();
                                    }));
                        }
                    });
            log("callback", "fixed_read_only_inputs speed_fid=" + speedFid);
        } catch (Exception failure) {
            subscription = null;
            log("fallback_1s", failure.toString());
        }
    }

    synchronized AvasEngineModel.State update(AvasEngineModel model, long now) {
        float speed = Float.intBitsToFloat(values[0]);
        return model.update(now, speed,
                values[1] == 1 && values[0] != MISSING && Float.isFinite(speed)
                        && speed >= 0 && speed <= 300,
                values[2], percentValid(values[2], values[3]),
                values[4], percentValid(values[4], values[5]),
                values[6], values[6] >= 1 && values[6] <= 6);
    }

    static boolean percentValid(int value, int valid) {
        return valid == 1 && value >= 0 && value <= 100;
    }

    static void retryNow(boolean currentSession, Future<?> delayed, Runnable retry) {
        if (!currentSession) return;
        if (delayed != null) delayed.cancel(false);
        retry.run();
    }

    private synchronized void invalidate() {
        Arrays.fill(values, MISSING);
        for (int i = 0; i < arrivals.length; i++) arrivals[i]++;
    }

    private int read(int index) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            service = AvasAutoService.resolve(service);
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            data.writeInt(index == 6 ? 1011 : 1013);
            data.writeInt(fids[index]);
            if (!service.transact(index == 0 ? 7 : 5, data, reply, 0)
                    || reply.dataAvail() < 8) return MISSING;
            int status = reply.readInt();
            int raw = reply.readInt();
            return status == 0 ? raw : MISSING;
        } catch (Exception failure) {
            service = null;
            return MISSING;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int speedFid() {
        try {
            int fid = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
                    .getField("SPEED_AUTO_SPEED").getInt(null);
            if (fid == -1807745016 || fid == -1176502256 || fid == 303038472) return fid;
        } catch (ReflectiveOperationException | LinkageError ignored) {}
        return -1807745016;
    }

    private void log(String mode, String reason) {
        try {
            events.accept(new JSONObject().put("kind", "avas_engine_telemetry")
                    .put("mode", mode).put("reason", reason));
        } catch (Exception ignored) {}
    }

    private void cancel() {
        if (next != null) next.cancel(false);
        next = null;
        if (subscription != null) subscription.close();
        subscription = null;
    }

    @Override public void close() {
        closed = true;
        enabled = false;
        generation++;
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(() -> { try { cancel(); } finally { done.countDown(); } });
        boolean interrupted = false;
        for (;;) {
            try { done.await(); break; }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
