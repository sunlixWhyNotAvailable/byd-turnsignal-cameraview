package com.byd.extend;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Fixed read-only OK indicators; GET remains necessary while callback delivery is unproven. */
final class AvasEngineReadyMonitor implements AutoCloseable {
    private static final int INSTRUMENT = 1007;
    private static final int OK_FID = 738197524;
    private static final int GB = 1039;
    private static final int GB_OK_FID = 873463824;
    private final Context context;
    private final ScheduledExecutorService executor;
    private final Consumer<Boolean> sink;
    private final Consumer<JSONObject> events;
    private volatile boolean closed;
    private boolean enabled;
    private boolean powerOff;
    private long generation;
    private long lastSubscribe;
    private ScheduledFuture<?> next;
    private FixedBydTelemetryManager.Subscription subscription;
    private IBinder service;
    private String lastLog = "";
    private Boolean lastReady;
    private boolean hasReady;

    AvasEngineReadyMonitor(Context context, ScheduledExecutorService executor,
            Consumer<Boolean> sink, Consumer<JSONObject> events) {
        this.context = context;
        this.executor = executor;
        this.sink = sink;
        this.events = events;
    }

    void setEnabled(boolean value) {
        executor.execute(() -> {
            if (closed || enabled == value) return;
            enabled = value;
            restart();
        });
    }

    void onPower(int raw) {
        if (raw < 0 || raw > 4) return;
        executor.execute(() -> {
            boolean off = raw == 0;
            if (closed || off == powerOff) return;
            powerOff = off;
            restart();
        });
    }

    private void restart() {
        generation++;
        cancel();
        lastSubscribe = Long.MIN_VALUE;
        hasReady = false;
        lastReady = null;
        sink.accept(null);
        if (enabled && !powerOff && !closed) poll();
    }

    private void poll() {
        if (closed || !enabled || powerOff) return;
        if (next != null) next.cancel(false);
        long now = SystemClock.elapsedRealtime();
        if (subscription == null && (lastSubscribe == Long.MIN_VALUE
                || now - lastSubscribe >= 60_000)) {
            lastSubscribe = now;
            long session = generation;
            try {
                subscription = FixedBydTelemetryManager.get(context).subscribe(
                        new FixedBydTelemetryManager.Request[]{
                            new FixedBydTelemetryManager.Request(INSTRUMENT, OK_FID)},
                        new FixedBydTelemetryManager.Listener() {
                            @Override public void onValue(int device, int fid, int raw, long at) {
                                if (device == INSTRUMENT && fid == OK_FID) executor.execute(() -> {
                                    if (session == generation) poll();
                                });
                            }
                            @Override public void onError(String reason, long at) {
                                executor.execute(() -> {
                                    if (session != generation || closed) return;
                                    if (subscription != null) subscription.close();
                                    subscription = null;
                                    poll();
                                });
                            }
                        });
            } catch (Exception ignored) { subscription = null; }
        }
        Integer primary = read(INSTRUMENT, OK_FID);
        Boolean ready = decode(primary, false);
        Integer fallback = null;
        String source = "instrument";
        if (ready == null) {
            fallback = read(GB, GB_OK_FID);
            ready = decode(fallback, true);
            source = ready == null ? "unavailable" : "gb";
        }
        String logKey = primary + ":" + fallback + ":" + source;
        if (!Objects.equals(logKey, lastLog)) {
            lastLog = logKey;
            try {
                events.accept(new JSONObject().put("kind", "avas_engine_ready")
                        .put("source", source).put("instrument_raw", primary == null ? JSONObject.NULL : primary)
                        .put("gb_raw", fallback == null ? JSONObject.NULL : fallback)
                        .put("ready", ready == null ? JSONObject.NULL : ready));
            } catch (Exception ignored) {}
        }
        if (!hasReady || !Objects.equals(lastReady, ready)) {
            hasReady = true;
            lastReady = ready;
            sink.accept(ready);
        }
        if (!closed && enabled && !powerOff) next = executor.schedule(this::poll, 1000, TimeUnit.MILLISECONDS);
    }

    static Boolean decode(Integer raw, boolean gb) {
        if (raw == null) return null;
        if (raw == 1) return true;
        if (raw == (gb ? 0 : 2)) return false;
        return null;
    }

    private Integer read(int device, int fid) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            service = AvasAutoService.resolve(service);
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            data.writeInt(device);
            data.writeInt(fid);
            if (!service.transact(5, data, reply, 0) || reply.dataAvail() < 8) return null;
            int status = reply.readInt();
            int raw = reply.readInt();
            return status == 0 ? raw : null;
        } catch (Exception ignored) { service = null; return null; }
        finally { data.recycle(); reply.recycle(); }
    }

    private void cancel() {
        if (next != null) next.cancel(false);
        next = null;
        if (subscription != null) subscription.close();
        subscription = null;
    }

    @Override public void close() {
        closed = true;
        executor.execute(this::cancel);
    }
}
